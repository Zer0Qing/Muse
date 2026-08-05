package io.zer0.muse.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * v1.x: 会话级资源管理器 — 引用计数 + 自动 idle 清理。
 *
 * 设计目的:
 *  - 多会话切换场景下,跟踪每个会话的"活跃持有者"数量(acquire/release 配对),
 *    在引用计数归零且生成任务结束后的 [IDLE_TIMEOUT_MS] 内自动清理会话资源,
 *    避免内存泄漏(协程未取消、引用未释放)。
 *  - 集中管理会话级生成任务([setGenerationJob] 自动取消前一个),与 [io.zer0.muse.schedule.ChatGenerationManager]
 *    互补:后者负责应用级"切页/后台保持生成不中断",本类负责会话级引用计数 + idle 释放。
 *
 * 使用约定:
 *  - ChatViewModel.switchSession / createNewSession 等会话切换入口:
 *      release(oldSessionId); acquire(newSessionId)
 *  - launchStream 启动生成:setGenerationJob(sessionId, job)
 *  - onCleared / release:release(currentSessionId)
 *
 * 线程安全:[ConcurrentHashMap] + [AtomicInteger] 保证多线程 acquire/release 安全;
 *  idle 清理器运行在构造时传入的 [scope] 中。
 *
 * @param scope idle 清理器运行的 CoroutineScope(应使用应用级 appScope,避免随 ViewModel 生命周期取消)
 */
class ConversationSessionManager(
    private val scope: CoroutineScope,
) {

    /**
     * 单个会话的引用记录。
     *
     * @param sessionId 会话 id
     * @param refCount 当前活跃持有者数量(acquire +1 / release -1),归零时触发 idle 检查
     * @param generationJob 当前会话的生成任务(可被 [setGenerationJob] 替换/取消)
     * @param lastActivityAt 最近一次活动时间(用于 idle 检查的兜底判定)
     */
    data class SessionRef(
        val sessionId: String,
        val refCount: AtomicInteger = AtomicInteger(0),
        var generationJob: Job? = null,
        var lastActivityAt: Long = System.currentTimeMillis(),
    )

    private val sessions = ConcurrentHashMap<String, SessionRef>()
    private val idleReaper = SessionIdleReaper(scope, sessions, IDLE_TIMEOUT_MS)

    companion object {
        /** 引用计数归零后,等待 idle 多少毫秒再清理会话条目。 */
        private const val IDLE_TIMEOUT_MS = 5_000L
    }

    /**
     * 获取会话引用(引用计数 +1)。
     *
     * 应在进入会话(switchSession/createNewSession/setAgentMode 等)时调用。
     * 取消该会话已挂起的 idle 清理(若有)。
     */
    fun acquire(sessionId: String) {
        val ref = sessions.computeIfAbsent(sessionId) { SessionRef(it) }
        synchronized(ref) {
            ref.refCount.incrementAndGet()
            ref.lastActivityAt = System.currentTimeMillis()
        }
        idleReaper.cancel(sessionId)
    }

    /**
     * 释放会话引用(引用计数 -1)。
     *
     * 应在离开会话(switchSession 旧会话/onCleared/release 等)时调用。
     * 引用计数归零时调度 idle 清理(等 [IDLE_TIMEOUT_MS] 后移除,留出短时间切回的窗口)。
     */
    fun release(sessionId: String) {
        val ref = sessions[sessionId] ?: return
        val count = synchronized(ref) {
            ref.refCount.decrementAndGet()
        }
        if (count <= 0) {
            idleReaper.schedule(sessionId)
        }
    }

    /**
     * 设置生成任务(自动取消前一个)。
     *
     * 应在 [io.zer0.muse.ui.ChatViewModel.launchStream] 启动生成时调用,
     * 把 chatGenerationManager 返回的 Job 传进来,本类负责:
     *  - 取消前一个生成任务(防重入)
     *  - 任务完成时清理引用 + 调度 idle 清理
     */
    fun setGenerationJob(sessionId: String, job: Job?) {
        val ref = sessions[sessionId] ?: return
        synchronized(ref) {
            ref.generationJob?.cancel()
            ref.generationJob = job
        }
        job?.invokeOnCompletion {
            synchronized(ref) {
                ref.generationJob = null
                ref.lastActivityAt = System.currentTimeMillis()
            }
            if (ref.refCount.get() <= 0) {
                idleReaper.schedule(sessionId)
            }
        }
    }

    /** 取消会话的生成任务(用户点停止时调用)。 */
    fun cancelGeneration(sessionId: String) {
        sessions[sessionId]?.generationJob?.cancel()
    }

    /**
     * 清理所有会话(App 退出 / 测试用)。
     *
     * 取消所有生成任务与 idle 清理协程,清空 [sessions]。
     */
    fun clearAll() {
        sessions.values.forEach { it.generationJob?.cancel() }
        sessions.clear()
        idleReaper.cancelAll()
    }
}

/**
 * 会话 idle 清理器:对每个会话至多保留一个延迟清理任务。
 */
private class SessionIdleReaper(
    private val scope: CoroutineScope,
    private val sessions: ConcurrentHashMap<String, ConversationSessionManager.SessionRef>,
    private val timeoutMs: Long,
) {

    private val pendingJobs = ConcurrentHashMap<String, Job>()

    fun schedule(sessionId: String) {
        cancel(sessionId)
        pendingJobs[sessionId] = scope.launch {
            delay(timeoutMs)
            val ref = sessions[sessionId]
            if (ref != null && ref.refCount.get() <= 0 && ref.generationJob == null) {
                sessions.remove(sessionId)
                pendingJobs.remove(sessionId)
            }
        }
    }

    fun cancel(sessionId: String) {
        pendingJobs.remove(sessionId)?.cancel()
    }

    fun cancelAll() {
        pendingJobs.values.forEach { it.cancel() }
        pendingJobs.clear()
    }
}
