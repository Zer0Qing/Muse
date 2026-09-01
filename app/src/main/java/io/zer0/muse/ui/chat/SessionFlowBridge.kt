package io.zer0.muse.ui.chat

/**
 * v1.x: [ChatSessionController] 的跨职责回调 bundle。
 * 由 ChatViewModel 实现,使会话 Controller 不反向依赖宿主。
 */
interface SessionFlowBridge {
    suspend fun refreshContext()
    fun detachStreaming()
    fun onForkError(throwable: Throwable)
}
