package io.zer0.muse.tools

/**
 * B2-04: 子代理/主会话共享的工具审批桥接。
 *
 * [SubagentRunner] 遇到 ASK_EVERY_TIME 工具时不再直接拒绝,而是通过
 * [ToolApprovalRouter] 把请求路由到当前 ChatViewModel,复用主会话的审批卡。
 */
interface ToolApprovalBridge {
    suspend fun requestToolApproval(
        toolName: String,
        toolCallId: String,
        argsPreview: String,
        args: Map<String, Any?> = emptyMap(),
    ): ToolApprovalState
}

/** 单例路由器,由 ChatViewModel 在初始化时注册为 delegate。 */
class ToolApprovalRouter {
    @Volatile
    var delegate: ToolApprovalBridge? = null

    suspend fun request(
        toolName: String,
        toolCallId: String,
        argsPreview: String,
        args: Map<String, Any?> = emptyMap(),
    ): ToolApprovalState =
        delegate?.requestToolApproval(toolName, toolCallId, argsPreview, args)
            ?: ToolApprovalState.Denied("审批通道未就绪")
}
