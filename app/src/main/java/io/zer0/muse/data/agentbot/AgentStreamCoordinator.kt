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
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.rag.RagService
import io.zer0.muse.tools.FindToolsTool
import io.zer0.muse.tools.RouteTable
import io.zer0.muse.tools.SessionToolLoadRegistry
import io.zer0.muse.tools.SkillExecutor
import io.zer0.muse.tools.ToolApprovalRouter
import io.zer0.muse.tools.ToolCallGate
import io.zer0.muse.tools.ToolConfigStore
import io.zer0.muse.tools.ToolExecutionContext
import io.zer0.muse.tools.ToolExposurePolicy
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRouteExecutionGuard
import io.zer0.muse.tools.ToolRouteSnapshot
import io.zer0.muse.tools.ToolSchemaRules
import io.zer0.muse.tools.ToolRiskLevel
import io.zer0.muse.tools.ToolApprovalPolicy
import io.zer0.muse.tools.ToolApprovalState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
class AgentStreamCoordinator(
    private val streamingClient: StreamingClient,
    private val publication: PublicationService,
    private val governor: BotResourceGovernor,
    private val toolRegistry: ToolRegistry? = null,
    private val skillRepository: SkillRepository? = null,
    private val skillExecutorProvider: (() -> SkillExecutor)? = null,
    private val toolConfigStore: ToolConfigStore? = null,
    private val settings: io.zer0.muse.data.SettingsRepository? = null,
    private val allowances: ConversationToolAllowanceStore? = null,
    private val ragService: RagService? = null,
    private val assistantRepository: AssistantRepository? = null,
) {

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

    data class TurnOutcome(
        val text: String = "",
        val reasoning: String = "",
        val reasoningDurationMs: Long? = null,
        val mood: String = "",
        val citationUrls: List<String> = emptyList(),
        val reactions: List<ReactionAction> = emptyList(),
        val dispatches: List<MissionDispatch> = emptyList(),
        val steerings: List<SteerAction> = emptyList(),
        val choiceOffer: ChoiceOffer? = null,
        val routineCreations: List<RoutineCreation> = emptyList(),
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
                routineCreations.isNotEmpty()
    }

    private data class ToolCallAccumulator(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
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
    ): TurnOutcome {
        val identityError = identity.validate()
        if (identityError != null) {
            Logger.e(TAG, "流式回合身份无效: $identityError")
            return TurnOutcome(streamError = identityError)
        }

        val conversationMessages = mutableListOf<UIMessage>().apply {
            if (systemPrompt.isNotBlank()) {
                add(UIMessage(role = MessageRole.SYSTEM, content = systemPrompt))
            }
            val ragMessage = buildAgentRagSystemMessage(assistant, userText)
            if (ragMessage != null) {
                add(ragMessage)
            }
            addAll(history)
            add(
                UIMessage(
                    role = MessageRole.USER,
                    content = userText,
                    imageBase64List = inputImageBase64List,
                ),
            )
        }

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

        fun buildSanitizedDraft(
            rawText: String,
            rawReasoning: String,
            thinkingNow: Boolean,
        ): StreamingDraft {
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

        fun emitDraftThrottled(
            rawText: String,
            rawReasoning: String,
            deltaLen: Int,
            force: Boolean = false,
        ) {
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
            } catch (e: Exception) {
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

            conversationMessages += UIMessage(
                role = MessageRole.ASSISTANT,
                content = roundTextBuilder.toString(),
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
                    val toolOutput = executeForegroundHostTool(identity, tc, activeToolset)
                    stickyToolNames += tc.name
                    conversationMessages += UIMessage(
                        role = MessageRole.TOOL,
                        content = toolOutput.take(MAX_FOREGROUND_TOOL_RESULT_CHARS),
                        toolCallId = tc.id,
                    )
                }
            }
        }

        val parsed = parseToolCalls(collectedMetaCalls, fallbackText = totalTextBuilder.toString())
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
            reasoning = finalReasoning,
            reasoningDurationMs = finalReasoningDurationMs,
            mood = finalMood,
            citationUrls = finalCitations,
            reactions = finalReactions,
            dispatches = finalDispatches,
            steerings = parsed.steerings,
            choiceOffer = parsed.choiceOffer,
            routineCreations = parsed.routineCreations,
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

    private suspend fun buildAgentRagSystemMessage(
        assistant: AssistantEntity?,
        userText: String,
    ): UIMessage? {
        val service = ragService ?: return null
        val repo = settings ?: return null
        val query = userText.trim().takeIf { it.isNotBlank() } ?: return null
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
            service.buildInjectionContextWithCitations(query, effectiveConfig, scopeDocIds)
        }.onFailure { Logger.w(TAG, "Agent RAG 检索失败: ${it.message}") }.getOrNull() ?: return null
        if (injection.text.isBlank()) return null
        val clamped = io.zer0.muse.context.ContextBudget().clampText(
            io.zer0.muse.context.ContextSection.RAG_CITATION,
            injection.text,
        )
        return UIMessage(role = MessageRole.SYSTEM, content = clamped)
    }

    private suspend fun buildHostToolset(
        sessionId: String,
        assistant: AssistantEntity?,
    ): HostToolset? {
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
    ): String {
        val registry = toolRegistry ?: return "Error: 工具注册表不可用"
        val store = toolConfigStore ?: return "Error: 工具策略不可用"
        val gate = ToolCallGate(ToolApprovalRouter(), registry, store, settings)
        when (val localState = gate.resolveLocalState(tc.name, tc.arguments)) {
            is ToolApprovalState.Auto, is ToolApprovalState.Approved -> Unit
            is ToolApprovalState.Denied -> {
                return "Error: 工具 '${tc.name}' 未获批准(${localState.reason.ifBlank { "策略不允许" }})"
            }
            is ToolApprovalState.Pending, is ToolApprovalState.Answered -> {
                val allowedInSession = allowances?.isAllowed(identity.sessionId, tc.name) == true
                if (!allowedInSession) {
                    return "ApprovalRequired: 工具 '${tc.name}' 需由用户在审批卡片中拍板；请调用 launch_mission 派发后台任务以弹出审批卡片，或在提示用户开启完全放权后重试。"
                }
            }
        }
        val currentSpace = runCatching { settings?.currentSpaceIdFlow?.first() }
            .getOrNull().orEmpty().ifBlank { "default" }
        val execContext = ToolExecutionContext(
            scope = "main",
            spaceId = currentSpace,
            assistantId = identity.assistantId,
            sessionId = identity.sessionId,
        )
        return runCatching {
            val route = hostToolset.snapshot.routeFor(tc.name)
            val executor = skillExecutorProvider?.invoke()
            when (route) {
                is ToolRouteSnapshot.Route.Skill -> {
                    executor?.execute(
                        skill = route.skill,
                        argumentsJson = tc.arguments,
                        sessionId = identity.sessionId,
                    ) ?: "Error: SkillExecutor 未就绪"
                }
                ToolRouteSnapshot.Route.Local -> {
                    ToolRouteExecutionGuard(registry).executeFromJson(
                        name = tc.name,
                        argumentsJson = tc.arguments,
                        snapshot = hostToolset.snapshot,
                        executionContext = execContext,
                    )
                }
                null -> "Error: 工具 '${tc.name}' 不在当前会话允许的工具面中"
            }
        }.onFailure { e ->
            if (e is CancellationException) throw e
            Logger.w(TAG, "前台执行工具 ${tc.name} 失败: ${e.message}", e)
        }.getOrElse { e ->
            "Error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private data class ParsedActions(
        val sayText: String,
        val reactions: List<ReactionAction>,
        val dispatches: List<MissionDispatch>,
        val steerings: List<SteerAction>,
        val choiceOffer: ChoiceOffer?,
        val routineCreations: List<RoutineCreation>,
    )

    private fun parseToolCalls(toolCalls: List<ToolCall>, fallbackText: String): ParsedActions {
        var explicitSay = ""
        val reactions = mutableListOf<ReactionAction>()
        val dispatches = mutableListOf<MissionDispatch>()
        val steerings = mutableListOf<SteerAction>()
        var choiceOffer: ChoiceOffer? = null
        val routines = mutableListOf<RoutineCreation>()

        toolCalls.forEach { call ->
            val args = runCatching {
                json.parseToJsonElement(call.arguments) as? JsonObject
            }.onFailure {
                Logger.w(TAG, "流式工具参数解析失败(${call.name}): ${it.message}")
            }.getOrNull()

            when (call.name) {
                TOOL_REACT -> {
                    val emoji = args?.str("emoji").orEmpty().trim()
                    if (emoji.isNotBlank()) {
                        reactions += ReactionAction(
                            emoji = emoji.take(MAX_EMOJI_CHARS),
                            targetMessageId = args?.str("target_message_id").orEmpty().trim(),
                        )
                    }
                }
                TOOL_LAUNCH_MISSION,
                ForegroundCoordinator.TOOL_DISPATCH,
                -> {
                    val goal = args?.str("goal").orEmpty().trim()
                    if (goal.isNotBlank()) {
                        dispatches += MissionDispatch(
                            goal = goal,
                            acceptance = args?.str("acceptance").orEmpty().trim(),
                            resourceScope = args?.str("resource_scope").orEmpty().trim(),
                            assigneeBotId = args?.str("assignee_bot_id").orEmpty().trim(),
                        )
                    }
                }
                TOOL_STEER_MISSION -> {
                    val taskId = args?.str("task_id").orEmpty().trim()
                    val instruction = args?.str("instruction").orEmpty().trim()
                    if (taskId.isNotBlank() && instruction.isNotBlank()) {
                        steerings += SteerAction(taskId = taskId, instruction = instruction)
                    }
                }
                TOOL_OFFER_CHOICES -> {
                    val prompt = args?.str("prompt").orEmpty().trim()
                    val rawOptions = (args?.get("options") as? JsonArray).orEmpty()
                    val parsedOptions = rawOptions.mapIndexedNotNull { idx, elem ->
                        when (elem) {
                            is JsonPrimitive -> {
                                val text = elem.content.trim()
                                if (text.isBlank()) null else ChoiceOption("opt_${idx + 1}", text, text)
                            }
                            is JsonObject -> {
                                val label = elem.str("label").ifBlank { elem.str("title") }.trim()
                                if (label.isBlank()) {
                                    null
                                } else {
                                    ChoiceOption(
                                        id = elem.str("id").ifBlank { "opt_${idx + 1}" },
                                        label = label,
                                        prompt = elem.str("prompt").ifBlank { label }.trim(),
                                    )
                                }
                            }
                            else -> null
                        }
                    }.take(MAX_CHOICE_OPTIONS)
                    if (parsedOptions.isNotEmpty()) {
                        choiceOffer = ChoiceOffer(prompt = prompt, options = parsedOptions)
                    }
                }
                TOOL_CREATE_ROUTINE -> {
                    val title = args?.str("title").orEmpty().trim()
                    val goal = args?.str("goal").orEmpty().trim()
                    if (title.isNotBlank() && goal.isNotBlank()) {
                        val triggerRaw = args?.str("trigger_type").orEmpty().trim().uppercase()
                        val triggerType = if (triggerRaw in RoutineTriggerType.all) {
                            triggerRaw
                        } else {
                            RoutineTriggerType.MANUAL
                        }
                        routines += RoutineCreation(
                            title = title,
                            goalPrompt = goal,
                            triggerType = triggerType,
                            triggerExpr = args?.str("trigger_expr").orEmpty().trim(),
                            resourceScope = args?.str("resource_scope").orEmpty().trim(),
                        )
                    }
                }
                ForegroundCoordinator.TOOL_SAY -> {
                    val text = args?.str("text").orEmpty().trim()
                    if (text.isNotBlank()) explicitSay = text
                }
                else -> Logger.w(TAG, "忽略未注册的前台工具: ${call.name}")
            }
        }

        val combinedSay = fallbackText.trim().ifBlank { explicitSay }
        return ParsedActions(
            sayText = combinedSay,
            reactions = reactions,
            dispatches = dispatches,
            steerings = steerings,
            choiceOffer = choiceOffer,
            routineCreations = routines,
        )
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content.orEmpty()

    companion object {
        private const val TAG = "AgentStreamCoord"
        private const val MAX_FOREGROUND_TOOL_ROUNDS = 12
        private const val MAX_FOREGROUND_TOOL_RESULT_CHARS = 12_000
        private const val MAX_EMOJI_CHARS = 8
        private const val MAX_CHOICE_OPTIONS = 4
        private const val DEFAULT_DISPATCH_EMOJI = "👌"
        private const val DEFAULT_ACK_TEXT = "好的，我先放到后台帮你处理，弄好后马上告诉你。"
        private const val DEFAULT_STEER_ACK_TEXT = "收到，我已把新要求同步给正在执行的任务。"
        private const val DEFAULT_ROUTINE_ACK_TEXT = "已经为你保存为自动化任务，随时可在任务面板管理。"

        const val TOOL_REACT = "react_message"
        const val TOOL_LAUNCH_MISSION = "launch_mission"
        const val TOOL_STEER_MISSION = "steer_mission"
        const val TOOL_OFFER_CHOICES = "offer_choices"
        const val TOOL_CREATE_ROUTINE = "create_routine"

        private val AGENT_META_NAMES = setOf(
            TOOL_REACT,
            TOOL_LAUNCH_MISSION,
            TOOL_STEER_MISSION,
            TOOL_OFFER_CHOICES,
            TOOL_CREATE_ROUTINE,
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

        fun fromChatService(chatService: ChatService): StreamingClient =
            object : OptionsStreamingClient {
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
        )
    }
}
