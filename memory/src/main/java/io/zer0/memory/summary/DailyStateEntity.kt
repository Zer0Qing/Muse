package io.zer0.memory.summary

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 每日编译进度 (openhanako memory-ticker.ts daily-state.json 移植)。
 *
 * 用于崩溃恢复: 每步成功后立即落盘,下次崩溃重启不会重跑。
 * context key = logicalDate + resetAt + factsMode,任一变化都重置进度。
 *
 * Phase 7: @Serializable 用于备份导出/导入。
 */
@Serializable
@Entity(tableName = "daily_state")
data class DailyStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String = "default",

    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int = 2,

    /** 逻辑日期(YYYY-MM-DD)。 */
    @ColumnInfo(name = "logical_date")
    val logicalDate: String,

    /** 记忆重置水印(ISO),null 表示未重置。 */
    @ColumnInfo(name = "reset_at")
    val resetAt: String? = null,

    /** facts 模式: legacy(用 facts.md) / editable(用 editable-facts.md)。 */
    @ColumnInfo(name = "facts_mode")
    val factsMode: String = "legacy",

    /** 已完成的步骤 JSON: {"compileDaily":"ISO","compileToday":"ISO",...}。 */
    @ColumnInfo(name = "completed_steps")
    val completedSteps: String = "{}",

    /** 全部完成的时刻(ISO),null 表示当天还没全跑完。 */
    @ColumnInfo(name = "daily_completed_at")
    val dailyCompletedAt: String? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
)
