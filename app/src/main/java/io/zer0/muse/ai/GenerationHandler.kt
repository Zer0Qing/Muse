package io.zer0.muse.ai

import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolCallInfo
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.ai.registry.ModelRegistry
import io.zer0.common.Logger
import io.zer0.muse.tools.ToolApprovalState
import io.zer0.muse.tools.ToolConfigStore
import io.zer0.muse.tools.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private const val TAG = "GenerationHandler"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

/**
 * 支持多步工具循环的生成处理器（移植自 RikkaHub GenerationHandler.kt）。
 *
 * 管理 Agent 循环：
 * 1. 带当前消息与工具调用 LLM
 * 2. 从响应中解析工具调用
 * 3. 检查审批状态（自动/待审批/已拒绝）
 * 4. 执行已批准的工具，上报被拒绝的
 * 5. 将结果合并回会话
 * 6. 持续循环直到没有更多工具调用或达到最大步数
 *
 * 超过 [MAX_TOOL_OUTPUT_CHARS] 的工具输出会被截断并附带摘要。
 */
class GenerationHandler(
    private val chatService: ChatService,
    private val toolRegistry: ToolRegistry,
    private val toolConfigStore: ToolConfigStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /**
     * 单步生成的结果。
     */
    data class StepResult(
        val assistantMessage: UIMessage,
        val toolCalls: List<ToolCall> = emptyList(),
        val toolResults: List<ToolResult> = emptyList(),
        val isFinal: Boolean = false,
    )

    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val output: String,
        val isSuccess: Boolean,
        val approvalState: ToolApprovalState = ToolApprovalState.Auto,
    )

    /**
     * 执行多步生成循环。
     *
     * @param messages 初始会话消息
     * @param model 要使用的模型
     * @param providerConfig Provider 配置
     * @param tools 提供给 LLM 的可用工具定义
     * @param maxSteps Agent 最大步数（默认 32）
     * @param temperature 采样温度
     * @param maxTokens 最大输出 token 数
     * @param reasoningLevel 思考模型的推理级别
     * @param onStepResult 每步结果回调（用于 UI 更新）
     * @param approvalCallback 工具需要用户审批时调用；返回 Approved/Denied
     * @return 所有消息的最终列表（原始 + 生成的）
     */
    suspend fun generate(
        messages: List<UIMessage>,
        model: Model?,
        providerConfig: ProviderConfig? = null,
        tools: List<ToolDefinition>? = null,
        maxSteps: Int = 32,
        temperature: Float? = null,
        maxTokens: Int? = null,
        reasoningLevel: ReasoningLevel = ReasoningLevel.DEFAULT,
        onStepResult: (StepResult) -> Unit = {},
        approvalCallback: (suspend (toolName: String, argsPreview: String) -> ToolApprovalState)? = null,
    ): List<UIMessage> {
        val conversationHistory = messages.toMutableList()
        val enhancedModel = model?.let { ModelRegistry.enhanceModel(it) }

        // 根据模型能力判断是否需要发送工具
        val effectiveTools = if (enhancedModel?.supportsToolCalling() == true) tools else null

        for (step in 0 until maxSteps) {
            Logger.d(TAG, "Step #$step (model=${enhancedModel?.id})")

            // 收集流式响应
            val builder = StringBuilder()
            val toolCallAccumulator = mutableMapOf<Int, Triple<String?, String?, StringBuilder>>()
            var streamError: String? = null

            try {
                val flow = chatService.streamChat(
                    messages = conversationHistory,
                    model = enhancedModel,
                    temperature = temperature,
                    maxTokens = maxTokens,
                    tools = effectiveTools,
                    reasoningLevel = reasoningLevel,
                    providerConfig = providerConfig,
                )
                collectStream(flow, builder, toolCallAccumulator) { error ->
                    streamError = error
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Stream error at step $step: ${e.message}")
                streamError = e.message ?: "Unknown error"
            }

            if (streamError != null) {
                val errorMsg = UIMessage(
                    role = MessageRole.ASSISTANT,
                    content = "Error: $streamError",
                )
                conversationHistory.add(errorMsg)
                onStepResult(StepResult(assistantMessage = errorMsg, isFinal = true))
                break
            }

            val assistantContent = builder.toString()
            val toolCalls = toolCallAccumulator.values.mapNotNull { (id, name, args) ->
                if (id != null && name != null) {
                    ToolCall(id = id, name = name, arguments = args.toString())
                } else null
            }

            val assistantMessage = UIMessage(
                role = MessageRole.ASSISTANT,
                content = assistantContent,
                toolCalls = if (toolCalls.isNotEmpty()) toolCalls else null,
            )
            conversationHistory.add(assistantMessage)

            // 没有工具调用 → 完成
            if (toolCalls.isEmpty()) {
                onStepResult(StepResult(assistantMessage = assistantMessage, isFinal = true))
                break
            }

            // 处理工具调用并审批
            val toolResults = mutableListOf<ToolResult>()
            for (tc in toolCalls) {
                // 检查审批状态
                val approvalState = resolveApproval(tc, approvalCallback)

                when (approvalState) {
                    is ToolApprovalState.Denied -> {
                        toolResults.add(
                            ToolResult(
                                toolCallId = tc.id,
                                toolName = tc.name,
                                output = """{"error": "Tool denied by user", "reason": "${approvalState.reason}"}""",
                                isSuccess = false,
                                approvalState = approvalState,
                            )
                        )
                    }
                    is ToolApprovalState.Auto, is ToolApprovalState.Approved -> {
                        val result = executeTool(tc)
                        toolResults.add(result)
                    }
                    else -> {
                        toolResults.add(
                            ToolResult(
                                toolCallId = tc.id,
                                toolName = tc.name,
                                output = """{"error": "Unexpected approval state"}""",
                                isSuccess = false,
                            )
                        )
                    }
                }
            }

            // 构造工具结果消息
            val toolResultContent = toolResults.joinToString("\n") { result ->
                "[${result.toolName}]: ${truncateOutput(result.output)}"
            }
            // 添加一条 TOOL 角色消息，包含合并后的结果
            val toolMessage = UIMessage(
                role = MessageRole.TOOL,
                content = toolResultContent,
            )
            conversationHistory.add(toolMessage)

            onStepResult(
                StepResult(
                    assistantMessage = assistantMessage,
                    toolCalls = toolCalls,
                    toolResults = toolResults,
                    isFinal = false,
                )
            )
        }

        return conversationHistory
    }

    private suspend fun resolveApproval(
        tc: ToolCall,
        approvalCallback: (suspend (String, String) -> ToolApprovalState)?,
    ): ToolApprovalState {
        // 先检查已存储的审批策略
        val storedState = toolConfigStore.resolveApprovalState(tc.name)
        return when (storedState) {
            is ToolApprovalState.Auto -> ToolApprovalState.Auto
            is ToolApprovalState.Denied -> storedState
            is ToolApprovalState.Pending -> {
                // 需要用户审批
                val argsPreview = tc.arguments.take(200)
                approvalCallback?.invoke(tc.name, argsPreview)
                    ?: ToolApprovalState.Auto // 无回调时回退为自动
            }
            else -> storedState
        }
    }

    private suspend fun executeTool(tc: ToolCall): ToolResult {
        return try {
            val result = toolRegistry.executeFromJson(tc.name, tc.arguments)
            ToolResult(
                toolCallId = tc.id,
                toolName = tc.name,
                output = result,
                isSuccess = true,
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Tool execution failed: ${tc.name}: ${e.message}")
            ToolResult(
                toolCallId = tc.id,
                toolName = tc.name,
                output = """{"error": "${e.message ?: "Unknown error"}"}""",
                isSuccess = false,
            )
        }
    }

    private suspend fun collectStream(
        flow: Flow<ChatStreamEvent>,
        builder: StringBuilder,
        toolCallAccumulator: MutableMap<Int, Triple<String?, String?, StringBuilder>>,
        onError: (String) -> Unit,
    ) {
        flow.collect { event ->
            when (event) {
                is ChatStreamEvent.ContentDelta -> builder.append(event.delta)
                is ChatStreamEvent.ToolCallDelta -> {
                    val idx = event.index
                    val existing = toolCallAccumulator[idx]
                    if (existing != null) {
                        // 累积参数增量
                        event.argumentsDelta?.let { existing.third.append(it) }
                        // 如果当前分片携带 id/name 则更新（首个分片）
                        val newId = event.id ?: existing.first
                        val newName = event.name ?: existing.second
                        toolCallAccumulator[idx] = Triple(newId, newName, existing.third)
                    } else {
                        // 该 index 的首个分片
                        toolCallAccumulator[idx] = Triple(event.id, event.name, StringBuilder(event.argumentsDelta ?: ""))
                    }
                }
                is ChatStreamEvent.ReasoningDelta -> { /* 思考 token，工具循环中忽略 */ }
                is ChatStreamEvent.ImageDelta -> { /* 图片输出，与工具循环无关 */ }
                is ChatStreamEvent.Done -> { /* 流结束 */ }
                is ChatStreamEvent.Error -> onError(event.message)
                is ChatStreamEvent.StreamInterrupted -> onError(event.message)
            }
        }
    }

    private fun truncateOutput(output: String): String {
        return if (output.length > MAX_TOOL_OUTPUT_CHARS) {
            output.take(TOOL_OUTPUT_PREVIEW_CHARS) +
                "\n... [truncated, ${output.length - TOOL_OUTPUT_PREVIEW_CHARS} chars omitted]"
        } else {
            output
        }
    }
}
