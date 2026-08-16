package io.zer0.muse.data.subagent

import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.0.53: 子 agent 线程账本(持久化版,既有实现 SubagentThreadStore)。
 *
 * 替代旧 tools/SubagentThreadStore.kt(内存版)。两条 subagent 路径共享:
 *  - 路径 A: SubagentTool + SkillExecutor.delegateAgent nonBlocking
 *  - 路径 B: SubagentRunSkill + SubagentRunner
 *
 * API 兼容性:
 *  - 旧 API(beginRun/getThread/closeThread/runSerialized/listActiveThreads)保留,
 *    SubagentTool 无感升级(仅改 import 包名)
 *  - 新 API(getOrCreate/recordRun/close/listOpen/appendMessages/loadMessages)
 *    供 SubagentRunner / UI / subagent_close 使用
 *
 * 持久化:
 *  - Thread 账本: Room 表 subagent_threads
 *  - 子会话历史: JSONL 文件 filesDir/subagent_sessions/<threadId>.jsonl(由 [SubagentSessionStore] 管理)
 *  - runSerialized: 内存 Mutex Map(App 重启后丢失,但持久化的 thread 状态在 Room 里,重启后新并发 run 重新竞争锁无冲突)
 */
class SubagentThreadStore(
    private val dao: SubagentThreadDao,
    private val sessionStore: SubagentSessionStore,
) {
    companion object {
        private const val TAG = "SubagentThreadStore"
        /** v1.0.74: 孤儿线程判定 — open 且超过 24h 未更新视为遗留。 */
        private const val ORPHAN_STALE_MS = 24 * 60 * 60 * 1000L
    }

    private val runLocks = ConcurrentHashMap<String, Mutex>()

    // ============ 旧 API(等价方法,委托新 API,保持 SubagentTool 兼容)============

    /**
     * 旧 API:注册新线程(内存+持久化)。SubagentTool.doLaunch 调用。
     *
     * 注意:旧版是同步非 suspend,新版改为 suspend(因需写 Room)。
     * SubagentTool.doLaunch 已在协程中调用,suspend 无影响。
     */
    suspend fun beginRun(threadId: String, assistantId: String, parentSessionId: String?) {
        getOrCreate(
            threadId = threadId,
            parentSessionId = parentSessionId ?: "default",
            assistantId = assistantId,
        )
    }

    /** 旧 API:获取线程信息(从持久化层读取)。SubagentTool.doReply/doClose 调用。 */
    suspend fun getThread(threadId: String): ThreadEntry? {
        val entity = dao.getById(threadId) ?: return null
        return entity.toThreadEntry()
    }

    /** 旧 API:关闭线程(持久化)。SubagentTool.doClose 调用。 */
    suspend fun closeThread(threadId: String) {
        dao.close(threadId)
    }

    /** 旧 API:列活跃线程(从持久化层)。SubagentTool list 调用。 */
    suspend fun listActiveThreads(): List<ThreadEntry> {
        return dao.listAllOpen().map { it.toThreadEntry() }
    }

    /** 旧 API:串行化执行(同 threadId 排队)。 */
    suspend fun <T> runSerialized(threadId: String, block: suspend () -> T): T {
        val mutex = runLocks.computeIfAbsent(threadId) { Mutex() }
        try {
            return mutex.withLock {
                val entity = dao.getById(threadId)
                if (entity?.status == "closed") {
                    throw IllegalStateException("Thread $threadId is closed")
                }
                block()
            }
        } finally {
            // 审计修复 (C-08): 锁回收移到持锁块之外 — 线程已关闭时锁不再被合法使用,
            // 在 finally 里(此时 withLock 已释放锁)按需从 map 移除,防止 runLocks 无界增长。
            // 之前 close() 在持锁块内直接 runLocks.remove,会与并发 computeIfAbsent 竞争,
            // 可能为同一 threadId 创建新 Mutex 而打破串行化;现在移除与持锁完全解耦。
            // compare-and-check: 仅当 map 仍指向本次使用的同一 mutex 时才移除,
            // 避免误删已被新的 computeIfAbsent 写入的新条目。
            if (dao.getById(threadId)?.status == "closed") {
                runLocks.computeIfPresent(threadId) { _, current ->
                    if (current === mutex) null else current
                }
            }
        }
    }

    /** 旧 API:清理已关闭线程(内存锁清理;持久化层不动)。 */
    fun cleanupClosed() {
        // 审计修复 (C-08): runLocks 非弱引用(强引用 Map),回收由 runSerialized 的
        // finally + cleanupOrphanThreads 按需完成;本空实现仅兼容旧 API,不在这里清锁。
    }

    // ============ 新 API(供 SubagentRunner / UI / subagent_close 使用)============

    /** 审计修复 (1.1) + A-11: threadId 安全校验 — 仅接受安全字符集,杜绝路径穿越。
     * 原实现 LLM 输出的 threadId 直接拼 File(sessionsDir, "$threadId.jsonl"),
     * 恶意值(如 "../../databases/")可写应用私有目录任意位置。
     * A-11: 放行 ':' 并把长度上限放宽到 128 — 群聊 whisper 线程的稳定 key 形如
     * "whisper:<chatId>:<assistantId>"(两个 UUID 约 80+ 字符),此前被拒绝导致每次私信
     * 都新建随机线程(私聊历史拆散 + 表膨胀)。冒号在 Android 文件系统合法且无法用于
     * 路径穿越,放行不削弱原安全目标。 */
    private val SAFE_THREAD_ID = Regex("^[a-zA-Z0-9_:-]{1,128}$")

    /** 获取或创建线程。返回 (threadId, 是否新建)。 */
    suspend fun getOrCreate(
        threadId: String?,
        parentSessionId: String,
        assistantId: String,
        label: String? = null,
        access: String = "read",
    ): Pair<String, Boolean> {
        if (threadId != null) {
            // 非法 threadId(含路径分隔符/..等):不落库、不写文件,改用随机 id
            val safe = SAFE_THREAD_ID.matches(threadId)
            if (!safe) {
                Logger.w(TAG, "getOrCreate: 非法 threadId 拒绝: ${threadId.take(40)}")
            } else {
                val existing = dao.getById(threadId)
                if (existing != null) return threadId to false
            }
        }
        val newId = if (threadId != null && SAFE_THREAD_ID.matches(threadId)) {
            threadId
        } else {
            "thread-" + java.util.UUID.randomUUID().toString().take(12)
        }
        val now = System.currentTimeMillis()
        dao.upsert(SubagentThreadEntity(
            threadId = newId,
            parentSessionId = parentSessionId,
            childSessionId = null,
            childSessionPath = sessionStore.pathOf(newId).absolutePath,
            assistantId = assistantId,
            label = label,
            access = access,
            status = "open",
            runCount = 0,
            createdAt = now,
            updatedAt = now,
        ))
        return newId to true
    }

    /** 记录一次 run 的结果(更新 runCount/lastRunStatus/lastSummary)。 */
    suspend fun recordRun(threadId: String, status: String, summary: String?, sessionPath: String?) {
        val entity = dao.getById(threadId) ?: run {
            Logger.w(TAG, "recordRun: thread $threadId not found, skipping")
            return
        }
        dao.upsert(entity.copy(
            runCount = entity.runCount + 1,
            lastRunStatus = status,
            lastSummary = summary,
            childSessionPath = sessionPath ?: entity.childSessionPath,
            updatedAt = System.currentTimeMillis(),
        ))
    }

    /** 关闭线程(返回是否成功关闭:true=原 open→closed;false=不存在或已 closed)。 */
    suspend fun close(threadId: String): Boolean {
        val entity = dao.getById(threadId) ?: return false
        if (entity.status == "closed") return false
        dao.close(threadId)
        // 审查修复 (2.0 B-15): 无 run 持锁时立即回收锁条目 — 原 C-08 只靠
        // runSerialized 的 finally 回收:线程关闭后若无后续 runSerialized,锁永不回收。
        // Mutex.isLocked 判定当前是否有 run 持锁:
        //  - 无持锁 → 直接移除(线程已 closed,后续 computeIfAbsent 的新锁也会在
        //    锁内立即抛 "Thread is closed",不破坏串行化);
        //  - 有持锁(recordRunAndMaybeClose 在持锁块内调用本方法)→ 留给
        //    runSerialized 的 finally 回收,避免持锁内移除破坏串行化(C-08 原由)。
        runLocks.computeIfPresent(threadId) { _, current ->
            if (current.isLocked) current else null
        }
        return true
    }

    /** 列出某主会话的开放线程(供 UI / subagent_close 使用)。 */
    suspend fun listOpen(parentSessionId: String): List<SubagentThreadEntity> {
        return dao.listOpenBySession(parentSessionId)
    }

    /**
     * v1.0.74: 启动清理孤儿线程 — App 重启后,上次进程遗留的 open 线程
     * (进程被杀/崩溃导致没有走到 subagent_close)会被标记为 closed,
     * 避免"后台子 agent 任务"永远显示(用户反馈:N 个版本前的任务一直挂着)。
     *
     * 规则:open 且超过 [staleMs] 未更新的线程视为孤儿(真正在跑的线程
     * 会持续更新 updatedAt;启动瞬间刚创建的线程不受影响)。
     * @return 清理的线程数
     */
    suspend fun cleanupOrphanThreads(staleMs: Long = ORPHAN_STALE_MS): Int {
        val now = System.currentTimeMillis()
        val orphans = dao.listAllOpen().filter { now - it.updatedAt > staleMs }
        orphans.forEach { dao.close(it.threadId, now) }
        // 审计修复 (C-08): 孤儿线程(进程被杀/崩溃遗留,从未走到 close)是 runLocks
        // 无界增长的主要来源 — 它们永不触发 runSerialized 的 finally 回收。
        // 此处随清理一并把锁条目移除,避免这类永久 open 线程的 Mutex 驻留内存。
        orphans.forEach { runLocks.remove(it.threadId) }
        if (orphans.isNotEmpty()) {
            Logger.i(TAG, "启动清理 ${orphans.size} 个孤儿子 agent 线程: ${orphans.joinToString { it.threadId.take(12) }}")
        }
        return orphans.size
    }

    /** 会话文件路径(供 SubagentRunner Result.sessionPath 使用)。 */
    fun sessionPathOf(threadId: String): String = sessionStore.pathOf(threadId).absolutePath

    // ============ 子会话历史读写(委托 SessionStore)============

    suspend fun appendMessages(threadId: String, messages: List<UIMessage>) =
        sessionStore.append(threadId, messages)

    suspend fun loadMessages(threadId: String, maxContextTokens: Int = 6000): List<UIMessage> =
        sessionStore.load(threadId, maxContextTokens)

    // ============ 兼容旧 ThreadEntry 类型(避免 SubagentTool 大改)============

    data class ThreadEntry(
        val threadId: String,
        val assistantId: String,
        val parentSessionId: String?,
        val createdAt: Long,
        var status: ThreadStatus = ThreadStatus.ACTIVE,
        var lastActivityAt: Long = System.currentTimeMillis(),
    )

    enum class ThreadStatus { ACTIVE, CLOSED }

    private fun SubagentThreadEntity.toThreadEntry() = ThreadEntry(
        threadId = threadId,
        assistantId = assistantId,
        parentSessionId = parentSessionId,
        createdAt = createdAt,
        status = if (status == "open") ThreadStatus.ACTIVE else ThreadStatus.CLOSED,
        lastActivityAt = updatedAt,
    )
}
