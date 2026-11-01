package io.zer0.muse.data.plugin

import io.zer0.muse.tools.ToolCallGate
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 把"插件请求调用另一个工具"落到宿主上需要的两样东西。
 *
 * 单独一个类（而不是把 4 个依赖塞进 [PluginHostApi]）是为了让注入面清楚：
 * 审批判定与工具执行都必须由宿主提供，插件侧无法自行替换。
 */
class PluginToolInvokeBridge(
    /**
     * 审批判定。**故意做成函数而不是直接塞 [ToolCallGate] 实例**：
     * 闸门要真审批就得连着 ToolRegistry/ToolConfigStore，测试里没法替换成假审批。
     * 生产侧由 Koin 用真闸门绑定它，测试侧给 lambda —— 同一个签名，不存在"测的是另一套逻辑"。
     */
    private val gateCall: suspend (toolName: String, toolCallId: String, argumentsJson: String) -> ToolCallGate.Outcome,
    private val executeTool: suspend (name: String, argumentsJson: String) -> String,
) {
    /** 复用主会话那条审批链；拒绝时 reason 已带"该怎么改道"。 */
    suspend fun gate(toolName: String, toolCallId: String, argumentsJson: String): ToolCallGate.Outcome =
        gateCall(toolName, toolCallId, argumentsJson)

    /** 实际执行。调用方必须已经在 [PluginToolCallMarker] 上下文里。 */
    suspend fun execute(name: String, argumentsJson: String): String = executeTool(name, argumentsJson)

    companion object {
        /** 生产装配：审批判定交给共享闸门，发起方在文案里称"插件"。 */
        fun of(gate: ToolCallGate): suspend (String, String, String) -> ToolCallGate.Outcome =
            { name, id, args -> gate.gate(name, id, args, subject = "插件") }
    }
}

/**
 * 协程上下文标记：当前调用链里已经有一次"由插件发起的工具调用"在跑。
 *
 * 用协程上下文而不是 ThreadLocal，是因为工具执行会跨调度器换线程 ——
 * 换线程后 ThreadLocal 就丢了，深度限制会静默失效（这类"看着有、实际没生效"的护栏最危险）。
 */
class PluginToolCallMarker(val pluginId: String) : AbstractCoroutineContextElement(PluginToolCallMarker) {
    companion object Key : CoroutineContext.Key<PluginToolCallMarker>
}
