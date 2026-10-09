package io.zer0.muse.channel

import android.content.Context
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.SecureKeyStore
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.tools.ToolPermissionResolver
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel
import io.zer0.muse.tools.captureLargeToolOutput
import io.zer0.muse.transformer.TemplateTransformer
import io.zer0.muse.transformer.TransformContext
import io.zer0.muse.vision.VisionBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

@Serializable
internal data class ChannelAgentCheckpoint(
    val dispatchId: String,
    val workingMessages: List<UIMessage>,
    val inFlightToolCallId: String? = null,
    val sideEffectOutcomeUnknown: Boolean = false,
)

internal const val CHANNEL_AGENT_CHECKPOINT_UNREADABLE = "CHECKPOINT_UNREADABLE"

internal class ChannelAgentCheckpointUnreadableException(cause: Throwable) :
    IllegalStateException("渠道 Agent 恢复断点不可读取", cause)

private sealed interface ChannelAgentRunResult {
    data class Ready(val text: String) : ChannelAgentRunResult
    data object Retry : ChannelAgentRunResult
    data class Blocked(val errorCode: String) : ChannelAgentRunResult
}

private data class ChannelToolExecution(
    val content: String,
    val outcomeUnknown: Boolean,
)

/** 保守合并工具执行的不确定性,避免后续恢复时重跑潜在副作用。 */
internal fun channelToolOutcomeUnknown(
    previousUnknown: Boolean,
    recoveringUncertainCall: Boolean,
    executionOutcomeUnknown: Boolean,
): Boolean = previousUnknown || recoveringUncertainCall || executionOutcomeUnknown

/** NORMAL 工具出现异常或返回标准化错误时,保守视为副作用结果未知。 */
internal fun channelToolResultUnknown(risk: ToolRiskLevel, executionThrew: Boolean, result: String): Boolean {
    return risk == ToolRiskLevel.NORMAL &&
        (executionThrew || result.startsWith("Error:", ignoreCase = true))
}

private const val AGENT_CHECKPOINT_PREFIX = "muse.channel.agent-checkpoint.v1:"

internal suspend fun encryptChannelAgentCheckpoint(checkpoint: ChannelAgentCheckpoint): String {
    val encoded = AppJson.encodeToString(ChannelAgentCheckpoint.serializer(), checkpoint)
    return SecureKeyStore.encrypt(AGENT_CHECKPOINT_PREFIX + encoded)
}

