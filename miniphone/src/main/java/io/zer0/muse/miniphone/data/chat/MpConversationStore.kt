package io.zer0.muse.miniphone.data.chat

import androidx.room.withTransaction
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.dao.MpMessageDao
import io.zer0.muse.miniphone.data.db.dao.MpThreadDao
import io.zer0.muse.miniphone.data.db.dao.MpThreadMemberDao

/**
 * 会话列表上的写操作。
 *
 * 抽成接口是为了外壳 ViewModel 的测试不必为一次委托开一个 Room 库;
 * 真实行为在 [MpConversationStore] 的库测试里跑真 SQL 验证。
 */
interface MpConversationActions {
    /** 置顶 / 取消置顶。 */
    suspend fun setPinned(threadId: String, pinned: Boolean)

    /** 未读清零。 */
    suspend fun markRead(threadId: String)

    /** 删一个会话及其全部消息。 */
    suspend fun deleteThread(threadId: String)

    /** 清空全部会话与消息。 */
    suspend fun clearAll()
}

/**
 * 会话列表上的写操作:置顶、清未读、删会话、一次清空。
 *
 * 这些动作都同时碰两张表(会话行与消息行),放到这里统一在库的事务里做:
 * 界面看到的中间态不该是「会话还在、内容空了」或者反过来。
 * 群会话额外带成员行:删群时跟着一起走,不然会留下一堆指向不存在会话的成员记录。
 * 查询与界面状态归 [io.zer0.muse.miniphone.ui.shell.MiniPhoneShellViewModel],
 * 这里只管写,不持有任何 UI 状态。
 */
class MpConversationStore(
    private val db: MiniPhoneDb,
    private val threadDao: MpThreadDao,
    private val messageDao: MpMessageDao,
    private val memberDao: MpThreadMemberDao,
) : MpConversationActions {

    /** 置顶 / 取消置顶。列表排序由 DAO 的 ORDER BY 保证,这里只改标记。 */
    override suspend fun setPinned(threadId: String, pinned: Boolean) {
        threadDao.setPinned(threadId, pinned)
    }

    /** 未读清零。已经是 0 时这一句什么也不改。 */
    override suspend fun markRead(threadId: String) {
        threadDao.clearUnread(threadId)
    }

    /** 删一个会话,连它名下的全部消息与群成员一起删;别的会话与消息不受影响。 */
    override suspend fun deleteThread(threadId: String) {
        db.withTransaction {
            messageDao.deleteInThread(threadId)
            memberDao.deleteInThread(threadId)
            threadDao.delete(threadId)
        }
    }

    /**
     * 清空聊天记录:会话、消息、群成员三张表一起清空。朋友圈、资料、日记、相册都不在这里,不会被碰到。
     */
    override suspend fun clearAll() {
        db.withTransaction {
            messageDao.deleteAll()
            memberDao.deleteAll()
            threadDao.deleteAll()
        }
    }
}
