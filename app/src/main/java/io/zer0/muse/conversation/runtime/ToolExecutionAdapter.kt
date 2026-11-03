package io.zer0.muse.conversation.runtime

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
        return try {
            val result =
                if (context == null) {
                    registry.execute(invocation.toolName, args)
                } else {
                    registry.execute(invocation.toolName, args, context)
                }
            if (result.isError) {
                ToolOutcome.Failed(result.content)
            } else {
                ToolOutcome.Succeeded(result.content)
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            ToolOutcome.Failed(error.message ?: "tool execution failed")
        }
    }

    fun riskOf(toolName: String): ToolRisk =
        when (registry.getToolRiskLevel(toolName)) {
            ToolRiskLevel.SAFE -> ToolRisk.Safe
            ToolRiskLevel.NORMAL -> ToolRisk.Normal
            ToolRiskLevel.HIGH, null -> ToolRisk.High
        }

    private fun parseArguments(json: String): Map<String, String>? =
        runCatching {
            val element = io.zer0.common.AppJson.parseToJsonElement(json)
            element as? kotlinx.serialization.json.JsonObject
        }.getOrNull()?.entries?.associate { (key, value) ->
            key to ((value as? kotlinx.serialization.json.JsonPrimitive)?.content ?: value.toString())
        }
}
