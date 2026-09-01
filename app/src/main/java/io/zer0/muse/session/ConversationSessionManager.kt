package io.zer0.muse.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

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
 * M1.1 演进:内部持有对象从只含引用计数的 SessionRef 升级为 [SessionRuntime]
 * (turn 检查点 + 生成 Job + 取消标志 + 引用计数)。对外公共 API
 * (acquire/release/setGenerationJob/cancelGeneration/clearAll)保持不变;
 * 新增 [runtime]/[getOrCreateRuntime]/[beginTurn] 供生成链路读写 turn 检查点。
 *
 * 使用约定:
 *  - ChatViewModel.switchSession / createNewSession 等会话切换入口:
 *      release(oldSessionId); acquire(newSessionId)
 *  - launchStream 启动生成:beginTurn(sessionId, turnId) + setGenerationJob(sessionId, job)
 *  - onCleared / release:release(currentSessionId)
 *
 * 线程安全:[ConcurrentHashMap] + [SessionRuntime] 内部原子类型保证多线程安全;
 *  idle 清理器运行在构造时传入的 [scope] 中。
 *
 * @param scope idle 清理器运行的 CoroutineScope(应使用应用级 appScope,避免随 ViewModel 生命周期取消)
 */
class ConversationSessionManager(
    private val scope: CoroutineScope,
) {

    private val sessions = ConcurrentHashMap<String, SessionRuntime>()
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
        val runtime = getOrCreateRuntime(sessionId)
        runtime.refCount.incrementAndGet()
        runtime.touch()
        idleReaper.cancel(sessionId)
    }

    /**
     * 释放会话引用(引用计数 -1)。
     *
     * 应在离开会话(switchSession 旧会话/onCleared/release 等)时调用。
     * 引用计数归零时调度 idle 清理(等 [IDLE_TIMEOUT_MS] 后移除,留出短时间切回的窗口)。
     */
    fun release(sessionId: String) {
        val runtime = sessions[sessionId] ?: return
        val count = runtime.refCount.decrementAndGet()
        if (count <= 0) {
            idleReaper.schedule(sessionId)
        }
    }

    /**
     * 设置生成任务(自动取消前一个)。
     *
     * M1.1:会话未 acquire 过时自动创建 [SessionRuntime](群聊 "group:$chatId" 等
     * 生成 id 可能从未走 acquire 路径,旧实现直接 return 会让生成任务脱离引用管理)。
     *
     * 应在 [io.zer0.muse.ui.ChatViewModel.launchStream] 启动生成时调用,
     * 把 chatGenerationManager 返回的 Job 传进来,本类负责:
     *  - 取消前一个生成任务(防重入)
     *  - 任务完成时清理引用 + 调度 idle 清理
     */
    fun setGenerationJob(sessionId: String, job: Job?) {
        val runtime = getOrCreateRuntime(sessionId)
        synchronized(runtime) {
            runtime.generationJob?.cancel()
            runtime.generationJob = job
        }
        // 审查修复(P1):回调闭包捕获自己的 job,完成后仅在"运行时登记的仍是本 job"时清理。
        // 同 session 快速重入时,旧 job 的完成回调可能晚于新 job 的登记执行
        // (NonCancellable 收尾拖慢 completion);无条件置 null 会把新 job 引用清空,
        // 导致后台生成被误判结束、cancelGeneration 失效、idle reaper 误清理。
        job?.invokeOnCompletion {
            synchronized(runtime) {
                if (runtime.generationJob === job) {
                    runtime.generationJob = null
                    runtime.touch()
                }
            }
            // 引用计数归零且当前无生成任务时才调度 idle 清理;
            // 新 job 仍在挂时(generationJob != null)reaper 触发后也会跳过。
            if (runtime.refCount.get() <= 0) {
                idleReaper.schedule(sessionId)
            }
        }
    }

    /** 取消会话的生成任务(用户点停止时调用),并记录取消标志。 */
    fun cancelGeneration(sessionId: String) {
        val runtime = sessions[sessionId] ?: return
        runtime.requestCancel()
        runtime.generationJob?.cancel()
    }

    /** M1.1: 读取会话运行时(不存在时返回 null,只读观察用)。 */
    fun runtime(sessionId: String): SessionRuntime? = sessions[sessionId]

    /** M1.1: 获取或创建会话运行时 — 同一 sessionId 永远复用同一实例。 */
    fun getOrCreateRuntime(sessionId: String): SessionRuntime =
        sessions.computeIfAbsent(sessionId) { SessionRuntime(it) }

    /**
     * M1.1: 生成链路开启新 turn 的便捷入口 — 运行时不存在时自动创建,
     * 保证 launchStream 闭包内第一个检查点写入不会丢失。
     */
    fun beginTurn(sessionId: String, turnId: String) {
        getOrCreateRuntime(sessionId).beginTurn(turnId)
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
    private val sessions: ConcurrentHashMap<String, SessionRuntime>,
    private val timeoutMs: Long,
) {

    private val pendingJobs = ConcurrentHashMap<String, Job>()

    fun schedule(sessionId: String) {
        cancel(sessionId)
        pendingJobs[sessionId] = scope.launch {
            delay(timeoutMs)
            val runtime = sessions[sessionId]
            if (runtime != null && runtime.refCount.get() <= 0 && runtime.generationJob == null) {
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
