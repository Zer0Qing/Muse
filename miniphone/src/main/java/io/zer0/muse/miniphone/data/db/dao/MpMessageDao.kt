package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.MpMessageStatus
import io.zer0.muse.miniphone.data.db.entity.MpMessageEntity
import kotlinx.coroutines.flow.Flow

/** 单个会话内的消息读写。 */
@Dao
interface MpMessageDao {
    /** 会话内消息正序(旧 → 新),供界面直接渲染。 */
    @Query("SELECT * FROM mp_message WHERE threadId = :threadId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(threadId: String): Flow<List<MpMessageEntity>>

    /** 倒序翻页(新 → 旧):[offset] = 已加载条数。 */
    @Query(
        "SELECT * FROM mp_message WHERE threadId = :threadId " +
            "ORDER BY createdAt DESC, id DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun pageMessages(threadId: String, limit: Int, offset: Int): List<MpMessageEntity>

    @Query("SELECT * FROM mp_message WHERE id = :messageId LIMIT 1")
    suspend fun getMessage(messageId: String): MpMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MpMessageEntity)

    /** 改一条消息的发送状态;重发中途回到「发送中」也走这里。 */
    @Query("UPDATE mp_message SET status = :status WHERE id = :messageId")
    suspend fun updateStatus(messageId: String, status: MpMessageStatus)

    /** 本机删掉一条;会话行不动,预览文案与时间保留删除前的样子。 */
    @Query("DELETE FROM mp_message WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("SELECT COUNT(*) FROM mp_message WHERE threadId = :threadId")
    suspend fun countInThread(threadId: String): Int

    /** 该会话里某个发送方最后一条消息的时间;没有则 null。`sender` 传 [io.zer0.muse.miniphone.data.db.MpActorType.value]。 */
    @Query("SELECT MAX(createdAt) FROM mp_message WHERE threadId = :threadId AND sender = :sender")
    suspend fun lastMessageAt(threadId: String, sender: String): Long?

    @Query("DELETE FROM mp_message WHERE threadId = :threadId")
    suspend fun deleteInThread(threadId: String)

    /** 清空全部消息行(会话行由调用方一并删)。 */
    @Query("DELETE FROM mp_message")
    suspend fun deleteAll()
}
