package io.zer0.muse.ui.chat

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.lorebook.LorebookEntity
import io.zer0.muse.data.lorebook.LorebookRepository
import io.zer0.muse.data.promptinjection.PromptInjectionEntity
import io.zer0.muse.data.promptinjection.PromptInjectionRepository
import io.zer0.muse.data.quickmsg.QuickMessageEntity
import io.zer0.muse.data.quickmsg.QuickMessageRepository
import io.zer0.muse.data.session.FolderRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.data.session.SearchResult
import io.zer0.muse.ui.ChatError
import io.zer0.muse.ui.ChatErrorType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * v1.105 阶段 2: 从 ChatViewModel 抽离的杂项 Coordinator。
 *
 * 合并 4 个低耦合职责域(各自方法数不多,单独建类收益不大):
 *  - 文件夹分组 CRUD(createFolder / renameFolder / deleteFolder / toggleFolderExpanded / moveSessionToFolder / togglePinned)
 *  - 搜索(updateSearchQuery / search / clearSearch)
 *  - 收藏操作(toggleFavorite / setMessageFavoriteTag / setFavoriteTagFilter / deleteMessage)
 *  - 管理页 CRUD(Lorebook / PromptInjection / QuickMessage 的 refresh/save/delete)
 *
 * 这些方法不调用 ChatViewModel 的核心流式方法(detachStreaming / launchStream / refreshContextInfo),
 * 只读写 state + 调 repository,适合统一委托。
 */
