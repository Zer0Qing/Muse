package io.zer0.muse.session

import io.zer0.common.Logger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 可由生成器、工具和后台资源共同使用的执行资源类型。 */
enum class ExecutionKind {
    LLM,
    TOOL,
    APPROVAL,
    BROWSER,
    TERMINAL,
    SUBAGENT,
    DEFERRED_RESULT,
    MEDIA,
    NOTIFICATION,
    CHECKPOINT,
}

/** 执行资源的生命周期；取消请求和取消完成严格分开。 */
enum class ExecutionState {
    REGISTERED,
    RUNNING,
    CANCEL_REQUESTED,
    CANCELLED,
    COMPLETED,
    FAILED,
    SUPPRESSED,
}

/**
 * 一个生成身份关联的外部执行资源。
 *
 * [cancel] 只负责发出合作式取消信号；真正结束由 [SessionExecutionRegistry.finish]
 * 或 [SessionExecutionRegistry.fail] 记录，避免把“请求取消”误当成“资源已停止”。
 */
data class ExecutionRecord(
    val id: String,
    val identity: GenerationIdentity,
    val kind: ExecutionKind,
    val state: ExecutionState = ExecutionState.REGISTERED,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
)

/**
 * Session-scoped 执行资源注册表。
 *
 * 这是生命周期协调器，不拥有具体工具或网络实现。调用方登记可取消资源，
 * 停止时按 session/turn/generation 过滤并发出取消信号；晚到结果必须先通过
 * 由 [isCurrent] 检查，才能更新当前 UI 或写入当前代状态。
 *
 * Phase 3 可靠性(执行账本清理):
 *  - 活跃记录([ExecutionState.REGISTERED]/[ExecutionState.RUNNING]/CANCEL_REQUESTED)
 *    与终态历史([ExecutionState.CANCELLED]/COMPLETED/FAILED/SUPPRESSED)分离存放:
 *    取消扫描、代际判定只遍历活跃表，历史表无限增长不再拖慢这些热路径。
 *  - 终态记录受 TTL([historyRetentionMs])与容量([maxHistoryRecords])约束，
 *    每次终态迁移后自动 [prune];也可由调用方显式裁剪。
 *  - register/start/finish/fail/markCancelled 对外语义保持不变(仅新增历史裁剪)。
 */
