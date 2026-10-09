package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.Perf
import io.zer0.common.ProcessWriteGate
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.chat.InternalPromptMarkers
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantMemoryAccessPolicy
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
import io.zer0.muse.session.ExecutionKind
import io.zer0.muse.session.ExecutionState
import io.zer0.muse.session.SessionExecutionRegistry
import io.zer0.muse.session.TurnPhase
import io.zer0.muse.transformer.UserMessageTimeContext
import io.zer0.muse.ui.ChatErrorType
import io.zer0.muse.ui.ChatStreamPhase
import io.zer0.muse.ui.ChatUiState
import io.zer0.muse.ui.buildSendText
import io.zer0.muse.ui.canContinueGeneration
import io.zer0.muse.ui.canRegenerate
import io.zer0.muse.ui.canStartGeneration
import io.zer0.muse.ui.resumeFromInterrupted
import io.zer0.muse.util.ErrorMessages
import io.zer0.muse.util.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
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
internal fun composeSystemPromptMessages(staticPrompt: String, dynamicPrompt: String): List<UIMessage> = listOfNotNull(
    staticPrompt.takeIf { it.isNotBlank() }?.let { UIMessage(role = MessageRole.SYSTEM, content = it) },
    dynamicPrompt.takeIf { it.isNotBlank() }?.let { UIMessage(role = MessageRole.SYSTEM, content = it) },
)

/** Agent mode owns an independent session; task-session state must never win this lookup. */
internal fun effectiveGenerationSessionId(state: ChatUiState): String? =
    if (state.isAgentMode) state.agentSessionId else state.currentSessionId

