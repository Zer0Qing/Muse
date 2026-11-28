package io.zer0.muse.data.agentbot

import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.muse.computenode.ComputeTargetRouter
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.rag.RagService
import io.zer0.muse.tools.RouteTable
import io.zer0.muse.tools.SessionToolLoadRegistry
import io.zer0.muse.tools.SkillExecutor
import io.zer0.muse.tools.ToolApprovalPolicy
import io.zer0.muse.tools.ToolApprovalRouter
import io.zer0.muse.tools.ToolApprovalState
import io.zer0.muse.tools.ToolCallGate
import io.zer0.muse.tools.ToolConfigStore
import io.zer0.muse.tools.ToolExecutionContext
import io.zer0.muse.tools.ToolExposurePolicy
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel
import io.zer0.muse.tools.ToolRouteExecutionGuard
import io.zer0.muse.tools.ToolRouteSnapshot
import io.zer0.muse.tools.ToolSchemaRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 主对话 `@队友` 的输入辅助与定向委派解析。
 *
 * 输入框只需要判断「当前是否正在输入一个 @ 片段」以及「有哪些队友可选」；
 * 发送时再把文本解析成明确的队友身份与干净的任务正文，避免把昵称本身当成任务内容。
 */
object AgentMentionDispatch {

    /** 一个可被 @ 的队友。displayName 是输入框里展示的昵称。 */
    data class Target(
        val botId: String,
        val displayName: String,
        val roleHint: String = "",
    )

    /** 输入框里一段尚未完成的 @ 片段；[startIndex] 指向 '@' 本身。 */
    data class ActiveQuery(
        val startIndex: Int,
        val query: String,
    )

    /** 已解析出的定向委派：队友身份 + 去掉 @ 片段后的任务正文。 */
    data class Resolution(
        val target: Target,
        val taskText: String,
    )

    private const val MAX_SUGGESTIONS = 8
    private const val MAX_NAME_QUERY_CHARS = 24
    private val TRAILING_PUNCTUATION = charArrayOf(
        '，', '。', '！', '？', '、', ',', '.', '!', '?', ':', '：', ';', '；',
    )

    /**
     * 找出文本末尾正在输入的 @ 片段。
     *
     * 只有 '@' 位于开头或空白之后、且其后的内容不含空白时才视为候选输入；
     * 邮箱、网址里的 '@' 以及已经补完空格的文本都不会触发胶囊条。
     */
    fun activeQuery(text: String): ActiveQuery? {
        return if (text.isEmpty()) {
            null
        } else {
            val at = text.lastIndexOf('@')
            val query = if (at >= 0) text.substring(at + 1) else ""
            val valid = at >= 0 &&
                (at == 0 || text[at - 1].isWhitespace()) &&
                query.length <= MAX_NAME_QUERY_CHARS &&
                query.none { it.isWhitespace() || it == '@' }
            if (valid) ActiveQuery(startIndex = at, query = query) else null
        }
    }

    /** 按昵称过滤候选；空查询返回全部，最多 [MAX_SUGGESTIONS] 个。 */
    fun filter(candidates: List<Target>, query: String): List<Target> {
        val normalized = query.trim()
        val matched = if (normalized.isBlank()) {
            candidates
        } else {
            candidates.filter { it.displayName.contains(normalized, ignoreCase = true) }
        }
        return matched.take(MAX_SUGGESTIONS)
    }

    /** 把正在输入的 @ 片段替换成完整的 `@昵称 `，输入焦点自然落在空格之后。 */
    fun applySelection(text: String, query: ActiveQuery, target: Target): String {
        val prefix = text.substring(0, query.startIndex)
        return prefix + "@" + target.displayName + " "
    }

    /**
     * 从已发送文本中解析第一个有效的 `@昵称`。
     *
     * 昵称按最长优先匹配，避免「小」与「小助手」并存时误选前一个；
     * 匹配位置必须在开头或空白之后，命中后整段昵称连同其后的空白一起从任务正文里移除。
     */
    fun resolve(text: String, candidates: List<Target>): Resolution? {
        val ordered = candidates
            .filter { it.botId.isNotBlank() && it.displayName.isNotBlank() }
            .sortedByDescending { it.displayName.length }
        var best: Resolution? = null
        var bestIndex = Int.MAX_VALUE
        for (target in ordered) {
            var from = 0
            while (true) {
                val at = text.indexOf("@" + target.displayName, from)
                if (at < 0) break
                val after = at + 1 + target.displayName.length
                val boundaryBefore = at == 0 || text[at - 1].isWhitespace()
                val boundaryAfter = after >= text.length ||
                    text[after].isWhitespace() ||
                    text[after] in TRAILING_PUNCTUATION
                if (boundaryBefore && boundaryAfter) {
                    if (at < bestIndex) {
                        bestIndex = at
                        best = Resolution(
                            target = target,
                            taskText = removeMention(text, at, after),
                        )
                    }
                    break
                }
                from = at + 1
            }
        }
        return best?.takeIf { it.taskText.isNotBlank() }
    }

    /**
     * 决定一次 launch_mission 的接收队友。
     *
     * 用户在输入框显式 @ 的队友拥有最高优先级；模型自己填写的 assignee 只在没有 @ 时生效。
     */
    fun resolveAssignee(declared: String, target: Target?): String = target?.botId?.takeIf { it.isNotBlank() } ?: declared.trim()

    private fun removeMention(text: String, at: Int, endExclusive: Int): String {
        var end = endExclusive
        while (end < text.length && text[end].isWhitespace()) end++
        val head = text.substring(0, at).trimEnd()
        val tail = text.substring(end)
        return when {
            head.isBlank() -> tail.trim()
            tail.isBlank() -> head
            else -> (head + " " + tail).trim()
        }
    }
}

/**
 * 原生流式快慢双轨前台大脑。
 *
 * 与旧版强制 `bot_say` + 非流式 600 token 协议不同：
 * - 普通对话与轻问答直接走原生 SSE 流式输出，毫秒级首字响应且兼容全部供应商；
 * - 控制工具（贴表情反应、派发异步任务、运行中插话、给可点选项、存惯例）采用 `auto` 声明，
 *   不强迫模型每次必调工具；
 * - 即使在流式生成过程中，收到完整或结束后的工具调用也会统一结构化结算，并与
 *   [PublicationService] 保持同样的幂等与 revision 防串话护栏。
 */
data class AgentStreamCoordinatorDependencies(
    val toolRegistry: ToolRegistry? = null,
    val skillRepository: SkillRepository? = null,
    val skillExecutorProvider: (() -> SkillExecutor)? = null,
    val toolConfigStore: ToolConfigStore? = null,
    val settings: io.zer0.muse.data.SettingsRepository? = null,
    val ragService: RagService? = null,
    val assistantRepository: AssistantRepository? = null,
    val agentBotRepository: AgentBotRepository? = null,
    val foregroundMcpApprovalTimeoutMs: Long = FOREGROUND_MCP_APPROVAL_TIMEOUT_MS,
)

