package io.zer0.muse.data.groupchat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 群聊数据访问对象。
 *
 * 负责群聊实体的 CRUD + Flow 观察,以及跨表的聚合查询
 * (最新消息预览、消息计数),供群聊列表页使用。
 */
@Dao
interface GroupChatDao {

    /** 观察全部群聊(置顶在前,同置顶状态按 updatedAt 降序)。 */
    @Query("SELECT * FROM group_chats ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<GroupChatEntity>>

    /** 观察单个群聊(用于详情页实时刷新元数据)。 */
    @Query("SELECT * FROM group_chats WHERE id = :id")
    fun observeById(id: String): Flow<GroupChatEntity?>

    /** 按 id 取单个群聊(一次性)。 */
    @Query("SELECT * FROM group_chats WHERE id = :id")
    suspend fun getById(id: String): GroupChatEntity?

    /** 插入或更新群聊(冲突时替换)。 */
    @Upsert
    suspend fun upsert(chat: GroupChatEntity)

    /** 插入新群聊(冲突时忽略)。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(chat: GroupChatEntity)

    /** 按 id 删除群聊。 */
    @Query("DELETE FROM group_chats WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 更新群聊的 updatedAt 时间戳(每次有新消息时调用)。 */
    @Query("UPDATE group_chats SET updatedAt = :timestamp WHERE id = :id")
    suspend fun touchUpdatedAt(id: String, timestamp: Long = System.currentTimeMillis())

    // ── 跨表聚合查询(查 group_chat_messages 表)──

    /** 取指定群聊的最新一条消息(用于列表页预览)。 */
    @Query("SELECT * FROM group_chat_messages WHERE chatId = :chatId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestMessage(chatId: String): GroupChatMessageEntity?

    /** 统计指定群聊的消息数。 */
    @Query("SELECT COUNT(*) FROM group_chat_messages WHERE chatId = :chatId")
    suspend fun countMessages(chatId: String): Int

    // ── v1.107 冗余字段维护方法 ──

    /** v1.107: 原子更新群聊冗余字段(最后消息预览 + 计数 + 最后活动时间)。 */
    @Query("UPDATE group_chats SET lastMessagePreview = :preview, messageCount = messageCount + :delta, lastActivityAt = :timestamp, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateLastMessageAndCount(id: String, preview: String, delta: Int, timestamp: Long)

    @Query("SELECT * FROM group_chats")
    suspend fun getAll(): List<GroupChatEntity>

    @Query("DELETE FROM group_chats")
    suspend fun deleteAll()
}