@Suppress("TooManyFunctions", "TooGenericExceptionCaught", "LoopWithTooManyJumpStatements")
class SessionExecutionRegistry(
    /** 终态记录保留时长(毫秒),超过后由 [prune] 从历史中移除。 */
    private val historyRetentionMs: Long = DEFAULT_HISTORY_RETENTION_MS,
    /** 终态历史容量上限;超出时按 finishedAt 由旧到新淘汰。 */
    private val maxHistoryRecords: Int = DEFAULT_MAX_HISTORY_RECORDS,
    /** 时间源(测试可注入,确定性验证 TTL);生产用系统时钟。 */
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private data class Registered(
        val record: ExecutionRecord,
        val cancel: (() -> Unit)?,
    )

    /** 活跃记录(非终态);热路径只遍历此表。 */
    private val active = ConcurrentHashMap<String, Registered>()
    /** 终态历史记录;只保留诊断所需的有限窗口,由 [prune] 裁剪。 */
    private val history = ConcurrentHashMap<String, Registered>()
    /** 每个 session 最近登记的 generation；用于拒绝旧代晚到事件。 */
    private val latestGenerationBySession = ConcurrentHashMap<String, String>()

    /** 登记一个执行资源，返回稳定资源 ID。 */
    fun register(
        identity: GenerationIdentity,
        kind: ExecutionKind,
        cancel: (() -> Unit)? = null,
    ): String {
        val id = "exec-${UUID.randomUUID()}"
        active[id] = Registered(
            record = ExecutionRecord(id = id, identity = identity, kind = kind, startedAt = nowMs()),
            cancel = cancel,
        )
        latestGenerationBySession[identity.sessionId] = identity.generationId
        return id
    }

    /** 标记资源开始运行。 */
    fun start(id: String): Boolean = update(id) { record ->
        if (record.state != ExecutionState.REGISTERED) return@update null
        record.copy(state = ExecutionState.RUNNING)
    } != null

    /** 请求取消一个资源；重复调用保持幂等。 */
    fun requestCancel(id: String, reason: String = "cancelled"): Boolean {
        val registered = active[id] ?: return false
        val updated = update(id) { record ->
            if (record.state in TERMINAL_STATES || record.state == ExecutionState.CANCEL_REQUESTED) return@update null
            record.copy(state = ExecutionState.CANCEL_REQUESTED)
        }
        val accepted = updated != null
        if (accepted) {
            try {
                registered.cancel?.invoke()
            } catch (error: Exception) {
                Logger.w(TAG, "执行资源取消信号失败: id=$id kind=${registered.record.kind} reason=$reason", error)
            }
        }
        return accepted
    }

    /** 请求取消指定 session 下的全部非终态资源。 */
    fun requestCancelForSession(sessionId: String, reason: String = "session_cancelled"): List<String> =
        active.values
            .map { it.record }
            .filter { it.identity.sessionId == sessionId && it.state !in TERMINAL_STATES }
            .mapNotNull { record -> record.id.takeIf { requestCancel(it, reason) } }

    /**
     * 只允许当前 generation 更新当前状态；旧代晚到事件返回 false。
     *
     * ENG-10 复核结论：本方法**当前没有生产调用方**（工具侧的迟到结果由
     * `ChatViewModel` 的 `toolGenerationToken` 门禁负责），但它是这条不变量最直接的表达，
     * 且有测试覆盖（[SessionExecutionRegistryTest] / [SessionExecutionRegistryPruningTest]）。
     * 因此保留 API 而不删除——接线的价值高于删掉的整洁，谁要接入工具回写路径可以直接用。
     */
    fun isCurrent(identity: GenerationIdentity): Boolean =
        latestGenerationBySession[identity.sessionId] == identity.generationId &&
            active.values.any { registered ->
                registered.record.identity.sessionId == identity.sessionId &&
                    registered.record.identity.generationId == identity.generationId &&
                    registered.record.state in ACTIVE_STATES
            }

    /** 判断两个事件是否属于同一代。 */
    fun matches(left: GenerationIdentity, right: GenerationIdentity): Boolean =
        left.sessionId == right.sessionId && left.generationId == right.generationId

    /**
     * 标记资源正常完成并保留记录用于诊断。
     *
     * 若资源此前已收到取消请求，即使底层调用以普通返回结束，也只能收敛为
     * [ExecutionState.CANCELLED]；否则会把取消期间的晚到结果误报为成功。
     */
    fun finish(id: String): Boolean = update(id) { record ->
        when {
            record.state in TERMINAL_STATES -> record
            record.state == ExecutionState.CANCEL_REQUESTED ->
                record.copy(state = ExecutionState.CANCELLED, finishedAt = nowMs())
            else -> record.copy(state = ExecutionState.COMPLETED, finishedAt = nowMs())
        }
    } != null

    /** 标记资源失败并保留记录用于诊断。取消请求期间的异常归入取消终态。 */
    fun fail(id: String): Boolean = update(id) { record ->
        when {
            record.state in TERMINAL_STATES -> record
            record.state == ExecutionState.CANCEL_REQUESTED ->
                record.copy(state = ExecutionState.CANCELLED, finishedAt = nowMs())
            else -> record.copy(state = ExecutionState.FAILED, finishedAt = nowMs())
        }
    } != null

    /** 标记资源已取消；适用于 cancel handle 完成回调。 */
    fun markCancelled(id: String): Boolean = update(id) { record ->
        if (record.state in TERMINAL_STATES) record
        else record.copy(state = ExecutionState.CANCELLED, finishedAt = nowMs())
    } != null

    /** 获取资源当前状态；未知资源(含已裁剪历史)返回 null。 */
    fun state(id: String): ExecutionState? = (active[id] ?: history[id])?.record?.state

    /** 获取指定 session 的资源快照(活跃 + 未被裁剪的终态历史)。 */
    fun snapshot(sessionId: String? = null): List<ExecutionRecord> = (active.values + history.values)
        .map { it.record }
        .filter { sessionId == null || it.identity.sessionId == sessionId }
        .sortedBy { it.startedAt }

    /** Phase 3: 仅活跃记录的快照(取消扫描/诊断用)。 */
    fun activeSnapshot(sessionId: String? = null): List<ExecutionRecord> = active.values
        .map { it.record }
        .filter { sessionId == null || it.identity.sessionId == sessionId }
        .sortedBy { it.startedAt }

    /** Phase 3: 仅终态历史记录的快照(诊断用;可能已被 [prune] 裁剪)。 */
    fun historySnapshot(sessionId: String? = null): List<ExecutionRecord> = history.values
        .map { it.record }
        .filter { sessionId == null || it.identity.sessionId == sessionId }
        .sortedBy { it.startedAt }

    /**
     * Phase 3: 裁剪终态历史。
     *
     * 1. TTL: finishedAt(缺失时回退 startedAt)早于 `now - historyRetentionMs` 的记录移除;
     * 2. 容量: 仍超过 [maxHistoryRecords] 时，按 finishedAt 由旧到新淘汰。
     *
     * 活跃记录永不裁剪;每次终态迁移([finish]/[fail]/[markCancelled])后自动调用。
     *
     * @return 本次移除的记录数
     */
    fun prune(now: Long = nowMs()): Int {
        var removed = 0
        val cutoff = now - historyRetentionMs
        history.values.forEach { registered ->
            val finishedAt = registered.record.finishedAt ?: registered.record.startedAt
            if (finishedAt < cutoff && history.remove(registered.record.id, registered)) {
                removed++
            }
        }
        val overflow = history.size - maxHistoryRecords
        if (overflow > 0) {
            history.values
                .sortedBy { it.record.finishedAt ?: it.record.startedAt }
                .take(overflow)
                .forEach { oldest ->
                    if (history.remove(oldest.record.id, oldest)) removed++
                }
        }
        if (removed > 0) {
            Logger.d(TAG, "执行历史裁剪: 移除 $removed 条(剩余 ${history.size} 条)")
        }
        return removed
    }

    /**
     * 原子更新一条记录。
     *
     * 记录可能位于活跃表或历史表;一旦 transform 产生终态，记录从活跃表迁移到历史表
     * (迁移失败说明并发调用已抢先迁移，循环重试命中历史分支)，并触发历史裁剪。
     */
    private fun update(id: String, transform: (ExecutionRecord) -> ExecutionRecord?): ExecutionRecord? {
        while (true) {
            val currentActive = active[id]
            if (currentActive != null) {
                val next = transform(currentActive.record) ?: return null
                val updated = currentActive.copy(record = next)
                if (next.state in TERMINAL_STATES) {
                    // 终态迁移:从活跃表移除(而非替换),再写入历史表
                    if (!active.remove(id, currentActive)) continue
                    history[id] = updated
                    prune()
                    return next
                }
                if (active.replace(id, currentActive, updated)) return next
                continue
            }
            val currentHistory = history[id] ?: return null
            val next = transform(currentHistory.record) ?: return null
            if (history.replace(id, currentHistory, currentHistory.copy(record = next))) return next
        }
    }

    private companion object {
        const val TAG = "SessionExecutionRegistry"

        /** 终态记录默认保留 10 分钟,足够事后诊断又不无限占用内存。 */
        const val DEFAULT_HISTORY_RETENTION_MS = 10 * 60 * 1000L

        /** 终态历史默认容量上限(超出按最旧 finishedAt 淘汰)。 */
        const val DEFAULT_MAX_HISTORY_RECORDS = 256

        val ACTIVE_STATES = setOf(
            ExecutionState.REGISTERED,
            ExecutionState.RUNNING,
        )
        val TERMINAL_STATES = setOf(
            ExecutionState.CANCELLED,
            ExecutionState.COMPLETED,
            ExecutionState.FAILED,
            ExecutionState.SUPPRESSED,
        )
    }
}
