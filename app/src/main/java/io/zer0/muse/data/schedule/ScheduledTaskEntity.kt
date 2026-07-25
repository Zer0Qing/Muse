package io.zer0.muse.data.schedule

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "scheduled_tasks",
    indices = [
        // M-SC1: 覆盖 getDueTasks 的 WHERE enabled AND next_run_at <= ? ORDER BY next_run_at 查询
        Index(value = ["enabled", "next_run_at"]),
    ],
)
data class ScheduledTaskEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(defaultValue = "") val prompt: String = "",
    @ColumnInfo(name = "assistant_id", defaultValue = "default") val assistantId: String = "default",
    @ColumnInfo(defaultValue = "daily") val interval: String = "daily",  // once / hourly / daily / weekly / cron
    @ColumnInfo(name = "cron_expr", defaultValue = "") val cronExpr: String = "",
    @ColumnInfo(name = "enabled", defaultValue = "1") val enabled: Boolean = true,
    @ColumnInfo(name = "next_run_at", defaultValue = "0") val nextRunAt: Long = 0,
    @ColumnInfo(name = "last_run_at", defaultValue = "0") val lastRunAt: Long = 0,
    @ColumnInfo(name = "created_at", defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    /**
     * v1.134 P2-1: 专用会话 ID(会话聚合模式)。
     *
     * 空串表示未启用;首次执行时创建专用会话并写入此字段,后续执行复用同一会话,
     * 避免长期运行的 daily 任务每天产生一个新会话。
     */
    @ColumnInfo(name = "dedicated_session_id", defaultValue = "") val dedicatedSessionId: String = "",
    /**
     * v1.137: 自动化条件 JSON(空串表示 always)。
     * 支持:always / network_available / time_range / contains / quick_note_exists。
     */
    @ColumnInfo(name = "condition_json", defaultValue = "") val conditionJson: String = "",
    /**
     * v1.137: 动作类型(ai_prompt / create_quick_note / call_tool / notify)。
     * 默认 ai_prompt,保持与旧版定时任务兼容。
     */
    @ColumnInfo(name = "action_type", defaultValue = "ai_prompt") val actionType: String = "ai_prompt",
    /**
     * v1.137: 动作配置 JSON(具体参数随 action_type 变化)。
     */
    @ColumnInfo(name = "action_config_json", defaultValue = "") val actionConfigJson: String = "",
    /**
     * v1.137: 链式任务 ID 列表 JSON。本任务成功执行后,会将这些任务的 next_run_at
     * 设置为当前时间,触发后续链式任务在下一轮轮询中执行。
     */
    @ColumnInfo(name = "next_task_ids_json", defaultValue = "") val nextTaskIdsJson: String = "",
    /**
     * v1.137: 父任务 ID(用于链式任务溯源)。
     */
    @ColumnInfo(name = "parent_task_id", defaultValue = "") val parentTaskId: String = "",
    /**
     * v1.0.17: 任务创建来源("user"=用户通过 UI 创建, "assistant"=助手通过工具创建)。
     */
    @ColumnInfo(name = "created_by", defaultValue = "user") val createdBy: String = "user",
    /**
     * v1.0.17: 当前重试次数(执行失败后递增,成功或达到上限后重置为 0)。
     */
    @ColumnInfo(name = "retry_count", defaultValue = "0") val retryCount: Int = 0,
    /**
     * v1.0.17: 最大重试次数(达到后不再重试,正常推进到下次计划时间)。
     */
    @ColumnInfo(name = "max_retries", defaultValue = "3") val maxRetries: Int = 3,
)
