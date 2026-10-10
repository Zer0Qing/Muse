package io.zer0.muse.ui.chat
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.ChatStopReason
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ProviderType
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.Perf
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.chat.InternalPromptMarkers
import io.zer0.muse.data.artifact.ArtifactExtractor
import io.zer0.muse.data.chat.rewrite.ConversationEventDraft
import io.zer0.muse.data.chat.rewrite.ConversationEventType
import io.zer0.muse.data.chat.rewrite.ConversationRebuildFlagStore
import io.zer0.muse.data.chat.rewrite.MessageCommitRequest
import io.zer0.muse.data.chat.rewrite.buildCommitParts
import io.zer0.muse.data.session.ConversationTurnEntity
import io.zer0.muse.notification.MuseNotificationTarget
import io.zer0.muse.tools.SessionToolLoadRegistry
import io.zer0.muse.tools.StreamRoundParams
import io.zer0.muse.tools.StreamRoundResult
import io.zer0.muse.tools.ToolApprovalState
import io.zer0.muse.tools.ToolExposurePolicy
import io.zer0.muse.tools.ToolLoopHost
import io.zer0.muse.tools.ToolLoopParams
import io.zer0.muse.tools.ToolRoundPresentationPolicy
import io.zer0.muse.ui.ChatErrorType
import io.zer0.muse.ui.ChatStreamPhase
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.mergeFinalAssistantMedia
import io.zer0.muse.util.ErrorMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Suppress(
    "LongParameterList", "LongMethod", "ComplexMethod", "TooGenericExceptionCaught",
    "ReturnCount", "CyclomaticComplexMethod", "NestedBlockDepth", "ThrowsCount",
    "TooManyFunctions", "LargeClass", "ComplexCondition", "LoopWithTooManyJumpStatements",
)
internal class ChatToolLoopRunner(
    private val host: ChatToolLoopHostBridge,
) {

    /**
     * 原 ChatViewModel.runToolLoop。
     * 所有对 ViewModel 成员/方法的访问经 [host] 桥接,保持行为等价。
     */
    suspend fun run(state: StreamRunState): Boolean {
        val experiments = state.experiments
        val sessionId = state.sessionId
        val sessionTitle = state.sessionTitle
        val streamStartedAt = state.streamStartedAt
        // B5-01: 生成检查点 — 记录本轮用户消息与生成起始时间,供进程被杀后恢复
        val checkpointUserMessageId = state.conversationHistory.lastOrNull { it.role == MessageRole.USER }?.id?.toString() ?: ""
        val checkpointCreatedAt = streamStartedAt
        val pendingRagCitations = state.pendingRagCitations
        val assistant = state.assistant
        val effectiveModel = state.effectiveModel
        val effectiveProviderConfig = state.effectiveProviderConfig
        // C-12: 工具模型配置(可为 null)—— 仅"工具轮"(上一轮结果含 toolCalls 的续接轮)使用,
        // 最终回复轮切回主模型 effectiveModel,避免主模型视觉被 toolModel 降级为文本路由。
        val toolModel = state.toolModel
        val toolProviderConfig = state.toolProviderConfig
        val tools = state.tools
        val effectiveTemperature = state.effectiveTemperature
        val topP = assistant?.topP
        val reasoningLevel = state.reasoningLevel
        val searchConfig = host.stateStore.state.value.webSearchConfig
        val nativeWebSearchEnabled =
            searchConfig.enabled &&
                searchConfig.mode in
                setOf(
                    io.zer0.muse.web.WebSearchMode.AUTO,
                    io.zer0.muse.web.WebSearchMode.NATIVE,
                ) && effectiveProviderConfig?.type in
                setOf(
                    ProviderType.GEMINI,
                    ProviderType.OPENAI_RESPONSES,
                )
        val localWebSearchEnabled =
            searchConfig.enabled &&
                searchConfig.mode != io.zer0.muse.web.WebSearchMode.OFF &&
                !nativeWebSearchEnabled
        val conversationHistory = state.conversationHistory
        val unmaskPii: (String) -> String = state::unmaskPii
        // 工具执行上下文由宿主捕获，模型不能通过参数切换记忆 scope/space。
        val toolExecutionContext =
            io.zer0.muse.tools.ToolExecutionContext(
                scope = assistant?.id?.takeIf { it.isNotBlank() && it != "default" } ?: "main",
                spaceId = host.settings.currentSpaceIdFlow.firstOrNull().orEmpty().ifBlank { "default" },
                assistantId = assistant?.id,
                // v2.x 阶段3:宿主会话 id — find_tools 装载等按会话生效
                sessionId = state.sessionId,
            )

        // v1.42: 流式过程中 UI/通知/token 更新采用字符+时间双阈值节流,降低重组频率。
        // 这些变量仅在 streamRound 内使用,跨轮共享、重试时重置(与原实现一致)。
        var firstTokenTime = 0L // v2.3: 首 token 到达时间
        var lastLoggedCharCount = 0
        var lastUiUpdateChars = 0
        var lastUiUpdateAt = streamStartedAt
        var lastReasoningUiUpdateChars = 0
        var lastNotifChars = 0
        var lastNotifAt = streamStartedAt
        var lastTokenUpdateChars = 0
        var lastTokenUpdateAt = streamStartedAt
        // v1.43: 流式中周期性落盘,避免切页/后台后丢失进度。
        var lastPersistChars = 0
        var lastPersistAt = streamStartedAt
        // v1.0.4: 自适应切片状态 — 仅对 ContentDelta(文本流)生效,不影响 Reasoning/ToolCall。
        // pendingBuilder 累积未输出到 UI 的 delta,50ms 节流触发时按 computeAdaptiveSlice() 取切片。
        val pendingBuilder = StringBuilder()
        val chunkIntervals = ArrayDeque<Long>(STREAM_SLIDE_WINDOW)
        var lastChunkAt = 0L

        /**
         * v1.0.4: 根据流式速率(滑动平均)计算本次 50ms 节流应输出的字符数。
         *
         * - 间隔越大(慢速流,如 Reasoning)→ rate 越小 → 切片越小(细粒度,最小 2)
         * - 间隔越小(快速流,如纯文本)→ rate 越大 → 切片越大(批量,最大 240)
         * - 无样本时返回基准值 STREAM_SLICE_BASE
         */
        fun computeAdaptiveSlice(currentLength: Int): Int {
            // v2.0: 长文本阶段刷新节拍变慢,切片同步放大,保证屏幕上的流入速度不降。
            val flushScale = host.streamFlushIntervalMs(currentLength).toDouble() / STREAM_THROTTLE_MS.toDouble()
            if (chunkIntervals.isEmpty()) {
                return (STREAM_SLICE_BASE * flushScale).toInt().coerceIn(STREAM_SLICE_MIN, STREAM_SLICE_MAX)
            }
            val avgInterval = chunkIntervals.average().toLong()
            // rate = 50ms / avgInterval:间隔 50ms → rate=1;间隔 500ms → rate=0.1;间隔 5ms → rate=10
            val rate = (50.0 / maxOf(1L, avgInterval)).coerceIn(0.1, 10.0)
            return (STREAM_SLICE_BASE * rate * flushScale).toInt().coerceIn(STREAM_SLICE_MIN, STREAM_SLICE_MAX)
        }

        // Phase 2: 工具调用循环下沉到 ToolOrchestrator
        // v1.135: 记录当前助手消息 id,供媒体生成类工具更新消息 UI。
        // A-13: 启动新一轮生成时递增代际令牌,使上一轮在途工具执行的媒体写入失效。
        host.generationState.toolAssistantId = state.currentAssistantId
        host.generationState.activeToolSessionId = state.sessionId
        host.nextToolGenerationToken()
        // C-17: 本代媒体登记表清零(上一代遗留 id 不参与本代收尾兜底)
        host.generationState.toolMediaMessages.clear()
        val baseHistorySize = conversationHistory.size
        // B10: 流式 ThinkTag 实时剥离已接入 — 挂点在 flushPendingToUi(节流触发的 UI 更新),
        // 由 updateAssistantWithVisualTransform 完成: 先 O(n) contains("<think>") 短路,
        // 命中时才调用 transformer 层纯函数 splitThinkTagsForStreaming(见 ThinkTagTransformer.kt),
        // 把剥离后的 content / reasoning 喂回 updateAssistant。不含标签的普通流式路径
        // 零行为变化,50ms / 自适应切片节流机制本身未改动。
        val toolLoopHost =
            object : ToolLoopHost {
                /**
                 * v2.x: 本轮可用的上下文预算 = 窗口 − 预留量，口径与自动压缩触发一致
                 * （见 [shouldAutoCompress]）。宿主在这里只提供"还剩多少可用"，
                 * 编排器据此做零请求的本地重建，避免生成中途把窗口撞爆。
                 */
                override fun contextBudgetTokens(): Int {
                    val window = host.stateStore.state.value.contextMaxTokens
                    if (window <= 0) return 0
                    val reserve = maxOf(AUTO_COMPRESS_MIN_RESERVE_TOKENS, (window * AUTO_COMPRESS_RESERVE_RATIO).toInt())
                    return (window - reserve).coerceAtLeast(0)
                }

                override suspend fun streamRound(params: StreamRoundParams): StreamRoundResult {
                    val round = params.round
                    // Client-side tools necessarily use follow-up provider requests so the model can
                    // incorporate tool results. Keep reasoning visible for every round when the
                    // user enabled it; providers that do not support reasoning still legitimately
                    // produce no reasoning delta.
                    val exposeRoundThinking = ToolRoundPresentationPolicy.exposeReasoning(round)
                    // A-14: 每轮开始把当前轮占位消息 id 同步回 state — 中断恢复(catch 块)依赖
                    // state.currentAssistantId 定位"本轮生成消息",此前只在 runToolLoop 收尾更新,
                    // 多轮工具循环中段被取消时指向旧轮次,导致 [已中断] 落错消息。
                    state.currentAssistantId = params.currentAssistantId
                    if (!state.shadowTurnStarted && ConversationRebuildFlagStore.current.shadowEventsEnabled) {
                        state.shadowTurnStarted = true
                        val now = System.currentTimeMillis()
                        host.conversationService.startTurn(
                            ConversationTurnEntity(
                                turnId = state.turnId,
                                sessionId = sessionId,
                                inputUserMessageId = checkpointUserMessageId,
                                assistantMessageId = params.currentAssistantId.toString(),
                                phase = "STREAMING",
                                streamId = state.streamId,
                                generationSerial = state.generationSerial,
                                startedAt = state.streamStartedAt,
                                updatedAt = now,
                            ),
                        )
                        host.recordConversationShadow(
                            ConversationEventDraft(
                                sessionId = sessionId,
                                turnId = state.turnId,
                                type = ConversationEventType.ASSISTANT_STARTED,
                                streamId = state.streamId,
                                generationSerial = state.generationSerial,
                                payloadJson = "{\"assistantMessageId\":\"${params.currentAssistantId}\"," +
                                    "\"userMessageId\":\"$checkpointUserMessageId\"",
                            ),
                        )
                    }
                    // 审查修复 (2.0 A-02): 同步工具媒体写入目标 — host.generationState.toolAssistantId 原本只在
                    // runToolLoop 起点赋值一次,第 2 轮起的 exec* 媒体全部落回首轮占位 A0,
                    // 而正文/工具卡片在 A1/A2;这里每轮入口同步,使本轮工具执行(awaitAll
                    // 于下一轮 streamRound 之前完成)的媒体写入本轮占位消息。
                    host.generationState.toolAssistantId = params.currentAssistantId
                    // B5-03: 多轮 thinking 签名/加密内容累积,最终写入 assistant 消息
                    var thinkingSignature: String? = null
                    var thinkingEncryptedContent: String? = null
                    // B3-03: 断线续传去重 — 跳过与已显示内容重复的前缀 delta,避免用户看到重复文本
                    var duplicateRemaining: String? = if (params.preservePartialContent) params.builder.toString() else null
                    var firstContentDeltaSeen = false
                    // v2.0: 续传重写检测 — 本轮尝试累积的完整文本(含被去重跳过的前缀) + 原文长度。
                    // 当 provider 在续传/重试时从头重写、且与原内容大面积重叠时,
                    // 用新一轮全文替换旧内容,而不是把新内容追加到旧文后面(那也是“重复回复”的主要来源)。
                    var resumeAttemptText: StringBuilder? = if (params.preservePartialContent) StringBuilder() else null
                    val resumeDuplicateTotal = duplicateRemaining?.length ?: 0
                    // v2.0: 续传前的原文快照(Done 阶段“新文包含原文开头”兑底判定用)
                    val resumeOriginalText = duplicateRemaining?.takeIf { it.isNotEmpty() }
                    // v1.0.17: preservePartialContent=true 时跳过 clear,保留 StreamInterrupted 已收的部分内容
                    if (!params.preservePartialContent) {
                        params.builder.clear()
                        params.reasoningBuilder.clear()
                    } else {
                        Logger.i("ChatVM", "streamRound retry with preservePartialContent, keep ${params.builder.length} chars")
                    }

                    // B5-01: 每轮开始写入生成检查点,确保流式产出有持久化兜底
                    host.persistCheckpointAndReleaseOutbox(
                        state = state,
                        sessionId = sessionId,
                        userMessageId = checkpointUserMessageId,
                        assistantMessageId = params.currentAssistantId.toString(),
                        content = unmaskPii(params.builder.toString()),
                        createdAt = checkpointCreatedAt,
                        // 删除 outbox 只发生在 helper 确认 checkpoint 成功之后。
                    )

                    // C-12: 本轮模型选择 — 仅"工具轮"(上一轮结果含 toolCalls → round>1)用 toolModel,
                    // 首轮(可能直接出最终回复、需视觉读图)与后续最终回复一律使用主模型,
                    // 避免工具启用并配置 toolModel 后所有轮次被降级成纯文本路由。
                    // 未配置 toolModel(state.toolModel==null)时此分支恒走主模型,默认行为完全不变;
                    // 工具轮若历史含图而 toolModel 无视觉,也回退主模型避免给不支持视觉的模型带图 400。
                    // 审查修复 (2.0 A-01): forceMainModel 置位(主模型补轮递归)时短路 isToolRound,
                    // 防止二次补轮递归;工具轮最终答复检测在本轮流结束处进行。
                    val isToolRound = !params.forceMainModel && params.round > 1
                    val usesToolModel =
                        isToolRound && toolModel != null &&
                            params.history.let { host.canUseToolModelForRound(it, toolModel) }
                    val roundModel = if (usesToolModel) toolModel else effectiveModel
                    val roundProviderConfig = if (usesToolModel) toolProviderConfig else effectiveProviderConfig
                    val latestUserText =
                        params.history
                            .lastOrNull { it.role == MessageRole.USER }
                            ?.content
                            .orEmpty()
                    // 工具请求的第一轮只需完成意图判断;工具结果回填后的续接轮只需读取结果并收尾。
                    // 关闭这两类请求的重复深度思考,把用户主动开启的深度思考保留给普通复杂问答。
                    val roundReasoningLevel =
                        when {
                            params.forceMainModel -> reasoningLevel
                            params.round > 1 ->
                                ToolRoundPresentationPolicy.reasoningLevelForRound(
                                    configured = reasoningLevel,
                                    round = params.round,
                                )
                            // v2.0: 只有用户明确要求动作(工具意图)时才压缩本轮思考/输出预算;
                            // 普通短句(如“你好”)不再被误降级,用户开启的深度思考保持生效。
                            tools.isNotEmpty() && ToolExposurePolicy.isDirectToolRequest(latestUserText, tools) ->
                                ToolRoundPresentationPolicy.reasoningLevelForDirectTool(
                                    configured = reasoningLevel,
                                    supportsReasoning = roundModel?.supportsReasoning() == true,
                                )
                            else -> reasoningLevel
                        }
                    val configuredMaxTokens = assistant?.maxTokens?.takeIf { it > 0 }
                    val roundMaxTokens =
                        when {
                            params.forceMainModel -> configuredMaxTokens
                            params.round > 1 -> {
                                // v2.x: 续接轮预算修复 — 原 1024 上限在两类场景必被截断:
                                //   ① 工具结果回填后模型需输出长内容(写文件/长回复);
                                //   ② 推理模型单是思考就消耗 2-4K token, 1024 下正文几乎无空间。
                                // 二轮上调至 16384: 长工具结果回填后的收尾输出(如读完文件写分析)需要充足空间。
                                val base = (configuredMaxTokens ?: 16_384).coerceAtMost(16_384)
                                io.zer0.memory.budget.LlmBudget.withReasoningHeadroom(base, roundModel)
                            }
                            tools.isNotEmpty() && ToolExposurePolicy.isDirectToolRequest(latestUserText, tools) ->
                                if (roundModel?.supportsReasoning() == true) {
                                    // v2.x: 工具轮要生成完整工具参数(可能很长, 如写文件), 原 1536 会切参数;
                                    // 二轮上调 16384 — 推理模型思考+长参数, 空间要充足
                                    configuredMaxTokens?.coerceAtMost(16_384) ?: 16_384
                                } else {
                                    // v2.x: 原 512 同样会切长参数, 上调 8192
                                    configuredMaxTokens?.coerceAtMost(8_192) ?: 8_192
                                }
                            else ->
                                configuredMaxTokens?.let {
                                    io.zer0.memory.budget.LlmBudget.withReasoningHeadroom(it, roundModel)
                                }
                        }
                    val disableTools = ToolExposurePolicy.shouldDisableTools(latestUserText)
                    val toolChoice =
                        when {
                            params.forceFinalResponse || disableTools -> "none"
                            params.round == 1 &&
                                !params.forceMainModel &&
                                ToolExposurePolicy.shouldRequireTool(latestUserText, tools) -> "required"
                            else -> null
                        }
                    // ToolOrchestrator 原生请求失败后会把这一字段降为 false，
                    // 下一次同轮请求才会真正切到用户 API / Bing HTTP / 百度 HTTP。
                    val nativeSearchForRound =
                        params.nativeWebSearch && params.round == 1 && !params.forceFinalResponse
                    val streamToUi =
                        host.stateStore.state.value.chatPreferences.streamResponse &&
                            (assistant?.streamOutput ?: true)
                    val defaultRoundMode =
                        if (roundReasoningLevel == ReasoningLevel.OFF) {
                            ChatRequestMode.UTILITY
                        } else {
                            ChatRequestMode.CHAT
                        }

                    suspend fun requestRoundFlow(
                        requestedToolChoice: String?,
                        requestedReasoningLevel: ReasoningLevel,
                        requestedMode: ChatRequestMode,
                    ): Flow<ChatStreamEvent> {
                        // v2.0 简单请求关键词收窄 → v2.x 阶段1 升级为默认分层:
                        //   CORE/STANDARD 恒发;OPTIONAL/GLOBAL 按族命中 + 会话粘性(用过的工具)
                        //   + 会话授权("本会话允许") + find_tools 动态装载放行;GLOBAL 未命中/未授权不发。
                        val stickyToolNames = host.collectStickyToolNames(params.history)
                        val authorizedToolNames = host.sessionPermissionStore.allowedToolsThisSession(state.sessionId)
                        val loadedToolNames = SessionToolLoadRegistry.loadedFor(state.sessionId)
                        // 技能在 ToolCategories 里无分类,与 OPTIONAL 同口径收窄(见 filterToolsForRequest);
                        // 未收窄前每个已启用技能每轮都发,是工具 schema 占位的最大来源。
                        val skillToolNames = state.skillMap.keys
                        val requestTools =
                            ToolExposurePolicy
                                .filterToolsForRequest(
                                    latestUserText,
                                    tools,
                                    stickyToolNames,
                                    authorizedToolNames,
                                    loadedToolNames,
                                    skillToolNames,
                                )
                                .takeUnless { disableTools || nativeSearchForRound || params.forceFinalResponse }
                                ?: emptyList()
                        val resumeText =
                            params.builder.toString()
                                .takeIf { params.preservePartialContent && it.isNotBlank() }

                        @Suppress("TooGenericExceptionCaught")
                        suspend fun completeTextEvents(): List<ChatStreamEvent> = try {
                            val completion =
                                host.chatService.completeText(
                                    messages =
                                    if (resumeText != null) {
                                        params.history + UIMessage(role = MessageRole.ASSISTANT, content = resumeText)
                                    } else {
                                        params.history
                                    },
                                    model = roundModel,
                                    providerConfig = roundProviderConfig,
                                    tools = requestTools,
                                    toolChoice = requestedToolChoice,
                                    nativeWebSearch = nativeSearchForRound,
                                    temperature = effectiveTemperature,
                                    topP = topP,
                                    maxTokens = roundMaxTokens,
                                    reasoningLevel = requestedReasoningLevel,
                                    mode = requestedMode,
                                )
                            completionToStreamEvents(completion)
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            throw ce
                        } catch (error: Exception) {
                            listOf(
                                ChatStreamEvent.Error(
                                    message = error.message ?: "非流式请求失败",
                                    throwable = error,
                                ),
                            )
                        }
                        val currentTimeIncluded =
                            params.history.any { message ->
                                message.role == MessageRole.SYSTEM &&
                                    message.content.contains(InternalPromptMarkers.TIME_SECTION_PREFIX)
                            }
                        Logger.d(
                            "ChatVM",
                            "provider round request | round=${params.round}" +
                                " | systemMessages=${params.history.count { it.role == MessageRole.SYSTEM }}" +
                                " | currentTime=$currentTimeIncluded" +
                                " | tools=${requestTools.size} | toolChoice=${requestedToolChoice ?: "auto"}",
                        )
                        return if (streamToUi) {
                            host.chatService.streamChat(
                                messages = params.history,
                                model = roundModel,
                                providerConfig = roundProviderConfig,
                                // “不要调用工具”由请求层硬关闭；不依赖模型遵守提示词。
                                tools = requestTools,
                                toolChoice = requestedToolChoice,
                                nativeWebSearch = nativeSearchForRound,
                                temperature = effectiveTemperature,
                                topP = topP,
                                maxTokens = roundMaxTokens,
                                reasoningLevel = requestedReasoningLevel,
                                mode = requestedMode,
                                resumeFromText = resumeText,
                            )
                        } else {
                            // 关闭流式输出时仍走同一请求参数，但通过 completeText 真正执行一次性请求。
                            // 中断续传场景补回已显示的 assistant 文本，保持与 streamChat 一致的上下文。
                            flow {
                                completeTextEvents().forEach { emit(it) }
                            }
                        }
                    }
                    val imageAccumulator = mutableListOf<String>()
                    val citationUrls = linkedSetOf<String>()
                    val toolCallAccumulator = mutableMapOf<Int, Triple<String?, String?, StringBuilder>>()
                    var streamError: String? = null
                    var doneFinishReason: String? = null
                    // v1.0.15: StreamInterrupted 标志 — 已收部分内容后网络中断,
                    //   等待 NetworkMonitor 网络恢复事件后重试(非固定 delay),避免盲重试立即失败
                    var streamInterrupted = false
                    // v1.0.17: StreamInterrupted 的原始 throwable,用于判断是否网络错误(IOException)
                    var streamInterruptedThrowable: Throwable? = null
                    val pendingFlushMutex = Mutex()

                    suspend fun flushPendingToUi(force: Boolean = false) {
                        if (!streamToUi) return
                        pendingFlushMutex.withLock {
                            if (pendingBuilder.isEmpty()) return@withLock
                            val now = System.currentTimeMillis()
                            if (!force && now - lastUiUpdateAt < host.streamFlushIntervalMs(params.builder.length)) return@withLock
                            val sliceLength =
                                if (force) {
                                    pendingBuilder.length
                                } else {
                                    minOf(computeAdaptiveSlice(params.builder.length), pendingBuilder.length)
                                }
                            params.builder.append(pendingBuilder.substring(0, sliceLength))
                            pendingBuilder.delete(0, sliceLength)
                            host.updateAssistantWithVisualTransform(
                                params.currentAssistantId,
                                unmaskPii(params.builder.toString()),
                                isStreaming = true,
                            )
                            state.uiFlushCount++
                            lastUiUpdateChars = params.builder.length
                            lastUiUpdateAt = now
                        }
                    }

                    // P2-2: reasoning-only 流(无 ContentDelta)也必须周期性落盘。
                    // 统一增量入口 — ContentDelta 与 ReasoningDelta 都经此节流持久化,
                    // 避免"只有思考内容"的流被强杀后仅剩空 checkpoint。
                    suspend fun throttledPersist() {
                        val now = System.currentTimeMillis()
                        if (params.builder.length - lastPersistChars < PERSIST_CHAR_THRESHOLD &&
                            now - lastPersistAt < PERSIST_TIME_THRESHOLD_MS
                        ) {
                            return
                        }
                        lastPersistChars = params.builder.length
                        lastPersistAt = now
                        host.chatGenerationManager.touch(sessionId)
                        val persistMsg =
                            host.stateStore.messages.value
                                .firstOrNull { it.id == params.currentAssistantId }
                                ?.copy(
                                    content = unmaskPii(params.builder.toString()),
                                    reasoning = unmaskPii(params.reasoningBuilder.toString()).ifBlank { null },
                                    thinkingSignature = thinkingSignature,
                                    thinkingEncryptedContent = thinkingEncryptedContent,
                                )
                                ?: UIMessage(
                                    id = params.currentAssistantId,
                                    role = MessageRole.ASSISTANT,
                                    content = unmaskPii(params.builder.toString()),
                                    reasoning = unmaskPii(params.reasoningBuilder.toString()).ifBlank { null },
                                    thinkingSignature = thinkingSignature,
                                    thinkingEncryptedContent = thinkingEncryptedContent,
                                )
                        // B-1: 会话已删除则跳过周期性落盘与检查点,防止删后"复活"。
                        if (!host.generationController.isSessionWritesSuppressed(sessionId)) {
                            host.persistCurrentAssistant(sessionId, params.currentAssistantId, persistMsg)
                            host.persistCheckpointAndReleaseOutbox(
                                state = state,
                                sessionId = sessionId,
                                userMessageId = checkpointUserMessageId,
                                assistantMessageId = params.currentAssistantId.toString(),
                                content = unmaskPii(params.builder.toString()),
                                createdAt = checkpointCreatedAt,
                            )
                        }
                    }

                    var compatibilityRetryUsed = false
                    var retryWithoutToolChoice = false
                    coroutineScope {
                        // 旧逻辑只有收到下一个 token 时才检查 50ms 节流;
                        // 如果上游短暂停顿,尾部 pendingBuilder 会一直留到 Done 才一次性刷出。
                        // 独立刷新协程让 UI 按时间稳定更新,不改变网络流和工具执行顺序。
                        val pendingFlushJob =
                            launch {
                                while (isActive) {
                                    delay(host.streamFlushIntervalMs(params.builder.length))
                                    flushPendingToUi()
                                }
                            }
                        try {
                            do {
                                retryWithoutToolChoice = false
                                val requestToolChoice = if (compatibilityRetryUsed) null else toolChoice
                                val requestReasoningLevel =
                                    if (compatibilityRetryUsed) {
                                        ReasoningLevel.OFF
                                    } else {
                                        roundReasoningLevel
                                    }
                                val requestMode =
                                    if (compatibilityRetryUsed) {
                                        ChatRequestMode.UTILITY
                                    } else {
                                        defaultRoundMode
                                    }
                                val flow =
                                    requestRoundFlow(
                                        requestedToolChoice = requestToolChoice,
                                        requestedReasoningLevel = requestReasoningLevel,
                                        requestedMode = requestMode,
                                    )
                                flow.takeWhile { event ->
                                    val hasMeaningfulOutput =
                                        params.builder.isNotEmpty() ||
                                            params.reasoningBuilder.isNotEmpty() ||
                                            pendingBuilder.isNotEmpty() ||
                                            imageAccumulator.isNotEmpty() ||
                                            toolCallAccumulator.isNotEmpty()
                                    val shouldRetry =
                                        event is ChatStreamEvent.Error &&
                                            shouldRetryToolChoiceCompatibility(
                                                message = event.message,
                                                toolChoice = requestToolChoice,
                                                retryUsed = compatibilityRetryUsed,
                                                hasMeaningfulOutput = hasMeaningfulOutput,
                                            )
                                    if (shouldRetry) {
                                        compatibilityRetryUsed = true
                                        retryWithoutToolChoice = true
                                        Logger.w(
                                            "ChatVM",
                                            "thinking mode 不支持 tool_choice=required,关闭本轮思考后重试一次",
                                        )
                                    }
                                    !shouldRetry
                                }.collect { event ->
                                    when (event) {
                                        is ChatStreamEvent.ContentDelta -> {
                                            // C-11: 续传去重采用保守裁剪。只有完整重放的 delta 才丢弃；
                                            // 一个 delta 若在旧前缀中途分叉，公共前缀可能只是新内容的巧合开头，
                                            // 必须保留整段，避免用户真正的首字被吞掉。
                                            var effectiveDelta = event.delta
                                            if (experiments.debugMode && !firstContentDeltaSeen && event.delta.isNotEmpty()) {
                                                firstContentDeltaSeen = true
                                                Logger.d(
                                                    "ChatVM-Debug",
                                                    "first content delta | sessionId=$sessionId | round=$round | " +
                                                        "preservePartial=${params.preservePartialContent} | " +
                                                        "deltaChars=${event.delta.length} | " +
                                                        "firstCodeUnit=${event.delta[0].code} | " +
                                                        "builderBefore=${params.builder.length} | " +
                                                        "duplicateRemaining=${duplicateRemaining?.length ?: 0}",
                                                )
                                            }
                                            // v2.0: 续传尝试的全文累积(包含被判为重复而跳过的前缀);
                                            // delta 阶段命中“从头重写”或 Done 阶段命中“新文包含原文开头”时用它替换旧内容。
                                            resumeAttemptText?.append(event.delta)
                                            val duplicate = duplicateRemaining
                                            if (duplicate != null) {
                                                val drop = host.resumeOverlapToDrop(duplicate, event.delta)
                                                if (drop == 0) {
                                                    val consumedChars = resumeDuplicateTotal - duplicate.length
                                                    val restartedWithOverlap =
                                                        host.shouldReplaceOnResumeRewrite(
                                                            duplicateTotal = resumeDuplicateTotal,
                                                            consumedChars = consumedChars,
                                                        )
                                                    if (restartedWithOverlap) {
                                                        val rewritten = resumeAttemptText?.toString().orEmpty()
                                                        if (rewritten.isNotEmpty()) {
                                                            params.builder.setLength(0)
                                                            params.builder.append(rewritten)
                                                            lastUiUpdateChars = params.builder.length
                                                            lastNotifChars = params.builder.length
                                                            lastPersistChars = params.builder.length
                                                            Logger.i(
                                                                "ChatVM",
                                                                "resume rewrite detected, replace content | " +
                                                                "consumed=$consumedChars/$resumeDuplicateTotal | " +
                                                                    "new=${rewritten.length} chars",
                                                            )
                                                        }
                                                        effectiveDelta = ""
                                                    }
                                                    // 无法证明整段 delta 是重放内容，本轮起停止去重。
                                                    duplicateRemaining = null
                                                } else {
                                                    val remaining = duplicate.substring(drop).takeIf { it.isNotEmpty() }
                                                    duplicateRemaining = remaining
                                                    if (drop < event.delta.length) {
                                                        // 整个旧前缀在本 delta 开头被重放，后半段才是新内容。
                                                        effectiveDelta = event.delta.substring(drop)
                                                    } else {
                                                        // 整个 delta 都落在已显示内容内 → 本次忽略,等待后续 delta
                                                        return@collect
                                                    }
                                                }
                                            }
                                            // v1.0.3: 首 token 立即刷新 UI,消除"loading → 大量文字"的视觉断层
                                            val isFirstToken = firstTokenTime == 0L
                                            if (isFirstToken) {
                                                firstTokenTime = System.currentTimeMillis()
                                                // P3-9: 首字耗时埋点 — 从本轮生成启动到首个正文 token 的间隔
                                                Perf.log("chat-first-token", firstTokenTime - streamStartedAt)
                                                // 立即清除"等待首 token"状态,ShimmerBubble 消失,StreamingCursor 接管
                                                // F-10: 首 token 到达 → STREAMING
                                                host.stateStore.state.update {
                                                    it.copy(
                                                        isWaitingFirstToken = false,
                                                        streamState = it.streamState.copy(phase = ChatStreamPhase.STREAMING),
                                                    )
                                                }
                                            }
                                            val now = System.currentTimeMillis()
                                            if (streamToUi) {
                                                // v1.0.4: 自适应切片路径 — delta 先累积到 pendingBuilder,
                                                // 50ms 节流触发时按 computeAdaptiveSlice() 取前 N 字符输出,实现平滑流入。
                                                // chunkIntervals 的读写必须与 computeAdaptiveSlice 同锁：
                                                // pendingFlushJob 协程在 mutex 内遍历它算平均间隔，
                                                // 若这里在锁外 addLast/removeFirst，会触发 ConcurrentModificationException。
                                                pendingFlushMutex.withLock {
                                                    if (lastChunkAt != 0L) {
                                                        chunkIntervals.addLast(now - lastChunkAt)
                                                        if (chunkIntervals.size > STREAM_SLIDE_WINDOW) chunkIntervals.removeFirst()
                                                    }
                                                    lastChunkAt = now
                                                    pendingBuilder.append(effectiveDelta)
                                                }
                                                if (isFirstToken) {
                                                    // 首 token 立即输出,后续由独立刷新协程按 50ms 节奏更新。
                                                    flushPendingToUi(force = true)
                                                } else {
                                                    flushPendingToUi()
                                                }
                                            } else {
                                                // 非流式 UI:直接累积到 builder(保持原行为,通知/持久化仍按 builder.length 节流)
                                                params.builder.append(effectiveDelta)
                                            }
                                            if (params.builder.length - lastNotifChars >= 100 || now - lastNotifAt >= 500) {
                                                lastNotifChars = params.builder.length
                                                lastNotifAt = now
                                                runCatching {
                                                    // v2.5.0 fix: 同步字数到 manager,前台服务通知才能显示真实进度
                                                    host.chatGenerationManager.updateChars(sessionId, params.builder.length)
                                                    host.notificationManager.updateLiveProgress(
                                                        sessionTitle,
                                                        params.builder.length,
                                                        true,
                                                        MuseNotificationTarget.Session(sessionId),
                                                    )
                                                }.onFailure { Logger.w("ChatVM", "更新进度通知失败: ${it.message}") }
                                            }
                                            if (experiments.debugMode && params.builder.length - lastLoggedCharCount >= 100) {
                                                lastLoggedCharCount = params.builder.length
                                                val elapsedMs = System.currentTimeMillis() - streamStartedAt
                                                Logger.d(
                                                    "ChatVM-Debug",
                                                    "streaming | sessionId=$sessionId | round=$round | " +
                                                        "chars=${params.builder.length} | elapsed=${elapsedMs}ms",
                                                )
                                            }
                                            if (params.builder.length - lastTokenUpdateChars >= 400 || now - lastTokenUpdateAt >= 1500) {
                                                lastTokenUpdateChars = params.builder.length
                                                lastTokenUpdateAt = now
                                                host.updateContextTokenCount()
                                            }
                                            // P2-2: reasoning-only 也走统一节流落盘(见 throttledPersist)
                                            throttledPersist()
                                        }
                                        is ChatStreamEvent.ReasoningDelta -> {
                                            // v1.0.3: 首 token 立即刷新 UI(ReasoningDelta 也算首 token)
                                            val isFirstToken = firstTokenTime == 0L
                                            if (isFirstToken) {
                                                firstTokenTime = System.currentTimeMillis()
                                                // P3-9: 首字耗时埋点(reasoning 首 token 同样计入)
                                                Perf.log("chat-first-token", firstTokenTime - streamStartedAt)
                                                host.stateStore.state.update { it.copy(isWaitingFirstToken = false) }
                                            }
                                            if (exposeRoundThinking) params.reasoningBuilder.append(event.delta)
                                            if (!event.signature.isNullOrBlank()) thinkingSignature = event.signature
                                            if (!event.encryptedContent.isNullOrBlank()) thinkingEncryptedContent = event.encryptedContent
                                            val now = System.currentTimeMillis()
                                            val timeSinceUi = now - lastUiUpdateAt
                                            // v1.0.3: 首 token 立即刷新;后续按 12 字符或 50ms 节流
                                            // reasoning-only 流(content 为 0)必须按 reasoning 长度节流,
                                            // 否则首 token 后 UI 永远不更新,用户只看到第一个字符。
                                            val reasoningCharsSinceUi = params.reasoningBuilder.length - lastReasoningUiUpdateChars
                                            if (streamToUi && (
                                                    isFirstToken || reasoningCharsSinceUi >= STREAM_UI_CHAR_THRESHOLD ||
                                                        (timeSinceUi >= STREAM_UI_TIME_THRESHOLD_MS && reasoningCharsSinceUi > 0)
                                                    )
                                            ) {
                                                host.updateAssistant(
                                                    params.currentAssistantId,
                                                    unmaskPii(params.builder.toString()),
                                                    unmaskPii(params.reasoningBuilder.toString()),
                                                    isStreaming = true,
                                                )
                                                state.uiFlushCount++
                                                lastUiUpdateChars = params.builder.length
                                                lastUiUpdateAt = now
                                                lastReasoningUiUpdateChars = params.reasoningBuilder.length
                                            }
                                            // P2-2: reasoning-only 流(无 ContentDelta)也按节流周期落盘
                                            throttledPersist()
                                        }
                                        is ChatStreamEvent.ImageDelta -> {
                                            imageAccumulator.add(event.imageBase64)
                                            val now = System.currentTimeMillis()
                                            if (now - lastUiUpdateAt >= STREAM_UI_TIME_THRESHOLD_MS) {
                                                host.updateAssistant(
                                                    params.currentAssistantId,
                                                    unmaskPii(params.builder.toString()),
                                                    unmaskPii(params.reasoningBuilder.toString()),
                                                    imageAccumulator.toList(),
                                                )
                                                state.uiFlushCount++
                                                lastUiUpdateAt = now
                                            }
                                        }
                                        is ChatStreamEvent.ToolCallDelta -> {
                                            // F-10: 工具调用阶段开始
                                            host.stateStore.state.update {
                                                it.copy(streamState = it.streamState.copy(phase = ChatStreamPhase.TOOLING))
                                            }
                                            val acc =
                                                toolCallAccumulator.getOrPut(event.index) {
                                                    Triple(null, null, StringBuilder())
                                                }
                                            val newId = event.id ?: acc.first
                                            // v2.5.0 fix: 空白 name 不能覆盖已累积的工具名(与上游修复双保险)
                                            val newName = event.name?.takeIf { it.isNotBlank() } ?: acc.second
                                            // v1.0.81: isSnapshot=true 时参数是完整快照(源头已合并多 JSON 分片),替换而非追加
                                            if (event.isSnapshot) {
                                                acc.third.setLength(0)
                                                acc.third.append(event.argumentsDelta ?: "")
                                            } else {
                                                event.argumentsDelta?.let { acc.third.append(it) }
                                            }
                                            toolCallAccumulator[event.index] = Triple(newId, newName, acc.third)
                                            if (experiments.debugMode && event.name != null) {
                                                Logger.d(
                                                    "ChatVM-Debug",
                                                    "toolCallDelta | sessionId=$sessionId | round=$round | " +
                                                        "index=${event.index} | tool=${event.name}",
                                                )
                                            }
                                        }
                                        // A5: provider 实测 token 用量 — 每轮可能多次(Anthropic 输入/输出分开发),
                                        // 直接覆盖 state.usageTokens,保证留存最后一次(总量);provider 未返回则保持 null
                                        is ChatStreamEvent.UsageDelta -> {
                                            state.usageTokens = event.usage
                                        }
                                        is ChatStreamEvent.CitationDelta -> {
                                            citationUrls.addAll(event.urls)
                                        }
                                        is ChatStreamEvent.Done -> {
                                            // v1.0.30: 某些模型（如 GLM-4-9B）全流程只发空名 tool_call，
                                            // 无 ContentDelta/ReasoningDelta，isWaitingFirstToken 全程未清。
                                            // 在 Done 事件强制清除"等待首 token"状态。
                                            if (host.stateStore.state.value.isWaitingFirstToken) {
                                                host.stateStore.state.update { it.copy(isWaitingFirstToken = false) }
                                            }
                                            // The flush coroutine may still own pendingBuilder until collect returns.
                                            // Defer all content mutations until it is cancelled/joined and drained below.
                                            doneFinishReason = event.finishReason
                                        }
                                        is ChatStreamEvent.Error -> {
                                            streamError = event.message
                                            Logger.e("ChatVM", "stream error", event.throwable)
                                            if (experiments.debugMode) {
                                                Logger.d(
                                                    "ChatVM-Debug",
                                                    "stream Error | sessionId=$sessionId | round=$round | msg=${event.message}",
                                                )
                                            }
                                        }
                                        is ChatStreamEvent.FallbackNotice -> {
                                            MuseToast.show(event.message)
                                        }
                                        is ChatStreamEvent.StreamInterrupted -> {
                                            // v1.0.15: 已收部分内容后连接中断,保留内容并等待网络恢复后重试(非固定 delay)
                                            streamError = event.message
                                            streamInterrupted = true
                                            streamInterruptedThrowable = event.throwable
                                            Logger.w("ChatVM", "stream interrupted (partial content kept)", event.throwable)
                                            if (experiments.debugMode) {
                                                Logger.d(
                                                    "ChatVM-Debug",
                                                    "stream Interrupted | sessionId=$sessionId | round=$round | msg=${event.message}",
                                                )
                                            }
                                        }
                                    }
                                }
                                if (retryWithoutToolChoice) {
                                    Logger.i("ChatVM", "tool_choice 兼容重试已切换为 utility 模式")
                                }
                            } while (retryWithoutToolChoice)
                        } finally {
                            pendingFlushJob.cancelAndJoin()
                        }
                    }

                    // v1.0.4: 流结束 flush pendingBuilder 剩余内容(覆盖 Done/Error/StreamInterrupted)。
                    // 自适应切片下 params.builder 可能滞后于 pendingBuilder,这里把未输出部分一次性写入,
                    // 确保最终 updateAssistant / persist 拿到完整内容。
                    if (pendingBuilder.isNotEmpty()) {
                        params.builder.append(pendingBuilder)
                        pendingBuilder.clear()
                        if (streamToUi) {
                            host.updateAssistant(params.currentAssistantId, unmaskPii(params.builder.toString()), isStreaming = true)
                            lastUiUpdateChars = params.builder.length
                            lastUiUpdateAt = System.currentTimeMillis()
                        }
                    }

                    // v2.0: 续传重写兑底 — 本轮尝试的文本把原文开头片段写回来了,
                    // 说明 provider 是"从头生成"而不是"续写",用新文替换旧文,
                    // 消除旧文+新文的重复拼接(流式中未能命中替换的情况在这里收口)。
                    if (streamError == null) {
                        resumeOriginalText?.let { original ->
                            val attempt = resumeAttemptText?.toString()
                            if (host.shouldReplaceOnResumeSupersede(original, attempt, params.builder.toString())) {
                                params.builder.setLength(0)
                                params.builder.append(attempt)
                                lastUiUpdateChars = 0
                                Logger.i(
                                    "ChatVM",
                                    "resume attempt supersedes partial content | old=${original.length} new=${attempt?.length ?: 0}",
                                )
                            }
                        }
                    }

                    // Final content mutations happen only after pending deltas are fully drained.
                    // Reasoning-only fallback must observe the complete body, not just the pre-flush builder.
                    if (streamError == null &&
                        params.builder.isEmpty() &&
                        params.reasoningBuilder.isNotEmpty() &&
                        toolCallAccumulator.isEmpty()
                    ) {
                        params.builder.append(params.reasoningBuilder.toString())
                        params.reasoningBuilder.setLength(0)
                    }
                    if (streamError == null && ChatStopReason.isLengthLimited(doneFinishReason)) {
                        params.builder.append("\n\n").append(host.appContext.getString(R.string.err_reply_truncated))
                    }
                    // v2.x（诊断导出项目 A）: 流收尾分类入 trace（纯观察者，不影呴逻辑）
                    runCatching {
                        io.zer0.muse.diagnostic.GenerationTrace.noteStreamEnd(
                            kind = when {
                                streamError != null -> "failure"
                                doneFinishReason != null -> "done"
                                else -> "closed_no_finish"
                            },
                            finishReason = doneFinishReason,
                        )
                    }
                    if (experiments.debugMode && doneFinishReason != null) {
                        val elapsedMs = System.currentTimeMillis() - streamStartedAt
                        Logger.d(
                            "ChatVM-Debug",
                            "stream Done | sessionId=$sessionId | round=$round | chars=${params.builder.length} | " +
                                "reasoningChars=${params.reasoningBuilder.length} | elapsed=${elapsedMs}ms",
                        )
                    }

                    if (streamError != null) {
                        val retryType = host.classifyErrorType(streamError)
                        // v1.0.1 (P4): 用 params.retryCount 替代外层 streamRetryCount,每轮独立
                        // v1.0.17: StreamInterrupted 智能续传 — 已收部分内容 + 网络错误时,
                        //   等待网络恢复(最多 30s)后用 preservePartialContent=true 重试,
                        //   保留已显示内容,UI 仅追加新内容(不闪回到首字)。
                        //   超时未恢复网络则降级为手动重试(保留部分内容 + isRecoverable)。
                        val isNetworkError =
                            streamInterruptedThrowable is java.io.IOException ||
                                (streamInterrupted && retryType == ChatErrorType.NETWORK)
                        if (streamInterrupted &&
                            isNetworkError &&
                            params.builder.isNotEmpty() &&
                            params.retryCount < MAX_STREAM_RETRIES
                        ) {
                            val newRetryCount = params.retryCount + 1
                            Logger.w(
                                "ChatVM",
                                "StreamInterrupted 网络中断,等待网络恢复(最多 ${NETWORK_RECOVERY_TIMEOUT_MS}ms)" +
                                    "(第 $newRetryCount/$MAX_STREAM_RETRIES 次,round=${params.round}): $streamError",
                            )
                            val recovered = host.waitForNetworkRecovery(NETWORK_RECOVERY_TIMEOUT_MS)
                            if (recovered) {
                                Logger.i("ChatVM", "网络已恢复,preservePartialContent=true 重试")
                                // 重置自适应切片状态(上一轮的速率样本不适用于续传)
                                pendingBuilder.clear()
                                chunkIntervals.clear()
                                lastChunkAt = 0L
                                // 重置 token 计数与首 token 时间,让续传重新计时
                                // (但不重置 builder — preservePartialContent=true 保留已显示内容)
                                lastNotifChars = params.builder.length
                                lastNotifAt = System.currentTimeMillis()
                                lastTokenUpdateChars = params.builder.length
                                lastTokenUpdateAt = System.currentTimeMillis()
                                lastPersistChars = params.builder.length
                                lastPersistAt = System.currentTimeMillis()
                                // B3-03: UI 层续传去重已生效(duplicateRemaining 跳过重复前缀)。
                                // Provider 层 resumeFromText 已接入:已显示内容作为末尾 assistant 消息注入,模型从中断处续写。
                                return streamRound(
                                    params.copy(
                                        retryCount = newRetryCount,
                                        preservePartialContent = true,
                                    ),
                                )
                            } else {
                                Logger.w("ChatVM", "网络未恢复,降级为手动重试")
                            }
                        }
                        if (!streamInterrupted &&
                            (retryType == ChatErrorType.NETWORK || retryType == ChatErrorType.RATE_LIMIT) &&
                            params.retryCount < MAX_STREAM_RETRIES
                        ) {
                            val newRetryCount = params.retryCount + 1
                            // v1.0.16: 退避 3s/10s/30s,覆盖典型切后台时长(5-15s)
                            val delayMs =
                                when (newRetryCount) {
                                    1 -> 3_000L
                                    2 -> 10_000L
                                    else -> 30_000L
                                }
                            Logger.w(
                                "ChatVM",
                                "stream 错误($retryType),${delayMs}ms 后重试 " +
                                    "(第 $newRetryCount/$MAX_STREAM_RETRIES 次,round=${params.round}): $streamError",
                            )
                            kotlinx.coroutines.delay(delayMs)
                            lastUiUpdateChars = 0
                            lastUiUpdateAt = streamStartedAt
                            lastNotifChars = 0
                            lastNotifAt = streamStartedAt
                            lastTokenUpdateChars = 0
                            lastTokenUpdateAt = streamStartedAt
                            lastPersistChars = 0
                            lastPersistAt = streamStartedAt
                            firstTokenTime = 0L
                            // v1.0.4: 重置自适应切片状态,避免上一轮的速率样本污染新一轮
                            pendingBuilder.clear()
                            chunkIntervals.clear()
                            lastChunkAt = 0L
                            // v1.0.3: retry 时重新进入"等待首 token"阶段,
                            // 因为 builder 已被 streamRound 开头的 clear() 清空,
                            // UI 会重新显示 ShimmerBubble 直到首个 token 到达
                            host.stateStore.state.update { it.copy(isWaitingFirstToken = true) }
                            return streamRound(params.copy(retryCount = newRetryCount))
                        }
                    }

                    streamError?.let {
                        val type = host.classifyErrorType(it)
                        val displayMsg = ErrorMessages.resolve(host.appContext, it)
                        // 工具循环出口统一负责向 UI 上报错误，避免同一错误出现两张卡片。
                        host.updateAssistant(
                            params.currentAssistantId,
                            unmaskPii(params.builder.toString()),
                            unmaskPii(params.reasoningBuilder.toString()),
                            imageAccumulator.toList(),
                            isStreaming = false,
                        )
                        val partialAssistant = host.stateStore.messages.value.firstOrNull { it.id == params.currentAssistantId }
                        // B-1: 会话已删除则跳过错误落盘,防止删后"复活"。
                        if (partialAssistant != null && !host.generationController.isSessionWritesSuppressed(sessionId)) {
                            try {
                                host.sessionRepository.upsertMessage(sessionId, partialAssistant)
                            } catch (e: Exception) {
                                Logger.e("ChatVM", "streamError upsertMessage failed", e)
                                host.addError(
                                    ChatErrorType.UNKNOWN,
                                    host.appContext.getString(
                                        R.string.err_chat_reply_save_failed,
                                        e.message ?: host.appContext.getString(R.string.err_chat_unknown),
                                    ),
                                )
                            }
                        }
                        // B-24: 仅自己仍是最新生成时才清零(旧生成错误不得清掉新生成的流式状态)
                        // F-10: 流式错误 → FAILED
                        host.clearStreamingStateIfLatest(state, finalPhase = ChatStreamPhase.FAILED)
                        // B-02: 出口同步最后已知流式内容 — 中断 catch 块只读 state.builder,
                        // 逐轮 params.builder 是局部对象,不同步则停止时部分回复无法构造
                        state.builder.setLength(0)
                        state.builder.append(params.builder)
                        state.reasoningBuilder.setLength(0)
                        state.reasoningBuilder.append(params.reasoningBuilder)
                        return StreamRoundResult.Error(type, displayMsg, params.builder.toString(), params.reasoningBuilder.toString())
                    }

                    // 审查修复 (2.0 A-01): 工具轮若直接产出最终答复(无 toolCalls),说明本轮
                    // 实为"最终回复轮" — 用主模型补一轮,保证最终答复由主模型输出(与上方
                    // 4840 注释意图一致)。仅在 toolModel != 主模型时重跑;补轮失败(网络/限流)
                    // 时保留 toolModel 已产出的答复作为兜底,不让用户面对空回复。
                    val hasToolCalls = toolCallAccumulator.isNotEmpty()
                    if (usesToolModel && !hasToolCalls && roundModel != effectiveModel) {
                        Logger.i("ChatVM", "工具轮产出最终答复(round=$round), 用主模型补一轮 | model=${effectiveModel?.id}")
                        val toolModelContent = params.builder.toString()
                        val toolModelReasoning = params.reasoningBuilder.toString()
                        // 清空本轮累积,补轮从零开始;自适应切片/节流/去重/首 token 状态一并复位
                        params.builder.setLength(0)
                        params.reasoningBuilder.setLength(0)
                        imageAccumulator.clear()
                        toolCallAccumulator.clear()
                        pendingBuilder.clear()
                        chunkIntervals.clear()
                        lastChunkAt = 0L
                        duplicateRemaining = null
                        thinkingSignature = null
                        thinkingEncryptedContent = null
                        firstTokenTime = 0L
                        lastUiUpdateChars = 0
                        lastUiUpdateAt = streamStartedAt
                        lastNotifChars = 0
                        lastNotifAt = streamStartedAt
                        lastTokenUpdateChars = 0
                        lastTokenUpdateAt = streamStartedAt
                        lastPersistChars = 0
                        lastPersistAt = streamStartedAt
                        // 立即置空 UI 内容 + 恢复"等待首 token",补轮流式到达后替换
                        host.updateAssistant(params.currentAssistantId, content = "", isStreaming = true)
                        host.stateStore.state.update { it.copy(isWaitingFirstToken = true) }
                        val replayed = streamRound(params.copy(forceMainModel = true))
                        // 补轮失败:整体回填 toolModel 答复(整段替换,避免与补轮的部分内容拼接)
                        if (replayed is StreamRoundResult.Error) {
                            Logger.w("ChatVM", "主模型补轮失败, 保留 toolModel 答复: ${replayed.message}")
                            params.builder.setLength(0)
                            params.builder.append(toolModelContent)
                            params.reasoningBuilder.setLength(0)
                            params.reasoningBuilder.append(toolModelReasoning)
                            // B-02: 回填后同步 state.builder,保持中断 catch 可读到已恢复的内容
                            state.builder.setLength(0)
                            state.builder.append(params.builder)
                            state.reasoningBuilder.setLength(0)
                            state.reasoningBuilder.append(params.reasoningBuilder)
                            host.updateAssistant(
                                params.currentAssistantId,
                                unmaskPii(toolModelContent),
                                unmaskPii(toolModelReasoning).ifBlank { null },
                                isStreaming = false,
                            )
                            host.stateStore.state.update { it.copy(isWaitingFirstToken = false) }
                        }
                        return replayed
                    }

                    // v1.0.54: 先判断本轮是否为工具轮,再推 UI —
                    //   工具轮(有 toolCalls)的思考过程不显示(出戏),最终回复轮正常显示。
                    host.updateAssistant(
                        params.currentAssistantId,
                        unmaskPii(params.builder.toString()),
                        if (exposeRoundThinking) unmaskPii(params.reasoningBuilder.toString()).ifBlank { null } else null,
                        imageAccumulator.toList(),
                        isStreaming = false,
                    )
                    if (!streamToUi) {
                        host.stateStore.messages.value = host.stateStore.messages.value
                    }

                    val finalizedAssistant = host.stateStore.messages.value.firstOrNull { it.id == params.currentAssistantId }
                    val assistantMessage =
                        if (hasToolCalls) {
                            UIMessage(
                                id = params.currentAssistantId,
                                role = MessageRole.ASSISTANT,
                                content = finalizedAssistant?.content ?: unmaskPii(params.builder.toString()),
                                reasoning = if (exposeRoundThinking) {
                                    finalizedAssistant?.reasoning ?: unmaskPii(params.reasoningBuilder.toString()).ifBlank { null }
                                } else {
                                    null
                                },
                                mood = finalizedAssistant?.mood.takeIf { exposeRoundThinking },
                                reflection = finalizedAssistant?.reflection.takeIf { exposeRoundThinking },
                                thinkingSignature = finalizedAssistant?.thinkingSignature ?: thinkingSignature,
                                thinkingEncryptedContent = finalizedAssistant?.thinkingEncryptedContent ?: thinkingEncryptedContent,
                                imageBase64List = finalizedAssistant?.imageBase64List ?: emptyList(),
                                citationUrls = citationUrls.toList(),
                                // v1.0.88 (R-2): 变体身份字段必须完整保留 — 此前工具轮构建的
                                // assistantMessage 丢失 variantGroupId/variantIndex/variantCount/parentGroupId,
                                // 重试(变体)+工具调用后落盘,重进会话树重建时消息挂错位置,
                                // 表现为"消息重叠/第一句回复变最后一句"。
                                variantGroupId = finalizedAssistant?.variantGroupId,
                                variantIndex = finalizedAssistant?.variantIndex ?: 0,
                                variantCount = finalizedAssistant?.variantCount ?: 1,
                                parentGroupId = finalizedAssistant?.parentGroupId,
                                toolCalls =
                                toolCallAccumulator.toSortedMap().map { (idx, triple) ->
                                    ToolCall(
                                        id = triple.first ?: "call_${System.currentTimeMillis()}_$idx",
                                        name = triple.second ?: "",
                                        arguments = triple.third.toString(),
                                    )
                                },
                            )
                        } else {
                            val finalAssistant =
                                finalizedAssistant ?: UIMessage(
                                    id = params.currentAssistantId,
                                    role = MessageRole.ASSISTANT,
                                    content = unmaskPii(params.builder.toString()),
                                    reasoning = unmaskPii(params.reasoningBuilder.toString()).ifBlank { null },
                                    thinkingSignature = thinkingSignature,
                                    thinkingEncryptedContent = thinkingEncryptedContent,
                                    imageBase64List = imageAccumulator.toList(),
                                )
                            val withCitations =
                                finalAssistant.copy(
                                    reasoning = if (exposeRoundThinking) finalAssistant.reasoning else null,
                                    mood = if (exposeRoundThinking) finalAssistant.mood else null,
                                    reflection = if (exposeRoundThinking) finalAssistant.reflection else null,
                                    citationUrls = (finalAssistant.citationUrls + citationUrls).distinct(),
                                    ragCitations = if (pendingRagCitations.isNotEmpty()) {
                                        pendingRagCitations
                                    } else {
                                        finalAssistant.ragCitations
                                    },
                                )
                            withCitations
                        }

                    // B-02: 出口同步最后已知流式内容(同上,供中断 catch 构造部分回复)
                    state.builder.setLength(0)
                    state.builder.append(params.builder)
                    state.reasoningBuilder.setLength(0)
                    state.reasoningBuilder.append(params.reasoningBuilder)
                    return StreamRoundResult.Success(
                        assistantMessage = assistantMessage,
                        hasToolCalls = hasToolCalls,
                        contentLength = params.builder.length,
                        firstTokenTime = firstTokenTime,
                        citationUrls = citationUrls.toList(),
                    )
                }

                override suspend fun requestToolApproval(
                    toolName: String,
                    toolCallId: String,
                    argsPreview: String,
                    args: Map<String, Any?>,
                ): ToolApprovalState {
                    return host.requestToolApprovalForSession(
                        sessionId = sessionId,
                        toolName = toolName,
                        toolCallId = toolCallId,
                        argsPreview = argsPreview,
                        args = args,
                    )
                }

                override fun onToolLoopError(type: ChatErrorType, message: String, recoverable: Boolean) {
                    host.addError(type, message, recoverable)
                }

                // v1.x: 单个工具开始/结束回调(用于调试日志)
                //  默认空实现已存在于接口,这里覆盖做 debug 日志,便于追踪工具执行耗时与状态。
                override fun onToolStart(toolCallId: String, toolName: String) {
                    host.chatGenerationManager.touch(sessionId)
                    // M1.7: 工具执行在途 -> WAITING_TOOL 检查点
                    host.sessionManager.runtime(sessionId)?.markWaitingTool(state.turnId)
                    if (ConversationRebuildFlagStore.current.shadowEventsEnabled) {
                        host.coroutineScope.launch(Dispatchers.IO) {
                            host.recordConversationShadow(
                                ConversationEventDraft(
                                    sessionId = sessionId,
                                    turnId = state.turnId,
                                    type = ConversationEventType.TOOL_CALLED,
                                    streamId = state.streamId,
                                    generationSerial = state.generationSerial,
                                    payloadJson = "{\"toolCallId\":\"$toolCallId\",\"toolName\":\"$toolName\"}",
                                ),
                            )
                        }
                    }
                    if (experiments.debugMode) {
                        Logger.d("ChatVM", "onToolStart | tool=$toolName | id=$toolCallId | sessionId=$sessionId")
                    }
                }

                override fun onToolFinish(toolCallId: String, toolName: String, success: Boolean, durationMs: Long) {
                    host.chatGenerationManager.touch(sessionId)
                    // M1.7: 工具执行结束 -> 回 GENERATING(续接请求或最终回复轮)
                    host.sessionManager.runtime(sessionId)?.markResumed(state.turnId)
                    if (ConversationRebuildFlagStore.current.shadowEventsEnabled) {
                        host.coroutineScope.launch(Dispatchers.IO) {
                            host.recordConversationShadow(
                                ConversationEventDraft(
                                    sessionId = sessionId,
                                    turnId = state.turnId,
                                    type = ConversationEventType.TOOL_RESULT,
                                    streamId = state.streamId,
                                    generationSerial = state.generationSerial,
                                    payloadJson = "{\"toolCallId\":\"$toolCallId\",\"toolName\":\"$toolName\"," +
                                        "\"success\":$success,\"durationMs\":$durationMs}",
                                ),
                            )
                        }
                    }
                    if (experiments.debugMode) {
                        Logger.d(
                            "ChatVM",
                            "onToolFinish | tool=$toolName | success=$success | duration=${durationMs}ms | sessionId=$sessionId",
                        )
                    }
                }
            }

        // v2.x: 工具轮次上限来自设置(0=无限制);读取失败回退固定值,不阻断对话。
        val toolRoundLimit =
            (resultOf { host.settings.getToolLoopMaxRounds() }.getOrNull() ?: MAX_TOOL_ROUNDS)
                .takeIf { it > 0 } ?: 0

        val toolLoopResult =
            host.toolOrchestrator.runLoop(
                params =
                ToolLoopParams(
                    sessionId = sessionId,
                    // F-12: 统一链路 id(贯穿工具执行审计与日志)
                    traceId = state.traceId,
                    initialAssistantId = state.currentAssistantId,
                    baseHistorySize = baseHistorySize,
                    maxRounds = toolRoundLimit,
                    tools = tools,
                    skillMap = state.skillMap,
                    routeSnapshot = state.routeSnapshot,
                    model = effectiveModel,
                    providerConfig = effectiveProviderConfig,
                    temperature = effectiveTemperature,
                    maxTokens = assistant?.maxTokens,
                    reasoningLevel = reasoningLevel,
                    webSearchEnabled = localWebSearchEnabled,
                    nativeWebSearch = nativeWebSearchEnabled,
                    experiments = experiments,
                    assistant = assistant,
                    initialBuilderContent = state.builder.toString(),
                    initialReasoningContent = state.reasoningBuilder.toString(),
                    turnId = state.turnId,
                    generationIdentity = state.generationIdentity,
                    toolExecutionContext = toolExecutionContext,
                    allowToolExecution = !host.stateStore.state.value.chatPreferences.pauseToolExecution,
                ),
                conversationHistory = conversationHistory,
                host = toolLoopHost,
                accessor = host.accessor,
                taskCardCoordinator = host.taskCardCoordinator,
            )

        state.round = toolLoopResult.round
        state.totalCharCount = toolLoopResult.totalCharCount
        state.totalToolCallCount = toolLoopResult.totalToolCallCount
        state.firstTokenTime = toolLoopResult.firstTokenTime
        state.currentAssistantId = toolLoopResult.finalAssistantId
        // 审查修复 (2.0 C-17): 收尾兜底落盘 — exec* 内已立即落盘,这里对登记过的
        // 工具媒体消息再扫一次,覆盖落盘失败/进程竞争等窗口;NonCancellable 保证
        // 取消路径也能完成(登记者在取消分支走 attachMediaToMessage,此处幂等无害)。
        if (host.generationState.toolMediaMessages.isNotEmpty()) {
            val sweepSession = host.generationState.activeToolSessionId ?: host.currentSessionIdForApproval()
            host.generationState.toolMediaMessages.forEach { mediaId ->
                host.persistToolMessageMedia(sweepSession, mediaId)
            }
            host.generationState.toolMediaMessages.clear()
        }
        // A-13: 收尾递增令牌,使任何仍在途的工具执行(图片生成可达数十秒)媒体写入失效
        host.generationState.toolAssistantId = null
        host.generationState.activeToolSessionId = null
        host.nextToolGenerationToken()

        if (!toolLoopResult.success) {
            val err = toolLoopResult.error
            host.recordConversationShadow(
                ConversationEventDraft(
                    sessionId = sessionId,
                    turnId = state.turnId,
                    type = ConversationEventType.TURN_FAILED,
                    streamId = state.streamId,
                    generationSerial = state.generationSerial,
                    payloadJson = "{\"round\":${toolLoopResult.round},\"errorType\":\"${err?.type ?: ChatErrorType.UNKNOWN}\"}",
                ),
            )
            // P2-3: 工具循环失败必须收口 shadow turn,否则 turn 长期 OPEN、后续审计/重建失真。
            if (ConversationRebuildFlagStore.current.shadowEventsEnabled && state.shadowTurnStarted) {
                host.conversationService.finishTurn(state.turnId, "FAILED")
            }
            val errMessage = err?.message ?: host.appContext.getString(R.string.err_chat_unknown)
            host.addError(err?.type ?: ChatErrorType.UNKNOWN, errMessage, isRecoverable = err?.type != ChatErrorType.API_KEY)
            // B-24: 仅自己仍是最新生成时才清零
            // F-10: 工具循环失败 → FAILED
            host.clearStreamingStateIfLatest(state, finalPhase = ChatStreamPhase.FAILED)
            return false
        }

        // Phase 8.5 修复 S16: 工具调用达到 maxToolRounds 上限且未产生最终回复时,
        // 累积的 pendingRagCitations 需要附加到当前 assistant 消息。
        if (toolLoopResult.finalAssistantMessage == null && pendingRagCitations.isNotEmpty()) {
            val lastAssistant = host.stateStore.messages.value.firstOrNull { it.id == state.currentAssistantId }
            if (lastAssistant != null && lastAssistant.ragCitations.isEmpty()) {
                val withCitations = lastAssistant.copy(ragCitations = pendingRagCitations)
                host.stateStore.messages.value =
                    host.stateStore.messages.value.map { msg ->
                        if (msg.id == withCitations.id) withCitations else msg
                    }
                // B-1: 会话已删除则跳过 citations 落盘,防止删后"复活"。
                if (!host.generationController.isSessionWritesSuppressed(sessionId)) {
                    try {
                        host.sessionRepository.upsertMessage(sessionId, withCitations)
                    } catch (e: Exception) {
                        Logger.e("ChatVM", "upsertMessage(citations) failed", e)
                        host.addError(
                            ChatErrorType.UNKNOWN,
                            host.appContext.getString(
                                R.string.err_chat_ref_save_failed,
                                e.message ?: host.appContext.getString(R.string.err_chat_unknown),
                            ),
                        )
                    }
                }
            }
        }

        // 将 finalAssistantMessage(含 citations + artifacts)写入 UI / DB / branch
        var finalMessagePersistenceFailed = false
        toolLoopResult.finalAssistantMessage?.let { finalAssistant ->
            // v1.0.75 fix (生图只显示链接根因): finalAssistantMessage 由 GenerationHandler 构造,
            //   只含 content + toolCalls,不含媒体字段。若直接覆盖同 id 消息,
            //   execGenerateImage 写入的 imageUrls / videoFileUri / imageBase64List 会丢失,
            //   导致图片不渲染、模型只能复述 URL。合并保留原消息的媒体字段。
            // A-16: 合并逻辑抽成 internal 纯函数 [mergeFinalAssistantMedia](有单测护栏)
            val existingMsg = host.stateStore.messages.value.firstOrNull { it.id == finalAssistant.id }
            val mergedMedia = mergeFinalAssistantMedia(finalAssistant, existingMsg)
            val withCitations =
                when {
                    toolLoopResult.citationUrls.isNotEmpty() && pendingRagCitations.isNotEmpty() ->
                        mergedMedia.copy(citationUrls = toolLoopResult.citationUrls, ragCitations = pendingRagCitations)
                    toolLoopResult.citationUrls.isNotEmpty() ->
                        mergedMedia.copy(citationUrls = toolLoopResult.citationUrls)
                    pendingRagCitations.isNotEmpty() ->
                        mergedMedia.copy(ragCitations = pendingRagCitations)
                    else -> mergedMedia
                }
            val (replacedContent, artifacts) =
                ArtifactExtractor.extractArtifacts(
                    sessionId = sessionId,
                    messageId = state.currentAssistantId.toString(),
                    content = withCitations.content,
                )
            val withArtifacts =
                if (artifacts.isNotEmpty()) {
                    artifacts.forEach { host.artifactRepository.upsert(it) }
                    withCitations.copy(
                        content = replacedContent,
                        artifactIds = artifacts.map { it.id },
                    )
                } else {
                    withCitations
                }
            host.stateStore.messages.value =
                host.stateStore.messages.value.map { msg ->
                    if (msg.id == withArtifacts.id) withArtifacts else msg
                }
            conversationHistory.add(withArtifacts)
            // v1.0.88 (S-3): 流式收尾完整性自检日志 — 记录最终 content 长度与角色,
            // 若用户反馈"消息重叠/错乱"时可从日志快速判断是内容叠加还是排序问题。
            Logger.i(
                "ChatVM",
                "finalize: msg=${withArtifacts.id.toString().take(8)} len=${withArtifacts.content.length} " +
                    "reasoning=${withArtifacts.reasoning?.length ?: 0} " +
                    "mood=${withArtifacts.mood?.length ?: 0} " +
                    "variant=${withArtifacts.variantGroupId ?: "-"}",
            )
            try {
                if (ConversationRebuildFlagStore.current.useNewConversationService && state.shadowTurnStarted) {
                    val commitResult =
                        host.messageCommit.commit(
                            MessageCommitRequest(
                                turnId = state.turnId,
                                sessionId = sessionId,
                                userMessageId = state.conversationHistory.lastOrNull {
                                    it.role == MessageRole.USER
                                }?.id?.toString().orEmpty(),
                                assistantMessageId = withArtifacts.id.toString(),
                                message = withArtifacts,
                                parts = buildCommitParts(withArtifacts, System.currentTimeMillis()),
                                toolRounds = toolLoopResult.toolRounds,
                            ),
                        )
                    if (commitResult is io.zer0.muse.data.chat.rewrite.MessageCommitResult.Rejected) {
                        error("conversation commit rejected")
                    }
                    // B-1: 会话已删除则跳过 artifacts 落盘,防止删后"复活"。
                } else if (!host.generationController.isSessionWritesSuppressed(sessionId)) {
                    host.sessionRepository.upsertMessage(sessionId, withArtifacts)
                }
            } catch (e: Exception) {
                // 提交失败时不能继续走正常 finalize，否则会出现“回合完成但正文未落库”。
                finalMessagePersistenceFailed = true
                Logger.e("ChatVM", "upsertMessage failed", e)
                host.addError(
                    ChatErrorType.UNKNOWN,
                    host.appContext.getString(
                        R.string.err_chat_reply_save_failed,
                        e.message ?: host.appContext.getString(R.string.err_chat_unknown),
                    ),
                )
            }
            host.messageController.rebuildConversationTree()
        }

        if (finalMessagePersistenceFailed) {
            if (ConversationRebuildFlagStore.current.shadowEventsEnabled && state.shadowTurnStarted) {
                host.recordConversationShadow(
                    ConversationEventDraft(
                        sessionId = sessionId,
                        turnId = state.turnId,
                        type = ConversationEventType.TURN_FAILED,
                        streamId = state.streamId,
                        generationSerial = state.generationSerial,
                        payloadJson = "{\"reason\":\"message_persistence_failed\",\"messageId\":\"${state.currentAssistantId}\"}",
                    ),
                )
                host.conversationService.finishTurn(state.turnId, "FAILED")
            }
            host.clearStreamingStateIfLatest(state, finalPhase = ChatStreamPhase.FAILED)
            return false
        }

        return true
    }

    /**
     * 从 ChatViewModel.companion 复制的常量(原 runToolLoop 直接引用)。
     * 值逐字照搬,保证行为等价。
     */
    private companion object {
        const val STREAM_SLIDE_WINDOW = 10
        const val STREAM_THROTTLE_MS = 50L
        const val STREAM_SLICE_BASE = 40
        const val STREAM_SLICE_MIN = 12
        const val STREAM_SLICE_MAX = 240
        const val STREAM_UI_CHAR_THRESHOLD = 12
        const val STREAM_UI_TIME_THRESHOLD_MS = 60L
        const val PERSIST_CHAR_THRESHOLD = 400
        const val PERSIST_TIME_THRESHOLD_MS = 1_500L
        const val AUTO_COMPRESS_MIN_RESERVE_TOKENS = 16_384
        const val AUTO_COMPRESS_RESERVE_RATIO = 0.1
        const val COMPRESSION_SAFETY_TAIL_MESSAGES = 10
        const val MAX_STREAM_RETRIES = 3
        const val MAX_TOOL_ROUNDS = 12
        const val NETWORK_RECOVERY_TIMEOUT_MS = 30_000L
    }
}
