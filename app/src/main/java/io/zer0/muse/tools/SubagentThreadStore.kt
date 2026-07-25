package io.zer0.muse.tools

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 子 Agent 线程管理器 — 管理可续接的子 agent 会话线程。
 *
 * 参考 openhanako SubagentThreadStore:
 *  - 每个 threadId 对应一个隔离子会话,可通过 subagent_reply(threadId, task) 续接
 *  - 同一 thread 用 [runSerialized] 串行执行,避免并发竞争
 *  - 线程可被 close 释放
 */
class SubagentThreadStore {
    data class ThreadEntry(
        val threadId: String,
        val assistantId: String,
        val parentSessionId: String?,
        val createdAt: Long,
        var status: ThreadStatus = ThreadStatus.ACTIVE,
        var lastActivityAt: Long = System.currentTimeMillis(),
    )
    enum class ThreadStatus { ACTIVE, CLOSED }

    private val threads = ConcurrentHashMap<String, ThreadEntry>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** 注册新线程,返回 threadId。 */
    fun beginRun(threadId: String, assistantId: String, parentSessionId: String?) {
        threads[threadId] = ThreadEntry(threadId, assistantId, parentSessionId, System.currentTimeMillis())
        locks[threadId] = Mutex()
    }

    /** 串行执行(同一 threadId 不会并发)。 */
    suspend fun <T> runSerialized(threadId: String, block: suspend () -> T): T {
        val mutex = locks[threadId] ?: throw IllegalStateException("Thread $threadId not found")
        return mutex.withLock {
            val entry = threads[threadId]
            if (entry?.status == ThreadStatus.CLOSED) {
                throw IllegalStateException("Thread $threadId is closed")
            }
            entry?.lastActivityAt = System.currentTimeMillis()
            block()
        }
    }

    /** 获取线程信息。 */
    fun getThread(threadId: String): ThreadEntry? = threads[threadId]

    /** 列出所有活跃线程。 */
    fun listActiveThreads(): List<ThreadEntry> = threads.values.filter { it.status == ThreadStatus.ACTIVE }

    /** 关闭线程(释放 slot,不可再续接)。 */
    fun closeThread(threadId: String) {
        threads[threadId]?.status = ThreadStatus.CLOSED
    }

    /** 清理已关闭的线程(定期调用避免内存泄漏)。 */
    fun cleanupClosed() {
        threads.entries.removeAll { it.value.status == ThreadStatus.CLOSED }
        locks.entries.removeAll { !threads.containsKey(it.key) }
    }
}
