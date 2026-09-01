package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.chat.PendingToolCallStore
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.tools.BrowserManagerRegistry
import io.zer0.muse.tools.WeakToolUseDetector
import io.zer0.muse.ui.ChatStreamPhase
import io.zer0.muse.ui.SessionMemoryCache
import io.zer0.muse.ui.common.feedback.MuseToast
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * v1.x: 从 ChatViewModel 抽离的会话 CRUD Controller。
 *
 * 职责(会话列表副作用 + 会话生命周期维护,不含消息加载/生成):
 *  - renameSession / setSessionArchived / deleteSession / setSessionIgnoreMemory / forkSessionFromMessage
 *  - 会话删除/归档时:清理浏览器实例、内存消息缓存,并在删除/归档当前会话时切换到剩余首个会话
 *  - 记忆开关变更后经 [SessionFlowBridge.refreshContext] 刷新上下文
 *
 * 跨职责调用(消息加载、上下文刷新、断开流式、分叉失败上报)经 [SessionFlowBridge] 注入,
 * 不反向依赖 ChatViewModel。共享 state 走 [ChatStateAccessor],不复制 StateFlow。
 */
@Suppress("LongParameterList")
internal class ChatSessionController(
    private val accessor: ChatStateAccessor,
    private val sessionRepository: SessionRepository,
    private val sessionMemoryCache: SessionMemoryCache,
    private val browserManagerRegistry: BrowserManagerRegistry?,
    private val bridge: SessionFlowBridge,
    private val sessionDeps: SessionDeps,
) {

    fun renameSession(sessionId: String, title: String) {
        accessor.coroutineScope.launch {
            sessionRepository.renameSession(sessionId, title)
        }
    }

    /** v0.45: 切换会话归档状态。归档当前会话时切换到剩余首个会话;无剩余会话时清空状态,不创建新会话。 */
    fun setSessionArchived(sessionId: String, archived: Boolean) {
        accessor.coroutineScope.launch {
            sessionRepository.setArchived(sessionId, archived)
            if (accessor.snapshot.currentSessionId == sessionId && archived) {
                val remaining = sessionRepository.observeSessions().first()
                val target = remaining.firstOrNull()
                if (target != null) {
                    switchSession(target.id)
                } else {
                    accessor.updateMessages { emptyList() }
                    accessor.update { it.copy(currentSessionId = null) }
                }
            }
        }
    }

    fun deleteSession(sessionId: String) {
        accessor.coroutineScope.launch {
            sessionRepository.softDeleteSession(sessionId)
            // v1.x: 会话删除时释放该会话的浏览器实例
            browserManagerRegistry?.let { registry ->
                resultOf { registry.closeSession(sessionId) }
                    .onError { msg, _ -> Logger.w("ChatVM", "closeSession browser 失败: $msg") }
            }
            // v1.93+: 从内存 LRU 缓存移除,避免持有已删除会话的消息副本(防止内存泄漏与脏读)
            sessionMemoryCache.remove(sessionId)
            if (accessor.snapshot.currentSessionId == sessionId) {
                val remaining = sessionRepository.observeSessions().first()
                val target = remaining.firstOrNull()
                if (target != null) {
                    switchSession(target.id)
                } else {
                    accessor.updateMessages { emptyList() }
                    accessor.update { it.copy(currentSessionId = null) }
                }
            }
        }
    }

    fun setSessionIgnoreMemory(ignore: Boolean) {
        val st = accessor.snapshot
        val sessionId = (if (st.isAgentMode) st.agentSessionId else st.currentSessionId) ?: return
        accessor.coroutineScope.launch {
            sessionRepository.setSessionIgnoreMemory(sessionId, ignore)
            // 更新本地会话状态(驱动 EmptyChatGuide 开关 + system prompt 缓存键)
            accessor.update { state ->
                state.copy(
                    sessions = state.sessions.map {
                        if (it.id == sessionId) it.copy(ignoreMemory = ignore) else it
                    },
                )
            }
            // 记忆开关影响静态快照,刷新上下文
            bridge.refreshContext()
        }
    }

    /**
     * v1.58: 从指定消息处分叉对话 — 在 repository 创建分叉会话,成功后切换到新会话。
     */
    @Suppress("TooGenericExceptionCaught")
    fun forkSessionFromMessage(messageId: Uuid) {
        val sourceSessionId = accessor.snapshot.currentSessionId ?: return
        if (accessor.snapshot.isStreaming) bridge.detachStreaming()
        accessor.coroutineScope.launch {
            try {
                val newId = sessionRepository.forkSession(sourceSessionId, messageId.toString())
                if (newId != null) {
                    switchSession(newId)
                }
            } catch (e: Exception) {
                Logger.w("ChatViewModel", "forkSession failed: ${e.message}")
                bridge.onForkError(e)
            }
        }
    }

    /** 新建会话:释放旧会话 → 按默认助手创建 → 重置 UI 状态 → 刷新上下文。 */
    @Suppress("CyclomaticComplexMethod")
    fun createNewSession() {
        if (accessor.snapshot.isStreaming) bridge.detachStreaming()
        // Phase 8.7: 切换会话时停止 TTS(避免跨会话继续朗读)
        sessionDeps.onStopTts()
        // v1.91: 释放流式 ASR(避免跨会话继续占用麦克风)
        sessionDeps.onDisposeAsr()
        // 通知 ticker: 旧 session 结束
        sessionDeps.onNotifySessionEnd()
        // v1.x: 清理旧会话的"本会话允许"临时缓存
        sessionDeps.currentSessionIdForApproval()?.let { sessionDeps.sessionPermissionStore.clearSession(it) }
        val currentSession = accessor.snapshot.currentSessionId
        // 引用计数:释放旧会话(新会话 id 在异步块内创建后再 acquire)
        currentSession?.let { sessionDeps.sessionManager.release(it) }
        accessor.coroutineScope.launch {
            // v1.0.63: 新任务使用设置里的默认助手
            val currentAssistantId = sessionDeps.settings.defaultAssistantIdFlow.first().ifBlank { "default" }
            val id = sessionRepository.createSession(assistantId = currentAssistantId)
            // v1.x: 新会话权限模式跟随全局默认
            val permissionMode = sessionDeps.sessionPermissionStore.getMode(
                id,
                sessionDeps.settings.defaultSessionPermissionModeFlow.first(),
            )
            sessionDeps.sessionManager.acquire(id)
            val assistant = sessionDeps.assistantRepository.getById(currentAssistantId)
                ?: sessionDeps.assistantRepository.getById("default")
            sessionDeps.stateStore.messages.value = emptyList()
            accessor.update {
                it.copy(
                    currentSessionId = id,
                    activeProviderId = sessionDeps.globalActiveProviderId(),
                    selectedModelId = sessionDeps.globalSelectedModelId(),
                    input = "",
                    hasDraft = false,
                    errors = emptyList(),
                    isDrawerOpen = false,
                    currentAssistant = assistant,
                    deepThinkingEnabled = it.chatPreferences.defaultDeepThinking,
                    taskCards = emptyMap(),
                    toolCallHistory = emptyList(),
                    agentPlans = emptyMap(),
                    inputHistory = emptyList(),
                    inputHistoryIndex = null,
                    visionAssistedMessageIds = emptySet(),
                    visionProgress = null,
                    sessionPermissionMode = permissionMode,
                )
            }
            // v0.45: 刷新上下文 token 占用(新会话 messages 为空,只加载 contextWindow)
            bridge.refreshContext()
            // R-UI-02: 新建会话后同步持久化查看焦点。
            resultOf { sessionDeps.settings.saveViewedSessionId(id) }
                .onError { msg, _ -> Logger.w("ChatVM", "saveViewedSessionId 失败: $msg") }
        }
    }

    /** 重启上下文:释放旧会话 → 新建任务/Agent 会话 → 重置 UI 状态。 */
    fun restartContext() {
        if (accessor.snapshot.isStreaming) bridge.detachStreaming()
        sessionDeps.onStopTts()
        sessionDeps.onNotifySessionEnd()
        // v1.x: 清理旧会话的"本会话允许"临时缓存
        sessionDeps.currentSessionIdForApproval()?.let { sessionDeps.sessionPermissionStore.clearSession(it) }
        // 引用计数:释放旧会话
        sessionDeps.currentSessionIdForApproval()?.let { sessionDeps.sessionManager.release(it) }
        accessor.coroutineScope.launch {
            val currentAssistantId = accessor.snapshot.currentAssistant?.id ?: "default"
            // v1.28: Agent 模式下创建 Agent 会话,不污染任务列表
            val id = if (accessor.snapshot.isAgentMode) {
                sessionRepository.createAgentSession(assistantId = currentAssistantId)
            } else {
                sessionRepository.createSession(assistantId = currentAssistantId)
            }
            sessionDeps.sessionManager.acquire(id)
            val assistant = sessionDeps.assistantRepository.getById(currentAssistantId)
                ?: sessionDeps.assistantRepository.getById("default")
            accessor.update {
                if (it.isAgentMode) {
                    sessionDeps.stateStore.messages.value = emptyList()
                    it.copy(
                        agentSessionId = id,
                        input = "",
                        errors = emptyList(),
                        isDrawerOpen = false,
                        currentAssistant = assistant,
                        taskCards = emptyMap(),
                        toolCallHistory = emptyList(),
                        agentPlans = emptyMap(),
                        visionAssistedMessageIds = emptySet(),
                        visionProgress = null,
                    )
                } else {
                    sessionDeps.stateStore.messages.value = emptyList()
                    it.copy(
                        currentSessionId = id,
                        input = "",
                        errors = emptyList(),
                        isDrawerOpen = false,
                        currentAssistant = assistant,
                        taskCards = emptyMap(),
                        toolCallHistory = emptyList(),
                        agentPlans = emptyMap(),
                        visionAssistedMessageIds = emptySet(),
                        visionProgress = null,
                    )
                }
            }
            bridge.refreshContext()
            accessor.update {
                it.copy(toast = sessionDeps.appContext.getString(R.string.err_chat_context_restarted_toast))
            }
            // R-UI-02: 任务模式下重启上下文后同步持久化查看焦点。
            if (!accessor.snapshot.isAgentMode) {
                resultOf { sessionDeps.settings.saveViewedSessionId(id) }
                    .onError { msg, _ -> Logger.w("ChatVM", "saveViewedSessionId 失败: $msg") }
            }
        }
    }

    /** v1.97 gap8: 将文本发送到新会话(原子创建新会话 + 填充输入 + 触发发送)。 */
    fun sendToNewChat(text: String) {
        if (accessor.snapshot.isStreaming) bridge.detachStreaming()
        sessionDeps.onStopTts()
        sessionDeps.onDisposeAsr()
        sessionDeps.onNotifySessionEnd()
        // v1.x: 清理旧会话的"本会话允许"临时缓存
        sessionDeps.currentSessionIdForApproval()?.let { sessionDeps.sessionPermissionStore.clearSession(it) }
        val currentSession = accessor.snapshot.currentSessionId
        val currentInput = accessor.snapshot.input
        // 引用计数:释放旧会话
        currentSession?.let { sessionDeps.sessionManager.release(it) }
        accessor.coroutineScope.launch {
            if (currentSession != null && currentInput.isNotBlank()) {
                // F-14: 草稿写入失败一次性提示
                resultOf { sessionDeps.settings.saveChatDraft(currentSession, currentInput) }
                    .onError { msg, _ ->
                        Logger.w("ChatVM", "saveChatDraft failed: $msg")
                        MuseToast.show(sessionDeps.appContext.getString(R.string.chat_draft_save_failed))
                    }
            }
            // v1.0.63: 新任务使用设置里的默认助手
            val currentAssistantId = sessionDeps.settings.defaultAssistantIdFlow.first().ifBlank { "default" }
            val id = sessionRepository.createSession(assistantId = currentAssistantId)
            val permissionMode = sessionDeps.sessionPermissionStore.getMode(
                id,
                sessionDeps.settings.defaultSessionPermissionModeFlow.first(),
            )
            sessionDeps.sessionManager.acquire(id)
            val assistant = sessionDeps.assistantRepository.getById(currentAssistantId)
                ?: sessionDeps.assistantRepository.getById("default")
            sessionDeps.stateStore.messages.value = emptyList()
            accessor.update {
                it.copy(
                    currentSessionId = id,
                    activeProviderId = sessionDeps.globalActiveProviderId(),
                    selectedModelId = sessionDeps.globalSelectedModelId(),
                    input = text,
                    hasDraft = false,
                    errors = emptyList(),
                    isDrawerOpen = false,
                    currentAssistant = assistant,
                    deepThinkingEnabled = it.chatPreferences.defaultDeepThinking,
                    taskCards = emptyMap(),
                    toolCallHistory = emptyList(),
                    agentPlans = emptyMap(),
                    visionAssistedMessageIds = emptySet(),
                    visionProgress = null,
                    sessionPermissionMode = permissionMode,
                )
            }
            bridge.refreshContext()
            sessionDeps.onSend()
            // R-UI-02: 新建会话并发送时同步持久化查看焦点。
            resultOf { sessionDeps.settings.saveViewedSessionId(id) }
                .onError { msg, _ -> Logger.w("ChatVM", "saveViewedSessionId 失败: $msg") }
        }
    }

    /** v1.28: 设置 Agent Tab 模式(恢复/创建独立 Agent 会话;退出时恢复任务会话)。 */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun setAgentMode(enabled: Boolean, requestedSessionId: String? = null) {
        if (accessor.snapshot.isStreaming) bridge.detachStreaming()
        sessionDeps.onStopTts()
        sessionDeps.onDisposeAsr()
        sessionDeps.onNotifySessionEnd()
        val prevSessionId = sessionDeps.currentSessionIdForApproval()
        if (enabled) {
            accessor.update { it.copy(isSwitchingSession = true) }
            accessor.coroutineScope.launch {
                val preferredAgentId = sessionDeps.settings.proactiveMessageConfigFlow.first()
                    .agentId.ifBlank { "default" }
                val requestedAgentSession = requestedSessionId
                    ?.takeIf { it.isNotBlank() }
                    ?.let { sessionRepository.getSessionById(it) }
                    ?.takeIf { it.isAgentSession && it.deletedAt == null }
                val agentSession = requestedAgentSession
                    ?: sessionRepository.getRecentAgentByAssistant(preferredAgentId, 1).firstOrNull()
                    ?: sessionRepository.getLatestAgentSession()
                val sessionId = agentSession?.id
                    ?: sessionRepository.createAgentSession(preferredAgentId)
                prevSessionId?.let { sessionDeps.sessionManager.release(it) }
                sessionDeps.sessionManager.acquire(sessionId)
                val permissionMode = sessionDeps.sessionPermissionStore.getMode(
                    sessionId,
                    sessionDeps.settings.defaultSessionPermissionModeFlow.first(),
                )
                val (messages, hasMore) = sessionDeps.messageController.loadMessagesPaged(sessionId)
                val assistantId = sessionRepository.getAssistantId(sessionId)
                val assistant = sessionDeps.assistantRepository.getById(assistantId)
                    ?: sessionDeps.assistantRepository.getById("default")
                val restoredAgentPlans = sessionDeps.messageController.restoreAgentPlansForSession(sessionId, messages)
                sessionDeps.stateStore.messages.value = messages
                val agentBackgroundStreaming = sessionDeps.chatGenerationManager.isStreaming(sessionId)
                accessor.update {
                    it.copy(
                        isAgentMode = true,
                        agentSessionId = sessionId,
                        isSwitchingSession = false,
                        isStreaming = agentBackgroundStreaming,
                        isWaitingFirstToken = agentBackgroundStreaming && messages
                            .lastOrNull { msg -> msg.role == MessageRole.ASSISTANT }
                            ?.let { msg -> msg.content.isBlank() && msg.toolCalls.isNullOrEmpty() }
                            == true,
                        currentAssistant = assistant,
                        errors = emptyList(),
                        hasMoreHistory = hasMore,
                        isLoadingMore = false,
                        lastHistoryLoadCount = 0,
                        replyingTo = null,
                        replyQuoteOverride = null,
                        taskCards = emptyMap(),
                        toolCallHistory = emptyList(),
                        agentPlans = restoredAgentPlans,
                        visionAssistedMessageIds = emptySet(),
                        visionProgress = null,
                        sessionPermissionMode = permissionMode,
                        listFirstVisibleItemIndex = messages.lastIndex.coerceAtLeast(0),
                        listFirstVisibleItemScrollOffset = 0,
                        isSessionLocked = true,
                    )
                }
                val model = resultOf { sessionDeps.settings.getSelectedModel() }.getOrNull()
                val weakHint = WeakToolUseDetector.getWeakToolHint(model)
                accessor.update {
                    it.copy(isWeakToolModel = weakHint != null, weakToolHint = weakHint)
                }
                bridge.refreshContext()
            }
        } else {
            accessor.update {
                it.copy(
                    isAgentMode = false,
                    agentSessionId = null,
                    taskCards = emptyMap(),
                    toolCallHistory = emptyList(),
                    agentPlans = emptyMap(),
                    visionAssistedMessageIds = emptySet(),
                    visionProgress = null,
                    isSessionLocked = false,
                    isWeakToolModel = false,
                    weakToolHint = null,
                    agentModeHint = null,
                )
            }
            prevSessionId?.let { sessionDeps.sessionManager.release(it) }
            accessor.snapshot.currentSessionId?.let { sid ->
                sessionDeps.sessionManager.acquire(sid)
                accessor.coroutineScope.launch {
                    val (messages, hasMore) = sessionDeps.messageController.loadMessagesPaged(sid)
                    val permissionMode = sessionDeps.sessionPermissionStore.getMode(
                        sid,
                        sessionDeps.settings.defaultSessionPermissionModeFlow.first(),
                    )
                    val assistantId = sessionRepository.getAssistantId(sid)
                    val assistant = sessionDeps.assistantRepository.getById(assistantId)
                        ?: sessionDeps.assistantRepository.getById("default")
                    val restoredAgentPlans = sessionDeps.messageController.restoreAgentPlansForSession(sid, messages)
                    sessionDeps.stateStore.messages.value = messages
                    val taskBackgroundStreaming = sessionDeps.chatGenerationManager.isStreaming(sid)
                    accessor.update {
                        it.copy(
                            currentAssistant = assistant,
                            isStreaming = taskBackgroundStreaming,
                            isWaitingFirstToken = taskBackgroundStreaming && messages
                                .lastOrNull { msg -> msg.role == MessageRole.ASSISTANT }
                                ?.let { msg -> msg.content.isBlank() && msg.toolCalls.isNullOrEmpty() }
                                == true,
                            hasMoreHistory = hasMore,
                            isLoadingMore = false,
                            lastHistoryLoadCount = 0,
                            agentPlans = restoredAgentPlans,
                            sessionPermissionMode = permissionMode,
                            listFirstVisibleItemIndex = messages.lastIndex.coerceAtLeast(0),
                            listFirstVisibleItemScrollOffset = 0,
                        )
                    }
                    bridge.refreshContext()
                    resultOf { sessionDeps.settings.saveViewedSessionId(sid) }
                        .onError { msg, _ -> Logger.w("ChatVM", "saveViewedSessionId 失败: $msg") }
                }
            }
        }
    }

    /** 切换到指定会话:清理 → 释放/获取引用 → 加载消息/计划/权限 → 恢复审批/outbox。 */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "ComplexCondition")
    fun switchSession(sessionId: String) {
        if (accessor.snapshot.isStreaming) bridge.detachStreaming()
        sessionDeps.onStopTts()
        sessionDeps.onDisposeAsr()
        sessionDeps.onNotifySessionEnd()
        // v1.201: 切换会话清空委派链路 + 暂停状态
        sessionDeps.onClearDelegation()
        // v1.x: 清理旧会话的"本会话允许"临时缓存
        sessionDeps.currentSessionIdForApproval()?.let { sessionDeps.sessionPermissionStore.clearSession(it) }
        val currentSession = accessor.snapshot.currentSessionId
        val currentInput = accessor.snapshot.input
        // 引用计数:释放旧会话 + 获取新会话
        if (currentSession != null && currentSession != sessionId) {
            sessionDeps.sessionManager.release(currentSession)
        }
        sessionDeps.sessionManager.acquire(sessionId)
        // C3: 记录最近浏览历史
        accessor.coroutineScope.launch { sessionDeps.settings.recordSessionViewed(sessionId) }
        // v1.93+: 切换前把当前会话消息快照存入 LRU 缓存(有变体分支则不缓存)
        if (currentSession != null && sessionDeps.stateStore.messages.value.isNotEmpty() &&
            sessionDeps.stateStore.conversationTree.value.userNodes.none {
                it.variants.size > 1 || it.currentVariant?.assistantNodes?.any { a -> a.variants.size > 1 } == true
            }
        ) {
            sessionMemoryCache.put(currentSession, sessionDeps.stateStore.messages.value)
        }
        accessor.coroutineScope.launch {
            if (currentSession != null && currentInput.isNotBlank()) {
                resultOf { sessionDeps.settings.saveChatDraft(currentSession, currentInput) }
                    .onError { msg, _ ->
                        Logger.w("ChatVM", "saveChatDraft failed: $msg")
                        MuseToast.show(sessionDeps.appContext.getString(R.string.chat_draft_save_failed))
                    }
            }
        }
        accessor.coroutineScope.launch {
            // F-15: DeepLink 目标校验 — 会话不存在时回退会话列表
            val exists = resultOf { sessionRepository.getSessionById(sessionId) }.getOrNull() != null
            if (!exists) {
                Logger.w("ChatVM", "switchSession 目标会话不存在,回退会话列表: $sessionId")
                accessor.update {
                    it.copy(
                        currentSessionId = null,
                        isStreaming = false,
                        isWaitingFirstToken = false,
                        streamState = it.streamState.copy(phase = ChatStreamPhase.IDLE),
                    )
                }
                sessionDeps.stateStore.messages.value = emptyList()
                return@launch
            }
            val isBackgroundStreaming = sessionDeps.chatGenerationManager.isStreaming(sessionId)
            val cached = if (!isBackgroundStreaming) sessionMemoryCache.get(sessionId) else null
            val memoryCacheHit = cached != null
            val (messages, hasMore) = if (cached != null) {
                cached to (cached.size >= MESSAGE_PAGE_SIZE)
            } else {
                sessionDeps.messageController.loadMessagesPaged(sessionId)
            }
            val backgroundWaitingForOutput = isBackgroundStreaming && (
                messages.lastOrNull { it.role == MessageRole.ASSISTANT }
                    ?.let { it.content.isBlank() && it.toolCalls.isNullOrEmpty() } == true
                )
            val permissionMode = sessionDeps.sessionPermissionStore.getMode(
                sessionId,
                sessionDeps.settings.defaultSessionPermissionModeFlow.first(),
            )
            val assistantId = sessionRepository.getAssistantId(sessionId)
            val assistant = sessionDeps.assistantRepository.getById(assistantId)
                ?: sessionDeps.assistantRepository.getById("default")
            val restoredAgentPlans = sessionDeps.messageController.restoreAgentPlansForSession(sessionId, messages)
            accessor.update {
                sessionDeps.stateStore.messages.value = messages
                it.copy(
                    currentSessionId = sessionId,
                    activeProviderId = sessionDeps.activeProviderForSession(sessionId),
                    selectedModelId = sessionDeps.selectedModelForSession(sessionId),
                    input = "",
                    hasDraft = false,
                    errors = emptyList(),
                    isDrawerOpen = false,
                    currentAssistant = assistant,
                    memoryCacheHit = memoryCacheHit,
                    deepThinkingEnabled = it.chatPreferences.defaultDeepThinking,
                    hasMoreHistory = hasMore,
                    isLoadingMore = false,
                    lastHistoryLoadCount = 0,
                    isStreaming = isBackgroundStreaming,
                    isWaitingFirstToken = backgroundWaitingForOutput,
                    taskCards = emptyMap(),
                    pendingToolApprovals = emptyList(),
                    toolCallHistory = emptyList(),
                    agentPlans = restoredAgentPlans,
                    sessionsError = null,
                    visionProgress = null,
                    pendingImages = emptyList(),
                    pendingDocuments = emptyList(),
                    replyingTo = null,
                    replyQuoteOverride = null,
                    inputHistory = emptyList(),
                    inputHistoryIndex = null,
                    sessionPermissionMode = permissionMode,
                    listFirstVisibleItemIndex = messages.lastIndex.coerceAtLeast(0),
                    listFirstVisibleItemScrollOffset = 0,
                )
            }
            if (isBackgroundStreaming) {
                sessionMemoryCache.remove(sessionId)
            }
            resultOf { sessionDeps.settings.saveViewedSessionId(sessionId) }
                .onError { msg, _ -> Logger.w("ChatVM", "saveViewedSessionId 失败: $msg") }
            // v1.0.30: 标记会话切换时间戳,供 onAppForeground 判断是否需强制刷新
            sessionDeps.onSessionSwitched(sessionId)
            // v0.45: 刷新上下文 token 占用
            bridge.refreshContext()
            // P0 对话树: 读取上次分支选择快照,重建时恢复用户/助手变体
            val treeSnapshot = sessionDeps.treeSnapshotStore?.load(sessionId)
            sessionDeps.messageController.rebuildConversationTree(previousOverride = treeSnapshot)
            sessionDeps.restorePendingApprovalsForSession(sessionId)
            // 断点续传:检查本会话是否有未完成的工具调用
            val pendingCount = resultOf { PendingToolCallStore.getForChat(sessionId) }
                .onError { msg, t -> Logger.w("ChatVM", "switchSession getForChat 失败: $msg", t) }
                .getOrNull()?.size ?: 0
            if (pendingCount > 0) {
                accessor.update { it.copy(pendingToolCallCount = pendingCount) }
                Logger.i("ChatVM", "switchSession 检测到 $pendingCount 个未完成工具调用,会话=$sessionId")
            } else {
                accessor.update { it.copy(pendingToolCallCount = 0) }
            }
            sessionDeps.requeueOutboxForSession(sessionId)
        }
    }

    companion object {
        private const val MESSAGE_PAGE_SIZE = 50
    }
}
