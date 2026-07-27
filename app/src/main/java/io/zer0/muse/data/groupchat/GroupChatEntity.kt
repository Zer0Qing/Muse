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
    // ── v2.x 群聊讨论模式 ──
    /**
     * 讨论模式:
     * - "round_robin": 串行轮转(默认,向后兼容)— 用户发消息后每个 AI 依次发言一轮
     * - "auto": 自由讨论 — AI 之间自动连续对话,达到 [autoMaxRounds] 或全部 PASS 时停止
     * - "debate": 辩论模式 — 固定链条:用户提问 → A 给方案 → B 找漏洞 → C 提改进,每轮必须回应上一人
     * - "host": 主持人模式 — 由 [hostId] 指定的 AI 分析问题后动态派发任务给其他成员
     */
    @ColumnInfo(defaultValue = "round_robin") val discussionMode: String = "round_robin",
    /** Auto 模式:AI 之间最大连续对话轮数(每轮 = 所有成员各发言一次)。 */
    @ColumnInfo(defaultValue = "5") val autoMaxRounds: Int = 5,
    /** 主持人模式:担任群主持的 assistant id,null 时回退到 round_robin。 */
    @ColumnInfo(name = "host_id", defaultValue = "NULL") val hostId: String? = null,
    // ── v2.x 群聊上下文管理 ──
    /**
     * 群共享文档(JSON 字符串,默认 "[]")。
     *
     * 序列化为 [List<GroupSharedDoc>],所有群成员可见,会注入到每个 agent 的 system prompt
     * 中作为共享背景知识(【群共享文档】段落)。
     *
     * 用途:群聊内共享的资料(如会议纪要、需求文档、参考资料),让所有 AI 基于同一份材料讨论。
     */
    @ColumnInfo(name = "shared_docs_json", defaultValue = "[]") val sharedDocsJson: String = "[]",
    /**
     * AI 专属上下文(JSON 字符串,默认 "{}")。
     *
     * 序列化为 `Map<String, String>`(key = assistantId,value = 该 AI 的私密上下文文本)。
     * 仅对应的 agent 可见,注入到其 system prompt 中(【你的专属上下文】段落),
     * 其他群成员看不到,与 [whisperTargetId] 悄悄话共同构成"私密上下文"能力。
     *
     * 用途:给特定 AI 注入角色设定、私有约束、未公开的偏好等。
     */
    @ColumnInfo(name = "member_private_context_json", defaultValue = "{}") val memberPrivateContextJson: String = "{}",
)

/**
 * v2.x: 群聊共享文档数据结构。
 *
 * - [id] 文档唯一 id(用于删除/更新)
 * - [title] 文档标题(展示与检索用)
 * - [content] 文档正文(注入到 agent system prompt)
 * - [addedAt] 添加时间戳
 *
 * 序列化为 JSON 存储在 [GroupChatEntity.sharedDocsJson] 字段中。
 */
@Serializable
data class GroupSharedDoc(
    val id: String,
    val title: String,
    val content: String,
    val addedAt: Long = System.currentTimeMillis(),
)
