package io.zer0.muse.schedule

import io.zer0.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * v1.43: 应用级聊天生成管理器。
 *
 * 解决切页 / 后台时 AI 生成被 ViewModel 生命周期中断的问题:
 * - 生成任务运行在 [appScope] 而非 [androidx.lifecycle.viewModelScope],不依赖页面生命周期
 * - 维护 [activeGeneration] 状态,供新创建的 [ChatViewModel] 重新绑定
 * - 生成结束后自动清理;用户点"停止"时取消任务
 *
 * v1.113: 重构为按 sessionId 独立管理多个并发生成。
 *  - 旧设计:全局一个 streamJob,单聊和群聊互相 cancel,导致一方生成中断。
 *  - 新设计:用 Map<sessionId, Job> 独立管理每个生成任务,单聊和群聊可同时进行互不干扰。
 *  - [activeGeneration] 仍保持单个(取最新活跃的),供 [ChatGenerationService] 前台服务通知用。
 *  - [stop] 只取消指定 sessionId 的生成;[stopAll] 取消全部。
 *  - finally 块只更新自己 sessionId 对应的状态,避免竞态覆盖。
 */
class ChatGenerationManager(
    private val appScope: CoroutineScope,
) {

    /** 当前活跃的生成会话。null 表示没有正在生成。 */
    data class ActiveGeneration(
        val sessionId: String,
        val assistantId: String,
        val sessionTitle: String,
        val isStreaming: Boolean = true,
        val lastUpdatedAt: Long = System.currentTimeMillis(),
    )

    private val _activeGeneration = MutableStateFlow<ActiveGeneration?>(null)
    val activeGeneration: StateFlow<ActiveGeneration?> = _activeGeneration.asStateFlow()

    // v1.113: 按 sessionId 独立管理 Job,单聊和群聊互不抢占。
    private val streamJobs = mutableMapOf<String, Job>()
    private val lock = Any()

    /**
     * 在应用级协程中启动生成任务。
     *
     * v1.113: 同一 sessionId 的旧生成会被取消(防重入),不同 sessionId 的生成互不影响。
     * v1.x: 返回值改为 [Job],供调用方(如 [io.zer0.muse.session.ConversationSessionManager.setGenerationJob])
     * 跟踪会话级生成任务,实现引用计数 + idle 清理。
     *
     * @param sessionId 当前会话 id(单聊为会话 id,群聊为 "group:$chatId")
     * @param assistantId 当前占位 assistant 消息 id
     * @param sessionTitle 会话标题(用于通知/日志)
     * @param block 实际的流式生成逻辑
     * @return 生成任务的 [Job],可用于 join/cancel 或注册完成回调
     */
    fun launchGeneration(
        sessionId: String,
        assistantId: String,
        sessionTitle: String,
        block: suspend () -> Unit,
    ): Job {
        synchronized(lock) {
            // 取消同一 sessionId 的旧 job(防重入),不影响其他 sessionId
            streamJobs.remove(sessionId)?.cancel()
            val job = appScope.launch {
                _activeGeneration.value = ActiveGeneration(
                    sessionId = sessionId,
                    assistantId = assistantId,
                    sessionTitle = sessionTitle,
                    isStreaming = true,
                    lastUpdatedAt = System.currentTimeMillis(),
                )
                try {
                    block()
                } finally {
                    // v1.113: 只更新自己 sessionId 的状态,避免取消时竞态覆盖其他生成
                    _activeGeneration.update { current ->
                        if (current?.sessionId == sessionId) {
                            current.copy(isStreaming = false, lastUpdatedAt = System.currentTimeMillis())
                        } else {
                            // 另一个生成已成为 active,不要覆盖它
                            current
                        }
                    }
                    synchronized(lock) {
                        // 审计修复 (3.2): 按 Job 身份判断,仅当 map 中登记的仍是当前 job 时才移除。
                        // 防止重入场景下旧 job 被取消后,其 finally 无条件 remove 误删新 job 的登记。
                        if (streamJobs[sessionId] === coroutineContext[Job]) {
                            streamJobs.remove(sessionId)
                        }
                    }
                    Logger.i("ChatGenMgr", "generation finished: $sessionId")
                }
            }
            streamJobs[sessionId] = job
            return job
        }
    }

    /**
     * 用户手动停止或页面主动取消指定会话的生成。
     *
     * v1.113: 只取消 [sessionId] 对应的生成,不影响其他会话(如群聊生成中用户停止单聊)。
     *
     * @param sessionId 要停止的会话 id;不传则取消全部(兼容旧调用方)
     */
    fun stop(sessionId: String? = null) {
        synchronized(lock) {
            if (sessionId != null) {
                streamJobs.remove(sessionId)?.cancel()
                // 只在当前 active 是该 sessionId 时才清空
                _activeGeneration.update { current ->
                    if (current?.sessionId == sessionId) null else current
                }
                Logger.i("ChatGenMgr", "generation stopped: $sessionId")
            } else {
                // 取消全部
                streamJobs.values.forEach { it.cancel() }
                streamJobs.clear()
                _activeGeneration.value = null
                Logger.i("ChatGenMgr", "all generations stopped")
            }
        }
    }

    /** 心跳刷新,避免后台被系统判定为无活跃任务(由流式循环周期性调用)。 */
    fun touch() {
        _activeGeneration.update {
            it?.copy(lastUpdatedAt = System.currentTimeMillis())
        }
    }

    /**
     * v1.111: 更新当前生成的会话标题(群聊场景异步获取群聊名后更新通知显示)。
     */
    fun updateSessionTitle(title: String) {
        _activeGeneration.update {
            it?.copy(sessionTitle = title, lastUpdatedAt = System.currentTimeMillis())
        }
    }

    /** v1.113: 检查指定 sessionId 是否有活跃生成。 */
    fun isStreaming(sessionId: String): Boolean {
        synchronized(lock) {
            val job = streamJobs[sessionId]
            return job != null && job.isActive
        }
    }
}
