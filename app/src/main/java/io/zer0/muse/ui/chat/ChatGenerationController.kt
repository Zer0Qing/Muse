package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.ai.core.limitContextWithContext
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.chat.rewrite.ConversationEventDraft
import io.zer0.muse.data.chat.rewrite.ConversationEventType
import io.zer0.muse.data.chat.rewrite.ConversationRebuildFlagStore
import io.zer0.muse.data.chat.rewrite.sha256
import io.zer0.muse.data.session.MessageOutboxEntity
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.notification.MuseNotificationTarget
import io.zer0.muse.schedule.ChatGenerationManager
import io.zer0.muse.schedule.ConversationEndType
import io.zer0.muse.schedule.UserActivityProfile
import io.zer0.muse.session.ConversationSessionManager
import io.zer0.muse.session.TurnPhase
import io.zer0.muse.ui.ChatErrorType
import io.zer0.muse.ui.ChatStreamPhase
import io.zer0.muse.util.ErrorMessages
import io.zer0.muse.util.TokenEstimator
import io.zer0.muse.ui.buildSendText
import io.zer0.muse.ui.canContinueGeneration
import io.zer0.muse.ui.canRegenerate
import io.zer0.muse.ui.canStartGeneration
import io.zer0.muse.ui.resumeFromInterrupted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.uuid.Uuid

/**
 * v1.x: 从 ChatViewModel 抽离的生成控制 Controller。
 *
 * 职责(生成生命周期控制 + 发送管线生产者;launchStream/消费循环后续随迁):
 *  - [stop] 停止当前会话生成(应用级+会话级取消、清流式状态、取消图片/翻译与待审批)。
 *  - [enqueueSend] 乐观入队一条用户消息(活动/审计/路由/outbox/队列,失败回滚)。
 *
 * 跨职责的附属任务取消(图片/翻译)、待审批清理、addError/generateImage 经回调注入,
 * 不反向依赖 ChatViewModel。
 */
