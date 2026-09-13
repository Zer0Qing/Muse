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
 * [isCurrent] 检查，才能更新当前 UI 或写入当前代状态。
 */
@Suppress("TooManyFunctions", "TooGenericExceptionCaught", "LoopWithTooManyJumpStatements")
class SessionExecutionRegistry {

    private data class Registered(
        val record: ExecutionRecord,
        val cancel: (() -> Unit)?,
    )

    private val records = ConcurrentHashMap<String, Registered>()
    /** 每个 session 最近登记的 generation；用于拒绝旧代晚到事件。 */
    private val latestGenerationBySession = ConcurrentHashMap<String, String>()

    /** 登记一个执行资源，返回稳定资源 ID。 */
    fun register(
        identity: GenerationIdentity,
        kind: ExecutionKind,
        cancel: (() -> Unit)? = null,
    ): String {
        val id = "exec-${UUID.randomUUID()}"
        records[id] = Registered(
            record = ExecutionRecord(id = id, identity = identity, kind = kind),
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
        val registered = records[id] ?: return false
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
        records.values
            .map { it.record }
            .filter { it.identity.sessionId == sessionId && it.state !in TERMINAL_STATES }
            .mapNotNull { record -> record.id.takeIf { requestCancel(it, reason) } }

    /** 只允许当前 generation 更新当前状态；旧代晚到事件返回 false。 */
    fun isCurrent(identity: GenerationIdentity): Boolean =
        latestGenerationBySession[identity.sessionId] == identity.generationId &&
            records.values.any { registered ->
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
                record.copy(state = ExecutionState.CANCELLED, finishedAt = System.currentTimeMillis())
            else -> record.copy(state = ExecutionState.COMPLETED, finishedAt = System.currentTimeMillis())
        }
    } != null

    /** 标记资源失败并保留记录用于诊断。取消请求期间的异常归入取消终态。 */
    fun fail(id: String): Boolean = update(id) { record ->
        when {
            record.state in TERMINAL_STATES -> record
            record.state == ExecutionState.CANCEL_REQUESTED ->
                record.copy(state = ExecutionState.CANCELLED, finishedAt = System.currentTimeMillis())
            else -> record.copy(state = ExecutionState.FAILED, finishedAt = System.currentTimeMillis())
        }
    } != null

    /** 标记资源已取消；适用于 cancel handle 完成回调。 */
    fun markCancelled(id: String): Boolean = update(id) { record ->
        if (record.state in TERMINAL_STATES) record
        else record.copy(state = ExecutionState.CANCELLED, finishedAt = System.currentTimeMillis())
    } != null

    /** 获取资源当前状态；未知资源返回 null。 */
    fun state(id: String): ExecutionState? = records[id]?.record?.state

    /** 获取指定 session 的资源快照。 */
    fun snapshot(sessionId: String? = null): List<ExecutionRecord> = records.values
        .map { it.record }
        .filter { sessionId == null || it.identity.sessionId == sessionId }
        .sortedBy { it.startedAt }

    private fun update(id: String, transform: (ExecutionRecord) -> ExecutionRecord?): ExecutionRecord? {
        while (true) {
            val current = records[id] ?: break
            val next = transform(current.record) ?: break
            if (records.replace(id, current, current.copy(record = next))) return next
        }
        return null
    }

    private companion object {
        const val TAG = "SessionExecutionRegistry"
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
