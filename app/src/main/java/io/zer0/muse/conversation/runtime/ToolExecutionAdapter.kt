package io.zer0.muse.conversation.runtime

import io.zer0.common.Result
import io.zer0.common.resultOf
import io.zer0.muse.conversation.core.ToolExecutor
import io.zer0.muse.conversation.core.ToolInvocation
import io.zer0.muse.conversation.core.ToolOutcome
import io.zer0.muse.conversation.core.ToolRisk
import io.zer0.muse.tools.ToolExecutionContext
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel

/**
 * Adapts the existing validated tool registry to the conversation execution
 * boundary. Permission, cancellation and argument checks remain in the registry.
 */
class ToolExecutionAdapter(
    private val registry: ToolRegistry,
    private val context: ToolExecutionContext? = null,
) : ToolExecutor {
    override suspend fun execute(invocation: ToolInvocation): ToolOutcome {
        val args = parseArguments(invocation.argumentsJson)
            ?: return ToolOutcome.Failed("invalid tool arguments")
        // `resultOf` 与原来的手写 try/catch 等价：它同样**重抛 CancellationException**，
        // 其它异常带 message 转成 Failed，兜底句保持原样。区别只是把"边界捕获"交回
        // 仓库里那一处实现，而不是每个适配器各写一遍（各写一遍迟早有人漏掉重抛）。
        val executed = resultOf {
            if (context == null) {
                registry.execute(invocation.toolName, args)
            } else {
                registry.execute(invocation.toolName, args, context)
            }
        }
        if (executed is Result.Error) {
            return ToolOutcome.Failed(executed.throwable?.message ?: "tool execution failed")
        }
        val toolResult = executed.getOrThrow()
        return if (toolResult.isError) {
            ToolOutcome.Failed(toolResult.content)
        } else {
            ToolOutcome.Succeeded(toolResult.content)
        }
    }

    fun riskOf(toolName: String): ToolRisk = when (registry.getToolRiskLevel(toolName)) {
        ToolRiskLevel.SAFE -> ToolRisk.Safe
        ToolRiskLevel.NORMAL -> ToolRisk.Normal
        ToolRiskLevel.HIGH, null -> ToolRisk.High
    }

    private fun parseArguments(json: String): Map<String, String>? = runCatching {
        val element = io.zer0.common.AppJson.parseToJsonElement(json)
        element as? kotlinx.serialization.json.JsonObject
    }.getOrNull()?.entries?.associate { (key, value) ->
        key to ((value as? kotlinx.serialization.json.JsonPrimitive)?.content ?: value.toString())
    }
}
