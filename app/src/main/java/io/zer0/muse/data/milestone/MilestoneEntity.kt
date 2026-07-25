package io.zer0.muse.data.milestone

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Phase 2 2B: 里程碑实体 — 记录用户与 AI 伙伴的关系里程碑。
 *
 * type: "auto" (系统自动触发) / "manual" (用户手动创建)
 * condition: 触发条件标识符 (e.g. "first_message", "day_7", "msg_100")
 * triggerValue: 触发时的实际值 (e.g. 消息数, 天数)
 * dismissedAt: 用户关闭时间 (null = 未关闭)
 */
@Serializable
@Entity(tableName = "milestones")
data class MilestoneEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "type")
    val type: String = "auto",
    @ColumnInfo(name = "condition_type")
    val conditionType: String,
    @ColumnInfo(name = "trigger_value")
    val triggerValue: Long = 0,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "message")
    val message: String,
    @ColumnInfo(name = "assistant_id")
    val assistantId: String? = null,
    @ColumnInfo(name = "session_id")
    val sessionId: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "dismissed_at")
    val dismissedAt: Long? = null,
)
