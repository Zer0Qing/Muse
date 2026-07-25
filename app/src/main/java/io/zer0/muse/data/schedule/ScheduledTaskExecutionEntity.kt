package io.zer0.muse.data.schedule

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * P1-7: 定时任务执行历史实体。
 *
 * 每次定时任务执行后插入一条记录,用于在 UI 展示执行历史与失败原因。
 * - [id] 主键,字符串(UUID),保证多次执行(即使同一毫秒)不冲突
 * - [taskId] 关联的定时任务 id(对应 scheduled_tasks.id)
 * - [executedAt] 执行时间戳
 * - [status] 执行状态: "success" / "failed"
 * - [replySummary] AI 回复摘要(前 200 字),失败时为空串
 * - [errorMessage] 失败时的错误信息(成功时为空串)
 */
@Entity(
    tableName = "scheduled_task_executions",
    indices = [Index(value = ["task_id"])],
)
@Serializable
data class ScheduledTaskExecutionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "executed_at") val executedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "success") val status: String = "success",  // success / failed
    @ColumnInfo(name = "reply_summary", defaultValue = "") val replySummary: String = "",
    @ColumnInfo(name = "error_message", defaultValue = "") val errorMessage: String = "",
)
