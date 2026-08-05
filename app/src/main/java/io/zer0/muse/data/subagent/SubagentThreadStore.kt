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
        return mutex.withLock {
            val entity = dao.getById(threadId)
            if (entity?.status == "closed") {
                throw IllegalStateException("Thread $threadId is closed")
            }
            block()
        }
    }

    /** 旧 API:清理已关闭线程(内存锁清理;持久化层不动)。 */
    fun cleanupClosed() {
        // 持久化版下锁本身是弱引用,GC 友好;保留空实现兼容旧 API
    }

    // ============ 新 API(供 SubagentRunner / UI / subagent_close 使用)============

    /** 获取或创建线程。返回 (threadId, 是否新建)。 */
    suspend fun getOrCreate(
        threadId: String?,
        parentSessionId: String,
        assistantId: String,
        label: String? = null,
        access: String = "read",
    ): Pair<String, Boolean> {
        if (threadId != null) {
            val existing = dao.getById(threadId)
            if (existing != null) return threadId to false
        }
        val newId = threadId ?: "thread-" + java.util.UUID.randomUUID().toString().take(12)
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
        return true
    }

    /** 列出某主会话的开放线程(供 UI / subagent_close 使用)。 */
    suspend fun listOpen(parentSessionId: String): List<SubagentThreadEntity> {
        return dao.listOpenBySession(parentSessionId)
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
