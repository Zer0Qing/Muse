package io.zer0.muse.ui.chat

/**
 * v1.x: [ChatSessionController] 的跨职责回调 bundle。
 * 由 ChatViewModel 实现,使会话 Controller 不反向依赖宿主。
 */
interface SessionFlowBridge {
    suspend fun refreshContext()
    fun detachStreaming()
    fun onForkError(throwable: Throwable)

    /**
     * P0-5: 停止指定会话的全部在途生成(应用级 + 会话级取消)。
     * 删除/归档会话时必须先调用,防止流式回调继续写入已软删会话。
     */
    fun stopGenerationForSession(sessionId: String?)

    /**
     * P0-5: 标记该会话写入抑制 — 在途流式落盘(persist / upsert)跳过,
     * 防止已删/已归档会话被写入并在恢复后"复活"。新流式启动时自动清除。
     */
    fun suppressSessionWrites(sessionId: String?)
}
