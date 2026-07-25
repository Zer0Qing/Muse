package io.zer0.memory.summary

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Session 摘要实体 (openhanako session-summary.ts JSON 文件 → Room)。
 *
 * 每个 session 一个摘要,通过 rollingSummary() 滚动更新(覆盖式,非追加)。
 * snapshot 字段记录上次 deep-memory 处理时的摘要副本,用于 diff 抽取新事实。
 *
 * Phase 7: @Serializable 用于备份导出/导入。
 */
@Serializable
@Entity(tableName = "session_summaries")
data class SessionSummaryEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,

    /** 摘要正文(rolling summary,两节 markdown 格式)。 */
    @ColumnInfo(name = "summary")
    val summary: String = "",

    /** 已覆盖的消息总数(用于增量取新消息)。 */
    @ColumnInfo(name = "message_count")
    val messageCount: Int = 0,

    /** source_time_range JSON(包含 start/end/timezone/localDates)。 */
    @ColumnInfo(name = "source_time_range")
    val sourceTimeRange: String? = null,

    /** 上次 deep-memory 处理时的摘要副本(diff 基线)。 */
    @ColumnInfo(name = "snapshot")
    val snapshot: String = "",

    /** 上次 deep-memory 处理时间。 */
    @ColumnInfo(name = "snapshot_at")
    val snapshotAt: String? = null,

    /** 所属 Assistant id（v0.22: 用于 per-assistant facts.db 隔离）。空表示未关联。 */
    @ColumnInfo(name = "assistant_id")
    val assistantId: String = "",
)