internal fun shouldRollbackOptimisticSend(state: ChatUiState, requestSessionId: String): Boolean =
    effectiveGenerationSessionId(state) == requestSessionId

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
    private val executionRegistry: SessionExecutionRegistry? = null,
) {
    private val shadowEventSequencer = ConversationShadowEventSequencer()

    // B-1: 会话删除写抑制集 — 删除会话消息时登记,令该会话全部在途流式落盘
    // (persistCurrentAssistant / persistInterruptedAssistant / 收尾 upsertMessage)
    // 跳过,防止删除后"复活"。新流式 launchStream 启动时清除,允许删除后重新生成。
    private val sessionWritesSuppressed: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** 备份恢复替换数据库文件期间，聊天生成不得继续制造新写入。 */
    private fun restoreBlocksChatWrites(operation: String): Boolean {
        if (!ProcessWriteGate.restoring) return false
        Logger.w("ChatVM", "备份恢复进行中，跳过聊天写入: $operation")
        return true
    }

    /** B-1: 标记该会话本次删除已发生 — 后续在途流式落盘据此跳过。 */
    fun suppressSessionWrites(sessionId: String?) {
        if (sessionId != null) sessionWritesSuppressed.add(sessionId)
    }

    /** B-1: 该会话是否处于"删除后写抑制"状态。 */
    fun isSessionWritesSuppressed(sessionId: String): Boolean = sessionId in sessionWritesSuppressed

    /** B-1: 删除会话消息时停止其全部在途生成(应用级 + 会话级取消)。 */
    fun stopGenerationForSession(sessionId: String?) {
        sessionId?.let { executionRegistry?.requestCancelForSession(it, "delete_message") }
        chatGenerationManager.stop(sessionId)
        sessionId?.let { sessionManager.cancelGeneration(it) }
    }

    /** 用户点"停止"。 */
    fun stop() {
        // 只停止单聊的生成,不影响群聊
        val sid = effectiveGenerationSessionId(accessor.snapshot)
        sid?.let { executionRegistry?.requestCancelForSession(it, "user_stop") }
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
                // v2.6.4: 用户停止时收尾未结束的计划 —— 把 PENDING / IN_PROGRESS 步骤落成 CANCELLED。
                // 否则计划卡会永远停在转圈态（stop 后没人再调 update_plan_step 推进它）。
                agentPlans = it.agentPlans.mapValues { (_, plan) -> plan.settleAsCancelled() },
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
        val canStart =
            canStartGeneration(
                text,
                images,
                accessor.snapshot.isStreaming,
                deps.generationState.isCreatingAgentSession,
            )
        if (!canStart) {
            return
        }
        // 输入内容安全过滤(此前 SafetyPolicy 定义完整却全仓零调用 —— 安全网没插电)。
        // 命中明确违规词时中止发送并提示,不写入会话。
        val safety = io.zer0.muse.privacy.SafetyPolicy.checkInput(text)
        if (!safety.safe) {
            Logger.w("ChatVM", "输入被安全策略拦截: ${safety.reason}")
            deps.addError(ChatErrorType.UNKNOWN, safety.suggestion ?: "", false)
            return
        }
        // v1.68: 引用回复必须把被引用内容拼进消息体,LLM 才能读到引用原文。
        val replyingToLatest =
            accessor.snapshot.replyingTo?.let { r ->
                deps.stateStore.messages.value.find { it.id == r.id } ?: r
            }
        val quoteText =
            accessor.snapshot.replyQuoteOverride?.takeIf { it.isNotBlank() }
                ?: replyingToLatest?.content?.takeIf { it.isNotBlank() }
        if (quoteText != null) {
            text = buildQuotedContent(quoteText, text)
        }

        // v1.28: Agent 模式用独立的 agentSessionId,无会话时自动创建
        // v1.79 (M-CV8): 用 isCreatingAgentSession 标志防止重入,避免快速双击创建两个会话
        val sessionId =
            if (accessor.snapshot.isAgentMode) {
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
        if (restoreBlocksChatWrites("regenerate")) {
            reportRegenerateUnavailable("backup restore in progress")
            return
        }
        val st = accessor.snapshot
        val sessionId =
            if (st.isAgentMode) {
                st.agentSessionId ?: return reportRegenerateUnavailable("agent session missing")
            } else {
                st.currentSessionId ?: return reportRegenerateUnavailable("session missing")
            }
        val tree = deps.stateStore.conversationTree.value
        if (!canRegenerate(
                isStreaming = st.isStreaming,
                hasSession = true,
                hasSelectedUserVariant = tree.selectedUserNode != null && tree.selectedUserVariant != null,
            )
        ) {
            return reportRegenerateUnavailable(
                when {
                    st.isStreaming -> "generation already running"
                    tree.selectedUserNode == null -> "selected user branch missing"
                    tree.selectedUserVariant == null -> "selected user message missing"
                    else -> "regeneration not available"
                },
            )
        }
        val update = tree.retryLastAssistant()
        val newMsg = update.newMessage ?: return reportRegenerateUnavailable("reply branch missing")
        deps.stateStore.conversationTree.value = update.tree
        deps.stateStore.messages.value = update.tree.displayMessages
        accessor.update {
            it.copy(isStreaming = true, isWaitingFirstToken = true, errors = emptyList())
        }
        deps.sessionMemoryCache.remove(sessionId)
        // 先完成新 assistant variant 的落库,再启动生成,避免分支数据库竞态。
        accessor.coroutineScope.launch {
            val persisted =
                withContext(Dispatchers.IO) {
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
                reportRegenerateUnavailable("new reply could not be saved")
            }
        }
    }

    private fun reportRegenerateUnavailable(reason: String) {
        Logger.w("ChatVM", "regenerate ignored: $reason")
        deps.addError(
            ChatErrorType.UNKNOWN,
            deps.appContext.getString(R.string.err_chat_stream_broken),
            false,
        )
    }

    /** v5: 乐观更新 — 用户消息立即显示,不等待 DB 写入;随后入队由消费循环串行处理。 */
    @Suppress("LongMethod")
    fun enqueueSend(text: String, images: List<String>, sessionId: String) {
        if (restoreBlocksChatWrites("enqueueSend")) {
            deps.addError(
                ChatErrorType.UNKNOWN,
                deps.appContext.getString(R.string.err_chat_stream_broken),
                false,
            )
            return
        }
        // v2.1: 记录用户活动到活跃度画像,并更新对话结束类型(驱动自适应主动消息调度)
        deps.activityProfile.recordActivity()
        deps.activityProfile.setConversationEndType(
            if (UserActivityProfile.containsEndKeyword(text)) {
                ConversationEndType.USER_EXPLICIT_END
            } else {
                ConversationEndType.NATURAL_FADE
            },
        )
        // P2-4: 审计日志 — 发送消息
        deps.auditLogger.log(
            category = "user_action",
            action = "send_message",
            target = sessionId,
            detail =
            mapOf(
                "text_length" to text.length,
                "image_count" to images.size,
                "assistant_id" to (accessor.snapshot.currentAssistant?.id ?: "default"),
            ),
        )
        // v2.3: 任务模型路由只绑定到当前发送请求,不能把一次自动判断永久写成会话手动覆盖。
        // 否则先发一条“写代码”后,后续普通闲聊会一直粘在代码模型上。
        val selectedModel =
            sessionId.let { deps.generationState.sessionModelOverrides[it] }
                ?: deps.generationState.globalSelectedModelId
        val selectedProvider =
            sessionId.let { deps.generationState.sessionProviderOverrides[it] }
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
        val outboxReady =
            accessor.coroutineScope.async(Dispatchers.IO) {
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
                            ),
                        )
                    }.onError { _, t -> Logger.w("ChatVM", "outbox 写入失败,本次发送将被阻止", t) }.isSuccess
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
        val sendResult =
            deps.generationState.sendChannel.trySend(
                SendRequest(
                    text,
                    images,
                    sessionId,
                    userMessage = userMsg,
                    assistantMessageId = assistantMsg.id,
                    outboxId = outboxId,
                    outboxReady = outboxReady,
                    taskRouteSelection = routed,
                ),
            )
        if (sendResult.isFailure) {
            // 队列已满,回滚乐观更新 + 删除 outbox(消息未入队,outbox 无用)
            accessor.coroutineScope.launch(Dispatchers.IO) {
                val persisted = runCatching { outboxReady.await() }.getOrDefault(false)
                if (persisted) {
                    resultOf { deps.sessionRepository.deleteOutbox(outboxId) }
                }
            }
            accessor.update {
                val filtered =
                    deps.stateStore.messages.value.filterNot { msg ->
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
        if (!shouldRollbackOptimisticSend(accessor.snapshot, req.sessionId)) return
        deps.stateStore.messages.value =
            deps.stateStore.messages.value.filterNot { message ->
                message.id == req.userMessage.id || message.id == req.assistantMessageId
            }
    }

    /** 消费单条发送请求:会话匹配校验 → 落盘 user 消息 → 启动生成 → 清理 outbox。 */
    @Suppress("TooGenericExceptionCaught")
    suspend fun consumeSendRequest(req: SendRequest) {
        deps.generationState.outboxRecoveryQueuedIds.remove(req.outboxId)
        // 新请求必须先确认 outbox 已落盘；否则 launchStream 后立即删除 outbox 会留下不可恢复窗口。
        if (req.outboxReady != null && !req.outboxReady.await()) {
            rollbackOptimisticSend(req)
            accessor.update { it.copy(isStreaming = false, isWaitingFirstToken = false) }
            deps.addError(
                ChatErrorType.UNKNOWN,
                deps.appContext.getString(R.string.err_chat_msg_save_failed, "outbox persistence failed"),
                true,
            )
            return
        }
        if (restoreBlocksChatWrites("consumeSendRequest")) {
            rollbackOptimisticSend(req)
            accessor.update { it.copy(isStreaming = false, isWaitingFirstToken = false) }
            return
        }
        val state = accessor.snapshot
        val currentSid =
            if (state.isAgentMode) {
                state.agentSessionId ?: req.sessionId
            } else {
                state.currentSessionId ?: req.sessionId
            }
        if (currentSid != req.sessionId) {
            // 会话已切换,该 req 被跳过 — 仅回滚乐观更新(占位消息),保留 outbox 给切回后的恢复流程。
            // B-14: 不清全局 isStreaming —— 旧会话请求不得隐藏当前会话的流式动画;
            // 仅当当前会话(List 当前)确无在途生成时才复位指示器。
            val currentGenerating = chatGenerationManager.activeGenerations.value.containsKey(currentSid)
            accessor.update { st ->
                val filtered =
                    deps.stateStore.messages.value.filterNot { msg ->
                        msg.id == req.userMessage.id || msg.id == req.assistantMessageId
                    }
                deps.stateStore.messages.value = filtered
                if (currentGenerating) st else st.copy(isStreaming = false, isWaitingFirstToken = false)
            }
            Logger.i("ChatVM", "跳过当前会话外的 outbox 请求: ${req.outboxId}")
            return
        }
        try {
            if (restoreBlocksChatWrites("appendUserMessage")) {
                rollbackOptimisticSend(req)
                accessor.update { it.copy(isStreaming = false, isWaitingFirstToken = false) }
                return
            }
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
                            if (e.message?.contains("connection is closed", ignoreCase = true) == true) {
                                deps.appContext.getString(R.string.err_chat_db_closed)
                            } else {
                                e.message ?: deps.appContext.getString(R.string.err_chat_unknown)
                            },
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
                        if (e.message?.contains("connection is closed", ignoreCase = true) == true) {
                            deps.appContext.getString(R.string.err_chat_db_closed)
                        } else {
                            e.message ?: deps.appContext.getString(R.string.err_chat_unknown)
                        },
                    ),
                    true,
                )
                accessor.update { it.copy(isStreaming = false) }
                resultOf { deps.sessionRepository.deleteOutbox(req.outboxId) }
            }
            return
        }
        rehydrateOptimisticPlaceholders(currentSid, req)
        val delegated = deps.maybeAutoRoute(req.text, req.assistantMessageId, currentSid)
        if (delegated) {
            restoreSelectionForSession(currentSid)
            // delegated 路径由委派执行器接管，不会经过本地 generation checkpoint。
            if (!restoreBlocksChatWrites("clearDelegatedOutbox")) {
                resultOf { deps.sessionRepository.deleteOutbox(req.outboxId) }
            }
        } else {
            launchStream(
                assistantId = req.assistantMessageId,
                sessionId = currentSid,
                isNewBranch = false,
                continueFrom = null,
                taskRouteSelection = req.taskRouteSelection,
                outboxId = req.outboxId,
            )
        }
        // 非 delegated 路径由首个 generation checkpoint 成功后删除 outbox，保留启动前的恢复窗口。
    }

    /**
     * v2.2.1 修复: outbox 恢复重投路径(切会话回退后重新投递)不经过 enqueueSend 的乐观插入。
     * 若占位不在列表,流式更新会被 updateAssistant 的 detached 守卫整段跳过 ——
     * 表现为"后端已回、前端不渲染,重试或重进会话才出现"。这里在当前会话被显示时补回占位。
     */
    private fun rehydrateOptimisticPlaceholders(sessionId: String, req: SendRequest) {
        val shown =
            if (accessor.snapshot.isAgentMode) {
                accessor.snapshot.agentSessionId
            } else {
                accessor.snapshot.currentSessionId
            }
        if (shown != sessionId) return
        val messages = deps.stateStore.messages.value
        val hasUser = messages.any { it.id == req.userMessage.id }
        val hasAssistant = messages.any { it.id == req.assistantMessageId }
        if (hasUser && hasAssistant) return
        val additions =
            buildList {
                if (!hasUser) add(req.userMessage)
                if (!hasAssistant) {
                    add(
                        UIMessage(
                            id = req.assistantMessageId,
                            role = MessageRole.ASSISTANT,
                            content = "",
                            createdAt = req.userMessage.createdAt + 1,
                        ),
                    )
                }
            }
        if (additions.isEmpty()) return
        deps.stateStore.messages.value = messages + additions
        deps.messageController.rebuildConversationTree()
        // v2.3.1: 收敛为单行 —— 多行写法会把日志中文落到「非 Logger 行」,
        // 被 check_hardcoded_cjk 误判为 UI 硬编码文案(该检查只按同一行识别日志调用)
        val userShort = req.userMessage.id.toString().take(8)
        val assistantShort = req.assistantMessageId.toString().take(8)
        Logger.i("ChatVM", "outbox 恢复: 补回占位消息 user=$userShort, assistant=$assistantShort")
    }

    /**
     * v2.2.1: 清理指定会话中悬空的 Provider/模型覆盖(Provider 不存在,或模型不在该
     * Provider 下)。仅处理"会话显式覆盖",不触碰助手/全局配置;providers 未加载完成时
     * 跳过,避免误清。清掉后本代生成即按助手/全局回退解析,同时停用后续警告刷屏。
     */
    private suspend fun healStaleSessionOverride(sessionId: String) {
        val providers = accessor.snapshot.providers
        if (providers.isEmpty()) return
        val state = deps.generationState
        val providerId = state.sessionProviderOverrides[sessionId]
        var changed = false
        if (providerId != null && providers.none { it.id == providerId }) {
            // Provider 已消失:成对清掉 provider + model 覆盖(防半套配置,
            // 与 ProviderReferenceCleanup 的删除清理同语义)
            state.sessionProviderOverrides = state.sessionProviderOverrides - sessionId
            state.sessionModelOverrides = state.sessionModelOverrides - sessionId
            runCatching { deps.settings.saveSessionProviderOverride(sessionId, null) }
                .onFailure { e -> Logger.w("ChatVM", "清理悬空 Provider 覆盖失败: ${e.message}", e) }
            runCatching { deps.settings.saveSessionModelOverride(sessionId, null) }
                .onFailure { e -> Logger.w("ChatVM", "清理悬空模型覆盖失败: ${e.message}", e) }
            changed = true
        } else if (providerId != null) {
            val modelId = state.sessionModelOverrides[sessionId]
            if (modelId != null && providers.first { it.id == providerId }.models.none { it.id == modelId }) {
                state.sessionModelOverrides = state.sessionModelOverrides - sessionId
                runCatching { deps.settings.saveSessionModelOverride(sessionId, null) }
                    .onFailure { e -> Logger.w("ChatVM", "清理悬空模型覆盖失败: ${e.message}", e) }
                changed = true
            }
        }
        if (changed) {
            Logger.i("ChatVM", "已清除会话 $sessionId 的悬空模型/Provider 覆盖")
        }
    }

    /**
     * v2.5.3 (P3-2): 首次生成时拍摄会话级配置快照。
     *
     * 把启动这一刻的 provider/model/思考级别/温度固定下来，写入 sessions.configSnapshotJson。
     * - 已有快照：不覆盖（保持“当时的配置”）。
     * - 空快照/写入失败：静默跳过，不阻断生成。
     */
    private suspend fun captureConfigSnapshotIfAbsent(sessionId: String) {
        val st = accessor.snapshot
        // 会话级覆盖优先（用户显式指定），无覆盖时回退全局当前选择。
        val modelId = deps.generationState.sessionModelOverrides[sessionId] ?: st.selectedModelId
        val providerId = deps.generationState.sessionProviderOverrides[sessionId] ?: st.activeProviderId
        val snapshot =
            io.zer0.muse.data.session.SessionConfigSnapshot(
                providerId = providerId,
                modelId = modelId,
            )
        runCatching {
            deps.sessionRepository.saveConfigSnapshotIfAbsent(sessionId, snapshot)
        }.onFailure { e -> Logger.w("ChatVM", "拍摄会话配置快照失败: ${e.message}", e) }
    }

    /** 快速更新 token 计数(流式过程中每 200 字符或 1000ms 调用,避免每次重建 system prompt)。 */
    /**
     * 刷新上下文占用估算。
     *
     * v2.x: [excludedMessageIds] 排除"已并入会话检查点、不会再发给模型"的消息。
     * 占用率必须反映**模型实际会看到的内容**：否则压缩之后占用率仍停在压缩前的水位，
     * 会每轮重复触发压缩同一段历史（既费钱，也会让用户看到反复压缩的怪现象）。
     */
    suspend fun updateContextTokenCount(excludedMessageIds: Set<String> = emptySet()) {
        val msgsSnapshot = deps.stateStore.messages.value
        val counted =
            if (excludedMessageIds.isEmpty()) {
                msgsSnapshot
            } else {
                msgsSnapshot.filterNot { it.id.toString() in excludedMessageIds }
            }
        val sysPromptSnapshot = deps.systemPromptCache.cachedSystemPrompt
        val tokenCount =
            withContext(Dispatchers.Default) {
                runCatching { TokenEstimator.estimate(counted, sysPromptSnapshot) }
                    .onFailure { Logger.w("ChatVM", "TokenEstimator failed: ${it.message}") }
                    .getOrDefault(0)
            }
        accessor.update { it.copy(contextTokenCount = tokenCount) }
    }

    /** 静态 system prompt 快照的失效 key(assistant/settings/工具清单/偏好等变化触发重建)。 */
    internal fun computeStaticSnapshotKey(assistant: AssistantEntity?, memoryEnabled: Boolean): String {
        val prefs = accessor.snapshot.chatPreferences
        val registeredToolFingerprint =
            deps.toolRegistry.listTools()
                .sortedBy { it.name }
                .joinToString(";") { tool ->
                    "${tool.name}|${tool.description}|${tool.parameters}|${tool.required.sorted()}"
                }
                .hashCode()
        val state = accessor.snapshot
        val effectiveSessionId = if (state.isAgentMode) state.agentSessionId else state.currentSessionId
        val sessionSkillHash =
            state.sessions
                .firstOrNull { it.id == effectiveSessionId }?.skillIdsJson?.hashCode() ?: 0
        // v1.0.72: 本会话不参考记忆标志加入缓存键
        val sessionIgnoreMemory =
            state.sessions
                .firstOrNull { it.id == effectiveSessionId }?.ignoreMemory ?: false
        return buildString {
            append(assistant?.id ?: "null")
            append("|")
            append(assistant?.updatedAt ?: 0)
            append("|")
            append(assistant?.systemPrompt?.hashCode() ?: 0)
            append("|")
            append(assistant?.toolIdsJson?.hashCode() ?: 0)
            append("|")
            append(assistant?.mcpServerIdsJson?.hashCode() ?: 0)
            append("|")
            append(registeredToolFingerprint)
            append("|")
            append(assistant?.skillIdsJson?.hashCode() ?: 0)
            append("|")
            append(sessionSkillHash)
            append("|")
            append(assistant?.memoryEnabled ?: true)
            append("|")
            append(memoryEnabled)
            append("|")
            append(deps.settings.experienceEnabledCache)
            append("|")
            append(state.multiAgentConfig.enabled)
            append("|")
            append(prefs.showMoodBlock)
            append("|")
            append(prefs.responseStyle)
            append("|")
            append(prefs.responseTone)
            append("|")
            append(sessionIgnoreMemory)
        }
    }

    /** 组装 system prompt(9 个 section)+ 相关记忆检索 + RAG 自动注入 + 发送前上下文截断检查。 */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    suspend fun buildSystemPromptForStream(state: StreamRunState) {
        with(state) {
            // 共享上下文开关只控制全局画像/置顶/经验；助手自己的 facts 与近期会话保持独立。
            val memoryEnabled = assistant?.memoryEnabled ?: true
            val useGlobalMemory = assistant?.useGlobalMemory ?: false
            val timeReminderEnabled = assistant?.enableTimeReminder ?: true
            val assistantId = assistant?.id?.takeIf { it.isNotBlank() }
            val effectiveMemoryEnabled = memoryEnabled && deps.settings.isMemoryEnabled()
            // v1.0.72: 本会话不参考记忆标志
            val effSid =
                if (accessor.snapshot.isAgentMode) {
                    accessor.snapshot.agentSessionId
                } else {
                    accessor.snapshot.currentSessionId
                }
            val sessionIgnoreMem =
                accessor.snapshot.sessions
                    .firstOrNull { it.id == effSid }?.ignoreMemory ?: false
            val relevantMemoryAssistantId =
                assistantId?.takeIf {
                    AssistantMemoryAccessPolicy.canInjectAssistantFacts(
                        assistantId = it,
                        useGlobalMemory = useGlobalMemory,
                        memoryEnabled = effectiveMemoryEnabled,
                        forSubagent = false,
                        ignoreMemory = sessionIgnoreMem,
                    )
                }
            val memoryScope = assistantId?.takeIf { it != "default" } ?: "main"
            val memorySpaceId = deps.settings.currentSpaceIdFlow.firstOrNull().orEmpty().ifBlank { "default" }
            // 复用静态 system prompt 快照,只追加动态"当前时间"。作用域/空间也属于快照身份,
            // 否则切换 Assistant 或 Space 后会复用上一份记忆 prompt。
            val currentKey =
                computeStaticSnapshotKey(assistant, effectiveMemoryEnabled) +
                    "|global=$useGlobalMemory|scope=$memoryScope|space=$memorySpaceId"
            val staticSnapshot =
                if (currentKey == deps.systemPromptCache.cachedStaticSnapshotKey &&
                    deps.systemPromptCache.cachedStaticSystemPrompt.isNotBlank()
                ) {
                    deps.systemPromptCache.cachedStaticSystemPrompt
                } else {
                    val rebuilt =
                        resultOf {
                            deps.systemPromptAssembler.buildStaticSnapshot(
                                assistant = assistant,
                                memoryEnabled = effectiveMemoryEnabled,
                                ignoreMemory = sessionIgnoreMem,
                                useGlobalMemory = useGlobalMemory,
                                memoryScope = memoryScope,
                                memorySpaceId = memorySpaceId,
                            )
                        }.getOrNull() ?: ""
                    deps.systemPromptCache.cachedStaticSystemPrompt = rebuilt
                    deps.systemPromptCache.cachedStaticSnapshotKey = currentKey
                    rebuilt
                }
            val dynamicSection = if (timeReminderEnabled) deps.systemPromptAssembler.buildDynamicSection() else ""
            val userMessageTimeContext = UserMessageTimeContext.build(rawHistory)
            val lastUserInputForPrompt = rawHistory.lastOrNull { it.role == MessageRole.USER }?.content
            // v2.x: 表情包使用指南(动态读取;库为空/开关关闭时为空串)
            val stickerGuide = resultOf { deps.systemPromptAssembler.buildStickerGuideSection() }.getOrNull().orEmpty()
            val combinedSystemPrompt =
                buildString {
                    if (staticSnapshot.isNotBlank()) append(staticSnapshot)
                    if (dynamicSection.isNotBlank()) {
                        if (isNotEmpty()) append("\n\n---\n\n")
                        append(dynamicSection)
                    }
                    if (userMessageTimeContext.isNotBlank()) {
                        if (isNotEmpty()) append("\n\n---\n\n")
                        append(userMessageTimeContext)
                    }
                    if (stickerGuide.isNotBlank()) {
                        if (isNotEmpty()) append("\n\n---\n\n")
                        append(stickerGuide)
                    }
                    // 相关记忆检索(仅当记忆开启且非子助手;检索失败静默跳过)。
                    if (relevantMemoryAssistantId != null) {
                        // buildSystemPrompt 在 applyTransformers 之前执行,此时 transformedMessages
                        // 仍为空;使用本轮已准备好的 rawHistory,否则相关记忆永远不会注入。
                        val lastUserInput = lastUserInputForPrompt
                        if (!lastUserInput.isNullOrBlank()) {
                            val relevant =
                                resultOf {
                                    deps.systemPromptAssembler.buildRelevantMemorySection(
                                        currentUserInput = lastUserInput,
                                        store = null,
                                        scope = memoryScope,
                                        spaceId = memorySpaceId,
                                        assistantId = relevantMemoryAssistantId,
                                    )
                                }
                                    .onError { msg, _ -> Logger.w("ChatVM", "buildRelevantMemorySection 失败: $msg") }
                                    .getOrNull() ?: ""
                            if (relevant.isNotBlank()) {
                                if (isNotEmpty()) append("\n\n---\n\n")
                                append(relevant)
                            }
                        }
                    }
                    // 会话原文回溯:复用 messages_fts 的当前会话范围检索,不复制消息到知识库,
                    // 也不把 SYSTEM/TOOL 内部内容注入模型。用户可在记忆设置中关闭此能力。
                    if (
                        effectiveMemoryEnabled &&
                        !sessionIgnoreMem &&
                        deps.settings.memoryConfigCache.conversationRecallEnabled &&
                        !lastUserInputForPrompt.isNullOrBlank() &&
                        effSid != null
                    ) {
                        val recalled =
                            resultOf {
                                deps.sessionRepository.searchMessagesInSession(
                                    sessionId = effSid,
                                    query = lastUserInputForPrompt,
                                    limit = 8,
                                )
                            }.onError { msg, throwable ->
                                Logger.w("ChatVM", "会话原文回溯失败: $msg", throwable)
                            }.getOrNull().orEmpty()
                        val recallSection =
                            ConversationRecallFormatter.build(
                                query = lastUserInputForPrompt,
                                results = recalled,
                                maxTokens = deps.settings.memoryConfigCache.tokenBudget.coerceAtMost(1600),
                            )
                        if (recallSection.isNotBlank()) {
                            if (isNotEmpty()) append("\n\n---\n\n")
                            append(recallSection)
                        }
                        Logger.d(
                            "ChatVM",
                            "conversation recall | enabled=true | sessionId=$effSid | " +
                                "matches=${recalled.size} | injected=${recallSection.isNotBlank()}",
                        )
                    }
                }
            val dynamicSystemPrompt =
                if (staticSnapshot.isNotBlank() && combinedSystemPrompt.startsWith(staticSnapshot)) {
                    combinedSystemPrompt
                        .removePrefix(staticSnapshot)
                        .removePrefix("\n\n---\n\n")
                } else {
                    combinedSystemPrompt
                }
            systemMessages = composeSystemPromptMessages(staticSnapshot, dynamicSystemPrompt)
            Logger.d(
                "ChatVM",
                "system prompt dynamic sections | timeReminder=$timeReminderEnabled" +
                    " | currentTimeIncluded=${dynamicSection.contains(InternalPromptMarkers.TIME_SECTION_PREFIX)}" +
                    " | userMessageTimeIncluded=${userMessageTimeContext.isNotBlank()}" +
                    " | systemMessages=${systemMessages.size}",
            )
            deps.systemPromptCache.cachedSystemPrompt = combinedSystemPrompt
            updateContextTokenCount()

            // 发送前上下文长度硬检查:token 占用超过预警比例时激进截断历史。
            // P3-10: 同时记录本轮是否已近上限 — 仅近上限时才做发送 payload 超限复核,
            // 避免正常发送多付一次 BPE 估算开销。
            var nearContextLimit = false
            run {
                val maxTokens = accessor.snapshot.contextMaxTokens
                val currentTokens = accessor.snapshot.contextTokenCount
                if (maxTokens > 0 && currentTokens > 0) {
                    val ratio = currentTokens.toFloat() / maxTokens
                    nearContextLimit = ratio >= PRESEND_TOKEN_WARNING_RATIO
                    // v2.x 导入预热:预算截断已生效,跳过按条数折半(否则会毁掉全量意图)
                    if (nearContextLimit && rawHistory.size > 5 && !warmupActive) {
                        val newSize = (contextSize / 2).coerceAtLeast(2)
                        if (newSize < contextSize) {
                            Logger.w(
                                "ChatVM",
                                "pre-send context warning: " +
                                    "token=$currentTokens/$maxTokens (${(ratio * 100).toInt()}%), " +
                                    "history truncated $contextSize -> $newSize messages",
                            )
                            contextSize = newSize
                            truncatedHistory = buildContextWindow(rawHistory, contextSize)
                        }
                    }
                }
            }

            prefixMessages =
                buildList<UIMessage> {
                    addAll(systemMessages)
                    // presetMessages(预设对话)
                    assistant?.let { deps.assistantRepository.parsePresetMessages(it) }?.forEach { add(it) }
                    // v1.54: RAG 自动注入(失败不阻断主流程)。
                    val ragConfig = resultOf { deps.settings.getRagConfig() }.getOrNull() ?: io.zer0.muse.rag.RagConfig()
                    val effectiveRagConfig =
                        assistant?.let {
                            runCatching { deps.assistantRepository.mergeRagConfigOverride(it, ragConfig) }
                                .onFailure { e -> Logger.w("ChatViewModel", "mergeRagConfigOverride 失败: ${e.message}") }
                                .getOrDefault(ragConfig)
                        } ?: ragConfig
                    if (effectiveRagConfig.enabled) {
                        val lastUser = rawHistory.lastOrNull { it.role == MessageRole.USER }
                        val ragQuery = lastUser?.content?.takeIf { it.isNotBlank() }
                        if (ragQuery != null) {
                            val mentionDocIds =
                                resultOf { deps.ragService.resolveMentionToDocIds(ragQuery) }
                                    .onError { msg, t -> Logger.w("ChatViewModel", "@mention 解析失败: $msg", t) }
                                    .getOrNull()
                            // 助手绑定 KB 时,未显式 @mention 的查询只检索这些 KB;
                            // 显式 mention 优先,可临时定向到用户指定的文档。
                            val boundKnowledgeBaseIds =
                                assistant
                                    ?.let { deps.assistantRepository.parseKnowledgeBaseIds(it) }
                                    .orEmpty()
                            // v2.4.6: 优先用「KB+文件夹」作用域(文件夹 = 独立检索域);
                            // 未设文件夹作用域时回退到旧的整库绑定。
                            val boundFolderScopes =
                                assistant
                                    ?.let { deps.assistantRepository.parseKnowledgeFolderScopes(it) }
                                    .orEmpty()
                            val boundDocIds =
                                if (mentionDocIds.isNullOrEmpty()) {
                                    resultOf {
                                        if (boundFolderScopes.isNotEmpty()) {
                                            deps.ragService.resolveKbFolderScopes(boundFolderScopes)
                                        } else {
                                            deps.ragService.resolveKnowledgeBaseDocIds(boundKnowledgeBaseIds)
                                        }
                                    }.onError { msg, t ->
                                        Logger.w("ChatViewModel", "助手绑定知识库展开失败: $msg", t)
                                    }.getOrNull()
                                } else {
                                    emptyList()
                                }
                            val scopeDocIds =
                                mentionDocIds.takeIf { !it.isNullOrEmpty() }
                                    ?: boundDocIds?.takeIf { it.isNotEmpty() }
                            val injection =
                                resultOf {
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
                                    val clampedRagText =
                                        io.zer0.muse.context.ContextBudget().clampText(
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

            // P3-10: 上下文超限兜底 — 近上限时复核本轮真实 payload(system + preset + RAG
            // + 截断后历史)的估算 token;仍达到硬上限(CONTEXT_HARD_LIMIT_RATIO)则置位拒绝
            // 发送,由 launchStream 回滚空占位并给出用户可见提示。
            if (nearContextLimit) {
                val payloadTokens =
                    withContext(Dispatchers.Default) {
                        TokenEstimator.estimate(prefixMessages + truncatedHistory)
                    }
                val hardMaxTokens = accessor.snapshot.contextMaxTokens
                if (hardMaxTokens > 0 && payloadTokens >= (hardMaxTokens * CONTEXT_HARD_LIMIT_RATIO).toInt()) {
                    contextOverflowBlocked = true
                    Logger.w(
                        "ChatVM",
                        "pre-send context overflow: payload=$payloadTokens/$hardMaxTokens " +
                            "(${payloadTokens * 100 / hardMaxTokens}%), refuse to send | sessionId=$sessionId",
                    )
                }
            }
        }
    }

    /** 记录对话 shadow 事件(ConversationRebuild 关闭时静默跳过)。 */
    @Suppress("TooGenericExceptionCaught")
    suspend fun recordConversationShadow(event: ConversationEventDraft) {
        if (!ConversationRebuildFlagStore.current.shadowEventsEnabled) return
        val key = listOf(event.sessionId, event.turnId, event.generationSerial.toString()).joinToString("\u001f")
        shadowEventSequencer.enqueue(key) {
            try {
                deps.conversationService.record(event)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                Logger.w("ChatVM", "conversation shadow event failed: ${event.type}", e)
            }
        }
    }

    /** 切回会话时重新投递仍未启动生成的 outbox 请求。 */
    suspend fun requeueOutboxForSession(sessionId: String) {
        if (restoreBlocksChatWrites("requeueOutbox")) return
        val pending = resultOf { deps.sessionRepository.getPendingOutbox(sessionId) }.getOrNull().orEmpty()
        for (req in pending) {
            if (!deps.generationState.outboxRecoveryQueuedIds.add(req.id)) continue
            val images =
                runCatching {
                    deps.idListJson.decodeFromString<List<String>>(req.imageBase64Json)
                }.getOrDefault(emptyList())
            val userId = runCatching { Uuid.parse(req.userMessageId) }.getOrElse { Uuid.random() }
            val assistantId = runCatching { Uuid.parse(req.assistantMessageId) }.getOrElse { Uuid.random() }
            val result =
                deps.generationState.sendChannel.trySend(
                    SendRequest(
                        text = req.text,
                        images = images,
                        sessionId = req.sessionId,
                        userMessage =
                        UIMessage(
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
        val modelId =
            deps.generationState.sessionModelOverrides[sessionId]
                ?: deps.generationState.globalSelectedModelId
        val providerId =
            deps.generationState.sessionProviderOverrides[sessionId]
                ?: deps.generationState.globalActiveProviderId
        accessor.update { it.copy(selectedModelId = modelId, activeProviderId = providerId) }
    }

    /** Keep one diagnostic summary on every debug-mode terminal path. */
    suspend fun recordDebugSummary(state: StreamRunState, outcome: String) {
        if (!state.experiments.debugMode) return
        val elapsedMs = System.currentTimeMillis() - state.streamStartedAt
        val ttftMs = if (state.firstTokenTime > 0L) state.firstTokenTime - state.streamStartedAt else -1L
        val elapsedSec = (elapsedMs / 1000f).coerceAtLeast(0.001f)
        val tokenRate = state.totalCharCount / elapsedSec
        val selectedModel = resultOf { deps.settings.getSelectedModel() }.getOrNull()
        val modelName = selectedModel?.name ?: selectedModel?.id
            ?: deps.appContext.getString(R.string.msg_info_unknown)
        val debugInfo =
            buildString {
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
                append(" | status=$outcome")
                append(" | uiFlush=${state.uiFlushCount}")
            }
        accessor.update { it.copy(debugInfo = debugInfo) }
        Logger.d("ChatVM-Debug", "launchStream terminal | outcome=$outcome | $debugInfo")
    }

    /** 仅当自己仍是最新生成时才清零流式状态(快速连发时 gen-1 收尾不得清掉 gen-2)。 */
    fun clearStreamingStateIfLatest(state: StreamRunState, finalPhase: ChatStreamPhase = ChatStreamPhase.IDLE): Boolean {
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
        outboxId: String? = null,
    ) {
        if (restoreBlocksChatWrites("launchStream")) {
            accessor.update { it.copy(isStreaming = false, isWaitingFirstToken = false) }
            return
        }
        // v1.94: 每次启动流式生成前清空工具调用历史(InputBar 动态胶囊计数归零)
        accessor.update { it.copy(toolCallHistory = emptyList()) }
        // B-1: 新流式启动时清除该会话的"删除写抑制",允许删除后重新生成落盘。
        sessionWritesSuppressed.remove(sessionId)
        // R-UI-02: 生成会话单独持久化,避免与用户查看焦点互相覆盖。
        accessor.coroutineScope.launch {
            resultOf { settings.saveGeneratingSessionId(sessionId) }
                .onError { msg, _ -> Logger.w("ChatVM", "saveGeneratingSessionId 失败: $msg") }
        }
        // 在调度器等待旧代 finally 之前就分配新代身份，旧代收尾从此刻起不能清零新代 UI。
        val generationSerial = ++deps.generationState.streamGenerationSerial
        // 先创建流状态，再把同一 generationId 交给调度器；活跃状态、LLM、工具和审批因此共享代际身份。
        val state = StreamRunState(sessionId = sessionId, assistantId = assistantId, isNewBranch = isNewBranch)
        state.outboxId = outboxId
        chatGenerationManager.launchGeneration(
            sessionId = sessionId,
            assistantId = assistantId.toString(),
            sessionTitle =
            accessor.snapshot.sessions.firstOrNull { it.id == sessionId }?.title
                ?: deps.appContext.getString(R.string.chat_new_session),
            generationId = state.generationIdentity.generationId,
        ) {
            if (restoreBlocksChatWrites("launchStream")) {
                accessor.update { it.copy(isStreaming = false, isWaitingFirstToken = false) }
                return@launchGeneration
            }
            val generationJob = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
            val generationExecutionId =
                executionRegistry?.register(
                    identity = state.generationIdentity,
                    kind = ExecutionKind.LLM,
                    cancel = { generationJob?.cancel() },
                )
            generationExecutionId?.let { id -> executionRegistry?.start(id) }
            // v2.2.1: 会话级覆盖悬空自愈 — 指向的 provider/model 已不存在时,先清掉本会话的
            // 悬空覆盖(内存 + 持久化),避免每次生成都走回退并刷 "requested model binding
            // unavailable" 警告;助手/全局绑定不受影响。
            healStaleSessionOverride(sessionId)
            // v2.5.3 (P3-2): 首次生成时拍摄会话级配置快照 —— 把当时的 provider/model/思考级别/温度
            // 固定下来，之后即使全局模型被切换，旧会话仍按“当时的配置”重发。
            // 仅在尚未拍摄时写入，不覆盖既有快照；失败不阻断生成。
            captureConfigSnapshotIfAbsent(sessionId)
            // 会话选择显式覆盖助手/全局默认；生成任务捕获启动时的配置，期间切页不会串台。
            state.sessionModelOverride = deps.generationState.sessionModelOverrides[sessionId]
            state.sessionProviderOverride = deps.generationState.sessionProviderOverrides[sessionId]
            state.taskRouteSelection = taskRouteSelection
            state.fallbackModelId = deps.generationState.globalSelectedModelId
            state.fallbackProviderId = deps.generationState.globalActiveProviderId
            // B-24: 使用入口处已分配的本代序号；避免等待旧代 finally 时产生竞态。
            state.generationSerial = generationSerial
            // M1.1: 开启会话运行时 turn 检查点(NOT_STARTED -> GENERATING)。
            sessionManager.beginTurn(sessionId, state.turnId)
            chatGenerationManager.setTurnId(
                sessionId = sessionId,
                generationId = state.generationIdentity.generationId,
                turnId = state.generationIdentity.turnId,
            )
            // B7-04: 继续生成时预置已产出内容
            continueFrom?.let { state.builder.append(it.content) }
            // P3-9: 阶段耗时埋点 — 准备/首字/每轮/收尾分段计时,一次 debug 日志可定位慢在哪一段。
            // (首字/每轮由 ChatViewModel / ToolOrchestrator 的 Perf 埋点补充)
            val stageTimer = Perf.start("chat-turn")
            try {
                deps.streamCoordinator.prepareHistory(state)
                stageTimer.split("prepare")
                val mcpServerIds =
                    state.assistant
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
                stageTimer.split("prompt")
                // P3-10: 上下文超限兜底 — 预压缩(80%)+ 激进截断(90%)之后仍超出模型窗口时,
                // 拒绝发送本轮请求(避免必然 400 的无效调用),回滚空占位并给出可见提示。
                if (state.contextOverflowBlocked) {
                    deps.addError(
                        ChatErrorType.UNKNOWN,
                        deps.appContext.getString(R.string.error_api_context_length),
                        true,
                    )
                    recordDebugSummary(state, "context_overflow")
                    deps.stateStore.messages.value =
                        deps.stateStore.messages.value.filterNot { msg ->
                            msg.id == state.currentAssistantId && msg.content.isBlank()
                        }
                    deps.messageController.rebuildConversationTree()
                    clearStreamingStateIfLatest(state, ChatStreamPhase.FAILED)
                    sessionManager.runtime(sessionId)?.markFinished(TurnPhase.FAILED, state.turnId)
                    return@launchGeneration
                }
                deps.streamCoordinator.applyTransformers(state)
                deps.streamCoordinator.resolveToolsAndModel(state)
                deps.streamCoordinator.applyPiiGuard(state)
                deps.streamCoordinator.prepareVisionContext(state)
                stageTimer.split("assemble")
                val success = deps.runToolLoop(state)
                stageTimer.split("stream")
                if (success) {
                    finalizeResponse(state)
                    stageTimer.split("finalize")
                    sessionManager.runtime(sessionId)?.markFinished(TurnPhase.COMPLETED, state.turnId)
                } else {
                    recordDebugSummary(state, "failed")
                    sessionManager.runtime(sessionId)?.markFinished(TurnPhase.FAILED, state.turnId)
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                generationExecutionId?.let { executionRegistry?.markCancelled(it) }
                val partialFromBuilder =
                    if (
                        state.builder.isNotEmpty() || state.reasoningBuilder.isNotEmpty()
                    ) {
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
                // B-1: 会话已删除则跳过中断落盘,防止删后"复活"。
                if (!isSessionWritesSuppressed(sessionId)) {
                    deps.persistInterruptedAssistant(
                        sessionId,
                        partialFromBuilder,
                        state.currentAssistantId,
                        System.currentTimeMillis() - state.streamStartedAt,
                    )
                }
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
                withContext(NonCancellable) {
                    recordDebugSummary(state, "cancelled")
                }
                deps.generationState.nextToolGenerationToken()
                deps.generationState.toolAssistantId = null
                deps.generationState.activeToolSessionId = null
                throw ce
            } catch (t: Exception) {
                generationExecutionId?.let { executionRegistry?.fail(it) }
                Logger.e("ChatVM", "stream failed", t)
                val partialFromBuilder =
                    if (
                        state.builder.isNotEmpty() || state.reasoningBuilder.isNotEmpty()
                    ) {
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
                // B-1: 会话已删除则跳过异常落盘,防止删后"复活"。
                if (!isSessionWritesSuppressed(sessionId)) {
                    deps.persistInterruptedAssistant(
                        sessionId,
                        partialFromBuilder,
                        state.currentAssistantId,
                        System.currentTimeMillis() - state.streamStartedAt,
                    )
                }
                withContext(NonCancellable) {
                    runCatching {
                        deps.sessionRepository.deleteGenerationCheckpoints(sessionId, state.streamStartedAt)
                    }.onFailure { Logger.w("ChatVM", "异常清理 generation checkpoints 失败: ${it.message}") }
                }
                deps.generationState.nextToolGenerationToken()
                deps.generationState.toolAssistantId = null
                deps.generationState.activeToolSessionId = null
                val type = deps.classifyErrorType(t.message ?: "", t)
                val msg = ErrorMessages.classifyNetworkError(deps.appContext, t)
                deps.addError(type, msg, type != ChatErrorType.API_KEY)
                recordDebugSummary(state, "failed")
                clearStreamingStateIfLatest(state, ChatStreamPhase.FAILED)
                sessionManager.runtime(sessionId)?.markFinished(TurnPhase.FAILED, state.turnId)
                runCatching {
                    notificationManager.updateLiveProgress("", 0, false)
                }.onFailure { Logger.w("ChatVM", "取消进度通知失败: ${it.message}") }
            } finally {
                // P3-9: 结束阶段计时(含准备/首字/每轮/收尾分段;异常路径同样落日志)
                stageTimer.end()
                val executionState = generationExecutionId?.let { executionRegistry?.state(it) }
                if (generationExecutionId != null && executionState == ExecutionState.RUNNING) {
                    executionRegistry?.finish(generationExecutionId)
                }
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
        // v2.x 导入预热:首轮成功完成响应后清除标记(全量历史只补一次;幂等)
        if (!restoreBlocksChatWrites("finalizeClearWarmup")) {
            resultOf { deps.sessionRepository.clearWarmupPending(sessionId) }
                .onError { msg, _ -> Logger.w("ChatVM", "clearWarmupPending failed: $msg") }
        }
        if (ConversationRebuildFlagStore.current.shadowEventsEnabled &&
            !restoreBlocksChatWrites("finalizeShadowEvents")
        ) {
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
                    payloadJson =
                    "{\"messageId\":\"${state.currentAssistantId}\"," +
                        "\"contentLength\":$shadowLength,\"contentHash\":\"${sha256(shadowContent)}\"}",
                ),
            )
            deps.conversationService.finishTurn(state.turnId)
        }

        clearStreamingStateIfLatest(state, ChatStreamPhase.FINISHED)

        // 三钩子:生成完成后调用 applyOnGenerationFinish,若最终 assistant 消息被改变则写回 DB。
        resultOf {
            val ctx = state.transformContext ?: return@resultOf
            val currentAssistantId = state.currentAssistantId
            val finalAssistant =
                deps.stateStore.messages.value
                    .firstOrNull { it.id == currentAssistantId } ?: return@resultOf
            val transformed = deps.transformerPipeline.applyOnGenerationFinish(listOf(finalAssistant), ctx)
            val newAssistant = transformed.firstOrNull()
            if (newAssistant != null && newAssistant != finalAssistant) {
                // P2-5: transformer 会重建 UIMessage,丢失变体身份字段(原实现依赖
                // 恒为 null 的 _pendingVariantInfo 写回,是死路径)。这里直接在替换时
                // 保留 finalAssistant 的变体字段,删除死路径机制。
                val preservedAssistant =
                    newAssistant.copy(
                        variantGroupId = newAssistant.variantGroupId ?: finalAssistant.variantGroupId,
                        variantIndex = if (newAssistant.variantIndex > 0) newAssistant.variantIndex else finalAssistant.variantIndex,
                        variantCount = if (newAssistant.variantCount > 0) newAssistant.variantCount else finalAssistant.variantCount,
                        parentGroupId = newAssistant.parentGroupId ?: finalAssistant.parentGroupId,
                    )
                deps.stateStore.messages.value =
                    deps.stateStore.messages.value.map {
                        if (it.id == currentAssistantId) preservedAssistant else it
                    }
                resultOf {
                    if (!restoreBlocksChatWrites("finalizeWriteAssistantMessage") &&
                        !isSessionWritesSuppressed(sessionId)
                    ) {
                        deps.sessionRepository.upsertMessage(sessionId, preservedAssistant)
                    }
                }
                    .onError { msg, _ -> Logger.w("ChatVM", "onGenerationFinish upsertMessage failed: $msg") }
            }
        }.onError { msg, _ -> Logger.w("ChatVM", "applyOnGenerationFinish failed: $msg") }

        // A5: 生成元数据持久化(provider 实测 token 用量 + 总耗时),失败不阻塞。
        resultOf {
            if (restoreBlocksChatWrites("finalizeWriteAssistantMeta")) return@resultOf
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
                ),
            )
            deps.stateStore.messages.value =
                deps.stateStore.messages.value.map {
                    if (it.id == state.currentAssistantId) {
                        it.copy(
                            durationMs = durationMs,
                            promptTokens = usage?.promptTokens,
                            completionTokens = usage?.completionTokens,
                            reasoningTokens = usage?.reasoningTokens,
                            cachedTokens = usage?.cachedTokens,
                        )
                    } else {
                        it
                    }
                }
        }.onError { msg, _ -> Logger.w("ChatVM", "persist A5 message metadata failed: $msg") }

        // 流式结束后刷新上下文 token 占用。
        deps.refreshContextInfo()

        // 上下文溢出保护:token 占用超过 80% 时后台自动压缩。
        if (!restoreBlocksChatWrites("finalizeAutoCompress")) {
            resultOf { deps.triggerAutoCompress(sessionId) }
        }

        recordDebugSummary(state, "completed")

        // 通知:流式完成 — 发"回复完成"通知。
        resultOf {
            notificationManager.updateLiveProgress(sessionTitle, 0, false)
            val finalText =
                deps.stateStore.messages.value
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
        // 聊天 UI 可能只保留最近一页消息;记忆管线必须从 DB 读取完整会话,
        // 再补上本轮尚未落库的 UI 消息,避免早期事实永远进不了摘要/抽取。
        val uiConversationMessages = deps.stateStore.messages.value
        val dbConversationMessages =
            if (deps.settings.memoryConfigCache.conversationRecallEnabled) {
                resultOf { deps.sessionRepository.getAllMessagesForWarmup(sessionId) }
                    .onError { msg, t -> Logger.w("ChatVM", "notifyTurn full history load failed: $msg", t) }
                    .getOrNull()
                    .orEmpty()
            } else {
                emptyList()
            }
        val dbMessageIds = dbConversationMessages.mapTo(HashSet()) { it.id }
        val conversationMessages =
            if (dbConversationMessages.isEmpty()) {
                uiConversationMessages
            } else {
                dbConversationMessages + uiConversationMessages.filter { it.id !in dbMessageIds }
            }
        val selectedModel = resultOf { deps.settings.getSelectedModel() }.getOrNull()
        val generationAssistantId = state.assistant?.id ?: "default"
        val generationSpaceId =
            (state.transformContext?.extra("current_space") as? String)
                ?.takeIf { it.isNotBlank() }
                ?: deps.settings.currentSpaceIdFlow.firstOrNull().orEmpty().ifBlank { "default" }
        if (!restoreBlocksChatWrites("finalizeNotifyMemory")) {
            runCatching {
                deps.memoryTicker.notifyTurn(
                    sessionId,
                    conversationMessages,
                    selectedModel,
                    assistantId = generationAssistantId,
                    spaceId = generationSpaceId,
                )
            }.onFailure { Logger.w("ChatVM", "notifyTurn failed: ${it.message}") }
        }

        // B5-01/B-23: 生成正常结束,按 (sessionId, streamStartedAt) 精确清理本代检查点。
        if (!restoreBlocksChatWrites("finalizeClearCheckpoints")) {
            resultOf { deps.sessionRepository.deleteGenerationCheckpoints(sessionId, streamStartedAt) }
                .onError { msg, _ -> Logger.w("ChatVM", "generation checkpoints 清理失败: $msg") }
        }
        // R-UI-02: 本轮生成结束后清除生成焦点。
        if (!restoreBlocksChatWrites("finalizeClearGenerationFocus") &&
            resultOf { deps.settings.getGeneratingSessionId() }.getOrNull() == sessionId
        ) {
            resultOf { deps.settings.saveGeneratingSessionId(null) }
                .onError { msg, _ -> Logger.w("ChatVM", "saveGeneratingSessionId 清理失败: $msg") }
        }
        // v2.5.9: 清除本会话登记的技能目录，防会话结束后残留（同 SessionToolLoadRegistry）。
        runCatching { deps.toolRegistry.clearSearchableSkills(sessionId) }
            .onFailure { Logger.w("ChatVM", "clearSearchableSkills 失败: ${it.message}") }
    }

    companion object {
        private const val MAX_INPUT_HISTORY = 50
        private const val PRESEND_TOKEN_WARNING_RATIO = 0.9f

        /**
         * P3-10: 发送 payload 硬上限比例 — 达到即拒绝发送（压缩/截断后仍超限的兜底）。
         *
         * v2.5.7 修复: 原 0.98 过激进 —— 客户端 BPE 估算是近似值（≠ 服务端真实计数），
         * 0.98 很容易把“实际没超”的请求误拦（用户实测“压缩也没用”）。
         * 改为留出估算误差余量：估算 > 真实窗口的 1.35 倍才拦；
         * 区间内交给 provider 真实校验（真超限时会返回 400，由错误映射处理）。
         */
        private const val CONTEXT_HARD_LIMIT_RATIO = 1.35f
    }
}
