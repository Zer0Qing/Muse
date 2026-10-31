package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import kotlinx.coroutines.flow.Flow

/** 会话列表的读写。 */
@Dao
interface MpThreadDao {
    /** 置顶在前,其余按最后一条消息时间倒序。 */
    @Query("SELECT * FROM mp_thread ORDER BY pinned DESC, lastMessageAt DESC, id ASC")
    fun observeThreads(): Flow<List<MpThreadEntity>>

    @Query("SELECT * FROM mp_thread WHERE id = :threadId LIMIT 1")
    suspend fun getThread(threadId: String): MpThreadEntity?

    /**
     * 按角色找会话:通讯录点一个人时用它判断「已有一对一」还是「得新开一个」。
     *
     * 同一角色只应该有一条会话,真出了多条时取最早建立的那条(主键升序),不让点击落到随机一条上。
     * 群会话的 characterId 也是某个角色的 id(第一个成员),但它不是「这个人的私信」,
     * 所以这里必须把群排掉:否则点一个当过群主的人,会直接掉进那个群。
     */
    @Query("SELECT * FROM mp_thread WHERE characterId = :characterId AND isGroup = 0 ORDER BY id ASC LIMIT 1")
    suspend fun findThreadByCharacter(characterId: String): MpThreadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(thread: MpThreadEntity)

    @Query("UPDATE mp_thread SET lastMessagePreview = :preview, lastMessageAt = :at WHERE id = :threadId")
    suspend fun updateLastMessage(threadId: String, preview: String?, at: Long)

    /** 改群名,同时记下「这次是不是用户自己改的」:[customized] 置 1 之后就不会再自动重算。 */
    @Query("UPDATE mp_thread SET title = :title, titleCustomized = :customized WHERE id = :threadId")
    suspend fun updateTitle(threadId: String, title: String, customized: Boolean)

    /** 改「找人开口用的那个角色」:群里第一个能说话的人被移出后,要换成新的第一个。 */
    @Query("UPDATE mp_thread SET characterId = :characterId WHERE id = :threadId")
    suspend fun updateCharacter(threadId: String, characterId: String)

    @Query("SELECT unreadCount FROM mp_thread WHERE id = :threadId")
    suspend fun unreadCount(threadId: String): Int?

    @Query("UPDATE mp_thread SET unreadCount = unreadCount + 1 WHERE id = :threadId")
    suspend fun increaseUnread(threadId: String)

    @Query("UPDATE mp_thread SET unreadCount = :count WHERE id = :threadId")
    suspend fun setUnread(threadId: String, count: Int)

    @Query("UPDATE mp_thread SET unreadCount = 0 WHERE id = :threadId")
    suspend fun clearUnread(threadId: String)

    /** 置顶/取消置顶。列表排序读的就是这一列。 */
    @Query("UPDATE mp_thread SET pinned = :pinned WHERE id = :threadId")
    suspend fun setPinned(threadId: String, pinned: Boolean)

    @Query("DELETE FROM mp_thread WHERE id = :threadId")
    suspend fun delete(threadId: String)

    /** 清空全部会话行。消息表要一起清,由 [io.zer0.muse.miniphone.data.chat.MpConversationStore] 统一事务。 */
    @Query("DELETE FROM mp_thread")
    suspend fun deleteAll()
}
