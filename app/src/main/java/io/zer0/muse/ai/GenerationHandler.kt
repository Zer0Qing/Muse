package io.zer0.muse.ai

import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.ai.registry.ModelRegistry
import io.zer0.common.Logger
import io.zer0.muse.tools.ToolApprovalState
import io.zer0.muse.tools.ToolConfigStore
import io.zer0.muse.tools.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TAG = "GenerationHandler"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

/**
 * 支持多步工具循环的生成处理器。
 *
 * 负责 Agent 循环的完整状态机：
 * 1. 带当前消息与工具调用 LLM
 * 2. 从流式响应中累积文本与工具调用
 * 3. 检查审批状态（自动/待审批/已拒绝）
 * 4. 执行已批准的工具，把拒绝结果回灌给模型
 * 5. 将结果合并回会话
 * 6. 持续循环直到没有更多工具调用或达到最大步数
 *
 * 超过 [MAX_TOOL_OUTPUT_CHARS] 的工具输出会被截断并附带摘要。
 * 取消信号（[kotlinx.coroutines.CancellationException]）会向上传播，不吞协程取消。
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
        val enhancedModel = model?.let { ModelRegistry.enhanceModel(it) }
        val effectiveTools = if (enhancedModel?.supportsToolCalling() == true) tools else null

        return GenerationLoop(
            chatService = chatService,
            toolRegistry = toolRegistry,
            toolConfigStore = toolConfigStore,
            json = json,
            messages = messages,
            enhancedModel = enhancedModel,
            effectiveTools = effectiveTools,
            maxSteps = maxSteps,
            providerConfig = providerConfig,
            temperature = temperature,
            maxTokens = maxTokens,
            reasoningLevel = reasoningLevel,
            onStepResult = onStepResult,
            approvalCallback = approvalCallback,
        ).run()
    }
}

/**
 * 生成循环状态机：持有会话历史与当前模型，逐轮推进直到终态。
 */
