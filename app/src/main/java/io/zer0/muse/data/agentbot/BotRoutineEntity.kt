package io.zer0.muse.data.agentbot

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * 可复用的 Agent 惯例（Routine）。
 *
 * 支持从已跑通的任务或示范操作一键沉淀，并按手动、定时、通知或外部渠道事件触发。
 * 归属于具体 botId，实现能力共享下的角色级惯例隔离。
 */
@Serializable
@Entity(
    tableName = "bot_routines",
    indices = [
        Index(value = ["botId", "enabled"]),
        Index(value = ["triggerType", "enabled"]),
    ],
)
data class BotRoutineEntity(
    @PrimaryKey val routineId: String,
    @ColumnInfo(defaultValue = "") val botId: String = "",
    @ColumnInfo(defaultValue = "") val title: String = "",
    /** 触发时派发给执行器的目标模板。 */
    @ColumnInfo(defaultValue = "") val goalPrompt: String = "",
    /** 固化下来的步骤与检查要点摘要。 */
    @ColumnInfo(defaultValue = "") val stepsSummary: String = "",
    @ColumnInfo(defaultValue = "") val resourceScope: String = "",
    /** 见 [RoutineTriggerType]。 */
    @ColumnInfo(defaultValue = "MANUAL") val triggerType: String = RoutineTriggerType.MANUAL,
    /** Cron 表达式、通知关键词/包名过滤或渠道标识。 */
    @ColumnInfo(defaultValue = "") val triggerExpr: String = "",
    /** 绑定定时触发时关联的 scheduled_tasks id。 */
    @ColumnInfo(defaultValue = "") val scheduledTaskId: String = "",
    /** 从历史任务沉淀时的源 taskId。 */
    @ColumnInfo(defaultValue = "") val sourceTaskId: String = "",
    /** 关联的技能或示范工作流 id。 */
    @ColumnInfo(defaultValue = "") val skillId: String = "",
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    @ColumnInfo(defaultValue = "0") val lastRunAt: Long = 0L,
    @ColumnInfo(defaultValue = "") val lastRunStatus: String = "",
    @ColumnInfo(defaultValue = "0") val runCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L,
)

object RoutineTriggerType {
    const val MANUAL = "MANUAL"
    const val CRON = "CRON"
    const val NOTIFICATION = "NOTIFICATION"
    const val CHANNEL = "CHANNEL"

    val all: Set<String> = setOf(MANUAL, CRON, NOTIFICATION, CHANNEL)
}

@Dao
interface BotRoutineDao {

    @Upsert
    suspend fun upsert(routine: BotRoutineEntity)

    @Upsert
    suspend fun upsertAll(routines: List<BotRoutineEntity>)

    @Query("SELECT * FROM bot_routines WHERE routineId = :routineId LIMIT 1")
    suspend fun getById(routineId: String): BotRoutineEntity?

    @Query("SELECT * FROM bot_routines WHERE botId = :botId ORDER BY updatedAt DESC")
    fun observeByBot(botId: String): Flow<List<BotRoutineEntity>>

    @Query("SELECT * FROM bot_routines ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<BotRoutineEntity>>

    @Query("SELECT * FROM bot_routines WHERE triggerType = :triggerType AND enabled = 1")
    suspend fun listEnabledByTrigger(triggerType: String): List<BotRoutineEntity>

    @Query("UPDATE bot_routines SET enabled = :enabled, updatedAt = :updatedAt WHERE routineId = :routineId")
    suspend fun setEnabled(routineId: String, enabled: Boolean, updatedAt: Long): Int

    @Query(
        "UPDATE bot_routines SET lastRunAt = :runAt, lastRunStatus = :status, runCount = runCount + 1, updatedAt = :runAt " +
            "WHERE routineId = :routineId",
    )
    suspend fun recordExecution(routineId: String, runAt: Long, status: String): Int

    @Query("DELETE FROM bot_routines WHERE routineId = :routineId")
    suspend fun deleteById(routineId: String)

    @Query("DELETE FROM bot_routines WHERE botId = :botId")
    suspend fun deleteByBot(botId: String)

    @Query("SELECT * FROM bot_routines")
    suspend fun getAll(): List<BotRoutineEntity>

    @Query("DELETE FROM bot_routines")
    suspend fun deleteAll()
}
