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
    // ── v2.x 群聊增强功能 ──
    /**
     * 悄悄话目标 AI id(null = 公开消息;非 null = 仅目标 AI 可见的私信)。
     *
     * - 用户发的悄悄话:[senderType]="user", [whisperTargetId]=目标 assistantId
     * - AI 收到时,系统 prompt 中标注"这是私信";其他 AI 看不到这条消息的 body
     * - UI 中显示锁图标标注为"悄悄话"
     */
    @ColumnInfo(name = "whisper_target_id", defaultValue = "NULL") val whisperTargetId: String? = null,
    /**
     * 引用回复的目标消息 id(null = 普通消息;非 null = 引用了指定消息)。
     * UI 中显示引用预览块,点击可跳转到原消息。
     */
    @ColumnInfo(name = "reply_to_id", defaultValue = "NULL") val replyToId: String? = null,
    /**
     * 消息类型标记:
     * - "normal": 普通消息(默认)
     * - "vote": 表决投票(各 AI 按人设投票,body 为投票内容)
     * - "summary": 结论总结(由总结器生成)
     * - "system": 系统提示(如"XXX 发起了表决")
     */
    @ColumnInfo(defaultValue = "normal") val messageType: String = "normal",
)
