package io.zer0.muse.ui.chat

import android.content.Context
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
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
import io.zer0.muse.data.session.SearchResult
import io.zer0.muse.data.session.SessionRepository
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
                    reportError(
                        appContext.getString(
                            R.string.err_chat_misc_move_session_failed,
                            t?.message ?: appContext.getString(R.string.err_chat_unknown),
                        ),
                    )
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
                    } else {
                        null
                    },
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
                    } else {
                        null
                    },
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
        val target = resolveFavoriteTarget(messageId) ?: return
        setFavorite(messageId, !target.favorite)
    }

    /**
     * 把收藏状态设成指定值（幂等：已是目标状态时不再写库，也不报错）。
     *
     * 与 [toggleFavorite] 的区别是不翻转，供外部入口（网页端长按菜单）直接指定“收藏/取消收藏”。
     * 目标不在当前消息列表也不在收藏列表时（跨会话删除后的残留引用），照样写库，只是没有乐观更新。
     */
    fun setFavorite(messageId: Uuid, favorite: Boolean) {
        val target = resolveFavoriteTarget(messageId)
        if (target != null && target.favorite == favorite) return
        if (target != null) applyFavoriteState(messageId, target, favorite)
        accessor.coroutineScope.launch {
            resultOf { sessionRepository.setMessageFavorite(messageId.toString(), favorite) }
                .onError { _, t ->
                    if (target != null) rollbackFavoriteState(messageId, target, favorite)
                    pushFavoriteError(t)
                }
        }
    }

    /** Phase 8.5 修复: 先查当前会话 messages,找不到再查 favoriteMessages(跨会话收藏列表)。 */
    private fun resolveFavoriteTarget(messageId: Uuid): UIMessage? = accessor.messagesSnapshot.firstOrNull { it.id == messageId }
        ?: accessor.snapshot.favoriteMessages.firstOrNull { it.id == messageId }

    /** 收藏/取消收藏的乐观更新(消息列表 + 收藏列表两处同时改)。 */
    private fun applyFavoriteState(messageId: Uuid, target: UIMessage, newFav: Boolean) {
        accessor.updateMessages { list -> list.map { if (it.id == messageId) it.copy(favorite = newFav) else it } }
        accessor.update { st ->
            val newFavs = if (newFav) {
                if (st.favoriteMessages.none { it.id == messageId }) {
                    st.favoriteMessages + target.copy(favorite = true)
                } else {
                    st.favoriteMessages
                }
            } else {
                st.favoriteMessages.filterNot { it.id == messageId }
            }
            st.copy(favoriteMessages = newFavs)
        }
    }

    /** 计算收藏列表的展示顺序(DAO 口径:createdAt 降序)。回滚插回时用它保持位置。 */
    private fun sortedFavorites(list: List<UIMessage>): List<UIMessage> = list.sortedByDescending { it.createdAt }

    /**
     * 收藏写库失败的回滚。
     *
     * 旧实现回滚时去列表里找那条消息,找不到就什么也不做 —— 而取消收藏的回滚正好是
     * "列表里已经没有它"的那种分支,图标就永远停在错误状态。这里直接用 [target] 快照
     * 复原,不依赖当前列表内容。
     */
    private fun rollbackFavoriteState(messageId: Uuid, target: UIMessage, attemptedFav: Boolean) {
        val restored = !attemptedFav
        accessor.updateMessages { list -> list.map { if (it.id == messageId) it.copy(favorite = restored) else it } }
        accessor.update { st ->
            val others = st.favoriteMessages.filterNot { it.id == messageId }
            val rolled = if (restored) sortedFavorites(others + target.copy(favorite = true)) else others
            st.copy(favoriteMessages = rolled)
        }
    }

    private fun pushFavoriteError(t: Throwable?) {
        accessor.update { st ->
            st.copy(
                errors = listOf(
                    ChatError(
                        type = ChatErrorType.UNKNOWN,
                        message = appContext.getString(R.string.err_chat_misc_favorite_failed, t?.message ?: ""),
                    ),
                ),
            )
        }
    }

    /**
     * v2.6.9: 批量收藏的结果反馈。
     *
     * 区分三种情形，避免“点了没反应”：
     *  - 全部已收藏（重复点）
     *  - 全部新增成功
     *  - 部分成功
     */
    fun reportBatchFavorite(added: Int, total: Int) {
        val msg = when {
            added == 0 -> appContext.getString(R.string.chat_favorite_selected_all_exist, total)
            added == total -> appContext.getString(R.string.chat_favorite_selected_ok, added)
            else -> appContext.getString(R.string.chat_favorite_selected_partial, added, total)
        }
        accessor.update { st ->
            st.copy(errors = st.errors + ChatError(type = ChatErrorType.UNKNOWN, message = msg))
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
                            errors = listOf(
                                ChatError(
                                    type = ChatErrorType.UNKNOWN,
                                    message = appContext.getString(R.string.err_chat_misc_set_tag_failed, t?.message ?: ""),
                                ),
                            ),
                        )
                    }
                }
        }
    }

    /** v1.104 U7: 设置当前收藏夹的分组筛选条件。 */
    fun setFavoriteTagFilter(tag: String?) {
        accessor.update { it.copy(favoriteTagFilter = tag) }
    }

    /**
     * 批量收藏:整批走与 [toggleFavorite] 同一条写入路径。
     *
     * 此前 ViewModel 里另写了一段"直调 repository + 手改两个列表"的实现,
     * 与单条 toggle 走的三份状态(messages / favoriteMessages / 缓存失效)
     * 各自为政,失败时也没有回滚。这里把批量路径收敛成循环调 [toggleOne],
     * 一条消息只有一种收藏/取消语义。
     *
     * @return 实际新收藏成功的条数(已收藏的不重复计数,也不重复写库)
     */
    suspend fun favoriteMessages(messageIds: List<Uuid>): Int = messageIds.count { id ->
        val target = accessor.messagesSnapshot.firstOrNull { it.id == id }
            ?: accessor.snapshot.favoriteMessages.firstOrNull { it.id == id }
            ?: return@count false
        !target.favorite && toggleOne(id)
    }

    /** [toggleFavorite] 的挂起版实现:同一套乐观更新 + 失败回滚,供批量路径复用。 */
    private suspend fun toggleOne(messageId: Uuid): Boolean {
        val target = resolveFavoriteTarget(messageId) ?: return false
        val newFav = !target.favorite
        applyFavoriteState(messageId, target, newFav)
        return resultOf { sessionRepository.setMessageFavorite(messageId.toString(), newFav) }
            .onError { _, t ->
                rollbackFavoriteState(messageId, target, newFav)
                pushFavoriteError(t)
            }
            .isSuccess
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
                    st.copy(
                        errors = listOf(
                            ChatError(
                                type = ChatErrorType.UNKNOWN,
                                message = appContext.getString(R.string.err_chat_misc_delete_msg_failed, t?.message ?: ""),
                            ),
                        ),
                    )
                }
            }
        }
    }

    /**
     * U-16: 删除目标消息及其后全部消息(截断语义,文档 §4「连同后续删除」)。
     *
     * 与 [deleteMessage] 的区别:后者仅删除该消息与其树级回复;本方法删除
     * createdAt >= 目标消息的全部消息(无论父子关系),数据层走 sessionRepository.truncateFrom
     * (事务内同步清 FTS 与生成检查点)。乐观更新 + 失败回滚,与单条删除行为一致。
     */
    fun deleteMessagesFrom(messageId: Uuid, sessionId: String) {
        val target = accessor.messagesSnapshot.firstOrNull { it.id == messageId } ?: return
        val fromCreatedAt = target.createdAt
        val removedList = accessor.messagesSnapshot.filter { it.createdAt >= fromCreatedAt }
        if (removedList.isEmpty()) return
        val removedIds = removedList.map { it.id }.toSet()
        accessor.updateMessages { messages -> messages.filterNot { it.id in removedIds } }
        accessor.coroutineScope.launch {
            resultOf {
                sessionRepository.truncateFrom(sessionId, fromCreatedAt)
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
                    st.copy(
                        errors = listOf(
                            ChatError(
                                type = ChatErrorType.UNKNOWN,
                                message = appContext.getString(R.string.err_chat_misc_delete_msg_failed, t?.message ?: ""),
                            ),
                        ),
                    )
                }
            }
        }
    }

    /**
     * v1.0.80 (T-4): 收集要级联删除的消息 id(防环 BFS)。
     *
     * B-15: DB 侧 [SessionRepository.deleteMessage] 用 messageDao.getChildrenByParentId 以
     * `parentGroupId = :parentId OR parentMessageId = :parentId` 关联子回复;UI 快照
     * (UIMessage)仅暴露 parentGroupId(新数据等于父消息 id,等价于 parentMessageId 关联)。
     * 为覆盖以"变体组"挂载的回复(DB 会删而旧逻辑漏删 UI),遍历时除按消息 id 匹配
     * parentGroupId 外,同时把被删消息的 variantGroupId 作为父引用,避免幽灵消息残留。
     */
    private fun collectCascadeIds(root: Uuid): Set<Uuid> {
        val result = linkedSetOf<Uuid>()
        val queue = ArrayDeque<Uuid>()
        queue.add(root)
        val snapshot = accessor.messagesSnapshot
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!result.add(id)) continue
            val idStr = id.toString()
            val refs: Set<String> = buildSet {
                add(idStr)
                snapshot.firstOrNull { it.id == id }?.variantGroupId?.let(::add)
            }
            snapshot.forEach { msg ->
                // v2.5.2: 同时考虑显式父消息引用(parentMessageId) ——
                // 工具展示消息挂在其所属助手消息下,删除助手时需一并带走。
                val parentRef = msg.parentGroupId ?: msg.parentMessageId
                if (parentRef != null && parentRef in refs && msg.id !in result) {
                    queue.add(msg.id)
                }
            }
        }
        // v2.5.2 存量兼底: 旧数据里的工具展示消息没有父关联（字段是本轮才补的），
        // 上面按 parent 找不到。抽出独立方法处理，保持本函数可读。
        appendTrailingOrphanToolDisplays(ordered = snapshot.sortedBy { it.createdAt }, result = result)

        return result
    }

    /**
     * v2.5.2: 存量孤儿工具卡片兼底。
     *
     * 从任一被删消息向后扫，遇到下一条 USER 消息停止；把中间「无父关联且带工具载荷」
     * 的展示消息一并加入删除集，清掉历史遗留的孤儿卡片。
     */
    private fun appendTrailingOrphanToolDisplays(ordered: List<io.zer0.ai.core.UIMessage>, result: MutableSet<Uuid>) {
        if (ordered.isEmpty() || result.isEmpty()) return
        var i = ordered.indexOfLast { it.id in result } + 1
        while (i < ordered.size) {
            val m = ordered[i]
            val hasParent = m.parentGroupId != null || m.parentMessageId != null
            val isToolDisplay = m.toolCallInfo != null || m.toolCalls != null
            val stop = m.role == io.zer0.ai.core.MessageRole.USER || hasParent || !isToolDisplay
            if (stop) break
            result.add(m.id)
            i++
        }
    }

    // ── 管理页 CRUD: Lorebook / PromptInjection / QuickMessage ───────────

    /** v1.97: 懒加载全部 Lorebook 条目。 */
    fun refreshLorebooks() {
        accessor.coroutineScope.launch {
            try {
                val list = lorebookRepository.observeAll().first()
                accessor.update { it.copy(lorebooks = list, lorebooksError = null) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // ST-01: 世界书列表加载失败 → 记录错误态,供 LorebookScreen 展示与重试
                Logger.w(tag, "refreshLorebooks 失败: ${e.message}", e)
                accessor.update { it.copy(lorebooksError = e.message ?: appContext.getString(R.string.common_load_failed)) }
            }
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
