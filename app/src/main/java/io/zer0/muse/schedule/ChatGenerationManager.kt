package io.zer0.muse.schedule

import io.zer0.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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
 *  - [activeGenerations] 按 sessionId 暴露全部活跃任务,供 [ChatGenerationService] 保活。
 *  - [activeGeneration] 保留为兼容 API,返回最近更新的任务。
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

    /** 所有活跃生成任务的快照, key 为 sessionId。 */
    private val _activeGenerations = MutableStateFlow<Map<String, ActiveGeneration>>(emptyMap())
    val activeGenerations: StateFlow<Map<String, ActiveGeneration>> = _activeGenerations.asStateFlow()

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
            // 取消同一 sessionId 的旧 job(防重入),不影响其他 sessionId。
            // 新任务会在旧 Job 完成 finally 后才进入 block，避免两代流同时写同一会话。
            val previousJob = streamJobs.remove(sessionId)
            previousJob?.cancel()
            // 先同步写入活跃状态,再启动 appScope 协程。
            // ON_STOP 可能紧跟在发送动作后发生;如果状态只在 launch 块内部异步写入,
            // 前台保活服务会错过这次生成,后台进程可能被系统回收。
            val generation = ActiveGeneration(
                sessionId = sessionId,
                assistantId = assistantId,
                sessionTitle = sessionTitle,
                isStreaming = true,
                lastUpdatedAt = System.currentTimeMillis(),
            )
            _activeGenerations.value = _activeGenerations.value + (sessionId to generation)
            _activeGeneration.value = generation
            // 先登记再启动,避免极短任务在 streamJobs 写入前完成,导致 finally
            // 无法确认自己是当前任务,留下永远活跃的快照。
            val job = appScope.launch(start = CoroutineStart.LAZY) {
                previousJob?.join()
                val heartbeatJob = launch {
                    while (isActive) {
                        delay(HEARTBEAT_INTERVAL_MS)
                        touch(sessionId)
                    }
                }
                try {
                    block()
                } finally {
                    heartbeatJob.cancel()
                    // 同一 session 快速重入时,旧 job 的 finally 不能把新 job 标记成已结束。
                    val isCurrentJob = synchronized(lock) {
                        streamJobs[sessionId] === coroutineContext[Job]
                    }
                    if (isCurrentJob) {
                        synchronized(lock) {
                            _activeGenerations.value = _activeGenerations.value - sessionId
                            // 保留 activeGeneration 的旧兼容语义:最后一个任务结束后,
                            // 观察者仍会收到一次 isStreaming=false;前台服务观察的是
                            // activeGenerations,不会因为这个兼容状态误判仍有任务。
                            val next = _activeGenerations.value.values.maxByOrNull { it.lastUpdatedAt }
                            _activeGeneration.value = next ?: generation.copy(
                                isStreaming = false,
                                lastUpdatedAt = System.currentTimeMillis(),
                            )
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
            job.start()
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
                _activeGenerations.value = _activeGenerations.value - sessionId
                // 只在当前 active 是该 sessionId 时才清空
                if (_activeGeneration.value?.sessionId == sessionId) {
                    _activeGeneration.value = _activeGenerations.value.values.maxByOrNull { it.lastUpdatedAt }
                }
                Logger.i("ChatGenMgr", "generation stopped: $sessionId")
            } else {
                // 取消全部
                streamJobs.values.forEach { it.cancel() }
                streamJobs.clear()
                _activeGenerations.value = emptyMap()
                _activeGeneration.value = null
                Logger.i("ChatGenMgr", "all generations stopped")
            }
        }
    }

    /**
     * 刷新指定会话的心跳,避免后台被系统判定为无活跃任务。
     *
     * @param sessionId 会话 id;为 null 时兼容旧调用,刷新最近任务。
     */
    fun touch(sessionId: String? = null) {
        synchronized(lock) {
            val targetId = sessionId ?: _activeGeneration.value?.sessionId ?: return
            val current = _activeGenerations.value[targetId] ?: return
            val touched = current.copy(lastUpdatedAt = System.currentTimeMillis())
            _activeGenerations.value = _activeGenerations.value + (targetId to touched)
            _activeGeneration.value = _activeGenerations.value.values.maxByOrNull { it.lastUpdatedAt }
        }
    }

    /**
     * v1.111: 更新当前生成的会话标题(群聊场景异步获取群聊名后更新通知显示)。
     */
    fun updateSessionTitle(title: String) {
        val sessionId = synchronized(lock) { _activeGeneration.value?.sessionId }
        if (sessionId != null) updateSessionTitle(sessionId, title)
    }

    /** 更新指定会话的通知标题,避免多会话并发时误改最近任务的标题。 */
    fun updateSessionTitle(sessionId: String, title: String) {
        synchronized(lock) {
            val current = _activeGenerations.value[sessionId] ?: return
            val updated = current.copy(sessionTitle = title, lastUpdatedAt = System.currentTimeMillis())
            _activeGenerations.value = _activeGenerations.value + (sessionId to updated)
            _activeGeneration.value = _activeGenerations.value.values.maxByOrNull { it.lastUpdatedAt }
        }
    }

    /** v1.113: 检查指定 sessionId 是否有活跃生成。 */
    fun isStreaming(sessionId: String): Boolean {
        synchronized(lock) {
            val job = streamJobs[sessionId]
            return job != null && job.isActive
        }
    }

    private companion object {
        /** 任务没有文本输出时仍定期证明协程存活,避免长工具调用被误判为卡死。 */
        const val HEARTBEAT_INTERVAL_MS = 30_000L
    }
}
