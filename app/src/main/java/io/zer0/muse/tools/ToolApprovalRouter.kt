package io.zer0.muse.tools

import io.zer0.common.Logger

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

/**
 * 单例路由器,由 ChatViewModel 在初始化时注册为 delegate。
 *
 * B-4: 原实现为单个 [@Volatile][kotlin.jvm.Volatile] delegate 字段,多会话/子代理
 * 共用该全局单例时,后注册的 ChatViewModel 会静默覆盖前者,导致子代理审批卡被
 * 错误路由到别的会话(审批请求本身同步 suspend,无网络回调需按请求 id 归属,故走
 * 写入守卫方案)。
 *
 * 修复:delegate 置入时加守卫 —— 当已有不同实例且存在进行中的审批请求(inFlight>0)
 * 时拒绝覆盖并记日志,杜绝审批进行中被跨会话窃取;空闲期切换 delegate 属正常活跃
 * 会话变更,予以放行。inFlight 与 [request] 一一配对,取消路径(全将抛出
 * CancellationException)也经 finally 归零,不会泄漏计数。
 */
class ToolApprovalRouter {
    private val TAG = "ToolApprovalRouter"
    private val lock = Any()
    private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile
    private var backingDelegate: ToolApprovalBridge? = null

    /**
     * 当前审批桥接实例。
     *
     * 置入时校验:若已有其它实例且存在进行中的审批请求,拒绝覆盖并记录日志,
     * 避免跨会话覆盖把进行中的审批卡切走。
     */
    var delegate: ToolApprovalBridge?
        get() = backingDelegate
        set(value) {
            synchronized(lock) {
                val current = backingDelegate
                if (value != null && current != null && current !== value && inFlight.get() > 0) {
                    Logger.w(TAG, "审批进行中,拒绝替换审批 delegate(跨会话覆盖) | inFlight=${inFlight.get()}")
                    return
                }
                backingDelegate = value
            }
        }

    suspend fun request(
        toolName: String,
        toolCallId: String,
        argsPreview: String,
        args: Map<String, Any?> = emptyMap(),
    ): ToolApprovalState {
        val bridge = backingDelegate ?: return ToolApprovalState.Denied("审批通道未就绪")
        inFlight.incrementAndGet()
        return try {
            bridge.requestToolApproval(toolName, toolCallId, argsPreview, args)
        } finally {
            inFlight.decrementAndGet()
        }
    }
}