@Suppress("LongParameterList", "TooManyFunctions", "LargeClass")
internal class ChatGenerationController(
    private val deps: GenerationDeps,
    private val accessor: ChatStateAccessor,
    private val chatGenerationManager: ChatGenerationManager,
    private val sessionManager: ConversationSessionManager,
    private val settings: SettingsRepository,
    private val notificationManager: MuseNotificationManager,
    private val onCancelAncillaryJobs: () -> Unit,
    private val onCancelPendingApprovals: (String?) -> Unit,
) {

    /** 用户点"停止"。 */
    fun stop() {
        // 只停止单聊的生成,不影响群聊
        val sid = accessor.snapshot.currentSessionId ?: accessor.snapshot.agentSessionId
        chatGenerationManager.stop(sid)
        // 记录运行时取消标志,区分"用户停止"与异常失败;Job 与 chatGenerationManager 持有同一实例,重复 cancel 幂等。
        sid?.let { sessionManager.cancelGeneration(it) }
        // 用户停止后清除该会话的生成焦点。
        accessor.coroutineScope.launch {
            if (resultOf { settings.getGeneratingSessionId() }.getOrNull() == sid) {
                resultOf { settings.saveGeneratingSessionId(null) }
                    .onError { msg, _ -> Logger.w("ChatVM", "saveGeneratingSessionId 清理失败: $msg") }
            }
        }
        onCancelAncillaryJobs()
        accessor.update {
            it.copy(
                isStreaming = false,
                isWaitingFirstToken = false,
                isGeneratingImage = false,
                isTranslating = false,
                translatingMessageId = null,
                pendingToolApprovals = emptyList(),
                toolProgressMessage = null,
            )
        }
        // 取消所有待审批的工具调用(防止 stop 后幽灵审批卡片 + requestToolApproval 协程挂起)
        onCancelPendingApprovals(sid)
        // 通知:用户停止时取消进度通知
        runCatching {
            notificationManager.updateLiveProgress("", 0, false)
        }.onFailure { Logger.w("ChatVM", "取消进度通知失败: ${it.message}") }
    }

    /** 发送当前输入。空文本(且无图片)或正在流式时忽略;isDrawMode 走图片生成。 */
    @Suppress("ReturnCount")
    fun send() {
        val rawText = accessor.snapshot.input.trim()
        val images = accessor.snapshot.pendingImages
        val docs = accessor.snapshot.pendingDocuments
        // v1.136 T10: 合并待发送文档内容到消息文本(文档文本 + 用户输入)
        var text = buildSendText(rawText, docs.map { it.content })
        val canStart = canStartGeneration(
            text, images, accessor.snapshot.isStreaming, deps.generationState.isCreatingAgentSession
        )
        if (!canStart) {
            return
        }
        // v1.68: 引用回复必须把被引用内容拼进消息体,LLM 才能读到引用原文。
        val replyingToLatest = accessor.snapshot.replyingTo?.let { r ->
            deps.stateStore.messages.value.find { it.id == r.id } ?: r
        }
        val quoteText = accessor.snapshot.replyQuoteOverride?.takeIf { it.isNotBlank() }
            ?: replyingToLatest?.content?.takeIf { it.isNotBlank() }
        if (quoteText != null) {
            text = buildQuotedContent(quoteText, text)
        }

        // v1.28: Agent 模式用独立的 agentSessionId,无会话时自动创建
        // v1.79 (M-CV8): 用 isCreatingAgentSession 标志防止重入,避免快速双击创建两个会话
        val sessionId = if (accessor.snapshot.isAgentMode) {
            accessor.snapshot.agentSessionId ?: run {
                if (deps.generationState.isCreatingAgentSession) return
                deps.generationState.isCreatingAgentSession = true
                accessor.coroutineScope.launch {
                    try {
                        val assistantId = accessor.snapshot.currentAssistant?.id ?: "default"
                        val id = deps.sessionRepository.createAgentSession(assistantId)
                        accessor.update { it.copy(agentSessionId = id) }
                        // v1.53-A1: 分页加载 Agent 会话消息(新会话为空,同时重置 hasMoreHistory)
                        val (msgs, hasMore) = deps.messageController.loadMessagesPaged(id)
                        deps.stateStore.messages.value = msgs
                        accessor.update {
                            it.copy(hasMoreHistory = hasMore, isLoadingMore = false, lastHistoryLoadCount = 0)
                        }
                        enqueueSend(text, images, id)
                    } finally {
                        deps.generationState.isCreatingAgentSession = false
                    }
                }
                return
            }
        } else {
            accessor.snapshot.currentSessionId ?: return
        }

        if (accessor.snapshot.isDrawMode) {
            deps.generateImage(text, sessionId)
            return
        }
        enqueueSend(text, images, sessionId)
    }

    /** B7-04: 流式打断后继续生成,复用最后一条带 [已中断] 标记的 assistant 消息续写。 */
    @Suppress("ReturnCount")
    fun continueGeneration() {
        if (accessor.snapshot.isStreaming) return
        val st = accessor.snapshot
        val sessionId = if (st.isAgentMode) st.agentSessionId ?: return else st.currentSessionId ?: return
        val messages = deps.stateStore.messages.value
        val last = messages.lastOrNull() ?: return
        if (!canContinueGeneration(st.isStreaming, last)) return
        val content = resumeFromInterrupted(last.content)
        val resumed = last.copy(content = content)
        deps.stateStore.messages.value = deps.stateStore.messages.value.map { if (it.id == last.id) resumed else it }
        deps.messageController.rebuildConversationTree()
        accessor.update {
            it.copy(isStreaming = true, isWaitingFirstToken = true, errors = emptyList())
        }
        launchStream(last.id, sessionId, false, resumed)
    }

    /** 重生成当前用户变体下的最后一条 assistant 回复:保留旧回复为变体,新建变体并重新请求。 */
    @Suppress("ReturnCount")
    fun regenerateLastAssistant() {
        val st = accessor.snapshot
        val sessionId = if (st.isAgentMode) st.agentSessionId ?: return else st.currentSessionId ?: return
        val tree = deps.stateStore.conversationTree.value
        if (!canRegenerate(
                isStreaming = st.isStreaming,
                hasSession = true,
                hasSelectedUserVariant = tree.selectedUserNode != null && tree.selectedUserVariant != null,
            )
        ) return
        val update = tree.retryLastAssistant()
        val newMsg = update.newMessage ?: return
        deps.stateStore.conversationTree.value = update.tree
        deps.stateStore.messages.value = update.tree.displayMessages
        accessor.update {
            it.copy(isStreaming = true, isWaitingFirstToken = true, errors = emptyList())
        }
        deps.sessionMemoryCache.remove(sessionId)
        deps.clearPendingVariantInfo()
        // 先完成新 assistant variant 的落库,再启动生成,避免分支数据库竞态。
        accessor.coroutineScope.launch {
            val persisted = withContext(Dispatchers.IO) {
                runCatching {
                    deps.sessionRepository.upsertMessage(sessionId, newMsg)
                    update.changedGroupId?.let { groupId ->
                        deps.sessionRepository.updateVariantCount(groupId, newMsg.variantCount)
                    }
                }.onFailure { e -> Logger.e("ChatVM", "regenerate upsertMessage failed", e) }.isSuccess
            }
            if (persisted) {
                launchStream(newMsg.id, sessionId, true, null)
            } else {
                accessor.update { it.copy(isStreaming = false, isWaitingFirstToken = false) }
            }
        }
    }

    /** v5: 乐观更新 — 用户消息立即显示,不等待 DB 写入;随后入队由消费循环串行处理。 */
    @Suppress("LongMethod")
    fun enqueueSend(text: String, images: List<String>, sessionId: String) {
        // v2.1: 记录用户活动到活跃度画像,并更新对话结束类型(驱动自适应主动消息调度)
        deps.activityProfile.recordActivity()
        deps.activityProfile.setConversationEndType(
            if (UserActivityProfile.containsEndKeyword(text)) ConversationEndType.USER_EXPLICIT_END
            else ConversationEndType.NATURAL_FADE
        )
        // P2-4: 审计日志 — 发送消息
        deps.auditLogger.log(
            category = "user_action",
            action = "send_message",
            target = sessionId,
            detail = mapOf(
                "text_length" to text.length,
                "image_count" to images.size,
                "assistant_id" to (accessor.snapshot.currentAssistant?.id ?: "default"),
            ),
        )
        // v2.3: 任务模型路由只绑定到当前发送请求,不能把一次自动判断永久写成会话手动覆盖。
        // 否则先发一条“写代码”后,后续普通闲聊会一直粘在代码模型上。
        val selectedModel = sessionId.let { deps.generationState.sessionModelOverrides[it] }
            ?: deps.generationState.globalSelectedModelId
        val selectedProvider = sessionId.let { deps.generationState.sessionProviderOverrides[it] }
            ?: deps.generationState.globalActiveProviderId
        val routed = deps.settings.recommendTaskRoute(text, selectedModel, selectedProvider)
        if (routed != null) {
            // 仅更新当前 UI 快照给用户可见;生成结束后恢复真实的手动/全局选择。
            accessor.update {
                it.copy(
                    selectedModelId = routed.modelId ?: it.selectedModelId,
                    activeProviderId = routed.providerId ?: it.activeProviderId,
                )
            }
        }
        // v1.0.47 P5: 记录输入历史(新→旧,去重,截断到 MAX_INPUT_HISTORY)
        val newHistory = (listOf(text) + accessor.snapshot.inputHistory.filter { it != text }).take(MAX_INPUT_HISTORY)
        val userMsg = UIMessage(role = MessageRole.USER, content = text, imageBase64List = images)
        // P0 修复: 强制 assistantMsg.createdAt 严格晚于 userMsg.createdAt(+1ms),避免 DB 排序不稳定。
        val assistantMsg = UIMessage(role = MessageRole.ASSISTANT, content = "", createdAt = userMsg.createdAt + 1)
        // v1.0.15: 异步写入 outbox(保证"刚点击发送就退出"时消息不丢失)
        val outboxId = Uuid.random().toString()
        val outboxInsertJob = accessor.coroutineScope.launch(Dispatchers.IO) {
            withContext(NonCancellable) {
                resultOf {
                    deps.sessionRepository.insertOutbox(
                        MessageOutboxEntity(
                            id = outboxId,
                            sessionId = sessionId,
                            text = text,
                            imageBase64Json = deps.idListJson.encodeToString(images),
                            userMessageId = userMsg.id.toString(),
                            assistantMessageId = assistantMsg.id.toString(),
                            createdAt = System.currentTimeMillis(),
                        )
                    )
                }.onError { _, t -> Logger.w("ChatVM", "outbox 写入失败,进程被杀可能丢失此消息", t) }
            }
        }
        deps.stateStore.messages.value = deps.stateStore.messages.value + userMsg + assistantMsg
        accessor.update {
            it.copy(
                input = "",
                hasDraft = false,
                pendingImages = emptyList(),
                pendingDocuments = emptyList(),
                replyingTo = null,
                replyQuoteOverride = null,
                // F-10: 发送即进入 CONNECTING(等待首 token)
                streamState = it.streamState.copy(phase = ChatStreamPhase.CONNECTING),
                isStreaming = true,
                // v1.0.3: 进入"等待首 token"阶段,UI 显示 ShimmerBubble
                isWaitingFirstToken = true,
                errors = emptyList(),
                // v1.0.47 P5: 记录输入历史,重置导航索引(发送后退出历史导航)
                inputHistory = newHistory,
                inputHistoryIndex = null,
            )
        }
        val sendResult = deps.generationState.sendChannel.trySend(
            SendRequest(
                text, images, sessionId,
                userMessage = userMsg,
                assistantMessageId = assistantMsg.id,
                outboxId = outboxId,
                taskRouteSelection = routed,
            )
        )
        if (sendResult.isFailure) {
            // 队列已满,回滚乐观更新 + 删除 outbox(消息未入队,outbox 无用)
            accessor.coroutineScope.launch(Dispatchers.IO) {
                outboxInsertJob.join()
                resultOf { deps.sessionRepository.deleteOutbox(outboxId) }
            }
            accessor.update {
                val filtered = deps.stateStore.messages.value.filterNot { msg ->
                    msg.id == userMsg.id || msg.id == assistantMsg.id
                }
                deps.stateStore.messages.value = filtered
                it.copy(isStreaming = false, isWaitingFirstToken = false)
            }
            deps.addError(ChatErrorType.UNKNOWN, deps.appContext.getString(R.string.err_chat_queue_full), true)
            return
        }
        deps.messageController.rebuildConversationTree()
    }

    /** 队列消费失败时回滚某条请求的乐观更新(user/assistant 占位消息)。 */
    fun rollbackOptimisticSend(req: SendRequest) {
        if (accessor.snapshot.currentSessionId != req.sessionId) return
        deps.stateStore.messages.value = deps.stateStore.messages.value.filterNot { message ->
            message.id == req.userMessage.id || message.id == req.assistantMessageId
        }
    }

    /** 消费单条发送请求:会话匹配校验 → 落盘 user 消息 → 启动生成 → 清理 outbox。 */
    @Suppress("TooGenericExceptionCaught")
    suspend fun consumeSendRequest(req: SendRequest) {
        deps.generationState.outboxRecoveryQueuedIds.remove(req.outboxId)
        val state = accessor.snapshot
        val currentSid = if (state.isAgentMode) {
            state.agentSessionId ?: req.sessionId
        } else {
            state.currentSessionId ?: req.sessionId
        }
        if (currentSid != req.sessionId) {
            // 会话已切换,该 req 被跳过 — 回滚乐观更新,但保留 outbox 给切回后的恢复流程。
            accessor.update {
                val filtered = deps.stateStore.messages.value.filterNot { msg ->
                    msg.id == req.userMessage.id || msg.id == req.assistantMessageId
                }
                deps.stateStore.messages.value = filtered
                it.copy(isStreaming = false, isWaitingFirstToken = false)
            }
            Logger.i("ChatVM", "跳过当前会话外的 outbox 请求: ${req.outboxId}")
            return
        }
        try {
            // P0 修复: 直接复用 enqueueSend 创建的 userMessage,保证 createdAt 顺序与 id 一致。
            deps.sessionRepository.appendMessage(currentSid, req.userMessage)
        } catch (e: Exception) {
            Logger.e("ChatVM", "appendMessage failed", e)
            if (req.retryCount < 1) {
                Logger.i("ChatVM", "重试发送 (attempt ${req.retryCount + 1})")
                val retryResult = deps.generationState.sendChannel.trySend(req.copy(retryCount = req.retryCount + 1))
                if (retryResult.isFailure) {
                    Logger.w("ChatVM", "重试入队失败(队列已满)")
                    deps.addError(
                        ChatErrorType.UNKNOWN,
                        deps.appContext.getString(
                            R.string.err_chat_msg_save_failed,
                            e.message ?: deps.appContext.getString(R.string.err_chat_unknown),
                        ),
                        true,
                    )
                    accessor.update { it.copy(isStreaming = false) }
                    resultOf { deps.sessionRepository.deleteOutbox(req.outboxId) }
                }
            } else {
                deps.addError(
                    ChatErrorType.UNKNOWN,
                    deps.appContext.getString(
                            R.string.err_chat_msg_save_failed,
                            e.message ?: deps.appContext.getString(R.string.err_chat_unknown),
                        ),
                    true,
                )
                accessor.update { it.copy(isStreaming = false) }
                resultOf { deps.sessionRepository.deleteOutbox(req.outboxId) }
            }
            return
        }
        val delegated = deps.maybeAutoRoute(req.text, req.assistantMessageId, currentSid)
        if (delegated) {
            restoreSelectionForSession(currentSid)
        } else {
            launchStream(
                assistantId = req.assistantMessageId,
                sessionId = currentSid,
                isNewBranch = false,
                continueFrom = null,
                taskRouteSelection = req.taskRouteSelection,
            )
        }
        resultOf { deps.sessionRepository.deleteOutbox(req.outboxId) }
    }

    /** 快速更新 token 计数(流式过程中每 200 字符或 1000ms 调用,避免每次重建 system prompt)。 */
    suspend fun updateContextTokenCount() {
        val msgsSnapshot = deps.stateStore.messages.value
        val sysPromptSnapshot = deps.systemPromptCache.cachedSystemPrompt
        val tokenCount = withContext(Dispatchers.Default) {
            runCatching { TokenEstimator.estimate(msgsSnapshot, sysPromptSnapshot) }
                .onFailure { Logger.w("ChatVM", "TokenEstimator failed: ${it.message}") }
                .getOrDefault(0)
        }
        accessor.update { it.copy(contextTokenCount = tokenCount) }
    }

    /** 静态 system prompt 快照的失效 key(assistant/settings/工具清单/偏好等变化触发重建)。 */
    internal fun computeStaticSnapshotKey(assistant: AssistantEntity?, memoryEnabled: Boolean): String {
        val prefs = accessor.snapshot.chatPreferences
        val registeredToolFingerprint = deps.toolRegistry.listTools()
            .sortedBy { it.name }
            .joinToString(";") { tool ->
                "${tool.name}|${tool.description}|${tool.parameters}|${tool.required.sorted()}"
            }
            .hashCode()
        val state = accessor.snapshot
        val effectiveSessionId = if (state.isAgentMode) state.agentSessionId else state.currentSessionId
        val sessionSkillHash = state.sessions
            .firstOrNull { it.id == effectiveSessionId }?.skillIdsJson?.hashCode() ?: 0
        // v1.0.72: 本会话不参考记忆标志加入缓存键
        val sessionIgnoreMemory = state.sessions
            .firstOrNull { it.id == effectiveSessionId }?.ignoreMemory ?: false
        return buildString {
            append(assistant?.id ?: "null")
            append("|"); append(assistant?.updatedAt ?: 0)
            append("|"); append(assistant?.systemPrompt?.hashCode() ?: 0)
            append("|"); append(assistant?.toolIdsJson?.hashCode() ?: 0)
            append("|"); append(assistant?.mcpServerIdsJson?.hashCode() ?: 0)
            append("|"); append(registeredToolFingerprint)
            append("|"); append(assistant?.skillIdsJson?.hashCode() ?: 0)
            append("|"); append(sessionSkillHash)
            append("|"); append(assistant?.memoryEnabled ?: true)
            append("|"); append(memoryEnabled)
            append("|"); append(deps.settings.experienceEnabledCache)
            append("|"); append(state.multiAgentConfig.enabled)
            append("|"); append(prefs.showMoodBlock)
            append("|"); append(prefs.responseStyle)
            append("|"); append(prefs.responseTone)
            append("|"); append(sessionIgnoreMemory)
        }
    }

    /** 组装 system prompt(9 个 section)+ 相关记忆检索 + RAG 自动注入 + 发送前上下文截断检查。 */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    suspend fun buildSystemPromptForStream(state: StreamRunState) {
        with(state) {
            // useGlobalMemory 是助手级开关;关闭时不能继续把全局画像/长期记忆注入当前对话。
            val memoryEnabled = (assistant?.memoryEnabled ?: true) &&
                (assistant?.useGlobalMemory ?: true)
            val timeReminderEnabled = assistant?.enableTimeReminder ?: true
            val effectiveMemoryEnabled = memoryEnabled && deps.settings.isMemoryEnabled()
            // v1.0.72: 本会话不参考记忆标志
            val effSid = if (accessor.snapshot.isAgentMode) {
                accessor.snapshot.agentSessionId
            } else {
                accessor.snapshot.currentSessionId
            }
            val sessionIgnoreMem = accessor.snapshot.sessions
                .firstOrNull { it.id == effSid }?.ignoreMemory ?: false
            // 复用静态 system prompt 快照,只追加动态"当前时间"。
            val currentKey = computeStaticSnapshotKey(assistant, effectiveMemoryEnabled)
            val staticSnapshot = if (currentKey == deps.systemPromptCache.cachedStaticSnapshotKey &&
                deps.systemPromptCache.cachedStaticSystemPrompt.isNotBlank()
            ) {
                deps.systemPromptCache.cachedStaticSystemPrompt
            } else {
                val rebuilt = resultOf {
                    deps.systemPromptAssembler.buildStaticSnapshot(
                        assistant = assistant,
                        memoryEnabled = effectiveMemoryEnabled,
                        ignoreMemory = sessionIgnoreMem,
                    )
                }.getOrNull() ?: ""
                deps.systemPromptCache.cachedStaticSystemPrompt = rebuilt
                deps.systemPromptCache.cachedStaticSnapshotKey = currentKey
                rebuilt
            }
            val dynamicSection = if (timeReminderEnabled) deps.systemPromptAssembler.buildDynamicSection() else ""
            val combinedSystemPrompt = buildString {
                if (staticSnapshot.isNotBlank()) append(staticSnapshot)
                if (dynamicSection.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n---\n\n")
                    append(dynamicSection)
                }
                // 相关记忆检索(仅当记忆开启且非子助手;检索失败静默跳过)。
                if (memoryEnabled && !sessionIgnoreMem && assistant?.memoryEnabled == true) {
                    // buildSystemPrompt 在 applyTransformers 之前执行,此时 transformedMessages
                    // 仍为空;使用本轮已准备好的 rawHistory,否则相关记忆永远不会注入。
                    val lastUserInput = rawHistory.lastOrNull { it.role == MessageRole.USER }?.content
                    if (!lastUserInput.isNullOrBlank()) {
                        val relevant = resultOf { deps.systemPromptAssembler.buildRelevantMemorySection(lastUserInput) }
                            .onError { msg, _ -> Logger.w("ChatVM", "buildRelevantMemorySection 失败: $msg") }
                            .getOrNull() ?: ""
                        if (relevant.isNotBlank()) {
                            if (isNotEmpty()) append("\n\n---\n\n")
                            append(relevant)
                        }
                    }
                }
            }
            systemMessages = if (combinedSystemPrompt.isBlank()) emptyList() else listOf(
                UIMessage(role = MessageRole.SYSTEM, content = combinedSystemPrompt)
            )
            deps.systemPromptCache.cachedSystemPrompt = combinedSystemPrompt
            updateContextTokenCount()

            // 发送前上下文长度硬检查:token 占用超过预警比例时激进截断历史。
            run {
                val maxTokens = accessor.snapshot.contextMaxTokens
                val currentTokens = accessor.snapshot.contextTokenCount
                if (maxTokens > 0 && currentTokens > 0) {
                    val ratio = currentTokens.toFloat() / maxTokens
                    if (ratio >= PRESEND_TOKEN_WARNING_RATIO && rawHistory.size > 5) {
                        val newSize = (contextSize / 2).coerceAtLeast(2)
                        if (newSize < contextSize) {
                            Logger.w(
                                "ChatVM",
                                "pre-send context warning: " +
                                    "token=$currentTokens/$maxTokens (${(ratio * 100).toInt()}%), " +
                                    "history truncated $contextSize -> $newSize messages",
                            )
                            contextSize = newSize
                            truncatedHistory = rawHistory.limitContextWithContext(contextSize)
                        }
                    }
                }
            }

            prefixMessages = buildList<UIMessage> {
                addAll(systemMessages)
                // presetMessages(预设对话)
                assistant?.let { deps.assistantRepository.parsePresetMessages(it) }?.forEach { add(it) }
                // v1.54: RAG 自动注入(失败不阻断主流程)。
                val ragConfig = resultOf { deps.settings.getRagConfig() }.getOrNull() ?: io.zer0.muse.rag.RagConfig()
                val effectiveRagConfig = assistant?.let {
                    runCatching { deps.assistantRepository.mergeRagConfigOverride(it, ragConfig) }
                        .onFailure { e -> Logger.w("ChatViewModel", "mergeRagConfigOverride 失败: ${e.message}") }
                        .getOrDefault(ragConfig)
                } ?: ragConfig
                if (effectiveRagConfig.enabled) {
                    val lastUser = rawHistory.lastOrNull { it.role == MessageRole.USER }
                    val ragQuery = lastUser?.content?.takeIf { it.isNotBlank() }
                    if (ragQuery != null) {
                        val mentionDocIds = resultOf { deps.ragService.resolveMentionToDocIds(ragQuery) }
                            .onError { msg, t -> Logger.w("ChatViewModel", "@mention 解析失败: $msg", t) }
                            .getOrNull()
                        // 助手绑定 KB 时,未显式 @mention 的查询只检索这些 KB;
                        // 显式 mention 优先,可临时定向到用户指定的文档。
                        val boundKnowledgeBaseIds = assistant
                            ?.let { deps.assistantRepository.parseKnowledgeBaseIds(it) }
                            .orEmpty()
                        val boundDocIds = if (mentionDocIds.isNullOrEmpty()) {
                            resultOf {
                                deps.ragService.resolveKnowledgeBaseDocIds(boundKnowledgeBaseIds)
                            }.onError { msg, t ->
                                Logger.w("ChatViewModel", "助手绑定知识库展开失败: $msg", t)
                            }.getOrNull()
                        } else {
                            emptyList()
                        }
                        val scopeDocIds = mentionDocIds.takeIf { !it.isNullOrEmpty() }
                            ?: boundDocIds?.takeIf { it.isNotEmpty() }
                        val injection = resultOf {
                            deps.ragService.buildInjectionContextWithCitations(
                                ragQuery, effectiveRagConfig, scopeDocIds,
                            )
                        }.onError { msg, _ ->
                            deps.addError(
                                ChatErrorType.NETWORK,
                                deps.appContext.getString(R.string.err_chat_rag_failed, msg),
                                true,
                            )
                        }.getOrNull()
                        if (injection != null) {
                            if (injection.text.isNotBlank()) {
                                val clampedRagText = io.zer0.muse.context.ContextBudget().clampText(
                                    io.zer0.muse.context.ContextSection.RAG_CITATION,
                                    injection.text,
                                )
                                add(UIMessage(role = MessageRole.SYSTEM, content = clampedRagText))
                            }
                            if (injection.citations.isNotEmpty()) {
                                pendingRagCitations = injection.citations
                            }
                        }
                    }
                }
            }
        }
    }

    /** 记录对话 shadow 事件(ConversationRebuild 关闭时静默跳过)。 */
    @Suppress("TooGenericExceptionCaught")
    suspend fun recordConversationShadow(event: ConversationEventDraft) {
        if (!ConversationRebuildFlagStore.current.shadowEventsEnabled) return
        try {
            deps.conversationService.record(event)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            Logger.w("ChatVM", "conversation shadow event failed: ${event.type}", e)
        }
    }

    /** 切回会话时重新投递仍未启动生成的 outbox 请求。 */
    suspend fun requeueOutboxForSession(sessionId: String) {
        val pending = resultOf { deps.sessionRepository.getPendingOutbox(sessionId) }.getOrNull().orEmpty()
        for (req in pending) {
            if (!deps.generationState.outboxRecoveryQueuedIds.add(req.id)) continue
            val images = runCatching {
                deps.idListJson.decodeFromString<List<String>>(req.imageBase64Json)
            }.getOrDefault(emptyList())
            val userId = runCatching { Uuid.parse(req.userMessageId) }.getOrElse { Uuid.random() }
            val assistantId = runCatching { Uuid.parse(req.assistantMessageId) }.getOrElse { Uuid.random() }
            val result = deps.generationState.sendChannel.trySend(
                SendRequest(
                    text = req.text,
                    images = images,
                    sessionId = req.sessionId,
                    userMessage = UIMessage(
                        id = userId,
                        role = MessageRole.USER,
                        content = req.text,
                        imageBase64List = images,
                        createdAt = req.createdAt,
                    ),
                    assistantMessageId = assistantId,
                    outboxId = req.id,
                ),
            )
            if (result.isFailure) {
                deps.generationState.outboxRecoveryQueuedIds.remove(req.id)
                Logger.w("ChatVM", "切回会话时 outbox 入队失败: ${req.id}")
            }
        }
    }

    /** 自动任务路由只展示当前请求的模型,收尾时恢复用户真实选择。 */
    private fun restoreSelectionAfterTaskRoute(state: StreamRunState) {
        if (state.taskRouteSelection == null) return
        restoreSelectionForSession(state.sessionId)
    }

    private fun restoreSelectionForSession(sessionId: String) {
        val current = accessor.snapshot
        val displayedSessionId = if (current.isAgentMode) current.agentSessionId else current.currentSessionId
        if (displayedSessionId != sessionId) return
        val modelId = deps.generationState.sessionModelOverrides[sessionId]
            ?: deps.generationState.globalSelectedModelId
        val providerId = deps.generationState.sessionProviderOverrides[sessionId]
            ?: deps.generationState.globalActiveProviderId
        accessor.update { it.copy(selectedModelId = modelId, activeProviderId = providerId) }
    }

    /** 仅当自己仍是最新生成时才清零流式状态(快速连发时 gen-1 收尾不得清掉 gen-2)。 */
    fun clearStreamingStateIfLatest(
        state: StreamRunState,
        finalPhase: ChatStreamPhase = ChatStreamPhase.IDLE,
    ): Boolean {
        if (state.generationSerial != deps.generationState.streamGenerationSerial) return false
        accessor.update {
            it.copy(
                isStreaming = false,
                isWaitingFirstToken = false,
                toolProgressMessage = null,
                streamState = it.streamState.copy(phase = finalPhase),
            )
        }
        restoreSelectionAfterTaskRoute(state)
        return true
    }

    /** 启动流式生成:组装 StreamRunState → 6 步准备 → 工具循环 → 收尾/中断/异常持久化。 */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "TooGenericExceptionCaught")
    fun launchStream(
        assistantId: Uuid,
        sessionId: String,
        isNewBranch: Boolean = false,
        continueFrom: UIMessage? = null,
        taskRouteSelection: io.zer0.muse.data.SettingsRepository.TaskRouteSelection? = null,
    ) {
        // v1.94: 每次启动流式生成前清空工具调用历史(InputBar 动态胶囊计数归零)
        accessor.update { it.copy(toolCallHistory = emptyList()) }
        // R-UI-02: 生成会话单独持久化,避免与用户查看焦点互相覆盖。
        accessor.coroutineScope.launch {
            resultOf { settings.saveGeneratingSessionId(sessionId) }
                .onError { msg, _ -> Logger.w("ChatVM", "saveGeneratingSessionId 失败: $msg") }
        }
        chatGenerationManager.launchGeneration(
            sessionId = sessionId,
            assistantId = assistantId.toString(),
            sessionTitle = accessor.snapshot.sessions.firstOrNull { it.id == sessionId }?.title
                ?: deps.appContext.getString(R.string.chat_new_session),
        ) {
            val state = StreamRunState(sessionId = sessionId, assistantId = assistantId, isNewBranch = isNewBranch)
            // 会话选择显式覆盖助手/全局默认；生成任务捕获启动时的配置，期间切页不会串台。
            state.sessionModelOverride = deps.generationState.sessionModelOverrides[sessionId]
            state.sessionProviderOverride = deps.generationState.sessionProviderOverrides[sessionId]
            state.taskRouteSelection = taskRouteSelection
            state.fallbackModelId = deps.generationState.globalSelectedModelId
            state.fallbackProviderId = deps.generationState.globalActiveProviderId
            // B-24: 捕获本代序号,收尾清零 isStreaming 前校验自己仍是最新生成
            state.generationSerial = ++deps.generationState.streamGenerationSerial
            // M1.1: 开启会话运行时 turn 检查点(NOT_STARTED -> GENERATING)。
            sessionManager.beginTurn(sessionId, state.turnId)
            // B7-04: 继续生成时预置已产出内容
            continueFrom?.let { state.builder.append(it.content) }
            try {
                deps.streamCoordinator.prepareHistory(state)
                val mcpServerIds = state.assistant
                    ?.let(deps.assistantRepository::parseMcpServerIds)
                    ?.toSet()
                    .orEmpty()
                if (mcpServerIds.isNotEmpty()) {
                    val ready = deps.mcpRegistry?.awaitToolsForServers(mcpServerIds) ?: true
                    if (!ready) {
                        Logger.w("ChatVM", "MCP tools not ready before stream: $mcpServerIds")
                    }
                }
                buildSystemPromptForStream(state)
                deps.streamCoordinator.applyTransformers(state)
                deps.streamCoordinator.resolveToolsAndModel(state)
                deps.streamCoordinator.applyPiiGuard(state)
                deps.streamCoordinator.prepareVisionContext(state)
                val success = deps.runToolLoop(state)
                if (success) {
                    finalizeResponse(state)
                    sessionManager.runtime(sessionId)?.markFinished(TurnPhase.COMPLETED, state.turnId)
                } else {
                    sessionManager.runtime(sessionId)?.markFinished(TurnPhase.FAILED, state.turnId)
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                val partialFromBuilder = if (state.builder.isNotEmpty()) {
                    UIMessage(
                        id = state.currentAssistantId,
                        role = MessageRole.ASSISTANT,
                        content = state.unmaskPii(state.builder.toString()),
                        reasoning = state.unmaskPii(state.reasoningBuilder.toString()).ifBlank { null },
                    )
                } else {
                    withContext(NonCancellable) {
                        runCatching {
                            deps.sessionRepository.getMessageAsUiMessage(state.currentAssistantId.toString())
                        }.getOrNull()
                    }
                }
                accessor.update { it.copy(streamState = it.streamState.copy(phase = ChatStreamPhase.INTERRUPTED)) }
                sessionManager.runtime(sessionId)?.markFinished(TurnPhase.CANCELLED, state.turnId)
                deps.persistInterruptedAssistant(
                    sessionId,
                    partialFromBuilder,
                    state.currentAssistantId,
                    System.currentTimeMillis() - state.streamStartedAt,
                )
                withContext(NonCancellable) {
                    runCatching {
                        deps.sessionRepository.deleteGenerationCheckpoints(sessionId, state.streamStartedAt)
                    }.onFailure { Logger.w("ChatVM", "中断清理 generation checkpoints 失败: ${it.message}") }
                }
                if (ConversationRebuildFlagStore.current.shadowEventsEnabled) {
                    withContext(NonCancellable) {
                        recordConversationShadow(
                            ConversationEventDraft(
                                sessionId = sessionId,
                                turnId = state.turnId,
                                type = ConversationEventType.TURN_INTERRUPTED,
                                streamId = state.streamId,
                                generationSerial = state.generationSerial,
                                payloadJson = "{\"contentLength\":${state.builder.length}}",
                            ),
                        )
                        deps.conversationService.finishTurn(state.turnId, "INTERRUPTED")
                    }
                }
                deps.generationState.toolGenerationToken++
                deps.generationState.toolAssistantId = null
                deps.generationState.activeToolSessionId = null
                throw ce
            } catch (t: Exception) {
                Logger.e("ChatVM", "stream failed", t)
                val partialFromBuilder = if (state.builder.isNotEmpty()) {
                    UIMessage(
                        id = state.currentAssistantId,
                        role = MessageRole.ASSISTANT,
                        content = state.unmaskPii(state.builder.toString()),
                        reasoning = state.unmaskPii(state.reasoningBuilder.toString()).ifBlank { null },
                    )
                } else {
                    withContext(NonCancellable) {
                        runCatching {
                            deps.sessionRepository.getMessageAsUiMessage(state.currentAssistantId.toString())
                        }.getOrNull()
                    }
                }
                deps.persistInterruptedAssistant(
                    sessionId,
                    partialFromBuilder,
                    state.currentAssistantId,
                    System.currentTimeMillis() - state.streamStartedAt,
                )
                withContext(NonCancellable) {
                    runCatching {
                        deps.sessionRepository.deleteGenerationCheckpoints(sessionId, state.streamStartedAt)
                    }.onFailure { Logger.w("ChatVM", "异常清理 generation checkpoints 失败: ${it.message}") }
                }
                deps.generationState.toolGenerationToken++
                deps.generationState.toolAssistantId = null
                deps.generationState.activeToolSessionId = null
                val type = deps.classifyErrorType(t.message ?: "", t)
                val msg = ErrorMessages.classifyNetworkError(deps.appContext, t)
                deps.addError(type, msg, type != ChatErrorType.API_KEY)
                clearStreamingStateIfLatest(state, ChatStreamPhase.FAILED)
                sessionManager.runtime(sessionId)?.markFinished(TurnPhase.FAILED, state.turnId)
                runCatching {
                    notificationManager.updateLiveProgress("", 0, false)
                }.onFailure { Logger.w("ChatVM", "取消进度通知失败: ${it.message}") }
            } finally {
                deps.sessionMemoryCache.remove(sessionId)
                deps.milestoneChecker?.checkAndTrigger(sessionId, accessor.snapshot.currentAssistant?.id ?: "default")
            }
        }
    }

    /** 收尾:shadow TURN_FINISHED + onGenerationFinish 钩子 + 元数据持久化 + 通知 + 清理检查点。 */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    suspend fun finalizeResponse(state: StreamRunState) {
        val experiments = state.experiments
        val sessionId = state.sessionId
        val sessionTitle = state.sessionTitle
        val streamStartedAt = state.streamStartedAt
        if (ConversationRebuildFlagStore.current.shadowEventsEnabled) {
            val finalMessage = deps.stateStore.messages.value.firstOrNull { it.id == state.currentAssistantId }
            val shadowContent = finalMessage?.content ?: state.builder.toString()
            val shadowLength = finalMessage?.content?.length ?: state.builder.length
            recordConversationShadow(
                ConversationEventDraft(
                    sessionId = sessionId,
                    turnId = state.turnId,
                    type = ConversationEventType.TURN_FINISHED,
                    streamId = state.streamId,
                    generationSerial = state.generationSerial,
                    payloadJson = "{\"messageId\":\"${state.currentAssistantId}\"," +
                        "\"contentLength\":${shadowLength},\"contentHash\":\"${sha256(shadowContent)}\"}",
                ),
            )
            deps.conversationService.finishTurn(state.turnId)
        }

        clearStreamingStateIfLatest(state, ChatStreamPhase.FINISHED)

        // 三钩子:生成完成后调用 applyOnGenerationFinish,若最终 assistant 消息被改变则写回 DB。
        resultOf {
            val ctx = state.transformContext ?: return@resultOf
            val currentAssistantId = state.currentAssistantId
            val finalAssistant = deps.stateStore.messages.value
                .firstOrNull { it.id == currentAssistantId } ?: return@resultOf
            val transformed = deps.transformerPipeline.applyOnGenerationFinish(listOf(finalAssistant), ctx)
            val newAssistant = transformed.firstOrNull()
            if (newAssistant != null && newAssistant != finalAssistant) {
                deps.stateStore.messages.value = deps.stateStore.messages.value.map {
                    if (it.id == currentAssistantId) newAssistant else it
                }
                resultOf { deps.sessionRepository.upsertMessage(sessionId, newAssistant) }
                    .onError { msg, _ -> Logger.w("ChatVM", "onGenerationFinish upsertMessage failed: $msg") }
                deps.applyPendingVariantInfo(newAssistant.id)
            }
        }.onError { msg, _ -> Logger.w("ChatVM", "applyOnGenerationFinish failed: $msg") }

        // A5: 生成元数据持久化(provider 实测 token 用量 + 总耗时),失败不阻塞。
        resultOf {
            val entity = deps.sessionRepository.getMessageById(state.currentAssistantId.toString()) ?: return@resultOf
            val usage = state.usageTokens
            val durationMs = System.currentTimeMillis() - streamStartedAt
            deps.sessionRepository.upsertMessageEntity(
                entity.copy(
                    durationMs = durationMs,
                    promptTokens = usage?.promptTokens,
                    completionTokens = usage?.completionTokens,
                    reasoningTokens = usage?.reasoningTokens,
                    cachedTokens = usage?.cachedTokens,
                )
            )
            deps.stateStore.messages.value = deps.stateStore.messages.value.map {
                if (it.id == state.currentAssistantId) {
                    it.copy(
                        durationMs = durationMs,
                        promptTokens = usage?.promptTokens,
                        completionTokens = usage?.completionTokens,
                        reasoningTokens = usage?.reasoningTokens,
                        cachedTokens = usage?.cachedTokens,
                    )
                } else it
            }
        }.onError { msg, _ -> Logger.w("ChatVM", "persist A5 message metadata failed: $msg") }

        // 流式结束后刷新上下文 token 占用。
        deps.refreshContextInfo()

        // 上下文溢出保护:token 占用超过 80% 时后台自动压缩。
        resultOf { deps.triggerAutoCompress(sessionId) }

        // v2.3: debugMode 下填充 debugInfo。
        if (experiments.debugMode) {
            val elapsedMs = System.currentTimeMillis() - streamStartedAt
            val ttftMs = if (state.firstTokenTime > 0L) state.firstTokenTime - streamStartedAt else -1L
            val elapsedSec = (elapsedMs / 1000f).coerceAtLeast(0.001f)
            val tokenRate = state.totalCharCount / elapsedSec
            val selectedModel = resultOf { deps.settings.getSelectedModel() }.getOrNull()
            val modelName = selectedModel?.name ?: selectedModel?.id
                ?: deps.appContext.getString(R.string.msg_info_unknown)
            val debugInfo = buildString {
                append(deps.appContext.getString(R.string.chat_debug_model_label))
                append(": $modelName")
                append(" | ")
                append(deps.appContext.getString(R.string.chat_debug_duration_label))
                append(": ${elapsedMs}ms")
                if (ttftMs >= 0) {
                    append(" | ")
                    append(deps.appContext.getString(R.string.chat_debug_ttft_label))
                    append(": ${ttftMs}ms")
                }
                append(" | ")
                append(deps.appContext.getString(R.string.chat_debug_rate_label))
                append(": ${"%.1f".format(tokenRate)} tok/s")
                append(" | ")
                append(deps.appContext.getString(R.string.chat_debug_chars_label))
                append(": ${state.totalCharCount}")
                append(" | ")
                append(deps.appContext.getString(R.string.chat_debug_tool_calls_label))
                append(": ${state.totalToolCallCount}")
                append(" | ")
                append(deps.appContext.getString(R.string.chat_debug_round_label))
                append(": ${state.round}")
            }
            accessor.update { it.copy(debugInfo = debugInfo) }
            Logger.d("ChatVM-Debug", "launchStream done | sessionId=$sessionId | $debugInfo")
        }

        // 通知:流式完成 — 发"回复完成"通知。
        resultOf {
            notificationManager.updateLiveProgress(sessionTitle, 0, false)
            val finalText = deps.stateStore.messages.value
                .firstOrNull { it.id == state.currentAssistantId }?.content.orEmpty()
            val preview = finalText.ifBlank { deps.appContext.getString(R.string.err_chat_reply_generated) }
            val policy = deps.settings.notificationPolicyFlow.first()
            notificationManager.notifyChatCompletedWithPolicy(
                policy = policy,
                sessionTitle = sessionTitle,
                preview = preview,
                target = MuseNotificationTarget.Session(sessionId),
            )
        }.onError { msg, t -> Logger.w("ChatVM", "流式完成通知失败: $msg", t) }

        // 通知 memory ticker(后台 rollingSummary + daily check)。
        val conversationMessages = deps.stateStore.messages.value
        val selectedModel = resultOf { deps.settings.getSelectedModel() }.getOrNull()
        runCatching {
            deps.memoryTicker.notifyTurn(
                sessionId,
                conversationMessages,
                selectedModel,
                assistantId = accessor.snapshot.currentAssistant?.id ?: "",
            )
        }.onFailure { Logger.w("ChatVM", "notifyTurn failed: ${it.message}") }

        // B5-01/B-23: 生成正常结束,按 (sessionId, streamStartedAt) 精确清理本代检查点。
        resultOf { deps.sessionRepository.deleteGenerationCheckpoints(sessionId, streamStartedAt) }
            .onError { msg, _ -> Logger.w("ChatVM", "generation checkpoints 清理失败: $msg") }
        // R-UI-02: 本轮生成结束后清除生成焦点。
        if (resultOf { deps.settings.getGeneratingSessionId() }.getOrNull() == sessionId) {
            resultOf { deps.settings.saveGeneratingSessionId(null) }
                .onError { msg, _ -> Logger.w("ChatVM", "saveGeneratingSessionId 清理失败: $msg") }
        }
    }

    companion object {
        private const val MAX_INPUT_HISTORY = 50
        private const val PRESEND_TOKEN_WARNING_RATIO = 0.9f
    }
}
