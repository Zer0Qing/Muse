package io.zer0.muse.data.groupchat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 群聊消息计数投影(按 chatId 分组,用于 Top N 活跃群聊)。
 */
data class GroupChatMessageCount(
    val chatId: String,
    val cnt: Int,
)

/**
 * 群聊消息数据访问对象。
 *
 * 负责消息实体的 CRUD + Flow 观察,以及按 chatId 分组统计(Top N 活跃群聊)。
 */
@Dao
interface GroupChatMessageDao {

    /**
     * 观察指定群聊的消息(按 timestamp 升序,聊天界面用)。
     * M4: 暂用 LIMIT 200 上限,后续可改 PagingSource。
     * M8: 用 id 作为次级排序 key,避免同毫秒碰撞。
     *
     * v1.53-GC: 全量观察长群聊有 OOM 风险(图片 base64 直存 DB),新代码改用
     * [observeRecentMessages] + [getOlderMessages] 分页加载。本方法保留向后兼容,
     * 已在 Repository 层标注 @Deprecated。
     */
    @Query("SELECT * FROM group_chat_messages WHERE chatId = :chatId ORDER BY timestamp ASC, id ASC LIMIT 200")
    fun observeMessages(chatId: String): Flow<List<GroupChatMessageEntity>>

    /**
     * v1.53-GC: 观察指定群聊最近 limit 条消息(按 timestamp 升序),分页首屏 Flow 实时更新用。
     *
     * 子查询先按 DESC 取最近 limit 条,外层再按 ASC 排序,保证返回「最近 limit 条」且按时间正序。
     * 用于替代全量 [observeMessages]:聊天界面首屏只观察最近一页,新消息到达时 Flow 自动重发,
     * ViewModel 据此增量追加到已加载列表。M8: 用 id 作次级排序 key,避免同毫秒碰撞。
     */
    @Query("SELECT * FROM (SELECT * FROM group_chat_messages WHERE chatId = :chatId ORDER BY timestamp DESC, id DESC LIMIT :limit) ORDER BY timestamp ASC, id ASC")
    fun observeRecentMessages(chatId: String, limit: Int): Flow<List<GroupChatMessageEntity>>

    /**
     * v1.53-GC: 窗口加载 — 取早于锚点(timestamp, id)的前 limit 条消息(降序)。
     *
     * 用于上滑加载更多:以当前列表最早一条消息的 (timestamp, id) 为双锚点,取更早的历史。
     * 双锚点(timestamp + id)避免同毫秒消息被漏取或重复。返回顺序为降序(最新在前),
     * 调用方(Repository)需自行 reversed() 转为升序后前置拼接。
     */
    @Query("SELECT * FROM group_chat_messages WHERE chatId = :chatId AND (timestamp < :beforeTimestamp OR (timestamp = :beforeTimestamp AND id < :beforeId)) ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getOlderMessages(chatId: String, beforeTimestamp: Long, beforeId: String, limit: Int): List<GroupChatMessageEntity>

    /** 插入或更新消息(冲突时替换)。 */
    @Upsert
    suspend fun upsert(message: GroupChatMessageEntity)

    /** 批量插入消息。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<GroupChatMessageEntity>)

    /** 按 id 删除单条消息。 */
    @Query("DELETE FROM group_chat_messages WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 删除指定群聊的全部消息(群聊删除时级联调用)。 */
    @Query("DELETE FROM group_chat_messages WHERE chatId = :chatId")
    suspend fun deleteByChat(chatId: String)

    /** 取指定群聊的最近 N 条消息(按 timestamp 降序取再反转,供 Agent 工具读取上下文)。M8: 用 id 作次级排序 key。 */
    @Query("SELECT * FROM group_chat_messages WHERE chatId = :chatId ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getRecentMessages(chatId: String, limit: Int): List<GroupChatMessageEntity>

    /** 按 chatId 分组统计消息数,取 Top N 活跃群聊(降序)。 */
    @Query("SELECT chatId, COUNT(*) as cnt FROM group_chat_messages GROUP BY chatId ORDER BY cnt DESC LIMIT :limit")
    fun observeTopActiveChats(limit: Int): Flow<List<GroupChatMessageCount>>

    @Query("SELECT * FROM group_chat_messages")
    suspend fun getAll(): List<GroupChatMessageEntity>

    @Query("DELETE FROM group_chat_messages")
    suspend fun deleteAll()
}
