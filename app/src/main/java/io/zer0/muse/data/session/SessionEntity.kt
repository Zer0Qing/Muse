package io.zer0.muse.data.session

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 会话实体。
 *
 * 一个会话对应一段连续对话,拥有独立的消息历史和 memory session 隔离。
 * - [id] 主键,字符串 UUID
 * - [title] 会话标题(用户可编辑,默认取首条消息前 20 字)
 * - [createdAt] 创建时间戳
 * - [updatedAt] 最后更新时间(用于侧栏排序)
 * - [lastMessagePreview] 最后一条消息预览(侧栏显示,空会话为空串)
 *
 * Phase 5-I: 加 @Serializable 用于备份导出/导入。
 * Phase 8.2: 加 [assistantId] 字段绑定 Assistant 人格;[pinned] 字段支持置顶。
 * Phase 8.5 修复: @ColumnInfo(defaultValue=...) 与 MIGRATION_1_2 SQL 完全对齐,避免 Room schema 验证崩溃。
 * Phase 9.1 (M13): 加 [folderId] 字段支持文件夹分组(null = 未分组)。
 * v0.45: 加 [archived] 字段支持归档(默认 false,归档后不在主列表显示)。
 * v1.28: 加 [isAgentSession] 字段区分 Agent Tab 会话(true)与任务 Tab 会话(false),
 *        Agent Tab 的日常聊天不污染任务列表。
 */
@Serializable
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastMessagePreview: String = "",
    @ColumnInfo(defaultValue = "default") val assistantId: String = "default",
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    @ColumnInfo(defaultValue = "") val folderId: String? = null,
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
    /** v1.28: 是否为 Agent Tab 的会话(Agent Tab 日常聊天,不在任务列表显示)。 */
    @ColumnInfo(defaultValue = "0") val isAgentSession: Boolean = false,
    /** v1.107 冗余: 会话消息数(避免列表页 COUNT 全表扫描,Repository 双写维护)。 */
    @ColumnInfo(defaultValue = "0") val messageCount: Int = 0,
    /** v2.0: 软删除时间戳(null = 未删除)。 */
    @ColumnInfo(defaultValue = "NULL") val deletedAt: Long? = null,
    /** v5: 分叉来源会话 id(null = 非分叉会话)。 */
    @ColumnInfo(defaultValue = "NULL") val parentSessionId: String? = null,
    /** v5: 分叉子会话数量(仅在原始会话上维护)。 */
    @ColumnInfo(defaultValue = "0") val childCount: Int = 0,
)
