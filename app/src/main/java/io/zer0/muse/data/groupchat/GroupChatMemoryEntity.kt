package io.zer0.muse.data.groupchat

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.serialization.Serializable

/**
 * v2.x: 群聊记忆隔离 — Room 实体。
 *
 * 背景:群聊消息含多个 Agent 的发言,若直接写入助手主记忆会污染主对话上下文。
 * 按 既有实现 的设计:群聊消息摘要写入独立的 fact store,不进入主记忆。
 * 本表即"群聊专属 fact store",与 [io.zer0.memory] 模块的主记忆系统完全隔离。
 *
 * 注入路径:[SystemPromptAssembler] 在主助手构建 system prompt 时,把当前助手关联的
 * 群聊记忆摘要用 `<group_chat_memory>` 标签注入,与 `<long_term_memory>` 区分。
 *
 * @param id 主键(UUID)
 * @param groupChatId 群聊 id(关联 [GroupChatEntity].id)
 * @param assistantId 助手 id(关联 [io.zer0.muse.data.assistant.AssistantEntity].id)
 * @param summary 群聊消息摘要(LLM 生成或简单截取)
 * @param createdAt 创建时间戳
 * @param expiresAt 可选过期时间戳,null 表示永不过期
 */
@Serializable
@Entity(
    tableName = "group_chat_memories",
    indices = [
        Index(value = ["assistantId"]),
        Index(value = ["groupChatId"]),
        Index(value = ["createdAt"]),
    ],
)
data class GroupChatMemoryEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "groupChatId")
    val groupChatId: String,
    @ColumnInfo(name = "assistantId")
    val assistantId: String,
    @ColumnInfo(name = "summary")
    val summary: String,
    @ColumnInfo(name = "createdAt")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "expiresAt")
    val expiresAt: Long? = null,
)

/**
 * 群聊记忆 DAO。
 *
 * 查询语义:
 *  - [getByAssistant]:按助手 id 查最近 N 条群聊记忆(供 SystemPromptAssembler 注入)
 *  - [getByGroupChat]:按群聊 id 查全部记忆(供群聊详情页展示)
 *  - [insert]:写入一条新记忆
 *  - [deleteOlderThan]:清理过期/陈旧记忆
 */
@Dao
interface GroupChatMemoryDao {
    /**
     * 按助手 id 查最近 N 条群聊记忆(按 createdAt 降序)。
     * 供 [SystemPromptAssembler] 注入到 system prompt。
     */
    @Query("SELECT * FROM group_chat_memories WHERE assistantId = :assistantId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getByAssistant(assistantId: String, limit: Int = 10): List<GroupChatMemoryEntity>

    /** 按群聊 id 查全部记忆(供群聊详情页展示)。 */
    @Query("SELECT * FROM group_chat_memories WHERE groupChatId = :groupChatId ORDER BY createdAt DESC")
    suspend fun getByGroupChat(groupChatId: String): List<GroupChatMemoryEntity>

    /** 写入一条新记忆(同 id 替换)。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: GroupChatMemoryEntity)

    /** 清理 createdAt 早于 [before] 的记忆(供定期清理任务调用)。 */
    @Query("DELETE FROM group_chat_memories WHERE createdAt < :before")
    suspend fun deleteOlderThan(before: Long)

    /** 删除指定群聊的全部记忆(群聊删除时级联调用)。 */
    @Query("DELETE FROM group_chat_memories WHERE groupChatId = :groupChatId")
    suspend fun deleteByGroupChat(groupChatId: String)

    /** 删除指定助手在指定群聊的记忆(助手机器人退出群聊时调用)。 */
    @Query("DELETE FROM group_chat_memories WHERE groupChatId = :groupChatId AND assistantId = :assistantId")
    suspend fun deleteByGroupChatAndAssistant(groupChatId: String, assistantId: String)
}