private class GenerationLoop(
    private val chatService: ChatService,
    private val toolRegistry: ToolRegistry,
    private val toolConfigStore: ToolConfigStore,
    private val json: Json,
    private val messages: List<UIMessage>,
    private val enhancedModel: Model?,
    private val effectiveTools: List<ToolDefinition>?,
    private val maxSteps: Int,
    private val temperature: Float?,
    private val providerConfig: ProviderConfig?,
    private val maxTokens: Int?,
    private val reasoningLevel: ReasoningLevel,
    private val onStepResult: (GenerationHandler.StepResult) -> Unit,
    private val approvalCallback: (suspend (toolName: String, argsPreview: String) -> ToolApprovalState)?,
) {

    private val conversationHistory = messages.toMutableList()

    suspend fun run(): List<UIMessage> {
        for (step in 0 until maxSteps) {
            Logger.d(TAG, "Step #$step (model=${enhancedModel?.id})")

            val outcome = runStep(step)
            conversationHistory += outcome.assistantMessage

            if (outcome.isFinal) {
                onStepResult(outcome)
                break
            }

            val toolResults = outcome.toolResults
            val toolResultContent = toolResults.joinToString("\n") { result ->
                "[${result.toolName}]: ${truncateOutput(result.output)}"
            }
            conversationHistory += UIMessage(
                role = MessageRole.TOOL,
                content = toolResultContent,
            )

            onStepResult(outcome)
        }
        return conversationHistory
    }

    private suspend fun runStep(step: Int): GenerationHandler.StepResult {
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            // v1.0.27 P0-1.4: 重抛取消信号,避免吞协程取消
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Stream error at step $step: ${e.message}")
            streamError = e.message ?: "Unknown error"
        }

        if (streamError != null) {
            val errorMsg = UIMessage(
                role = MessageRole.ASSISTANT,
                content = "Error: $streamError",
            )
            return GenerationHandler.StepResult(assistantMessage = errorMsg, isFinal = true)
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

        // 没有工具调用 → 终态
        if (toolCalls.isEmpty()) {
            return GenerationHandler.StepResult(assistantMessage = assistantMessage, isFinal = true)
        }

        val toolResults = toolCalls.map { tc -> resolveAndRunTool(tc) }
        return GenerationHandler.StepResult(
            assistantMessage = assistantMessage,
            toolCalls = toolCalls,
            toolResults = toolResults,
            isFinal = false,
        )
    }

    private suspend fun resolveAndRunTool(tc: ToolCall): GenerationHandler.ToolResult {
        val approvalState = resolveApproval(tc)
        return when (approvalState) {
            is ToolApprovalState.Denied -> {
                // v1.0.27 P0-1.4: 用 buildJsonObject 安全构造 JSON,避免 reason 含双引号破坏结构
                GenerationHandler.ToolResult(
                    toolCallId = tc.id,
                    toolName = tc.name,
                    output = buildJsonObject {
                        put("error", "Tool denied by user")
                        put("reason", approvalState.reason)
                    }.toString(),
                    isSuccess = false,
                    approvalState = approvalState,
                )
            }
            is ToolApprovalState.Auto, is ToolApprovalState.Approved -> executeTool(tc)
            else -> GenerationHandler.ToolResult(
                toolCallId = tc.id,
                toolName = tc.name,
                output = """{"error": "Unexpected approval state"}""",
                isSuccess = false,
            )
        }
    }

    private suspend fun resolveApproval(tc: ToolCall): ToolApprovalState {
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

    private suspend fun executeTool(tc: ToolCall): GenerationHandler.ToolResult {
        return try {
            val result = toolRegistry.executeFromJson(tc.name, tc.arguments)
            GenerationHandler.ToolResult(
                toolCallId = tc.id,
                toolName = tc.name,
                output = result,
                isSuccess = true,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // v1.0.27 P0-1.4: 重抛取消信号,避免吞协程取消
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Tool execution failed: ${tc.name}: ${e.message}")
            // v1.0.27 P0-1.4: 用 buildJsonObject 安全构造 JSON,避免 e.message 含双引号/反斜杠破坏结构
            GenerationHandler.ToolResult(
                toolCallId = tc.id,
                toolName = tc.name,
                output = buildJsonObject {
                    put("error", e.message ?: "Unknown error")
                }.toString(),
                isSuccess = false,
            )
        }
    }

    // CyclomaticComplexMethod 豁免:collectStream 是对 ChatStreamEvent 全分支的收集器,
    // 每个分支都是单行转发,复杂度随事件类型数量线性增长(A5 新增 UsageDelta 后 15>15 越线)。
    // 拆分反而降低可读性,先例: ToolOrchestrator @Suppress("LongParameterList")。
    @Suppress("CyclomaticComplexMethod")
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
                        // v1.0.81: isSnapshot=true 表示参数是完整快照(源头已合并多 JSON 分片),
                        //   替换而非追加,避免重新拼成 {..}{..} 拼接串。
                        if (event.isSnapshot) {
                            toolCallAccumulator[idx] = Triple(existing.first, existing.second, StringBuilder(event.argumentsDelta ?: ""))
                        } else {
                            // 累积参数增量
                            event.argumentsDelta?.let { existing.third.append(it) }
                        }
                        // 如果当前分片携带 id/name 则更新（首个分片）
                        val newId = event.id ?: existing.first
                        val newName = event.name ?: existing.second
                        toolCallAccumulator[idx] = Triple(newId, newName, toolCallAccumulator[idx]!!.third)
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
                is ChatStreamEvent.FallbackNotice -> { /* 已自动降级为非流式 */ }
                // A5: token 用量 — 工具循环路径只拼正文/工具调用,用量由 ChatViewModel 消费
                is ChatStreamEvent.UsageDelta -> { /* 忽略 */ }
                is ChatStreamEvent.CitationDelta -> { /* 原生搜索引用由主聊天路径消费 */ }
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