class ChatMiscCoordinator(
    private val accessor: ChatStateAccessor,
    private val sessionRepository: SessionRepository,
    private val folderRepository: FolderRepository,
    private val lorebookRepository: LorebookRepository,
    private val promptInjectionRepository: PromptInjectionRepository,
    private val quickMessageRepository: QuickMessageRepository,
    private val assistantRepository: AssistantRepository,
    private val appContext: Context,
) {

    private val tag = "ChatVM"

    // ── 文件夹分组 CRUD ──────────────────────────────────────────────────

    /** 新建文件夹。 */
    fun createFolder(name: String, reportError: (String) -> Unit) {
        accessor.coroutineScope.launch {
            resultOf { folderRepository.createFolder(name) }
                .onError { msg, t -> reportError(appContext.getString(R.string.err_chat_misc_create_folder_failed, t?.message ?: "")) }
        }
    }

    /** 重命名文件夹。 */
    fun renameFolder(id: String, name: String, reportError: (String) -> Unit) {
        accessor.coroutineScope.launch {
            resultOf { folderRepository.renameFolder(id, name) }
                .onError { msg, t -> reportError(appContext.getString(R.string.err_chat_misc_rename_folder_failed, t?.message ?: "")) }
        }
    }

    /** 删除文件夹(关联会话移到未分组)。 */
    fun deleteFolder(id: String, reportError: (String) -> Unit) {
        accessor.coroutineScope.launch {
            resultOf {
                accessor.snapshot.sessions.filter { it.folderId == id }.forEach { s ->
                    folderRepository.moveSessionToFolder(s.id, null)
                }
                folderRepository.deleteFolder(id)
            }.onError { msg, t -> reportError(appContext.getString(R.string.err_chat_misc_delete_folder_failed, t?.message ?: "")) }
        }
    }

    /** 切换文件夹展开/折叠状态。 */
    fun toggleFolderExpanded(id: String, expanded: Boolean) {
        accessor.coroutineScope.launch {
            resultOf { folderRepository.setExpanded(id, expanded) }
        }
    }

    /** 移动会话到文件夹(folderId=null = 移到未分组)。 */
    fun moveSessionToFolder(sessionId: String, folderId: String?, reportError: (String) -> Unit) {
        accessor.coroutineScope.launch {
            resultOf { folderRepository.moveSessionToFolder(sessionId, folderId) }
                .onError { msg, t ->
                    Logger.e(tag, "moveSessionToFolder failed", t)
                    reportError(appContext.getString(R.string.err_chat_misc_move_session_failed, t?.message ?: appContext.getString(R.string.err_chat_unknown)))
                }
        }
    }

    /** P0-1 修复: 切换会话置顶状态。 */
    fun togglePinned(sessionId: String, reportError: (String) -> Unit) {
        accessor.coroutineScope.launch {
            val session = accessor.snapshot.sessions.find { it.id == sessionId } ?: return@launch
            resultOf { sessionRepository.setSessionPinned(sessionId, !session.pinned) }
                .onError { msg, t -> reportError(appContext.getString(R.string.err_chat_misc_toggle_pin_failed, t?.message ?: "")) }
        }
    }

    /** B7-05: 置顶会话拖拽排序持久化。 */
    fun reorderPinnedSessions(ids: List<String>, reportError: (String) -> Unit) {
        accessor.coroutineScope.launch {
            resultOf { sessionRepository.reorderPinnedSessions(ids) }
                .onError { msg, t -> reportError(appContext.getString(R.string.err_chat_misc_reorder_pinned_failed, t?.message ?: "")) }
        }
    }

    // ── 搜索 ─────────────────────────────────────────────────────────────

    /** 更新搜索框文本。空文本时清空结果。 */
    fun updateSearchQuery(query: String) {
        accessor.update { it.copy(searchQuery = query) }
        if (query.isBlank()) {
            accessor.update {
                it.copy(
                    searchResults = emptyList(),
                    isSearching = false,
                    messageResults = emptyList(),
                    isSearchingMessages = false,
                    searchError = null,
                )
            }
        }
    }

    /** 执行搜索。空查询忽略。 */
    fun search() {
        val query = accessor.snapshot.searchQuery.trim()
        if (query.isEmpty()) return
        accessor.update { it.copy(isSearching = true, searchError = null) }
        accessor.coroutineScope.launch {
            var failed = false
            var detail: String? = null
            val results = try {
                kotlinx.coroutines.withTimeoutOrNull(5000L) {
                    sessionRepository.searchMessages(query)
                } ?: emptyList<SearchResult>().also {
                    failed = true
                    detail = appContext.getString(R.string.err_chat_network_timeout)
                }
            } catch (t: Throwable) {
                Logger.w(tag, "Search failed: ${t.message}")
                failed = true
                detail = t.message ?: appContext.getString(R.string.err_chat_unknown)
                emptyList()
            }
            accessor.update {
                it.copy(
                    searchResults = results,
                    isSearching = false,
                    searchError = if (failed) {
                        appContext.getString(R.string.err_chat_request_failed, detail ?: appContext.getString(R.string.err_chat_unknown))
                    } else null,
                )
            }
        }
    }

    /** 清空搜索状态。 */
    fun clearSearch() {
        accessor.update {
            it.copy(
                searchQuery = "",
                searchResults = emptyList(),
                isSearching = false,
                // v2.x: 同步清空消息内容搜索结果(切回会话 Tab 时也清空,避免残留)
                messageResults = emptyList(),
                isSearchingMessages = false,
                searchError = null,
            )
        }
    }

    // ── v2.x: 消息内容搜索(Tab=1) ──────────────────────────────────────

    /** v2.x: 切换搜索页 Tab(0=会话, 1=消息内容)。 */
    fun switchSearchTab(tab: Int) {
        accessor.update { it.copy(searchTab = tab, searchError = null) }
    }

    /**
     * v2.x: 执行消息内容搜索(FTS4 + snippet,失败回退 LIKE)。
     *
     * 与 [search] 区别:走 [SessionRepository.searchMessageContentFlow](直接返回 snippet 片段),
     * 结果存入 [io.zer0.muse.ui.ChatUiState.messageResults],供 SearchScreen Tab=1 展示。
     * 空查询清空结果。
     */
    fun searchMessageContent() {
        val query = accessor.snapshot.searchQuery.trim()
        if (query.isEmpty()) {
            accessor.update { it.copy(messageResults = emptyList(), isSearchingMessages = false, searchError = null) }
            return
        }
        accessor.update { it.copy(isSearchingMessages = true, searchError = null) }
        accessor.coroutineScope.launch {
            var failed = false
            var detail: String? = null
            val results = try {
                kotlinx.coroutines.withTimeoutOrNull(5000L) {
                    sessionRepository.searchMessageContentFlow(query).first()
                } ?: emptyList<SearchResult>().also {
                    failed = true
                    detail = appContext.getString(R.string.err_chat_network_timeout)
                }
            } catch (t: Throwable) {
                // v1.x: 协程取消(跳转/退出搜索页)必须重抛,不显示为搜索失败
                if (t is kotlinx.coroutines.CancellationException) throw t
                Logger.w(tag, "Message content search failed: ${t.message}")
                failed = true
                detail = t.message ?: appContext.getString(R.string.err_chat_unknown)
                emptyList()
            }
            accessor.update {
                it.copy(
                    messageResults = results,
                    isSearchingMessages = false,
                    searchError = if (failed) {
                        appContext.getString(R.string.err_chat_request_failed, detail ?: appContext.getString(R.string.err_chat_unknown))
                    } else null,
                )
            }
        }
    }

    /**
     * v2.x: 设置目标消息(从搜索结果点击跳转用)。
     *
     * ChatScreen 进入会话后会读取 [io.zer0.muse.ui.ChatUiState.targetMessageId]
     * 滚动到该消息,并依据 [io.zer0.muse.ui.ChatUiState.highlightedMessageId]
     * 短暂高亮。滚动定位完成后由 [consumeTargetMessage] 清空 targetMessageId;
     * 高亮持续约 2.5s 后由 [clearHighlightedMessage] 清空。
     *
     * @param messageId 目标消息 id(null = 清空状态)
     * @param query 搜索关键词(用于 MessageBubble 内文本高亮,null = 不高亮文本)
     */
    fun setTargetMessage(messageId: String?, query: String?) {
        accessor.update {
            it.copy(
                targetMessageId = messageId,
                searchHighlightQuery = query,
                highlightedMessageId = messageId,
            )
        }
    }

    /** v2.x: 消费目标消息 id(滚动定位完成后调用,避免重复触发滚动)。 */
    fun consumeTargetMessage() {
        accessor.update { it.copy(targetMessageId = null) }
    }

    /** v2.x: 清空高亮消息 id(高亮窗口期结束后调用,停止高亮闪烁)。 */
    fun clearHighlightedMessage() {
        accessor.update {
            it.copy(
                highlightedMessageId = null,
                searchHighlightQuery = null,
            )
        }
    }

    // ── 收藏操作 ─────────────────────────────────────────────────────────

    /** 切换消息收藏状态(乐观更新 + 失败回滚)。 */
    fun toggleFavorite(messageId: Uuid) {
        // Phase 8.5 修复: 先查当前会话 messages,找不到再查 favoriteMessages(跨会话收藏列表)
        val target = accessor.messagesSnapshot.firstOrNull { it.id == messageId }
            ?: accessor.snapshot.favoriteMessages.firstOrNull { it.id == messageId }
            ?: return
        val idStr = messageId.toString()
        val newFav = !target.favorite
        val newMessages = accessor.messagesSnapshot.map { if (it.id == messageId) it.copy(favorite = newFav) else it }
        accessor.updateMessages { newMessages }
        accessor.update { st ->
            val newFavs = if (newFav) {
                if (st.favoriteMessages.none { it.id == messageId }) st.favoriteMessages + target.copy(favorite = newFav)
                else st.favoriteMessages
            } else {
                st.favoriteMessages.filterNot { it.id == messageId }
            }
            st.copy(favoriteMessages = newFavs)
        }
        accessor.coroutineScope.launch {
            resultOf { sessionRepository.setMessageFavorite(idStr, newFav) }
                .onError { msg, t ->
                    val rolled = accessor.messagesSnapshot.map {
                        if (it.id == messageId) it.copy(favorite = !newFav) else it
                    }
                    accessor.updateMessages { rolled }
                    accessor.update { st ->
                        val rolledFavs = if (newFav) {
                            st.favoriteMessages.filterNot { it.id == messageId }
                        } else {
                            val tgt = st.favoriteMessages.firstOrNull { it.id == messageId }
                            if (tgt != null) st.favoriteMessages + tgt.copy(favorite = !newFav)
                            else st.favoriteMessages
                        }
                        st.copy(favoriteMessages = rolledFavs, errors = listOf(ChatError(type = ChatErrorType.UNKNOWN, message = appContext.getString(R.string.err_chat_misc_favorite_failed, t?.message ?: ""))))
                    }
                }
        }
    }

    /** v1.104 U7: 设置收藏分组标签(null = 移到未分组)。 */
    fun setMessageFavoriteTag(messageId: Uuid, tag: String?) {
        val target = accessor.snapshot.favoriteMessages.firstOrNull { it.id == messageId } ?: return
        val oldTag = target.favoriteTag
        val newTag = tag?.trim()?.takeIf { it.isNotEmpty() }
        if (oldTag == newTag) return
        val idStr = messageId.toString()
        accessor.update { st ->
            val newFavs = st.favoriteMessages.map {
                if (it.id == messageId) it.copy(favoriteTag = newTag) else it
            }
            st.copy(favoriteMessages = newFavs)
        }
        accessor.coroutineScope.launch {
            resultOf { sessionRepository.setMessageFavoriteTag(idStr, newTag) }
                .onError { msg, t ->
                    accessor.update { st ->
                        val rolled = st.favoriteMessages.map {
                            if (it.id == messageId) it.copy(favoriteTag = oldTag) else it
                        }
                        st.copy(
                            favoriteMessages = rolled,
                            errors = listOf(ChatError(type = ChatErrorType.UNKNOWN, message = appContext.getString(R.string.err_chat_misc_set_tag_failed, t?.message ?: ""))),
                        )
                    }
                }
        }
    }

    /** v1.104 U7: 设置当前收藏夹的分组筛选条件。 */
    fun setFavoriteTagFilter(tag: String?) {
        accessor.update { it.copy(favoriteTagFilter = tag) }
    }

    /** v2.0: 设置预设分类筛选(全部/灵感/代码/学习/自定义)。设 null = 全部。 */
    fun setFavoriteGroup(group: String?) {
        accessor.update { it.copy(favoriteGroup = group, favoriteTagFilter = null) }
    }

    /** v1.48: 删除单条消息(乐观更新 + 失败回滚)。 */
    fun deleteMessage(messageId: Uuid) {
        if (accessor.messagesSnapshot.none { it.id == messageId }) return
        val idStr = messageId.toString()
        // v1.0.80 (T-4): 级联删除 UI 上的子消息(assistant 回复),与 DB 级联删除一致,
        // 否则删 user 消息后其回复残留在列表,切回会话才消失(闪烁/错位)。
        val removedIds = collectCascadeIds(messageId)
        val removedList = accessor.messagesSnapshot.filter { it.id in removedIds }
        accessor.updateMessages { messages -> messages.filterNot { it.id in removedIds } }
        accessor.coroutineScope.launch {
            resultOf {
                sessionRepository.deleteMessage(idStr)
            }.onError { msg, t ->
                // 回滚: 把被删消息按 createdAt 插回原位,不覆盖删除后新 append 的消息。
                val rolled = accessor.messagesSnapshot.toMutableList()
                removedList.sortedBy { it.createdAt }.forEach { removedMsg ->
                    val idx = rolled.indexOfFirst { it.createdAt > removedMsg.createdAt }
                        .let { if (it < 0) rolled.size else it }
                    rolled.add(idx, removedMsg)
                }
                accessor.updateMessages { rolled }
                accessor.update { st ->
                    st.copy(errors = listOf(ChatError(type = ChatErrorType.UNKNOWN, message = appContext.getString(R.string.err_chat_misc_delete_msg_failed, t?.message ?: ""))))
                }
            }
        }
    }

    /** v1.0.80 (T-4): 收集要级联删除的消息 id(防环 BFS,按 parentGroupId 找子回复)。 */
    private fun collectCascadeIds(root: Uuid): Set<Uuid> {
        val result = linkedSetOf<Uuid>()
        val queue = ArrayDeque<Uuid>()
        queue.add(root)
        val snapshot = accessor.messagesSnapshot
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!result.add(id)) continue
            val idStr = id.toString()
            snapshot.forEach { msg ->
                if (msg.parentGroupId == idStr && msg.id !in result) queue.add(msg.id)
            }
        }
        return result
    }

    // ── 管理页 CRUD: Lorebook / PromptInjection / QuickMessage ───────────

    /** v1.97: 懒加载全部 Lorebook 条目。 */
    fun refreshLorebooks() {
        accessor.coroutineScope.launch {
            val list = lorebookRepository.observeAll().first()
            accessor.update { it.copy(lorebooks = list) }
        }
    }

    fun saveLorebook(entity: LorebookEntity) {
        accessor.coroutineScope.launch {
            lorebookRepository.upsert(entity.copy(updatedAt = System.currentTimeMillis()))
            refreshLorebooks()
        }
    }

    fun deleteLorebook(id: String) {
        accessor.coroutineScope.launch {
            lorebookRepository.delete(id)
            refreshLorebooks()
        }
    }

    /** v1.97: 懒加载全部 PromptInjection 条目。 */
    fun refreshPromptInjections() {
        accessor.coroutineScope.launch {
            val list = promptInjectionRepository.observeAll().first()
            accessor.update { it.copy(promptInjections = list) }
        }
    }

    fun savePromptInjection(entity: PromptInjectionEntity) {
        accessor.coroutineScope.launch {
            promptInjectionRepository.upsert(entity.copy(updatedAt = System.currentTimeMillis()))
            refreshPromptInjections()
        }
    }

    fun deletePromptInjection(id: String) {
        accessor.coroutineScope.launch {
            promptInjectionRepository.delete(id)
            refreshPromptInjections()
        }
    }

    /** v1.97: 懒加载全部 QuickMessage 条目。 */
    fun refreshAllQuickMessages() {
        accessor.coroutineScope.launch {
            val list = quickMessageRepository.observeAll().first()
            accessor.update { it.copy(allQuickMessages = list) }
        }
    }

    fun saveQuickMessage(entity: QuickMessageEntity) {
        accessor.coroutineScope.launch {
            quickMessageRepository.upsert(entity.copy(updatedAt = System.currentTimeMillis()))
            refreshAllQuickMessages()
        }
    }

    fun deleteQuickMessage(id: String) {
        accessor.coroutineScope.launch {
            quickMessageRepository.delete(id)
            refreshAllQuickMessages()
        }
    }
}
