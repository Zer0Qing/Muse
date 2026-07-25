package io.zer0.muse.data.session

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 消息实体(持久化的 [io.zer0.ai.core.UIMessage])。
 *
 * Phase 5-I: 加 @Serializable 用于备份导出/导入。
 * Phase 8.3: 加 [favorite] 字段支持消息收藏。
 * Phase 8.4: 加 [citationUrlsJson] 字段支持 UrlCitation 引用渲染。
 * Phase 8.5 修复: @ColumnInfo(defaultValue=...) 与 MIGRATION_2_3/3_4 SQL 对齐。
 * Phase 8.6: 加 [imageBase64Json] 字段支持多模态图片输入(USER)与 Gemini 绘图输出(ASSISTANT)。
 */
@Serializable
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("sessionId"),
        // M-SESS5: role 单列索引,加速 WHERE role='ASSISTANT'/'USER' 等无 sessionId 前缀的查询
        Index("role"),
        Index(value = ["sessionId", "createdAt"]),
        Index(value = ["sessionId", "createdAt", "role"]),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val reasoning: String? = null,
    val modelId: String? = null,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "[]") val imageUrlsJson: String = "[]",
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
    /**
     * v1.104 U7: 收藏分组标签(用户自定义,如"灵感"/"代码片段")。
     *
     * 仅在 [favorite]=true 时有意义;NULL 表示未分组。
     * 由 FavoritesScreen 长按卡片弹"设置分组"对话框写入,DAO 用 [MessageDao.setMessageFavoriteTag]。
     */
    @ColumnInfo(defaultValue = "NULL") val favoriteTag: String? = null,
    @ColumnInfo(defaultValue = "[]") val citationUrlsJson: String = "[]",
    /** v1.133: 知识库引用列表(JSON 序列化 [io.zer0.ai.core.RagCitation])。 */
    @ColumnInfo(defaultValue = "[]") val ragCitationsJson: String = "[]",
    /** Phase 8.6: 本地图片 base64 列表(JSON 数组,无 data: 前缀)。 */
    @ColumnInfo(defaultValue = "[]") val imageBase64Json: String = "[]",
    /** v1.43: 消息关联的 Artifact id 列表(JSON 数组)。 */
    @ColumnInfo(defaultValue = "[]") val artifactIdsJson: String = "[]",
    /**
     * v1.103: MOOD 块(LLM 在正文前输出的内部腹稿)。
     *
     * 由 MoodTagTransformer 从 content 中剥离 `<mood>...</mood>` 后存入。
     * 之前 MessageEntity 缺此字段,任务会话/Agent 聊天(用 messages 表)落盘时
     * mood 被静默丢弃,切页/重载后 MOOD 卡片消失。群聊表(group_chat_messages)
     * 已有 mood 列,本次补齐 messages 表。
     */
    @ColumnInfo(defaultValue = "NULL") val mood: String? = null,
    /** v1.103: 自我反思块(`<reflection>...</reflection>`),与 mood 同期补齐。 */
    @ColumnInfo(defaultValue = "NULL") val reflection: String? = null,
    /** v1.107 冗余: content 长度(完整性校验基准,检测存储截断/损坏)。 */
    @ColumnInfo(defaultValue = "0") val contentLength: Int = 0,
    /** v2.0: 软删除时间戳(null = 未删除)。 */
    @ColumnInfo(defaultValue = "NULL") val deletedAt: Long? = null,
    /** 功能1: 消息表情回应(ThumbUp/Favorite/SentimentSatisfied/SentimentDissatisfied/MoodBad/Bolt,null=无)。 */
    @ColumnInfo(defaultValue = "NULL") val reaction: String? = null,
)