class AgentStreamCoordinator(
    private val streamingClient: StreamingClient,
    private val publication: PublicationService,
    private val governor: BotResourceGovernor,
    private val dependencies: AgentStreamCoordinatorDependencies = AgentStreamCoordinatorDependencies(),
) {

    private val toolRegistry = dependencies.toolRegistry
    private val skillRepository = dependencies.skillRepository
    private val skillExecutorProvider = dependencies.skillExecutorProvider
    private val toolConfigStore = dependencies.toolConfigStore
    private val settings = dependencies.settings
    private val ragService = dependencies.ragService
    private val assistantRepository = dependencies.assistantRepository
    private val agentBotRepository = dependencies.agentBotRepository
    private val foregroundMcpApprovalTimeoutMs = dependencies.foregroundMcpApprovalTimeoutMs

    fun interface StreamingClient {
        suspend fun stream(
            messages: List<UIMessage>,
            tools: List<ToolDefinition>,
            providerConfig: ProviderConfig?,
            model: Model?,
            sessionId: String,
        ): Flow<ChatStreamEvent>
    }

    interface OptionsStreamingClient : StreamingClient {
        suspend fun streamWithOptions(
            messages: List<UIMessage>,
            tools: List<ToolDefinition>,
            providerConfig: ProviderConfig?,
            model: Model?,
            sessionId: String,
            nativeWebSearch: Boolean,
            reasoningLevel: io.zer0.ai.core.ReasoningLevel,
        ): Flow<ChatStreamEvent>

        override suspend fun stream(
            messages: List<UIMessage>,
            tools: List<ToolDefinition>,
            providerConfig: ProviderConfig?,
            model: Model?,
            sessionId: String,
        ): Flow<ChatStreamEvent> = streamWithOptions(
            messages = messages,
            tools = tools,
            providerConfig = providerConfig,
            model = model,
            sessionId = sessionId,
            nativeWebSearch = false,
            reasoningLevel = io.zer0.ai.core.ReasoningLevel.DEFAULT,
        )
    }

    /** 流式期间增量吐给 UI 的实时草稿。 */
    data class StreamingDraft(
        val text: String = "",
        val reasoning: String = "",
        val reasoningStartedAtMs: Long? = null,
        val reasoningDurationMs: Long? = null,
        val isThinkingActive: Boolean = false,
        val mood: String = "",
        val citationUrls: List<String> = emptyList(),
        val reactions: List<ReactionAction> = emptyList(),
    )

    data class ReactionAction(
        val emoji: String,
        val targetMessageId: String = "",
    )

    data class MissionDispatch(
        val goal: String,
        val acceptance: String = "",
        val resourceScope: String = "",
        val assigneeBotId: String = "",
    )

    data class SteerAction(
        val taskId: String,
        val instruction: String,
    )

    @Serializable
    data class ChoiceOption(
        val id: String,
        val label: String,
        val prompt: String = "",
    )

    data class ChoiceOffer(
        val prompt: String = "",
        val options: List<ChoiceOption> = emptyList(),
    )

    data class RoutineCreation(
        val title: String,
        val goalPrompt: String,
        val triggerType: String = RoutineTriggerType.MANUAL,
        val triggerExpr: String = "",
        val resourceScope: String = "",
    )

    /** 模型拟好的跟进项草稿；落库前先经用户确认卡批准。 */
    data class WatcherCreation(
        val title: String,
        val instruction: String,
        val script: String,
        val scriptKind: String = AgentWatcherEngine.SCRIPT_KIND_PYTHON,
        val computeTarget: String = ComputeTargetRouter.TARGET_LOCAL,
        val intervalMinutes: Int = AgentWatcherEngine.DEFAULT_INTERVAL_MINUTES,
        val webhookUrl: String = "",
    )

    /** 模型拟好的长期目标草稿；缺失字段表示沿用旧值，新建时取默认值。 */
    data class GoalUpsert(
        val goalId: String = "",
        val title: String = "",
        val description: String? = null,
        val milestones: List<AgentGoalMilestone>? = null,
        val progress: Int? = null,
        val status: String = "",
        val dueAt: Long? = null,
    )

    /** 一次里程碑更新：按 id 勾选/改名、追加或删除。 */
    data class GoalMilestoneUpdate(
        val goalId: String,
        val milestoneId: String = "",
        val title: String = "",
        val done: Boolean? = null,
        val remove: Boolean = false,
    )

    data class TurnOutcome(
        val text: String = "",
        val rawText: String = text,
        val reasoning: String = "",
        val reasoningDurationMs: Long? = null,
        val mood: String = "",
        val citationUrls: List<String> = emptyList(),
        val reactions: List<ReactionAction> = emptyList(),
        val dispatches: List<MissionDispatch> = emptyList(),
        val steerings: List<SteerAction> = emptyList(),
        val choiceOffer: ChoiceOffer? = null,
        val routineCreations: List<RoutineCreation> = emptyList(),
        val watcherCreations: List<WatcherCreation> = emptyList(),
        val goalUpserts: List<GoalUpsert> = emptyList(),
        val goalMilestoneUpdates: List<GoalMilestoneUpdate> = emptyList(),
        val publishedMessageId: String = "",
        val requestSuperseded: Boolean = false,
        val quotaRejected: Boolean = false,
        val streamError: String? = null,
    ) {
        val hasAnyAction: Boolean
            get() = text.isNotBlank() ||
                reactions.isNotEmpty() ||
                dispatches.isNotEmpty() ||
                steerings.isNotEmpty() ||
                choiceOffer != null ||
                routineCreations.isNotEmpty() ||
                watcherCreations.isNotEmpty() ||
                goalUpserts.isNotEmpty() ||
                goalMilestoneUpdates.isNotEmpty()
    }

    private data class ToolCallAccumulator(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )

    data class ForegroundMcpApproval(
        val requestId: String,
        val serverId: String,
        val toolName: String,
        val purpose: String,
        val argumentsJson: String,
    )

    suspend fun runStreamingTurn(
        identity: AgentTurnIdentity,
        systemPrompt: String,
        history: List<UIMessage>,
        userText: String,
        inputImageBase64List: List<String> = emptyList(),
        providerConfig: ProviderConfig? = null,
        model: Model? = null,
        assistant: AssistantEntity? = null,
        nativeWebSearch: Boolean = false,
        reasoningLevel: io.zer0.ai.core.ReasoningLevel = io.zer0.ai.core.ReasoningLevel.DEFAULT,
        currentRequestRevision: suspend () -> Long,
        onDraft: (StreamingDraft) -> Unit = {},
        mentionTarget: AgentMentionDispatch.Target? = null,
        /**
         * 本轮固定的记忆空间。
         *
         * 由发送方在回合开始前捕获后传进来：工具执行上下文、记忆写入与 prompt 必须用
         * 同一个 Space。这里再读一次设置的话，用户在生成中途切 Space 就会让
         * 「读的是 A 空间、写的是 B 空间」。
         */
        memorySpaceId: String? = null,
        dynamicSystemPrompt: String = "",
        onMcpApproval: (suspend (ForegroundMcpApproval) -> Boolean)? = null,
    ): TurnOutcome {
        val identityError = identity.validate()
        if (identityError != null) {
            Logger.e(TAG, "流式回合身份无效: $identityError")
            return TurnOutcome(streamError = identityError)
        }

        // 静态系统提示保持稳定以命中前缀缓存；动态信息与 @指定队友放入独立动态 SYSTEM 块。
        val dynamicBlock = buildString {
            if (dynamicSystemPrompt.isNotBlank()) appendLine(dynamicSystemPrompt.trim())
            if (mentionTarget != null) {
                if (isNotEmpty()) appendLine()
                appendLine("## 定向委派")
                appendLine("- 用户本轮用 @ 指定了队友「${mentionTarget.displayName}」（id=${mentionTarget.botId}）。")
                appendLine("- 必须调用 `launch_mission` 并把 `assignee_bot_id` 设为该 id，由该队友在后台执行；不要自己执行，也不要改派给其他队友。")
            }
        }.trim()

        // 没有显式传入时按当前设置兜底一次，之后整轮都使用这个值。
        val turnSpaceId = memorySpaceId?.takeIf { it.isNotBlank() }
            ?: runCatching { settings?.currentSpaceIdFlow?.first() }.getOrNull().orEmpty()
                .ifBlank { "default" }
        val hostToolset = buildHostToolset(identity.sessionId, assistant)
        val totalTextBuilder = StringBuilder()
        val totalReasoningBuilder = StringBuilder()
        val citationSet = linkedSetOf<String>()
        val collectedMetaCalls = mutableListOf<ToolCall>()
        val stickyToolNames = mutableSetOf<String>()
        var streamError: String? = null
        var reasoningStartedAtMs: Long? = null
        var reasoningDurationMs: Long? = null
        var isThinkingActive = false
        var lastDraftEmitAtMs = 0L
        var pendingDraftChars = 0
        var lastToolCallSignature: String? = null

        val currentUserMessage = UIMessage(
            role = MessageRole.USER,
            content = userText,
            imageBase64List = inputImageBase64List,
        )
        val systemMessages = buildList {
            if (systemPrompt.isNotBlank()) {
                add(UIMessage(role = MessageRole.SYSTEM, content = systemPrompt.trim()))
            }
            if (dynamicBlock.isNotBlank()) {
                add(UIMessage(role = MessageRole.SYSTEM, content = dynamicBlock))
            }
            val ragMessage = buildAgentRagSystemMessage(assistant, userText, identity.sessionId)
            if (ragMessage != null) {
                add(ragMessage)
            }
        }
        val trimmedHistory = trimHistoryToContextBudget(
            systemMessages = systemMessages,
            history = history,
            currentUser = currentUserMessage,
            model = model,
            toolCount = AGENT_TOOLS.size,
        )
        val conversationMessages = mutableListOf<UIMessage>().apply {
            addAll(systemMessages)
            addAll(trimmedHistory)
            add(currentUserMessage)
        }

        fun buildSanitizedDraft(rawText: String, rawReasoning: String, thinkingNow: Boolean): StreamingDraft {
            val extractedFromBody = io.zer0.muse.transformer.InternalMarkupSanitizer.extractMood(rawText)
            val extractedFromReasoning = if (extractedFromBody == null && rawReasoning.isNotEmpty()) {
                io.zer0.muse.transformer.InternalMarkupSanitizer.extractMood(rawReasoning)
            } else {
                null
            }
            val cleanedReasoning = if (extractedFromReasoning != null) {
                io.zer0.muse.transformer.InternalMarkupSanitizer.stripContainerTags(
                    io.zer0.muse.transformer.InternalMarkupSanitizer.stripMoodBlocks(rawReasoning),
                )
            } else {
                rawReasoning
            }
            val cleanedText = io.zer0.muse.transformer.InternalMarkupSanitizer.stripForDisplay(rawText)
            return StreamingDraft(
                text = cleanedText,
                reasoning = cleanedReasoning,
                reasoningStartedAtMs = reasoningStartedAtMs,
                reasoningDurationMs = reasoningDurationMs,
                isThinkingActive = thinkingNow,
                mood = extractedFromBody ?: extractedFromReasoning ?: "",
                citationUrls = citationSet.toList(),
            )
        }

        fun emitDraftThrottled(rawText: String, rawReasoning: String, deltaLen: Int, force: Boolean = false) {
            pendingDraftChars += deltaLen
            val now = System.currentTimeMillis()
            if (force || pendingDraftChars >= DRAFT_CHAR_THRESHOLD || now - lastDraftEmitAtMs >= DRAFT_INTERVAL_MS) {
                lastDraftEmitAtMs = now
                pendingDraftChars = 0
                onDraft(buildSanitizedDraft(rawText, rawReasoning, isThinkingActive))
            }
        }

        loop@ for (round in 0 until MAX_FOREGROUND_TOOL_ROUNDS) {
            val filteredHostTools = if (hostToolset != null) {
                ToolExposurePolicy.filterToolsForRequest(
                    userText = userText,
                    tools = hostToolset.snapshot.definitions,
                    stickyToolNames = stickyToolNames,
                    loadedToolNames = SessionToolLoadRegistry.loadedFor(identity.sessionId),
                    skillToolNames = hostToolset.skillNames,
                ).filterNot { it.name in FOREGROUND_EXCLUDED_HOST_TOOLS }
            } else {
                emptyList()
            }
            val roundTools = (AGENT_TOOLS + filteredHostTools).distinctBy { it.name }

            val roundTextBuilder = StringBuilder()
            val roundReasoningBuilder = StringBuilder()
            var roundThinkingSignature: String? = null
            var roundThinkingEncryptedContent: String? = null
            val toolAccumulators = linkedMapOf<Int, ToolCallAccumulator>()
            val baseTextPrefix = if (totalTextBuilder.isNotEmpty()) {
                totalTextBuilder.toString() + "\n\n"
            } else {
                ""
            }
            val baseReasoningPrefix = if (totalReasoningBuilder.isNotEmpty()) {
                totalReasoningBuilder.toString() + "\n\n"
            } else {
                ""
            }

            try {
                val flow = if (streamingClient is OptionsStreamingClient) {
                    streamingClient.streamWithOptions(
                        messages = conversationMessages.toList(),
                        tools = roundTools,
                        providerConfig = providerConfig,
                        model = model,
                        sessionId = identity.sessionId,
                        nativeWebSearch = nativeWebSearch,
                        reasoningLevel = reasoningLevel,
                    )
                } else {
                    streamingClient.stream(
                        messages = conversationMessages.toList(),
                        tools = roundTools,
                        providerConfig = providerConfig,
                        model = model,
                        sessionId = identity.sessionId,
                    )
                }
                flow.collect { event ->
                    when (event) {
                        is ChatStreamEvent.ContentDelta -> {
                            if (event.delta.isNotEmpty()) {
                                val transitionedFromThinking = isThinkingActive
                                if (isThinkingActive) {
                                    isThinkingActive = false
                                    val started = reasoningStartedAtMs
                                    if (started != null && reasoningDurationMs == null) {
                                        reasoningDurationMs = (System.currentTimeMillis() - started).coerceAtLeast(0L)
                                    }
                                }
                                roundTextBuilder.append(event.delta)
                                emitDraftThrottled(
                                    rawText = baseTextPrefix + roundTextBuilder.toString(),
                                    rawReasoning = baseReasoningPrefix + roundReasoningBuilder.toString(),
                                    deltaLen = event.delta.length,
                                    force = transitionedFromThinking,
                                )
                            }
                        }
                        is ChatStreamEvent.ReasoningDelta -> {
                            if (!event.signature.isNullOrBlank()) {
                                roundThinkingSignature = event.signature
                            }
                            if (!event.encryptedContent.isNullOrBlank()) {
                                roundThinkingEncryptedContent = event.encryptedContent
                            }
                            if (event.delta.isNotEmpty()) {
                                val firstThinking = !isThinkingActive
                                if (reasoningStartedAtMs == null) {
                                    reasoningStartedAtMs = System.currentTimeMillis()
                                }
                                isThinkingActive = true
                                roundReasoningBuilder.append(event.delta)
                                emitDraftThrottled(
                                    rawText = baseTextPrefix + roundTextBuilder.toString(),
                                    rawReasoning = baseReasoningPrefix + roundReasoningBuilder.toString(),
                                    deltaLen = event.delta.length,
                                    force = firstThinking,
                                )
                            }
                        }
                        is ChatStreamEvent.CitationDelta -> {
                            if (event.urls.isNotEmpty()) {
                                citationSet.addAll(event.urls)
                                emitDraftThrottled(
                                    rawText = baseTextPrefix + roundTextBuilder.toString(),
                                    rawReasoning = baseReasoningPrefix + roundReasoningBuilder.toString(),
                                    deltaLen = 0,
                                    force = true,
                                )
                            }
                        }
                        is ChatStreamEvent.ToolCallDelta -> {
                            if (isThinkingActive) {
                                isThinkingActive = false
                                val started = reasoningStartedAtMs
                                if (started != null && reasoningDurationMs == null) {
                                    reasoningDurationMs = (System.currentTimeMillis() - started).coerceAtLeast(0L)
                                }
                            }
                            val acc = toolAccumulators.getOrPut(event.index) { ToolCallAccumulator() }
                            event.id?.takeIf { it.isNotBlank() }?.let { acc.id = it }
                            event.name?.takeIf { it.isNotBlank() }?.let { acc.name = it }
                            event.argumentsDelta?.takeIf { it.isNotEmpty() }?.let { delta ->
                                if (event.isSnapshot) {
                                    acc.arguments.clear()
                                }
                                acc.arguments.append(delta)
                            }
                        }
                        is ChatStreamEvent.Error -> {
                            streamError = event.message
                        }
                        is ChatStreamEvent.StreamInterrupted -> {
                            if (roundTextBuilder.isEmpty() && totalTextBuilder.isEmpty()) {
                                streamError = event.message
                            }
                        }
                        is ChatStreamEvent.Done -> {
                            if (isThinkingActive) {
                                isThinkingActive = false
                                val started = reasoningStartedAtMs
                                if (started != null && reasoningDurationMs == null) {
                                    reasoningDurationMs = (System.currentTimeMillis() - started).coerceAtLeast(0L)
                                }
                            }
                            if (pendingDraftChars > 0) {
                                emitDraftThrottled(
                                    rawText = baseTextPrefix + roundTextBuilder.toString(),
                                    rawReasoning = baseReasoningPrefix + roundReasoningBuilder.toString(),
                                    deltaLen = 0,
                                    force = true,
                                )
                            }
                        }
                        is ChatStreamEvent.FallbackNotice,
                        is ChatStreamEvent.ImageDelta,
                        is ChatStreamEvent.UsageDelta,
                        -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                Logger.w(TAG, "流式前台回合异常: ${e.message}", e)
                streamError = e.message ?: "stream_failed"
            }

            if (currentRequestRevision() > identity.requestRevision) {
                Logger.i(TAG, "输入版本已更新，丢弃过期流式回合: ${identity.requestId}")
                return TurnOutcome(requestSuperseded = true)
            }

            if (roundTextBuilder.isNotEmpty()) {
                if (totalTextBuilder.isNotEmpty()) totalTextBuilder.append("\n\n")
                totalTextBuilder.append(roundTextBuilder)
            }
            if (roundReasoningBuilder.isNotEmpty()) {
                if (totalReasoningBuilder.isNotEmpty()) totalReasoningBuilder.append("\n\n")
                totalReasoningBuilder.append(roundReasoningBuilder)
            }

            val roundToolCalls = toolAccumulators.values
                .filter { it.name.isNotBlank() }
                .map { acc ->
                    ToolCall(
                        id = acc.id.ifBlank { "tc-${acc.name}-${System.nanoTime()}" },
                        name = acc.name,
                        arguments = acc.arguments.toString(),
                    )
                }

            val metaCalls = roundToolCalls.filter { it.name in AGENT_META_NAMES }
            val execCalls = if (hostToolset != null) {
                roundToolCalls.filter { it.name !in AGENT_META_NAMES }
            } else {
                emptyList()
            }
            collectedMetaCalls += metaCalls
            if (roundToolCalls.isNotEmpty() && hostToolset == null) {
                collectedMetaCalls += roundToolCalls.filterNot { it in metaCalls }
            }

            val activeToolset = hostToolset
            if (execCalls.isEmpty() || streamError != null || activeToolset == null) {
                break@loop
            }
            val roundSignature = execCalls.joinToString("|") { "${it.name}:${it.arguments}" }
            if (roundSignature == lastToolCallSignature) {
                Logger.w(TAG, "第 ${round + 1} 轮检测到连续重复工具调用，提前结束前台循环: $roundSignature")
                break@loop
            }
            lastToolCallSignature = roundSignature

            conversationMessages += UIMessage(
                role = MessageRole.ASSISTANT,
                content = roundTextBuilder.toString(),
                reasoning = roundReasoningBuilder.toString().takeIf { it.isNotEmpty() },
                thinkingSignature = roundThinkingSignature,
                thinkingEncryptedContent = roundThinkingEncryptedContent,
                modelId = model?.id,
                toolCalls = roundToolCalls,
            )
            for (tc in roundToolCalls) {
                if (tc.name in AGENT_META_NAMES) {
                    conversationMessages += UIMessage(
                        role = MessageRole.TOOL,
                        content = "{\"ok\":true}",
                        toolCallId = tc.id,
                    )
                } else {
                    val toolOutput = executeForegroundHostTool(
                        identity = identity,
                        tc = tc,
                        hostToolset = activeToolset,
                        spaceId = turnSpaceId,
                        onMcpApproval = onMcpApproval,
                    )
                    stickyToolNames += tc.name
                    conversationMessages += UIMessage(
                        role = MessageRole.TOOL,
                        content = toolOutput.take(MAX_FOREGROUND_TOOL_RESULT_CHARS),
                        toolCallId = tc.id,
                    )
                }
            }
        }

        val parsed = parseToolCalls(
            toolCalls = collectedMetaCalls,
            fallbackText = totalTextBuilder.toString(),
            mentionTarget = mentionTarget,
        )
        val finalReactions = parsed.reactions.toMutableList()
        if (parsed.dispatches.isNotEmpty() && finalReactions.isEmpty()) {
            // 派发后台任务但模型未主动贴角标时，自动给用户原消息贴一枚轻量确认角标。
            finalReactions += ReactionAction(emoji = DEFAULT_DISPATCH_EMOJI, targetMessageId = identity.requestId)
        }
        val finalDispatches = mutableListOf<MissionDispatch>()
        var quotaRejected = false
        for (dispatch in parsed.dispatches) {
            when (val verdict = governor.admit(runningCount = 0)) {
                is BotResourceGovernor.Verdict.Go,
                is BotResourceGovernor.Verdict.Queue,
                -> finalDispatches += dispatch
                is BotResourceGovernor.Verdict.Reject -> {
                    Logger.w(TAG, "后台配额拒绝派工: ${verdict.reason}")
                    quotaRejected = true
                }
            }
        }

        val rawReplyText = parsed.sayText.trim()
        val rawReasoningText = totalReasoningBuilder.toString().trim()
        val moodFromReply = io.zer0.muse.transformer.InternalMarkupSanitizer.extractMood(rawReplyText)
        val moodFromReasoning = if (moodFromReply == null && rawReasoningText.isNotBlank()) {
            io.zer0.muse.transformer.InternalMarkupSanitizer.extractMood(rawReasoningText)
        } else {
            null
        }
        val finalMood = (moodFromReply ?: moodFromReasoning).orEmpty().trim()
        val cleanReplyText = io.zer0.muse.transformer.InternalMarkupSanitizer.stripForDisplay(rawReplyText).trim()
        val effectiveText = when {
            cleanReplyText.isNotBlank() -> cleanReplyText
            finalDispatches.isNotEmpty() -> DEFAULT_ACK_TEXT
            parsed.steerings.isNotEmpty() -> DEFAULT_STEER_ACK_TEXT
            parsed.routineCreations.isNotEmpty() -> DEFAULT_ROUTINE_ACK_TEXT
            parsed.watcherCreations.isNotEmpty() -> DEFAULT_WATCHER_ACK_TEXT
            parsed.goalUpserts.isNotEmpty() || parsed.goalMilestoneUpdates.isNotEmpty() -> DEFAULT_GOAL_ACK_TEXT
            else -> ""
        }

        val finalReasoning = if (moodFromReasoning != null) {
            io.zer0.muse.transformer.InternalMarkupSanitizer.stripContainerTags(
                io.zer0.muse.transformer.InternalMarkupSanitizer.stripMoodBlocks(rawReasoningText),
            ).trim()
        } else {
            rawReasoningText
        }
        val finalReasoningDurationMs = reasoningDurationMs ?: reasoningStartedAtMs?.let {
            (System.currentTimeMillis() - it).coerceAtLeast(0L)
        }
        val finalCitations = citationSet.toList()
        var publishedMessageId = ""
        if (effectiveText.isNotBlank()) {
            val publishResult = runCatching {
                publication.publish(
                    botId = identity.assistantId,
                    originSessionId = identity.sessionId,
                    causeEventIds = identity.requestId,
                    requestRevision = identity.requestRevision,
                    taskRevision = 1,
                    items = listOf(PublicationService.Item(PublicationKind.TEXT, effectiveText)),
                    currentRequestRevision = currentRequestRevision(),
                    reasoningByItem = listOf(finalReasoning.ifBlank { null }),
                    reasoningDurationMsByItem = listOf(finalReasoningDurationMs),
                    moodByItem = listOf(finalMood.ifBlank { null }),
                    citationsByItem = listOf(finalCitations),
                )
            }.onFailure {
                Logger.w(TAG, "流式回复落盘发布失败: ${it.message}", it)
            }.getOrNull()
            publishedMessageId = publishResult?.messageIds?.firstOrNull().orEmpty()
        }

        return TurnOutcome(
            text = effectiveText,
            rawText = rawReplyText,
            reasoning = finalReasoning,
            reasoningDurationMs = finalReasoningDurationMs,
            mood = finalMood,
            citationUrls = finalCitations,
            reactions = finalReactions,
            dispatches = finalDispatches,
            steerings = parsed.steerings,
            choiceOffer = parsed.choiceOffer,
            routineCreations = parsed.routineCreations,
            watcherCreations = parsed.watcherCreations,
            goalUpserts = parsed.goalUpserts,
            goalMilestoneUpdates = parsed.goalMilestoneUpdates,
            publishedMessageId = publishedMessageId,
            quotaRejected = quotaRejected,
            streamError = if (effectiveText.isBlank() && finalDispatches.isEmpty() && finalReactions.isEmpty()) {
                streamError
            } else {
                null
            },
        )
    }

    private data class HostToolset(
        val snapshot: ToolRouteSnapshot,
        val skillNames: Set<String>,
    )

    // 这里的多个提前返回都是 fail-closed 门禁：任一条件不满足就不注入，不能退化成放开检索。
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private suspend fun buildAgentRagSystemMessage(assistant: AssistantEntity?, userText: String, sessionId: String): UIMessage? {
        val service = ragService ?: return null
        val repo = settings ?: return null
        val query = io.zer0.muse.rag.HybridSearchService.sanitizeQueryForRag(userText)
            .takeIf { it.isNotBlank() } ?: return null
        val baseConfig = runCatching { repo.getRagConfig() }.getOrNull() ?: return null
        val effectiveConfig = assistant?.let { a ->
            runCatching { assistantRepository?.mergeRagConfigOverride(a, baseConfig) }.getOrNull()
        } ?: baseConfig
        val mentionDocIds = runCatching { service.resolveMentionToDocIds(query) }.getOrNull()
        val hasExplicitMention = !mentionDocIds.isNullOrEmpty()
        if (!effectiveConfig.enabled && !hasExplicitMention) return null

        val boundKbIds = assistant?.let { assistantRepository?.parseKnowledgeBaseIds(it) }.orEmpty()
        val boundFolderScopes = assistant?.let { assistantRepository?.parseKnowledgeFolderScopes(it) }.orEmpty()
        val permittedDocIds: List<String>? = if (boundKbIds.isEmpty() && boundFolderScopes.isEmpty()) {
            null
        } else {
            when (val resolved = runCatching { service.resolveBindingScope(boundKbIds, boundFolderScopes) }.getOrNull()) {
                is io.zer0.muse.knowledge.domain.KnowledgeScope.Resolved -> resolved.docIds.toList()
                is io.zer0.muse.knowledge.domain.KnowledgeScope.AllUserLibraries -> null
                else -> emptyList()
            }
        }
        val scopeDocIds = io.zer0.muse.knowledge.retrieval.mergeMentionWithPermit(mentionDocIds, permittedDocIds)
        val shouldRetrieve = effectiveConfig.enabled || (scopeDocIds != null && scopeDocIds.isNotEmpty())
        if (!shouldRetrieve) return null

        val injection = runCatching {
            service.buildInjectionContextWithCitations(
                query = query,
                ragConfig = effectiveConfig,
                scopeDocIds = scopeDocIds,
                // 会话附件是会话私有资料:只有本会话的才允许召回。
                allowedSessionAttachmentDocIds = activeSessionAttachmentDocIds(sessionId),
            )
        }.onFailure { Logger.w(TAG, "Agent RAG 检索失败: ${it.message}") }.getOrNull() ?: return null
        // 预算截断与引用裁剪同源:截掉的片段其 [n] 不在正文里,引用也不应保留。
        // 文本为空时 clampAndTrim 原样返回空串,由下面这一次判空统一拦住。
        val budgeted = io.zer0.muse.rag.RagInjectionBudget.clampAndTrim(
            injection,
            io.zer0.muse.context.ContextBudget(),
            io.zer0.muse.context.ContextSection.RAG_CITATION,
        )
        if (budgeted.text.isBlank()) return null
        if (budgeted.truncated) {
            Logger.d(
                TAG,
                "Agent RAG 注入按预算截断 | sessionId=$sessionId" +
                    " | citations ${injection.citations.size}->${budgeted.citations.size}",
            )
        }
        return UIMessage(role = MessageRole.SYSTEM, content = budgeted.text)
    }

    /**
     * 取本会话当前可召回的附件 docId。
     *
     * 走全局容器按需解析,避免为一项旁路能力改动构造链;取不到时返回空集,
     * 即不召回任何附件 —— 不会因为解析失败就放开成全库。
     */
    private fun activeSessionAttachmentDocIds(sessionId: String): Set<String> {
        if (sessionId.isBlank()) return emptySet()
        val service = runCatching {
            org.koin.core.context.GlobalContext.get().get<io.zer0.muse.rag.SessionAttachmentService>()
        }.onFailure { Logger.w(TAG, "会话附件服务不可用,本轮不召回附件: ${it.message}") }
            .getOrNull() ?: return emptySet()
        return service.activeAttachmentDocIds(sessionId)
    }

    private suspend fun buildHostToolset(sessionId: String, assistant: AssistantEntity?): HostToolset? {
        val registry = toolRegistry ?: return null
        val deniedToolNames = runCatching {
            toolConfigStore?.policiesFlow?.first()
                ?.filterValues { it == ToolApprovalPolicy.ALWAYS_DENY }
                ?.keys
        }.getOrNull().orEmpty()
        val criteria = ToolSchemaRules.criteriaFor(assistant, deniedToolNames)
        val registeredDefs = ToolSchemaRules.selectLocalTools(
            registry.listToolsAsToolDefinitions(),
            criteria,
        )
        val localNames = registry.listTools().map { it.name }.toSet()
        val skillRepo = skillRepository
        val enabledSkills = if (skillRepo != null && skillExecutorProvider != null) {
            runCatching { skillRepo.listEnabled() }
                .onFailure { Logger.w(TAG, "读取前台 Agent 技能失败: ${it.message}") }
                .getOrDefault(emptyList())
                .let { raw -> ToolSchemaRules.selectSkills(raw, localNames, criteria) }
        } else {
            emptyList()
        }
        val skillSearchDefs = enabledSkills.map { sk ->
            ToolRegistry.ToolDef(
                name = sk.id,
                description = sk.description,
                parameters = emptyMap(),
                category = "skill",
                riskLevel = ToolRiskLevel.NORMAL,
            )
        }
        registry.setSearchableSkills(sessionId, skillSearchDefs)
        registry.setVisibleTools(sessionId, registeredDefs.map { it.name }.toSet() + enabledSkills.map { it.id })
        val snapshot = RouteTable.snapshot(registeredDefs, enabledSkills)
        return HostToolset(
            snapshot = snapshot,
            skillNames = enabledSkills.mapTo(mutableSetOf()) { it.id },
        )
    }

    private suspend fun executeForegroundHostTool(
        identity: AgentTurnIdentity,
        tc: ToolCall,
        hostToolset: HostToolset,
        spaceId: String,
        onMcpApproval: (suspend (ForegroundMcpApproval) -> Boolean)?,
    ): String {
        return when (val preparation = prepareForegroundTool(identity, tc, hostToolset, spaceId, onMcpApproval)) {
            is ForegroundToolPreparation.Rejected -> preparation.message
            is ForegroundToolPreparation.Ready -> executePreparedForegroundTool(tc, hostToolset, preparation)
        }
    }

    private suspend fun prepareForegroundTool(
        identity: AgentTurnIdentity,
        tc: ToolCall,
        hostToolset: HostToolset,
        spaceId: String,
        onMcpApproval: (suspend (ForegroundMcpApproval) -> Boolean)?,
    ): ForegroundToolPreparation {
        val registry = toolRegistry ?: return ForegroundToolPreparation.Rejected("Error: 工具注册表不可用")
        val route = hostToolset.snapshot.routeFor(tc.name)
            ?: return ForegroundToolPreparation.Rejected("Error: 当前助手无法使用工具 ${tc.name}。")
        val store = toolConfigStore ?: return ForegroundToolPreparation.Rejected("Error: 工具策略不可用")
        when (val localState = ToolCallGate(ToolApprovalRouter(), registry, store, settings)
            .resolveLocalState(tc.name, tc.arguments)) {
            is ToolApprovalState.Auto, is ToolApprovalState.Approved -> Unit
            is ToolApprovalState.Denied -> return ForegroundToolPreparation.Rejected(
                "Error: 工具 '${tc.name}' 未获批准(${localState.reason.ifBlank { "策略不允许" }})",
            )
            is ToolApprovalState.Answered -> return ForegroundToolPreparation.Rejected(
                "Error: 工具 '${tc.name}' 未获批准，调用未执行。",
            )
            is ToolApprovalState.Pending -> {
                val rejection = requestForegroundApproval(identity, tc, registry, onMcpApproval)
                if (rejection != null) return ForegroundToolPreparation.Rejected(rejection)
            }
        }
        val executionContext = ToolExecutionContext(
            scope = if (identity.assistantId == "default") "main" else identity.assistantId,
            spaceId = spaceId,
            assistantId = identity.assistantId,
            sessionId = identity.sessionId,
            onRemoteExecStarted = agentBotRepository?.let { repository ->
                { nodeId, remoteRunId ->
                    val recorded = repository.recordRemoteExecOwner(
                        nodeId = nodeId,
                        remoteRunId = remoteRunId,
                        botId = identity.assistantId,
                        originSessionId = identity.sessionId,
                    )
                    check(recorded) { "remote execution owner could not be recorded" }
                }
            },
        )
        return ForegroundToolPreparation.Ready(registry, route, executionContext)
    }

    private suspend fun requestForegroundApproval(
        identity: AgentTurnIdentity,
        tc: ToolCall,
        registry: ToolRegistry,
        onMcpApproval: (suspend (ForegroundMcpApproval) -> Boolean)?,
    ): String? {
        val definition = registry.listTools().firstOrNull { it.name == tc.name }
        if (definition?.category != "mcp" || onMcpApproval == null) {
            return "Error: 工具 '${tc.name}' 需要用户批准，但当前回合没有可用的审批入口；调用未执行。"
        }
        val requestId = "${identity.requestId}:${tc.id}:${tc.name}"
        val approved = try {
            withTimeoutOrNull(foregroundMcpApprovalTimeoutMs.coerceAtLeast(1L)) {
                onMcpApproval(
                    ForegroundMcpApproval(
                        requestId = requestId,
                        serverId = tc.name.removePrefix("mcp_").substringBefore("__"),
                        toolName = tc.name.substringAfter("__", tc.name),
                        purpose = definition.description.substringAfter("] ", definition.description),
                        argumentsJson = tc.arguments,
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            Logger.w(TAG, "前台 MCP 审批入口异常，拒绝本次调用: ${e.message}", e)
            return "Error: MCP 工具审批未能完成；本次调用未执行。"
        }
        return when (approved) {
            null -> "Error: MCP 工具审批超时；本次调用未执行。"
            false -> "Error: 用户拒绝了 MCP 工具 '${tc.name}' 的本次调用；调用未执行。"
            true -> null
        }
    }

    private suspend fun executePreparedForegroundTool(
        tc: ToolCall,
        hostToolset: HostToolset,
        preparation: ForegroundToolPreparation.Ready,
    ): String = runCatching {
        val executor = skillExecutorProvider?.invoke()
        when (val route = preparation.route) {
            is ToolRouteSnapshot.Route.Skill -> executor?.execute(
                skill = route.skill,
                argumentsJson = tc.arguments,
                sessionId = preparation.executionContext.sessionId,
            ) ?: "Error: SkillExecutor 未就绪"
            ToolRouteSnapshot.Route.Local -> ToolRouteExecutionGuard(preparation.registry).executeFromJson(
                name = tc.name,
                argumentsJson = tc.arguments,
                snapshot = hostToolset.snapshot,
                executionContext = preparation.executionContext,
            )
        }
    }.onFailure { e ->
        if (e is CancellationException) throw e
        Logger.w(TAG, "前台执行工具 ${tc.name} 失败: ${e.message}", e)
    }.getOrElse { e -> "Error: ${e.message ?: e.javaClass.simpleName}" }

    private sealed interface ForegroundToolPreparation {
        data class Ready(
            val registry: ToolRegistry,
            val route: ToolRouteSnapshot.Route,
            val executionContext: ToolExecutionContext,
        ) : ForegroundToolPreparation

        data class Rejected(val message: String) : ForegroundToolPreparation
    }

    private data class ParsedActions(
        val sayText: String,
        val reactions: List<ReactionAction>,
        val dispatches: List<MissionDispatch>,
        val steerings: List<SteerAction>,
        val choiceOffer: ChoiceOffer?,
        val routineCreations: List<RoutineCreation>,
        val watcherCreations: List<WatcherCreation>,
        val goalUpserts: List<GoalUpsert>,
        val goalMilestoneUpdates: List<GoalMilestoneUpdate>,
    )

    private fun parseToolCalls(
        toolCalls: List<ToolCall>,
        fallbackText: String,
        mentionTarget: AgentMentionDispatch.Target? = null,
    ): ParsedActions {
        val actions = ParsedActionsAccumulator()
        toolCalls.forEach { call ->
            val args = runCatching {
                json.parseToJsonElement(call.arguments) as? JsonObject
            }.onFailure {
                Logger.w(TAG, "流式工具参数解析失败(${call.name}): ${it.message}")
            }.getOrNull()
            appendParsedToolCall(call.name, args, mentionTarget, actions)
        }
        return actions.build(fallbackText)
    }

    private fun appendParsedToolCall(
        name: String,
        args: JsonObject?,
        mentionTarget: AgentMentionDispatch.Target?,
        actions: ParsedActionsAccumulator,
    ) {
        when (name) {
            TOOL_REACT -> appendReaction(args, actions)
            TOOL_LAUNCH_MISSION, ForegroundCoordinator.TOOL_DISPATCH -> appendDispatch(args, mentionTarget, actions)
            TOOL_STEER_MISSION -> appendSteering(args, actions)
            TOOL_OFFER_CHOICES -> appendChoiceOffer(args, actions)
            TOOL_CREATE_ROUTINE -> appendRoutine(args, actions)
            TOOL_CREATE_WATCHER -> appendWatcher(args, actions)
            TOOL_UPSERT_GOAL -> appendGoal(args, actions)
            TOOL_UPDATE_GOAL_MILESTONE -> appendGoalMilestone(args, actions)
            ForegroundCoordinator.TOOL_SAY -> args?.str("text").orEmpty().trim()
                .takeIf { it.isNotBlank() }?.let { actions.explicitSay = it }
            else -> Logger.w(TAG, "忽略未注册的前台工具: $name")
        }
    }

    private fun appendReaction(args: JsonObject?, actions: ParsedActionsAccumulator) {
        val emoji = args?.str("emoji").orEmpty().trim()
        if (emoji.isNotBlank()) {
            actions.reactions += ReactionAction(
                emoji = emoji.take(MAX_EMOJI_CHARS),
                targetMessageId = args?.str("target_message_id").orEmpty().trim(),
            )
        }
    }

    private fun appendDispatch(
        args: JsonObject?,
        mentionTarget: AgentMentionDispatch.Target?,
        actions: ParsedActionsAccumulator,
    ) {
        val goal = args?.str("goal").orEmpty().trim()
        if (goal.isNotBlank()) {
            actions.dispatches += MissionDispatch(
                goal = goal,
                acceptance = args?.str("acceptance").orEmpty().trim(),
                resourceScope = args?.str("resource_scope").orEmpty().trim(),
                assigneeBotId = AgentMentionDispatch.resolveAssignee(
                    declared = args?.str("assignee_bot_id").orEmpty().trim(),
                    target = mentionTarget,
                ),
            )
        }
    }

    private fun appendSteering(args: JsonObject?, actions: ParsedActionsAccumulator) {
        val taskId = args?.str("task_id").orEmpty().trim()
        val instruction = args?.str("instruction").orEmpty().trim()
        if (taskId.isNotBlank() && instruction.isNotBlank()) {
            actions.steerings += SteerAction(taskId = taskId, instruction = instruction)
        }
    }

    private fun appendChoiceOffer(args: JsonObject?, actions: ParsedActionsAccumulator) {
        val prompt = args?.str("prompt").orEmpty().trim()
        val options = (args?.get("options") as? JsonArray).orEmpty()
            .mapIndexedNotNull(::parseChoiceOption)
            .take(MAX_CHOICE_OPTIONS)
        if (options.isNotEmpty()) actions.choiceOffer = ChoiceOffer(prompt = prompt, options = options)
    }

    private fun parseChoiceOption(index: Int, element: kotlinx.serialization.json.JsonElement): ChoiceOption? = when (element) {
        is JsonPrimitive -> element.content.trim().takeIf { it.isNotBlank() }
            ?.let { text -> ChoiceOption("opt_${index + 1}", text, text) }
        is JsonObject -> {
            val label = element.str("label").ifBlank { element.str("title") }.trim()
            label.takeIf { it.isNotBlank() }?.let {
                ChoiceOption(
                    id = element.str("id").ifBlank { "opt_${index + 1}" },
                    label = it,
                    prompt = element.str("prompt").ifBlank { it }.trim(),
                )
            }
        }
        else -> null
    }

    private fun appendRoutine(args: JsonObject?, actions: ParsedActionsAccumulator) {
        val title = args?.str("title").orEmpty().trim()
        val goal = args?.str("goal").orEmpty().trim()
        if (title.isNotBlank() && goal.isNotBlank()) {
            val triggerRaw = args?.str("trigger_type").orEmpty().trim().uppercase()
            actions.routines += RoutineCreation(
                title = title,
                goalPrompt = goal,
                triggerType = triggerRaw.takeIf { it in RoutineTriggerType.all } ?: RoutineTriggerType.MANUAL,
                triggerExpr = args?.str("trigger_expr").orEmpty().trim(),
                resourceScope = args?.str("resource_scope").orEmpty().trim(),
            )
        }
    }

    private fun appendWatcher(args: JsonObject?, actions: ParsedActionsAccumulator) {
        val title = args?.str("title").orEmpty().trim()
        val instruction = args?.str("instruction").orEmpty().trim()
        val script = args?.str("script").orEmpty().trim()
        if (title.isBlank() || instruction.isBlank() || script.isBlank()) {
            Logger.w(TAG, "忽略缺少标题/说明/脚本的 create_watcher 调用")
            return
        }
        val rawKind = args?.str("script_kind").orEmpty().trim().lowercase()
        val rawTarget = args?.str("compute_target").orEmpty().trim().lowercase()
        val interval = (args?.str("interval_minutes")?.toIntOrNull() ?: AgentWatcherEngine.DEFAULT_INTERVAL_MINUTES)
            .coerceIn(AgentWatcherEngine.MIN_INTERVAL_MINUTES, AgentWatcherEngine.MAX_INTERVAL_MINUTES)
        actions.watchers += WatcherCreation(
            title = title,
            instruction = instruction,
            script = script,
            scriptKind = if (rawKind == AgentWatcherEngine.SCRIPT_KIND_NODE) {
                AgentWatcherEngine.SCRIPT_KIND_NODE
            } else {
                AgentWatcherEngine.SCRIPT_KIND_PYTHON
            },
            computeTarget = if (rawTarget == ComputeTargetRouter.TARGET_CLOUD) {
                ComputeTargetRouter.TARGET_CLOUD
            } else {
                ComputeTargetRouter.TARGET_LOCAL
            },
            intervalMinutes = interval,
            webhookUrl = args?.str("webhook_url").orEmpty().trim(),
        )
    }

    private fun appendGoal(args: JsonObject?, actions: ParsedActionsAccumulator) {
        val goalId = args?.str("goal_id").orEmpty().trim()
        val title = args?.str("title").orEmpty().trim()
        if (goalId.isBlank() && title.isBlank()) {
            Logger.w(TAG, "忽略既无 goal_id 也无标题的 upsert_goal 调用")
            return
        }
        actions.goalUpserts += GoalUpsert(
            goalId = goalId,
            title = title,
            description = args?.strOrNull("description"),
            milestones = (args?.get("milestones") as? JsonArray)?.mapNotNull(::parseMilestone),
            progress = (args?.get("progress") as? JsonPrimitive)?.content?.toIntOrNull(),
            status = args?.str("status").orEmpty().trim().uppercase(),
            dueAt = (args?.get("due_at") as? JsonPrimitive)?.content?.toLongOrNull(),
        )
    }

    private fun appendGoalMilestone(args: JsonObject?, actions: ParsedActionsAccumulator) {
        val goalId = args?.str("goal_id").orEmpty().trim()
        if (goalId.isBlank()) {
            Logger.w(TAG, "忽略缺少 goal_id 的 update_goal_milestone 调用")
            return
        }
        actions.goalMilestoneUpdates += GoalMilestoneUpdate(
            goalId = goalId,
            milestoneId = args?.str("milestone_id").orEmpty().trim(),
            title = args?.str("title").orEmpty().trim(),
            done = (args?.get("done") as? JsonPrimitive)?.content?.toBooleanStrictOrNull(),
            remove = (args?.get("remove") as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true,
        )
    }

    private class ParsedActionsAccumulator {
        var explicitSay: String = ""
        val reactions = mutableListOf<ReactionAction>()
        val dispatches = mutableListOf<MissionDispatch>()
        val steerings = mutableListOf<SteerAction>()
        var choiceOffer: ChoiceOffer? = null
        val routines = mutableListOf<RoutineCreation>()
        val watchers = mutableListOf<WatcherCreation>()
        val goalUpserts = mutableListOf<GoalUpsert>()
        val goalMilestoneUpdates = mutableListOf<GoalMilestoneUpdate>()

        fun build(fallbackText: String): ParsedActions = ParsedActions(
            sayText = fallbackText.trim().ifBlank { explicitSay },
            reactions = reactions,
            dispatches = dispatches,
            steerings = steerings,
            choiceOffer = choiceOffer,
            routineCreations = routines,
            watcherCreations = watchers,
            goalUpserts = goalUpserts,
            goalMilestoneUpdates = goalMilestoneUpdates,
        )
    }

    private fun parseMilestone(element: kotlinx.serialization.json.JsonElement): AgentGoalMilestone? = when (element) {
        is JsonObject -> {
            val title = element.str("title").trim()
            if (title.isBlank()) {
                null
            } else {
                AgentGoalMilestone(
                    id = element.str("id").trim(),
                    title = title,
                    done = element.str("done").toBooleanStrictOrNull() == true,
                )
            }
        }
        is kotlinx.serialization.json.JsonNull -> null
        is JsonPrimitive -> element.content.trim().takeIf { it.isNotBlank() }?.let { title ->
            AgentGoalMilestone(title = title)
        }
        is JsonArray -> null
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty()

    /** 区分「字段缺失」与「显式空串」：缺失返回 null，空串原样保留。 */
    private fun JsonObject.strOrNull(key: String): String? {
        val element = this[key]
        return if (element is JsonPrimitive && element !is kotlinx.serialization.json.JsonNull) {
            element.content
        } else {
            null
        }
    }

    companion object {
        private fun trimHistoryToContextBudget(
            systemMessages: List<UIMessage>,
            history: List<UIMessage>,
            currentUser: UIMessage,
            model: Model?,
            toolCount: Int,
        ): List<UIMessage> {
            val window = model?.contextWindow ?: 0
            if (window <= 0 || history.size <= 4) return history
            val systemTokens = io.zer0.muse.util.TokenEstimator.estimate(systemMessages)
            val userTokens = io.zer0.muse.util.TokenEstimator.estimate(listOf(currentUser))
            val budget = io.zer0.muse.transformer.ContextBudget.compute(
                contextWindow = window,
                systemTokens = systemTokens,
                toolTokens = toolCount * 120,
                maxOutputTokens = model?.maxOutputTokens ?: 0,
            ).historyBudget - userTokens
            if (budget <= 0) return history.takeLast(4)
            var usedTokens = 0
            var startIndex = history.size
            for (i in history.indices.reversed()) {
                val msgTokens = io.zer0.muse.util.TokenEstimator.estimate(listOf(history[i]))
                if (usedTokens + msgTokens > budget && history.size - startIndex >= 4) {
                    break
                }
                usedTokens += msgTokens
                startIndex = i
            }
            // 不从 TOOL 消息中间切断，保证 ASSISTANT(toolCalls) 与其 TOOL 结果成对保留
            while (startIndex > 0 && history[startIndex].role == MessageRole.TOOL) {
                startIndex--
            }
            return history.subList(startIndex, history.size)
        }

        private const val TAG = "AgentStreamCoord"
        private const val FOREGROUND_MCP_APPROVAL_TIMEOUT_MS = 30_000L
        private const val MAX_FOREGROUND_TOOL_ROUNDS = 12
        private const val MAX_FOREGROUND_TOOL_RESULT_CHARS = 12_000
        private const val MAX_EMOJI_CHARS = 8
        private const val MAX_CHOICE_OPTIONS = 4
        private const val DEFAULT_DISPATCH_EMOJI = "👌"
        private const val DEFAULT_ACK_TEXT = "好的，我先放到后台帮你处理，弄好后马上告诉你。"
        private const val DEFAULT_STEER_ACK_TEXT = "收到，我已把新要求同步给正在执行的任务。"
        private const val DEFAULT_ROUTINE_ACK_TEXT = "已经为你保存为自动化任务，随时可在任务面板管理。"
        private const val DEFAULT_WATCHER_ACK_TEXT = "好的，我先拟一条跟进项，确认后开始帮你盯。"
        private const val DEFAULT_GOAL_ACK_TEXT = "好的，目标已记下，我会按里程碑持续帮你推进。"

        const val TOOL_REACT = "react_message"
        const val TOOL_LAUNCH_MISSION = "launch_mission"
        const val TOOL_STEER_MISSION = "steer_mission"
        const val TOOL_OFFER_CHOICES = "offer_choices"
        const val TOOL_CREATE_ROUTINE = "create_routine"
        const val TOOL_CREATE_WATCHER = "create_watcher"
        const val TOOL_UPSERT_GOAL = "upsert_goal"
        const val TOOL_UPDATE_GOAL_MILESTONE = "update_goal_milestone"

        private val AGENT_META_NAMES = setOf(
            TOOL_REACT,
            TOOL_LAUNCH_MISSION,
            TOOL_STEER_MISSION,
            TOOL_OFFER_CHOICES,
            TOOL_CREATE_ROUTINE,
            TOOL_CREATE_WATCHER,
            TOOL_UPSERT_GOAL,
            TOOL_UPDATE_GOAL_MILESTONE,
            ForegroundCoordinator.TOOL_DISPATCH,
            ForegroundCoordinator.TOOL_SAY,
        )

        /** Agent 前台统一走 `launch_mission` 派发后台任务，屏蔽会造成角色混淆的重复委派工具。 */
        private val FOREGROUND_EXCLUDED_HOST_TOOLS = setOf(
            "subagent_run",
            "subagent_task",
            "delegate_agent",
        )

        private const val DRAFT_INTERVAL_MS = 50L
        private const val DRAFT_CHAR_THRESHOLD = 12

        private val json = Json { ignoreUnknownKeys = true }

        fun fromChatService(chatService: ChatService): StreamingClient = object : OptionsStreamingClient {
            override suspend fun streamWithOptions(
                messages: List<UIMessage>,
                tools: List<ToolDefinition>,
                providerConfig: ProviderConfig?,
                model: Model?,
                sessionId: String,
                nativeWebSearch: Boolean,
                reasoningLevel: io.zer0.ai.core.ReasoningLevel,
            ): Flow<ChatStreamEvent> = chatService.streamChat(
                messages = messages,
                model = model,
                tools = tools,
                toolChoice = "auto",
                providerConfig = providerConfig,
                nativeWebSearch = nativeWebSearch,
                reasoningLevel = reasoningLevel,
                mode = ChatRequestMode.CHAT,
                sessionId = sessionId,
            )
        }

        val AGENT_TOOLS: List<ToolDefinition> = listOf(
            ToolDefinition(
                name = TOOL_REACT,
                description = "在用户消息底角贴单个 Emoji 反应（如 👌 👀 ❤️ 🤔）。",
                parametersJsonSchema = buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "emoji",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "单个 Emoji 字符")
                                },
                            )
                            put(
                                "target_message_id",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "目标消息 id，留空取用户最新消息")
                                },
                            )
                        },
                    )
                    put("required", buildJsonArray { add(JsonPrimitive("emoji")) })
                }.toString(),
            ),
            ToolDefinition(
                name = TOOL_LAUNCH_MISSION,
                description = "派发后台异步任务（多步工具、脚本、文件或虚拟屏）。",
                parametersJsonSchema = buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "goal",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "目标描述")
                                },
                            )
                            put(
                                "acceptance",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "验收标准与交付物")
                                },
                            )
                            put(
                                "resource_scope",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "允许改动的路径范围；只读留空")
                                },
                            )
                            put(
                                "assignee_bot_id",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "委派助手 id（照抄环境信息「可协作助手」里的 id=...）；留空由当前助手执行")
                                },
                            )
                        },
                    )
                    put("required", buildJsonArray { add(JsonPrimitive("goal")) })
                }.toString(),
            ),
            ToolDefinition(
                name = TOOL_STEER_MISSION,
                description = "向运行中的后台任务注入新要求。",
                parametersJsonSchema = buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "task_id",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "运行中任务 id")
                                },
                            )
                            put(
                                "instruction",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "追加指令或变更约束")
                                },
                            )
                        },
                    )
                    put(
                        "required",
                        buildJsonArray {
                            add(JsonPrimitive("task_id"))
                            add(JsonPrimitive("instruction"))
                        },
                    )
                }.toString(),
            ),
            ToolDefinition(
                name = TOOL_OFFER_CHOICES,
                description = "在回复下方挂 2~4 个可点选后续方案卡片。",
                parametersJsonSchema = buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "prompt",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "简短提问")
                                },
                            )
                            put(
                                "options",
                                buildJsonObject {
                                    put("type", "array")
                                    put(
                                        "items",
                                        buildJsonObject {
                                            put("type", "object")
                                            put(
                                                "properties",
                                                buildJsonObject {
                                                    put(
                                                        "label",
                                                        buildJsonObject {
                                                            put("type", "string")
                                                            put("description", "按钮上展示的短选项文字")
                                                        },
                                                    )
                                                    put(
                                                        "prompt",
                                                        buildJsonObject {
                                                            put("type", "string")
                                                            put("description", "用户点选后发出的完整指令；留空则发 label")
                                                        },
                                                    )
                                                },
                                            )
                                            put("required", buildJsonArray { add(JsonPrimitive("label")) })
                                        },
                                    )
                                },
                            )
                        },
                    )
                    put("required", buildJsonArray { add(JsonPrimitive("options")) })
                }.toString(),
            ),
            ToolDefinition(
                name = TOOL_CREATE_ROUTINE,
                description = "创建可复用的自动化任务（手动/定时/事件触发）。",
                parametersJsonSchema = buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "title",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "自动化任务简短标题")
                                },
                            )
                            put(
                                "goal",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "每次触发时要执行的目标说明")
                                },
                            )
                            put(
                                "trigger_type",
                                buildJsonObject {
                                    put("type", "string")
                                    put(
                                        "enum",
                                        buildJsonArray {
                                            add(JsonPrimitive("MANUAL"))
                                            add(JsonPrimitive("CRON"))
                                            add(JsonPrimitive("NOTIFICATION"))
                                            add(JsonPrimitive("CHANNEL"))
                                        },
                                    )
                                    put("description", "触发类型：MANUAL/CRON/NOTIFICATION/CHANNEL")
                                },
                            )
                            put(
                                "trigger_expr",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "Cron（如 0 8 * * *）或触发关键词")
                                },
                            )
                            put(
                                "resource_scope",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "允许改动的路径范围；只读留空")
                                },
                            )
                        },
                    )
                    put(
                        "required",
                        buildJsonArray {
                            add(JsonPrimitive("title"))
                            add(JsonPrimitive("goal"))
                        },
                    )
                }.toString(),
            ),
            ToolDefinition(
                name = TOOL_CREATE_WATCHER,
                description = "创建一个持续跟进项（Watcher）：按固定周期运行采集脚本，内容有变化才通知用户。",
                parametersJsonSchema = buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "title",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "跟进项简短标题，如「商品降价提醒」")
                                },
                            )
                            put(
                                "instruction",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "要盯住什么变化，用一句话说明")
                                },
                            )
                            put(
                                "script",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "采集脚本：运行后把要比较的文本写到标准输出或 result；必须可独立运行且不依赖交互")
                                },
                            )
                            put(
                                "script_kind",
                                buildJsonObject {
                                    put("type", "string")
                                    put(
                                        "enum",
                                        buildJsonArray {
                                            add(JsonPrimitive(AgentWatcherEngine.SCRIPT_KIND_PYTHON))
                                            add(JsonPrimitive(AgentWatcherEngine.SCRIPT_KIND_NODE))
                                        },
                                    )
                                    put("description", "采集脚本类型：python（默认）或 node")
                                },
                            )
                            put(
                                "interval_minutes",
                                buildJsonObject {
                                    put("type", "integer")
                                    put("description", "检查周期（分钟），默认 30，最小 1")
                                },
                            )
                            put(
                                "compute_target",
                                buildJsonObject {
                                    put("type", "string")
                                    put(
                                        "enum",
                                        buildJsonArray {
                                            add(JsonPrimitive(ComputeTargetRouter.TARGET_LOCAL))
                                            add(JsonPrimitive(ComputeTargetRouter.TARGET_CLOUD))
                                        },
                                    )
                                    put("description", "托管位置：local（本机，默认）或 cloud（有在线云电脑时 24×7 托管）")
                                },
                            )
                            put(
                                "webhook_url",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "可选的外部回调地址；留空只写系统通知")
                                },
                            )
                        },
                    )
                    put(
                        "required",
                        buildJsonArray {
                            add(JsonPrimitive("title"))
                            add(JsonPrimitive("instruction"))
                            add(JsonPrimitive("script"))
                        },
                    )
                }.toString(),
            ),
            ToolDefinition(
                name = TOOL_UPSERT_GOAL,
                description = "创建或更新一条长期目标（Goal）：维护标题、说明、里程碑与状态；新建省略 goal_id，更新传 goal_id 与完整字段。",
                parametersJsonSchema = buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "goal_id",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "已有目标 id；新建时留空")
                                },
                            )
                            put(
                                "title",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "目标标题；更新时可省略沿用旧标题")
                                },
                            )
                            put(
                                "description",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "完整说明：为什么要做、做到什么算完成")
                                },
                            )
                            put(
                                "milestones",
                                buildJsonObject {
                                    put("type", "array")
                                    put(
                                        "items",
                                        buildJsonObject {
                                            put("type", "object")
                                            put(
                                                "properties",
                                                buildJsonObject {
                                                    put(
                                                        "id",
                                                        buildJsonObject {
                                                            put("type", "string")
                                                            put("description", "里程碑 id；新增项留空由系统生成")
                                                        },
                                                    )
                                                    put(
                                                        "title",
                                                        buildJsonObject {
                                                            put("type", "string")
                                                            put("description", "里程碑标题")
                                                        },
                                                    )
                                                    put(
                                                        "done",
                                                        buildJsonObject {
                                                            put("type", "boolean")
                                                            put("description", "是否已完成，默认 false")
                                                        },
                                                    )
                                                },
                                            )
                                            put("required", buildJsonArray { add(JsonPrimitive("title")) })
                                        },
                                    )
                                    put("description", "完整里程碑清单；更新时省略则沿用旧清单，传空数组表示清空")
                                },
                            )
                            put(
                                "progress",
                                buildJsonObject {
                                    put("type", "integer")
                                    put("description", "0..100 的推进度；省略时按里程碑完成度自动计算")
                                },
                            )
                            put(
                                "status",
                                buildJsonObject {
                                    put("type", "string")
                                    put(
                                        "enum",
                                        buildJsonArray {
                                            add(JsonPrimitive(AgentGoalStatus.ACTIVE))
                                            add(JsonPrimitive(AgentGoalStatus.PAUSED))
                                            add(JsonPrimitive(AgentGoalStatus.ACHIEVED))
                                            add(JsonPrimitive(AgentGoalStatus.ABANDONED))
                                        },
                                    )
                                    put("description", "目标状态；省略时保留旧状态或按里程碑自动推导")
                                },
                            )
                            put(
                                "due_at",
                                buildJsonObject {
                                    put("type", "integer")
                                    put("description", "期望完成时间（epoch millis）；0 或省略表示没有期限")
                                },
                            )
                        },
                    )
                }.toString(),
            ),
            ToolDefinition(
                name = TOOL_UPDATE_GOAL_MILESTONE,
                description = "勾选、改名、追加或删除一条目标里程碑；完成后自动重算进度并推进目标状态。",
                parametersJsonSchema = buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "goal_id",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "目标 id")
                                },
                            )
                            put(
                                "milestone_id",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "要更新的里程碑 id；新增时留空")
                                },
                            )
                            put(
                                "title",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "里程碑标题；新增时必填，更新时可省略")
                                },
                            )
                            put(
                                "done",
                                buildJsonObject {
                                    put("type", "boolean")
                                    put("description", "是否完成")
                                },
                            )
                            put(
                                "remove",
                                buildJsonObject {
                                    put("type", "boolean")
                                    put("description", "true 表示删除该里程碑")
                                },
                            )
                        },
                    )
                    put("required", buildJsonArray { add(JsonPrimitive("goal_id")) })
                }.toString(),
            ),
        )
    }
}
