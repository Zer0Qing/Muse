package io.zer0.muse.data.groupchat

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 群聊消息实体。
 *
 * 记录群聊中的每一条消息(用户发言 / Agent 发言)。
 * - [senderType] 发送者类型: "user" 或 "assistant"
 * - [senderId] 发送者 id(userId 或 assistantId)
 * - [senderName] 发送者显示名(缓存,避免每次反查 AssistantRepository)
 * - [mood] Agent 情绪(可选,留给后续 UI 展示)
 *
 * @param id 消息唯一 id
 * @param chatId 所属群聊 id
 * @param senderType 发送者类型: "user" / "assistant"
 * @param senderId 发送者 id
 * @param senderName 发送者显示名
 * @param body 消息正文
 * @param imageBase64Json 图片附件 base64 列表(JSON 字符串,默认 "[]")
 * @param timestamp 发送时间戳
 * @param mood Agent 情绪(可选)
 * @param reasoning Agent 思考过程(可选)
 */
@Entity(
    tableName = "group_chat_messages",
    indices = [Index(value = ["chatId"])],
)
@Serializable
data class GroupChatMessageEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val senderType: String,
    val senderId: String,
    val senderName: String,
    val body: String,
    @ColumnInfo(defaultValue = "[]") val imageBase64Json: String = "[]",
    @ColumnInfo(defaultValue = "0") val timestamp: Long = System.currentTimeMillis(),
    val mood: String? = null,
    val reasoning: String? = null,
)
