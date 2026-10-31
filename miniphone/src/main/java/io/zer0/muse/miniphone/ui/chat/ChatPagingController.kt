package io.zer0.muse.miniphone.ui.chat

import io.zer0.muse.miniphone.data.db.dao.MpMessageDao
import io.zer0.muse.miniphone.data.db.dao.MpThreadDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 一个会话的消息窗口:进页、首屏、向上翻页、未读清零。
 *
 * 游标就是已加载条数 —— 页内消息恒为最新的一段,更早的从它后面接着取。
 * 所以每次挪动窗口都在 [ChatMessageWriter] 那把闸里做:
 * 读游标与查询之间要是插进一条新消息,倒序窗口整体挪一位,这一页就会和前页重一条。
 *
 * 会话行读出来时顺带把「是不是群、群名叫什么」写进状态:这两件事只在这里读得到,
 * 且与是否群无关的界面分支都靠它们。
 *
 * @param state 界面状态;窗口相关的三个字段(messages / hasMore / isLoadingMore)由本类写
 * @param onThreadOpened 会话行读出来之后回调,界面据此补角色名、头像与群成员
 */
internal class ChatPagingController(
    private val threadId: String,
    private val messageDao: MpMessageDao,
    private val threadDao: MpThreadDao,
    private val writer: ChatMessageWriter,
    private val state: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope,
    private val onThreadOpened: () -> Unit,
) {

    /** 进页:取会话里的角色 id、清未读、拉最新一页。 */
    fun open() {
        scope.launch { openThread() }
    }

    /** 滚到顶时加载更早的一页。没有更多、或上一页还在路上,直接忽略。 */
    fun loadOlder() {
        val current = state.value
        if (!current.hasMore || current.isLoadingMore) return
        state.update { it.copy(isLoadingMore = true) }
        scope.launch { loadOlderPage() }
    }

    /**
     * 进页时清未读。
     *
     * 同一个会话退出再进会复用同一个 ViewModel,[open] 不会重跑,
     * 所以「进页清未读」不能只靠打开时那一次。
     */
    fun markRead() {
        scope.launch { threadDao.clearUnread(threadId) }
    }

    private suspend fun openThread() {
        val thread = threadDao.getThread(threadId)
        if (thread != null) {
            state.update {
                it.copy(characterId = thread.characterId, isGroup = thread.isGroup, groupTitle = thread.title)
            }
            onThreadOpened()
        }
        threadDao.clearUnread(threadId)
        loadFirstPage()
    }

    /**
     * 首屏:倒序取一页再翻正;`hasMore` 只看这一页有没有取满。
     *
     * 查询与写回界面在同一把闸里 —— 中途落进一条新消息,写回就会把它盖掉。
     */
    private suspend fun loadFirstPage() = writer.withWriteLock {
        val page = messageDao.pageMessages(threadId, CHAT_PAGE_SIZE, offset = 0)
        state.update {
            it.copy(
                messages = page.asReversed().map { item -> item.toChatMessageUi() },
                hasMore = page.size == CHAT_PAGE_SIZE,
                isLoadingMore = false,
            )
        }
    }

    /** 更早的一页接在已加载的那段前面;游标同样在闸里读。 */
    private suspend fun loadOlderPage() = writer.withWriteLock {
        val current = state.value
        val page = messageDao.pageMessages(threadId, CHAT_PAGE_SIZE, offset = current.messages.size)
        val older = page.asReversed().map { item -> item.toChatMessageUi() }
        state.update {
            it.copy(
                messages = older + current.messages,
                hasMore = page.size == CHAT_PAGE_SIZE,
                isLoadingMore = false,
            )
        }
    }
}
