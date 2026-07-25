package io.zer0.muse.data.groupchat

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 群聊实体。
 *
 * 一个群聊包含多个 Agent 成员,用户发消息后由 [GroupChatScheduler] 串行触发各 Agent 轮转发言。
 * - [memberIdsJson] 存储 List<String> 的 JSON 序列化(Assistant id 列表)
 * - [teamId] 可选关联 MultiAgentConfig 中的团队 id
 *
 * @param id 群聊唯一 id
 * @param name 群聊名称
 * @param description 群聊描述/用途
 * @param memberIdsJson 成员 assistantId 列表的 JSON 字符串
 * @param teamId 关联的团队 id(可选)
 * @param createdAt 创建时间戳
 * @param updatedAt 最近更新时间戳
 */
@Serializable
@Entity(tableName = "group_chats")
data class GroupChatEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(defaultValue = "") val description: String = "",
    val memberIdsJson: String,
    val teamId: String? = null,
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    // ── v1.107 冗余字段(避免群聊列表 JOIN messages,Repository 双写维护) ──
    /** 最后一条消息预览(列表显示,截断到 50 字)。 */
    @ColumnInfo(defaultValue = "") val lastMessagePreview: String = "",
    /** 群聊消息总数。 */
    @ColumnInfo(defaultValue = "0") val messageCount: Int = 0,
    /** 最后活动时间戳(排序用,等于最后一条消息的 createdAt)。 */
    @ColumnInfo(defaultValue = "0") val lastActivityAt: Long = 0,
)
