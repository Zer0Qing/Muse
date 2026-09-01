package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.resultOf
import io.zer0.muse.data.chat.ConversationTree
import io.zer0.muse.data.chat.ConversationTreeSnapshotStore
import io.zer0.muse.data.chat.mergeRebuildMessages
import io.zer0.muse.data.chat.orderConversationMessages
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.tools.SkillExecutor
import io.zer0.muse.ui.MessageExpandedState
import io.zer0.muse.ui.taskcard.AgentPlan
import io.zer0.muse.ui.taskcard.restoreAgentPlansFromHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * v1.x: 从 ChatViewModel 抽离的消息加载 Controller。
 *
 * 职责(消息读取与分页,不含流式生成/工具执行):
 *  - [loadMessagesPaged] 初始分页加载(无状态,纯 repository 读取)
 *  - [loadMoreHistory] 上滑加载更早历史(分页锚点用稳定 seq,合并去重,恢复计划)
 *  - [restoreAgentPlansForSession] 从落库的 tool 消息重放 Agent 计划
 *
 * 共享 state 走 [ChatStateAccessor](_state/_messages),不复制 StateFlow 或 Room 事实源。
 * 树重建经 [onTreeRebuild] 回调(ConversationTree StateFlow 仍在宿主),不反向依赖 ChatViewModel。
 */