@Suppress("TooGenericExceptionCaught") // Checkpoint decoding must classify every storage/format failure as terminal.
internal suspend fun decryptChannelAgentCheckpoint(encrypted: String): ChannelAgentCheckpoint {
    return try {
        val plaintext = SecureKeyStore.decryptOrNull(encrypted)
            ?: error("无法解密渠道 Agent 恢复断点")
        if (!plaintext.startsWith(AGENT_CHECKPOINT_PREFIX)) {
            error("渠道 Agent 恢复断点格式不匹配")
        }
        AppJson.decodeFromString(
            ChannelAgentCheckpoint.serializer(),
            plaintext.removePrefix(AGENT_CHECKPOINT_PREFIX),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw ChannelAgentCheckpointUnreadableException(error)
    }
}

/**
 * v2.0: 渠道自动回复 — 入站消息闭环(收到 → 跑一轮 → 回发到来源)。
 *
 * 由 [ChannelInbox] 的持久化队列驱动:入站消息可靠落盘后,若存在
 * 开启 [ChannelConfig.autoReply] 的渠道配置,则以绑定/默认助手跑一轮非流式对话,
 * 并把回复通过对应渠道回发到消息来源(from)。
 *
 * 设计约束:
 *  - 全局单消费者:事件在处理完成前保持持久化,进程重启后继续派发;
 *  - 失败按退避重试,生成结果先落盘再发送;
 *  - 入站 ACK 只确认本地队列已接收,外部发送窗口按至少一次语义处理。
 */
@Suppress("LargeClass") // Durable dispatch, agent recovery, and channel tool policy share one lifecycle owner.
class ChannelAutoReply(
    private val channelManager: ChannelManager,
    private val chatService: ChatService,
    private val assistantRepository: AssistantRepository,
    private val context: Context,
    private val appScope: CoroutineScope,
    /** v2.0.1: 用户档案(助手名/用户昵称) — 供系统提示词模板变量渲染。 */
    private val settings: SettingsRepository,
    /** v2.0.1: 工具注册表 — 渠道对话继承助手工具集(排除群聊工具与高风险工具)。 */
    private val toolRegistry: ToolRegistry,
    /** v2.0.1: 视觉辅助 — 模型不支持视觉时把图片转为文字描述(降级路径)。 */
    private val visionBridge: VisionBridge,
) {
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private var dispatcherJob: Job? = null

    /** v2.0.1: 模板渲染器 — 与聊天主链路一致地替换 {{char}}/{{user}} 等变量。 */
    private val templateTransformer = TemplateTransformer(context)

    /** 挂载入站监听(幂等,App 启动时调用一次)。 */
    @Synchronized
    @Suppress("TooGenericExceptionCaught") // App startup must keep retrying recoverable storage/key-store failures.
    fun start() {
        ChannelInbox.attach(context)
        ChannelConversationStore.attach(context)
        ChannelInbox.journal.setPendingListener {
            wakeups.trySend(Unit)
        }
        if (dispatcherJob?.isActive != true) {
            dispatcherJob = appScope.launch {
                while (currentCoroutineContext().isActive) {
                    try {
                        ChannelInbox.journal.recoverInterruptedDispatches()
                    } catch (error: Exception) {
                        Logger.e(TAG, "恢复中断的渠道事件失败: ${error.message}", error)
                        kotlinx.coroutines.delay(DISPATCH_STATE_RETRY_DELAY_MS)
                        continue
                    }
                    drainPending()
                    val retryDelay = ChannelInbox.journal.nextRetryDelayMillis()
                    if (retryDelay == null) {
                        wakeups.receive()
                    } else if (retryDelay > 0L) {
                        withTimeoutOrNull(retryDelay) { wakeups.receive() }
                    }
                }
            }
        }
        wakeups.trySend(Unit)
    }

    private suspend fun drainPending() {
        while (currentCoroutineContext().isActive) {
            if (!drainOnePending()) break
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun claimNextPendingOrDelay(): ChannelInbox.Inbound? = try {
        ChannelInbox.journal.claimNextPending()
    } catch (error: Exception) {
        Logger.e(TAG, "领取渠道入站事件失败: ${error.message}", error)
        kotlinx.coroutines.delay(DISPATCH_STATE_RETRY_DELAY_MS)
        null
    }

    @Suppress("TooGenericExceptionCaught") // Convert unexpected handler failures to a durable retry outcome.
    private suspend fun drainOnePending(): Boolean {
        val inbound = claimNextPendingOrDelay() ?: return false
        val outcome = try {
            handle(inbound)
        } catch (error: CancellationException) {
            runCatching { ChannelInbox.journal.releaseDispatch(inbound.dispatchId) }
            throw error
        } catch (error: Exception) {
            Logger.w(TAG, "自动回复处理异常,稍后重试: ${error.message}", error)
            HandleResult.Retry
        }
        return persistOutcome(inbound, outcome)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun persistOutcome(inbound: ChannelInbox.Inbound, outcome: HandleResult): Boolean = try {
        when (outcome) {
            is HandleResult.Sent -> {
                ChannelInbox.journal.completeDispatch(inbound.dispatchId, ignored = false)
                appScope.launch {
                    runCatching { maybeCompress(outcome.config, outcome.from) }
                        .onFailure { error -> Logger.w(TAG, "上下文压缩失败: ${error.message}") }
                }
            }
            HandleResult.Ignored -> ChannelInbox.journal.completeDispatch(inbound.dispatchId, ignored = true)
            is HandleResult.Blocked -> ChannelInbox.journal.blockDispatch(inbound.dispatchId, outcome.errorCode)
            HandleResult.Retry -> ChannelInbox.journal.retryDispatch(inbound.dispatchId)
        }
        true
    } catch (error: Exception) {
        Logger.e(TAG, "自动回复派发状态落盘失败: ${error.message}", error)
        false
    }

    private suspend fun handle(inbound: ChannelInbox.Inbound): HandleResult {
        val text = inbound.summary.trim()
        return if (text.isBlank()) HandleResult.Ignored else handleMessage(inbound, text)
    }

    private suspend fun handleMessage(inbound: ChannelInbox.Inbound, text: String): HandleResult {
        channelManager.refresh()
        val config = selectAutoReplyConfig(channelManager.channels.value, inbound)
        return if (config == null) {
            HandleResult.Ignored
        } else {
            ChannelConversationStore.appendTurn(
                config.id,
                inbound.from,
                ChannelConversationStore.Turn(
                    role = "user",
                    text = text,
                    mediaKind = inbound.mediaKind,
                    mediaBase64 = inbound.mediaBase64,
                    mediaPath = inbound.mediaPath,
                    dispatchId = inbound.dispatchId,
                ),
            )
            when (val reply = prepareReply(inbound, config)) {
                is ReplyPreparation.Ready -> sendPreparedReply(inbound, config, reply.text)
                ReplyPreparation.Ignored -> HandleResult.Ignored
                is ReplyPreparation.Blocked -> HandleResult.Blocked(reply.errorCode)
                ReplyPreparation.Retry -> HandleResult.Retry
            }
        }
    }

    private suspend fun prepareReply(inbound: ChannelInbox.Inbound, config: ChannelConfig): ReplyPreparation {
        val prepared = inbound.preparedReply
        if (prepared.isNotBlank()) return ReplyPreparation.Ready(prepared)
        return when (val generated = runAgent(config, inbound.from, inbound.dispatchId)) {
            is ChannelAgentRunResult.Blocked -> ReplyPreparation.Blocked(generated.errorCode)
            ChannelAgentRunResult.Retry -> ReplyPreparation.Retry
            is ChannelAgentRunResult.Ready -> if (generated.text.isBlank()) {
                Logger.w(TAG, "模型返回空回复,本条入站消息标记为忽略")
                ReplyPreparation.Ignored
            } else if (ChannelInbox.journal.savePreparedReply(inbound.dispatchId, generated.text)) {
                ReplyPreparation.Ready(generated.text)
            } else {
                ReplyPreparation.Retry
            }
        }
    }

    private suspend fun sendPreparedReply(inbound: ChannelInbox.Inbound, config: ChannelConfig, reply: String): HandleResult {
        ChannelConversationStore.appendTurn(
            config.id,
            inbound.from,
            ChannelConversationStore.Turn(
                role = "assistant",
                text = reply,
                dispatchId = inbound.dispatchId,
            ),
        )
        val contextTokenOverride = resolveReplyContextToken(inbound)
        return when (contextTokenOverride) {
            ContextTokenOverride.Unavailable -> HandleResult.Retry
            is ContextTokenOverride.Value -> {
                val qqEventIdOverride = qqReplyEventIdOverride(
                    platform = inbound.platform,
                    sourceEventId = inbound.sourceEventId,
                    receivedAt = inbound.timestamp,
                )
                val result = channelManager.sendTextWithOptions(
                    channelId = config.id,
                    text = reply,
                    options = ChannelSendOptions(
                        targetOverride = inbound.from.takeIf { it.isNotBlank() },
                        contextTokenOverride = contextTokenOverride.value,
                        sourceEventIdOverride = qqEventIdOverride,
                        sourceEventSequenceOverride = (inbound.attemptCount + 1)
                            .takeIf { inbound.platform == ChannelPlatform.QQ.name },
                    ),
                )
                if (!result.ok) {
                    Logger.w(TAG, "自动回复发送失败(${inbound.platform}): ${result.detail}")
                    HandleResult.Retry
                } else {
                    Logger.i(TAG, "自动回复已发送(${inbound.platform} ← ${inbound.from})")
                    HandleResult.Sent(config, inbound.from)
                }
            }
        }
    }

    private sealed interface HandleResult {
        data class Sent(val config: ChannelConfig, val from: String) : HandleResult
        data class Blocked(val errorCode: String) : HandleResult
        data object Ignored : HandleResult
        data object Retry : HandleResult
    }

    private sealed interface ReplyPreparation {
        data class Ready(val text: String) : ReplyPreparation
        data class Blocked(val errorCode: String) : ReplyPreparation
        data object Ignored : ReplyPreparation
        data object Retry : ReplyPreparation
    }

    private sealed interface ContextTokenOverride {
        data class Value(val value: String?) : ContextTokenOverride
        data object Unavailable : ContextTokenOverride
    }

    private suspend fun resolveReplyContextToken(inbound: ChannelInbox.Inbound): ContextTokenOverride {
        val isSupported = inbound.platform == ChannelPlatform.WECLAW.name ||
            inbound.platform == ChannelPlatform.DINGTALK.name
        if (!isSupported) return ContextTokenOverride.Value(null)

        val encrypted = ChannelInbox.journal.encryptedReplyContextToken(inbound.dispatchId)
            ?: inbound.encryptedReplyContextToken
        if (encrypted.isBlank()) return ContextTokenOverride.Value(null)

        val restored = when (inbound.platform) {
            ChannelPlatform.WECLAW.name -> restoreWeClawReplyContextToken(encrypted)
            ChannelPlatform.DINGTALK.name -> restoreDingtalkReplyContextToken(encrypted)
            else -> null
        }
        return restored?.let { ContextTokenOverride.Value(it) } ?: ContextTokenOverride.Unavailable
    }

    /** 跑一轮对话(渠道对话上下文 + 工具循环);失败按可恢复/不可恢复分类。 */
    @Suppress("CyclomaticComplexMethod", "ReturnCount", "TooGenericExceptionCaught")
    private suspend fun runAgent(config: ChannelConfig, from: String, dispatchId: String): ChannelAgentRunResult {
        val restoredCheckpoint = try {
            loadAgentCheckpoint(dispatchId)
        } catch (error: ChannelAgentCheckpointUnreadableException) {
            Logger.e(TAG, "渠道 Agent 恢复断点不可读取,转人工复核: ${error.message}", error)
            return ChannelAgentRunResult.Blocked(CHANNEL_AGENT_CHECKPOINT_UNREADABLE)
        }
        return try {
            val assistant = resolveAssistant(config)
            // v2.0.1: 渲染系统提示词的模板变量 — 此前直接发送原始文本,模型会复读 "{{char}}" 字面量。
            val renderedSystem = if (restoredCheckpoint == null) renderSystemPrompt(assistant) else ""
            // v2.0.1: 与聊天主链路同规则解析模型/Provider — 修复此前不带 model 请求、
            // 落到 Provider 首个模型(表现为"默认降级到免费模型")的问题。
            val (model, providerConfig) = resolveModelAndProvider(assistant)
            val workingMessages = restoredCheckpoint?.workingMessages?.toMutableList()
                ?: buildInitialAgentMessages(config, from, renderedSystem, model)
            if (workingMessages.none { it.role == MessageRole.USER }) {
                if (restoredCheckpoint != null) {
                    throw ChannelAgentCheckpointUnreadableException(
                        IllegalStateException("Agent checkpoint does not contain a user message"),
                    )
                }
                // 防御性兜底:理论上入站消息已入库,不会走到这里。
                return ChannelAgentRunResult.Retry
            }
            // v2.0.1: 工具继承 — 与聊天同源的工具集(助手白名单 + 排除群聊/高风险工具)。
            val toolDefs = resolveToolDefinitions(assistant)
            val offeredToolNames = toolDefs.mapTo(mutableSetOf()) { it.name }
            var checkpoint = restoredCheckpoint ?: ChannelAgentCheckpoint(
                dispatchId = dispatchId,
                workingMessages = workingMessages.toList(),
            )
            if (restoredCheckpoint != null) {
                checkpoint = executePendingToolCalls(workingMessages, offeredToolNames, checkpoint)
            }
            var finalText: String? = null
            var plainFallback = ""
            var round = 0
            while (round < MAX_TOOL_ROUNDS) {
                round++
                val completion = withTimeoutOrNull(LLM_TIMEOUT_MS) {
                    chatService.completeText(
                        messages = workingMessages,
                        model = model,
                        providerConfig = providerConfig,
                        temperature = assistant.temperature,
                        maxTokens = assistant.maxTokens,
                        tools = toolDefs.takeIf { it.isNotEmpty() },
                        mode = ChatRequestMode.CHAT,
                    )
                } ?: error(context.getString(R.string.channel_err_ai_timeout))
                plainFallback = completion.text
                val toolCalls = completion.toolCalls.orEmpty()
                if (toolCalls.isEmpty()) {
                    finalText = completion.text
                    break
                }
                // 回填带 tool_calls 的 assistant 消息 — OpenAI 兼容协议要求 TOOL 消息紧跟其前置。
                workingMessages += UIMessage(
                    role = MessageRole.ASSISTANT,
                    content = completion.text,
                    toolCalls = toolCalls,
                )
                checkpoint = checkpoint.copy(workingMessages = workingMessages.toList(), inFlightToolCallId = null)
                persistAgentCheckpoint(checkpoint)
                checkpoint = executePendingToolCalls(workingMessages, offeredToolNames, checkpoint)
            }
            // 轮次用尽仍未收口时,回退最后一次纯文本输出。
            val text = io.zer0.muse.transformer.stripThinkTags(finalText ?: plainFallback)
                .trim()
                .take(MAX_REPLY_LENGTH)
            ChannelAgentRunResult.Ready(text)
        } catch (error: CancellationException) {
            throw error
        } catch (error: ChannelAgentCheckpointUnreadableException) {
            Logger.e(TAG, "渠道 Agent 恢复断点不可读取,转人工复核: ${error.message}", error)
            ChannelAgentRunResult.Blocked(CHANNEL_AGENT_CHECKPOINT_UNREADABLE)
        } catch (error: Exception) {
            Logger.w(TAG, "自动回复生成失败: ${error.message}")
            ChannelAgentRunResult.Retry
        }
    }

    private suspend fun buildInitialAgentMessages(
        config: ChannelConfig,
        from: String,
        renderedSystem: String,
        model: Model?,
    ): MutableList<UIMessage> {
        val conversation = ChannelConversationStore.conversation(config.id, from)
        val contextTurns = conversation?.turns?.takeLast(CONTEXT_TURNS).orEmpty()
        val imageContext = buildImageContext(contextTurns, model?.supportsVisionInput() == true)
        return buildList {
            if (renderedSystem.isNotBlank()) {
                add(UIMessage(role = MessageRole.SYSTEM, content = renderedSystem))
            }
            conversation?.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                add(UIMessage(role = MessageRole.SYSTEM, content = "更早的对话摘要：$summary"))
            }
            contextTurns.forEach { turn ->
                add(buildInitialAgentTurn(config, from, turn, imageContext))
            }
        }.toMutableList()
    }

    private fun buildImageContext(turns: List<ChannelConversationStore.Turn>, visionCapable: Boolean): ChannelImageContext {
        val imageTurnAts = turns
            .filter { it.role == "user" && it.mediaKind == "image" && it.mediaBase64.isNotBlank() }
            .map { it.at }
        return ChannelImageContext(
            visionCapable = visionCapable,
            recentNativeAts = imageTurnAts.takeLast(MAX_NATIVE_VISION_TURNS).toSet(),
            recentFallbackAts = imageTurnAts.takeLast(MAX_FALLBACK_VISION_TURNS).toSet(),
        )
    }

    private suspend fun buildInitialAgentTurn(
        config: ChannelConfig,
        from: String,
        turn: ChannelConversationStore.Turn,
        imageContext: ChannelImageContext,
    ): UIMessage {
        val role = if (turn.role == "assistant") MessageRole.ASSISTANT else MessageRole.USER
        return if (turn.role == "user" && turn.mediaKind == "image" && turn.mediaBase64.isNotBlank()) {
            when {
                imageContext.visionCapable && turn.at in imageContext.recentNativeAts ->
                    UIMessage(
                        role = role,
                        content = turn.text.ifBlank { "[图片]" },
                        imageBase64List = listOf(turn.mediaBase64),
                    )
                !imageContext.visionCapable && turn.at in imageContext.recentFallbackAts ->
                    UIMessage(role = role, content = resolveImageContent(config, from, turn))
                turn.mediaDescription.isNotBlank() -> UIMessage(role = role, content = turn.mediaDescription)
                else -> UIMessage(role = role, content = turn.text.ifBlank { "[图片]" })
            }
        } else {
            UIMessage(role = role, content = turn.text.ifBlank { "[空]" })
        }
    }

    private data class ChannelImageContext(
        val visionCapable: Boolean,
        val recentNativeAts: Set<Long>,
        val recentFallbackAts: Set<Long>,
    )

    private suspend fun executePendingToolCalls(
        workingMessages: MutableList<UIMessage>,
        offeredToolNames: Set<String>,
        initialCheckpoint: ChannelAgentCheckpoint,
    ): ChannelAgentCheckpoint {
        val assistantIndex = workingMessages.indexOfLast {
            it.role == MessageRole.ASSISTANT && !it.toolCalls.isNullOrEmpty()
        }
        if (assistantIndex < 0) {
            if (initialCheckpoint.inFlightToolCallId != null) {
                throw ChannelAgentCheckpointUnreadableException(
                    IllegalStateException("Agent checkpoint references a missing tool call"),
                )
            }
            return initialCheckpoint
        }

        val toolCalls = workingMessages[assistantIndex].toolCalls.orEmpty()
        val resultIds = workingMessages.drop(assistantIndex + 1).asSequence()
            .filter { it.role == MessageRole.TOOL }
            .mapNotNull { it.toolCallId }
            .toMutableSet()
        var checkpoint = initialCheckpoint.copy(workingMessages = workingMessages.toList())
        val inFlightId = checkpoint.inFlightToolCallId
        if (inFlightId != null && toolCalls.none { it.id == inFlightId }) {
            throw ChannelAgentCheckpointUnreadableException(
                IllegalStateException("Agent checkpoint references a tool call outside the current batch"),
            )
        }
        if (inFlightId != null && inFlightId in resultIds) {
            checkpoint = checkpoint.copy(inFlightToolCallId = null)
            persistAgentCheckpoint(checkpoint)
        }

        for (toolCall in toolCalls) {
            if (toolCall.id in resultIds) continue
            checkpoint = executePendingToolCall(
                toolCall = toolCall,
                inFlightId = inFlightId,
                workingMessages = workingMessages,
                offeredToolNames = offeredToolNames,
                checkpoint = checkpoint,
            )
            resultIds += toolCall.id
        }
        return checkpoint
    }

    private suspend fun executePendingToolCall(
        toolCall: ToolCall,
        inFlightId: String?,
        workingMessages: MutableList<UIMessage>,
        offeredToolNames: Set<String>,
        checkpoint: ChannelAgentCheckpoint,
    ): ChannelAgentCheckpoint {
        val risk = ToolPermissionResolver.riskLevelFor(toolCall.name)
        val recoveringUncertainCall = toolCall.id == inFlightId
        var executionOutcomeUnknown = false
        val result = when {
            recoveringUncertainCall -> TOOL_OUTCOME_UNCERTAIN
            checkpoint.sideEffectOutcomeUnknown && risk == ToolRiskLevel.NORMAL ->
                TOOL_BLOCKED_AFTER_UNCERTAIN_ACTION
            !mayExecuteChannelToolCall(toolCall.name, offeredToolNames, risk) -> {
                Logger.w(TAG, "拒绝执行未授权或需审批的渠道工具: ${toolCall.name}")
                "工具未授权或需要交互审批，渠道自动回复未执行"
            }
            else -> {
                if (risk == ToolRiskLevel.NORMAL) {
                    persistAgentCheckpoint(
                        checkpoint.copy(
                            workingMessages = workingMessages.toList(),
                            inFlightToolCallId = toolCall.id,
                        ),
                    )
                }
                val execution = executeChannelTool(toolCall)
                executionOutcomeUnknown = execution.outcomeUnknown
                execution.content
            }
        }
        val resultForModel =
            captureLargeToolOutput(
                context = context,
                filePrefix = "channel_${toolCall.id}",
                output = result,
            )
        workingMessages += UIMessage(
            role = MessageRole.TOOL,
            content = resultForModel,
            toolCallId = toolCall.id,
        )
        val updated = checkpoint.copy(
            workingMessages = workingMessages.toList(),
            inFlightToolCallId = null,
            sideEffectOutcomeUnknown = channelToolOutcomeUnknown(
                previousUnknown = checkpoint.sideEffectOutcomeUnknown,
                recoveringUncertainCall = recoveringUncertainCall,
                executionOutcomeUnknown = executionOutcomeUnknown,
            ),
        )
        persistAgentCheckpoint(updated)
        Logger.i(TAG, "渠道工具调用: ${toolCall.name}")
        return updated
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun executeChannelTool(toolCall: ToolCall): ChannelToolExecution {
        val risk = ToolPermissionResolver.riskLevelFor(toolCall.name)
        return try {
            val result = toolRegistry.executeFromJson(toolCall.name, toolCall.arguments)
            ChannelToolExecution(
                content = result,
                outcomeUnknown = channelToolResultUnknown(
                    risk = risk,
                    executionThrew = false,
                    result = result,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ChannelToolExecution(
                content = "工具执行失败: ${error.message}",
                outcomeUnknown = channelToolResultUnknown(
                    risk = risk,
                    executionThrew = true,
                    result = "",
                ),
            )
        }
    }

    @Suppress("TooGenericExceptionCaught") // Corrupt ciphertext, schema, and dispatch identity are one terminal class.
    private suspend fun loadAgentCheckpoint(dispatchId: String): ChannelAgentCheckpoint? {
        val encrypted = ChannelInbox.journal.encryptedAgentCheckpoint(dispatchId).orEmpty()
        if (encrypted.isBlank()) return null
        return try {
            decryptChannelAgentCheckpoint(encrypted).also {
                check(it.dispatchId == dispatchId) { "渠道 Agent 恢复断点派发 ID 不匹配" }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw ChannelAgentCheckpointUnreadableException(error)
        }
    }

    private suspend fun persistAgentCheckpoint(checkpoint: ChannelAgentCheckpoint) {
        val encrypted = encryptChannelAgentCheckpoint(checkpoint)
        check(ChannelInbox.journal.saveEncryptedAgentCheckpoint(checkpoint.dispatchId, encrypted)) {
            "渠道 Agent 恢复断点未能持久化"
        }
    }

    /**
     * v2.0.1: 渠道可用工具集 — 与聊天同源([ToolRegistry.listToolsAsToolDefinitions]),
     * 按助手白名单过滤,并排除群聊 channel_* 工具与高风险(HIGH)工具。
     */
    private fun resolveToolDefinitions(assistant: AssistantEntity): List<ToolDefinition> {
        val configured = parseChannelToolAllowlist(assistant.toolIdsJson)
        if (configured == null) {
            Logger.w(TAG, "助手工具白名单格式无效，渠道自动回复不暴露工具")
            return emptyList()
        }
        return runCatching {
            toolRegistry.listToolsAsToolDefinitions().filter { def ->
                val selectedByTool = configured.isEmpty() || def.name in configured
                val notGroupChatTool = !def.name.startsWith("channel_")
                val notHighRisk = ToolPermissionResolver.riskLevelFor(def.name) != ToolRiskLevel.HIGH
                selectedByTool && notGroupChatTool && notHighRisk
            }
        }.onFailure { e ->
            Logger.w(TAG, "工具集解析失败: ${e.message}")
        }.getOrDefault(emptyList())
    }

    /**
     * v2.0.1: 视觉降级 — 把图片经视觉模型转为文字描述(结果缓存回 Turn,每张图只分析一次)。
     * 失败时返回占位文本(不缓存,下次触发重试)。
     */
    private suspend fun resolveImageContent(config: ChannelConfig, from: String, turn: ChannelConversationStore.Turn): String {
        if (turn.mediaDescription.isNotBlank()) return turn.mediaDescription
        val prepared = runCatching {
            visionBridge.prepare(
                text = turn.text.ifBlank { "[图片]" },
                images = listOf(turn.mediaBase64),
                userRequest = turn.text,
            )
        }.getOrNull()
        val text = prepared?.takeIf { it.success && it.text.isNotBlank() }?.text
        if (text != null) {
            ChannelConversationStore.updateTurnDescription(config.id, from, turn.at, text)
            return text
        }
        return "[图片(视觉辅助暂不可用)]" + if (turn.text.isNotBlank()) "\n${turn.text}" else ""
    }

    /**
     * v2.0.1: 上下文自动压缩 — 轮次超过 [COMPRESS_THRESHOLD] 时,
     * 把最旧的一段轮次经 LLM 合并进滚动摘要;失败仅记日志,下次触发时重试。
     */
    private suspend fun maybeCompress(config: ChannelConfig, from: String) {
        val conversation = ChannelConversationStore.conversation(config.id, from) ?: return
        if (conversation.turns.size <= COMPRESS_THRESHOLD) return
        val drainCount = conversation.turns.size - KEEP_TURNS
        if (drainCount <= 0) return
        val toCompress = conversation.turns.take(drainCount)
        val assistant = resolveAssistant(config)
        // v2.x: 摘要压缩优先走辅助模型路由「小工具」档(留空回退助手的模型解析)
        val routed = runCatching {
            io.zer0.muse.data.routing.UtilityModelRouter(settings)
                .resolve(io.zer0.muse.data.routing.UtilityTier.SMALL)
        }.getOrNull()
        val (model, providerConfig) = if (routed != null) {
            routed.second to routed.first
        } else {
            resolveModelAndProvider(assistant)
        }
        val prompt = buildString {
            if (conversation.summary.isNotBlank()) {
                appendLine("已有摘要：${conversation.summary}")
            }
            appendLine("新增对话：")
            toCompress.forEach { turn ->
                appendLine("${if (turn.role == "assistant") "助手" else "用户"}：${turn.text}")
            }
            appendLine("请把上述内容合并成一段简洁的中文对话摘要，保留关键信息、称呼与待办。只输出摘要正文。")
        }
        val summary = withTimeoutOrNull(COMPRESS_TIMEOUT_MS) {
            chatService.completeText(
                messages = listOf(UIMessage(role = MessageRole.USER, content = prompt)),
                model = model,
                providerConfig = providerConfig,
                maxTokens = SUMMARY_MAX_TOKENS,
                mode = ChatRequestMode.UTILITY,
            )
        }?.text?.let { io.zer0.muse.transformer.stripThinkTags(it).trim() } ?: return
        if (summary.isBlank()) return
        // v2.3.2: 条件回写 —— 压缩期间新到的轮次必须保留(原实现整份覆盖会把它们丢掉),
        // 摘要已被另一次压缩更新时本次结果作废。
        val applied =
            ChannelConversationStore.applyCompression(
                channelId = config.id,
                from = from,
                snapshotSummary = conversation.summary,
                drainedTurns = toCompress,
                summary = summary.take(SUMMARY_MAX_CHARS),
            )
        if (applied) {
            Logger.i(TAG, "上下文已压缩(${config.id} ← $from): ${toCompress.size} 轮并入摘要")
        } else {
            Logger.i(TAG, "压缩结果未写入(${config.id} ← $from): 对话已被更新,留待下次重试")
        }
    }

    /**
     * v2.0.1: 解析助手应使用的模型与 Provider — 与聊天主链路同规则:
     * 助手配置的 modelId/providerId 优先,回退全局选择模型,再回退激活 Provider 首个模型。
     */
    private suspend fun resolveModelAndProvider(assistant: AssistantEntity): Pair<Model?, ProviderConfig?> {
        val allProviders = runCatching { settings.getAllProviders() }.getOrDefault(emptyList())
        if (allProviders.isEmpty()) return null to null
        val activeProviderId = runCatching { settings.activeProviderIdFlow.first() }.getOrNull()
        val selectedModelId = runCatching { settings.selectedModelIdFlow.first() }.getOrNull()
        val assistantModelId = assistant.modelId?.takeIf { it.isNotBlank() }
        val assistantProviderId = assistant.providerId?.takeIf { it.isNotBlank() }
        val resolvedModel: Model? = if (assistantModelId != null && assistantProviderId != null) {
            allProviders.firstOrNull { it.id == assistantProviderId }
                ?.models?.firstOrNull { it.id == assistantModelId }
        } else {
            assistantModelId?.let { aid ->
                allProviders.firstOrNull { it.id == activeProviderId }
                    ?.models?.firstOrNull { it.id == aid }
                    ?: allProviders.flatMap { it.models }.firstOrNull { it.id == aid }
            }
        } ?: selectedModelId?.let { sid ->
            allProviders.firstOrNull { it.id == activeProviderId }
                ?.models?.firstOrNull { it.id == sid }
                ?: allProviders.flatMap { it.models }.firstOrNull { it.id == sid }
        } ?: allProviders.firstOrNull { it.id == activeProviderId && it.models.isNotEmpty() }
            ?.models?.firstOrNull()
            ?: allProviders.firstOrNull { it.models.isNotEmpty() }?.models?.firstOrNull()
        val resolvedProvider = resolvedModel?.let { m -> allProviders.firstOrNull { it.id == m.providerId } }
            ?: allProviders.firstOrNull { it.id == activeProviderId && it.models.isNotEmpty() }
            ?: allProviders.firstOrNull { it.models.isNotEmpty() }
        return resolvedModel to resolvedProvider
    }

    /**
     * v2.0.1: 渲染人设 systemPrompt 的模板变量({{char}}/{{user}}/日期等)。
     * 与聊天主链路同一套 TemplateTransformer;渲染失败回退原文,不阻塞回复。
     */
    private suspend fun renderSystemPrompt(assistant: AssistantEntity): String {
        val raw = assistant.systemPrompt
        if (raw.isBlank() || (!raw.contains("{{") && !raw.contains("{%"))) return raw
        val rendered = runCatching {
            val userProfile = settings.getUserProfile()
            templateTransformer.transform(
                messages = listOf(UIMessage(role = MessageRole.SYSTEM, content = raw)),
                context = TransformContext(
                    extras = mapOf(
                        "user_nickname" to userProfile.userNickName,
                        "assistant_name" to (userProfile.assistantName ?: assistant.name),
                    ),
                ),
            ).firstOrNull()?.content
        }.onFailure { e ->
            Logger.w(TAG, "系统提示词模板渲染失败: ${e.message}")
        }.getOrNull()
        return rendered?.takeIf { it.isNotBlank() } ?: raw
    }

    /** v2.0.1: 渠道绑定助手优先,再回退默认助手,再兜底第一个;完全无助手则抛出(由 runAgent 捕获)。 */
    private suspend fun resolveAssistant(config: ChannelConfig): AssistantEntity {
        val assistants = assistantRepository.observeAll.first()
        val bound = config.assistantId.takeIf { it.isNotBlank() }
            ?.let { id -> assistants.firstOrNull { it.id == id } }
        return bound
            ?: assistants.firstOrNull { it.id == "default" }
            ?: assistants.firstOrNull()
            ?: error(context.getString(R.string.channel_err_no_assistant))
    }

    companion object {
        private const val TAG = "ChannelAutoReply"

        /** 单轮 AI 调用超时(与定时任务一致,毫秒)。 */
        private const val LLM_TIMEOUT_MS = 60_000L

        /** 回发文本上限,防止超长内容超出各平台消息限制。 */
        private const val MAX_REPLY_LENGTH = 4_000
        private const val DISPATCH_STATE_RETRY_DELAY_MS = 1_000L

        /** v2.0.1: 提供给模型的最近轮次上限。 */
        private const val CONTEXT_TURNS = 30

        /**
         * v2.0.1: 压缩触发阈值(轮次)、压缩后保留轮次。
         *
         * X-5 说明：渠道侧按 **turn（轮次）** 而非 **message（消息条数）** 计数，
         * 且走 ChannelConversationStore 独立存储、不经 Transformer 管道 —— 与主对话自动压缩
         * （见 [io.zer0.muse.transformer.CompressionPolicy]）是**两套子系统**，阈值刻意独立，
         * 不与 CompressionPolicy 合并。
         */
        private const val COMPRESS_THRESHOLD = 40
        private const val KEEP_TURNS = 20

        /** v2.0.1: 压缩调用超时与摘要上限。 */
        private const val COMPRESS_TIMEOUT_MS = 30_000L
        private const val SUMMARY_MAX_TOKENS = 1200
        private const val SUMMARY_MAX_CHARS = 2_000

        /** v2.0.1: 工具循环上限。 */
        private const val MAX_TOOL_ROUNDS = 5

        /** v2.0.1: 上下文内携带图片的上限 — 原生模型最近 N 张、降级分析最近 N 张。 */
        private const val MAX_NATIVE_VISION_TURNS = 4
        private const val MAX_FALLBACK_VISION_TURNS = 2
        private const val TOOL_OUTCOME_UNCERTAIN =
            "Tool execution outcome is uncertain after app recovery. " +
                "It was not repeated to avoid duplicate side effects. Ask the user to verify before retrying."
        private const val TOOL_BLOCKED_AFTER_UNCERTAIN_ACTION =
            "Tool not executed: an earlier side effect has an uncertain outcome. " +
                "Ask the user to verify it before another side effect."
    }
}

internal fun selectAutoReplyConfig(configs: List<ChannelConfig>, inbound: ChannelInbox.Inbound): ChannelConfig? {
    return configs.firstOrNull {
        it.enabled &&
            it.autoReply &&
            it.platform.name == inbound.platform &&
            it.id == inbound.sourceChannelId
    }
}

internal fun qqReplyEventIdOverride(
    platform: String,
    sourceEventId: String,
    receivedAt: Long,
    nowMillis: Long = System.currentTimeMillis(),
): String? {
    if (platform != ChannelPlatform.QQ.name) return null
    val age = nowMillis - receivedAt
    return sourceEventId.takeIf {
        it.isNotBlank() && age in 0..QqMsgIdCache.VALID_WINDOW_MS
    } ?: ""
}