@Suppress("TooManyFunctions")
class ChatMessageController(
    private val accessor: ChatStateAccessor,
    private val sessionRepository: SessionRepository,
    private val skillExecutor: SkillExecutor,
    internal val treeState: MutableStateFlow<ConversationTree>,
    private val treeSnapshotStore: ConversationTreeSnapshotStore?,
    private val messagePageSize: Int = 50,
) {

    /** 初始分页加载:取最近 [messagePageSize] 条;返回 (升序消息列表, 是否还有更早历史)。 */
    suspend fun loadMessagesPaged(sessionId: String): Pair<List<UIMessage>, Boolean> {
        val total = sessionRepository.getMessageCount(sessionId)
        if (total == 0) return emptyList<UIMessage>() to false
        val limit = minOf(messagePageSize, total)
        val messages = sessionRepository.getRecentMessages(sessionId, limit)
        return messages to (total > messages.size)
    }

    /**
     * 从已持久化的工具展示消息恢复 Agent 计划。
     * 计划本体只存在 SkillAgentToolsImpl 内存缓存,切换会话/重启后需按消息顺序重放恢复。
     */
    suspend fun restoreAgentPlansForSession(
        sessionId: String,
        visibleMessages: List<UIMessage>,
    ): Map<String, AgentPlan> {
        val persistedToolMessages = resultOf {
            sessionRepository.getToolCallMessages(sessionId)
        }.getOrNull().orEmpty()
        val merged = linkedMapOf<String, UIMessage>()
        // 数据库历史先建立稳定顺序,当前窗口再覆盖同 id 的旧投影内容。
        persistedToolMessages.forEach { merged[it.id.toString()] = it }
        visibleMessages.forEach { merged[it.id.toString()] = it }
        val history = orderConversationMessages(merged.values.toList())
        val plans = restoreAgentPlansFromHistory(history, sessionId)
        // UI 投影和工具执行缓存必须同时恢复；恢复只替换当前会话，避免并行会话串计划。
        skillExecutor.restoreActivePlans(plans, sessionId)
        return plans
    }

    /**
     * 上滑加载更早历史:以当前最早消息的稳定 seq 为锚点取更早 [messagePageSize] 条,
     * 前置插入 messages,并刷新 hasMoreHistory 与计划恢复。
     * 流式期间/加载中/已到底时跳过。
     */
    fun loadMoreHistory() {
        val state = accessor.snapshot
        // 流式期间/加载中/已到底 → 跳过
        if (state.isStreaming || state.isLoadingMore || !state.hasMoreHistory) return
        val sessionId = if (state.isAgentMode) state.agentSessionId else state.currentSessionId
        val firstMsg = accessor.messagesSnapshot.firstOrNull()
        // 无有效会话/无消息 → 跳过
        if (sessionId == null || firstMsg == null) return
        val sessionIdSafe = sessionId
        val firstNonEmpty = firstMsg
        accessor.coroutineScope.launch {
            accessor.update { it.copy(isLoadingMore = true) }
            // 分页锚点用稳定序列(与 DAO 排序同源),不用会被 REPLACE 刷新的 createdAt。
            val beforeSeq = if (firstNonEmpty.commitSeq > 0) firstNonEmpty.commitSeq else firstNonEmpty.seq
            val older = sessionRepository.getOlderMessages(sessionIdSafe, beforeSeq, messagePageSize)
            if (older.isEmpty()) {
                accessor.update { it.copy(hasMoreHistory = false, isLoadingMore = false) }
                return@launch
            }
            // 重新读取最新列表,防止加载期间流式追加的新消息被覆盖
            val merged = older + accessor.messagesSnapshot
            val restoredAgentPlans = restoreAgentPlansForSession(sessionIdSafe, merged)
            accessor.updateMessages { merged }
            accessor.update {
                it.copy(
                    hasMoreHistory = older.size >= messagePageSize,
                    isLoadingMore = false,
                    lastHistoryLoadCount = older.size,
                    agentPlans = restoredAgentPlans,
                )
            }
            rebuildConversationTree()
        }
    }
    fun selectUserVariant(userGroupId: String, variantIndex: Int) {
        val tree = treeState.value
        val node = tree.userNodes.firstOrNull { user ->
            (user.currentVariant?.message?.variantGroupId ?: user.groupId) == userGroupId
        } ?: return
        val updated = tree.selectUserVariant(node.userId, variantIndex)
        treeState.value = updated
        accessor.updateMessages { updated.displayMessages }
    }

    /**
     * 切换助手回复变体（P0 对话树）：作用域仅限当前用户变体下的指定助手组。
     */
    fun selectAssistantVariant(userGroupId: String, assistantGroupId: String, index: Int) {
        val updated = treeState.value.selectAssistantVariant(userGroupId, assistantGroupId, index)
        treeState.value = updated
        accessor.updateMessages { updated.displayMessages }
    }

    /** v1.0.63: 把归一化后的分支索引/计数回写数据库,修复历史坏数据。 */
    suspend fun healBranchCounts(
        sessionId: String,
        original: List<UIMessage>,
        tree: ConversationTree,
    ) {
        val originalById = original.associateBy { it.id.toString() }
        val normalizedAll = buildList {
            tree.userNodes.forEach { user ->
                user.variants.forEach { add(it.message) }
                user.variants.forEach { variant ->
                    variant.assistantNodes.forEach { assistant -> assistant.variants.forEach { add(it) } }
                }
            }
        }
        val changed = normalizedAll.filter { msg ->
            val old = originalById[msg.id.toString()]
            old != null && (old.variantIndex != msg.variantIndex || old.variantCount != msg.variantCount)
        }
        if (changed.isEmpty()) return
        changed.forEach { sessionRepository.upsertMessage(sessionId, it) }
    }

    /** v0.44: 当前树所属会话 id,防止切会话/切 Agent 时把上一个会话的分支带过来。 */
    @Volatile
    private var treeSessionId: String? = null

    /**
     * P0 对话树: 从当前扁平消息列表重建树,并同步显示列表。
     * 流式期间 messages 是扁平事实源;发送/重试/编辑/切会话/流结束等稳定点调用。
     */
    fun rebuildConversationTree(previousOverride: ConversationTree? = null) {
        val sessionId = accessor.snapshot.currentSessionId ?: accessor.snapshot.agentSessionId
        val currentTree = if (sessionId != null && treeSessionId == sessionId) {
            treeState.value
        } else {
            ConversationTree()
        }
        // 旧树 flat 保留全部重试/编辑分支,current 保留最新内容与新追加消息,二者按 id 合并。
        val messages = mergeRebuildMessages(currentTree, accessor.messagesSnapshot)
        if (messages.isEmpty()) {
            treeState.value = ConversationTree()
            treeSessionId = sessionId
            return
        }
        val previous = previousOverride ?: currentTree
        val tree = ConversationTree.build(messages, previous)
        treeState.value = tree
        treeSessionId = sessionId
        accessor.updateMessages { tree.displayMessages }
        if (sessionId != null) {
            accessor.coroutineScope.launch(Dispatchers.IO) {
                treeSnapshotStore?.save(sessionId, tree)
                healBranchCounts(sessionId, messages, tree)
            }
        }
    }

    /** 编辑 assistant 消息内容(乐观更新消息列表 + 落库)。 */
    fun editAssistantMessage(messageId: kotlin.uuid.Uuid, newContent: String) {
        val snapshot = accessor.snapshot
        val messages = accessor.messagesSnapshot
        val index = messages.indexOfFirst { it.id == messageId && it.role == MessageRole.ASSISTANT }
        val sessionId = snapshot.currentSessionId
        // 流式中/无会话/无匹配 assistant 消息 → 跳过
        if (snapshot.isStreaming || sessionId == null || index == -1) return
        val sessionIdSafe = sessionId
        val updated = messages[index].copy(content = newContent, reasoning = null)
        accessor.updateMessages { messages.toMutableList().apply { set(index, updated) } }
        accessor.update { it.copy(errors = emptyList()) }
        accessor.coroutineScope.launch {
            sessionRepository.updateMessageContent(sessionIdSafe, messageId, newContent)
        }
    }

    /** 清空 lastHistoryLoadCount(UI 滚动补偿后调用,避免重组重复跳转)。 */
    fun clearHistoryLoadCount() {
        if (accessor.snapshot.lastHistoryLoadCount != 0) {
            accessor.update { it.copy(lastHistoryLoadCount = 0) }
        }
    }

    /** 缓存列表滚动位置,切页/后台后恢复。 */
    fun onListScrollPositionChanged(index: Int, offset: Int) {
        accessor.update {
            it.copy(listFirstVisibleItemIndex = index, listFirstVisibleItemScrollOffset = offset)
        }
    }

    /** v1.45: 切换指定消息 mood 块的展开/折叠状态。 */
    fun toggleMessageMoodExpanded(messageId: String) {
        accessor.update { current ->
            val currentState = current.messageExpandedStates[messageId] ?: MessageExpandedState()
            val default = current.chatPreferences.moodExpandedByDefault
            val newExpanded = !(currentState.isMoodExpanded ?: default)
            current.copy(
                messageExpandedStates = current.messageExpandedStates +
                    (messageId to currentState.copy(isMoodExpanded = newExpanded)),
            )
        }
    }

    /** v1.45: 切换指定消息 reasoning 块的展开/折叠状态。 */
    fun toggleMessageReasoningExpanded(messageId: String) {
        accessor.update { current ->
            val currentState = current.messageExpandedStates[messageId] ?: MessageExpandedState()
            val default = current.chatPreferences.reasoningExpandedByDefault
            val newExpanded = !(currentState.isReasoningExpanded ?: default)
            current.copy(
                messageExpandedStates = current.messageExpandedStates +
                    (messageId to currentState.copy(isReasoningExpanded = newExpanded)),
            )
        }
    }

    /** v1.64: 切换指定消息 reflection 块的展开/折叠状态。 */
    fun toggleMessageReflectionExpanded(messageId: String) {
        accessor.update { current ->
            val currentState = current.messageExpandedStates[messageId] ?: MessageExpandedState()
            val default = current.chatPreferences.reflectionExpandedByDefault
            val newExpanded = !(currentState.isReflectionExpanded ?: default)
            current.copy(
                messageExpandedStates = current.messageExpandedStates +
                    (messageId to currentState.copy(isReflectionExpanded = newExpanded)),
            )
        }
    }
}
