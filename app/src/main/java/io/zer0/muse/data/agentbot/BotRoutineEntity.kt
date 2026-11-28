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
    /**
     * 关联的全局技能 id（对应 skills 表）。
     *
     * 技能本身不含 botId，是跨队友共享的能力剧本；惯例只保存引用与触发条件，
     * 因此同一个技能可以被多个队友各自的惯例复用，删除技能时不会连带删除惯例。
     */
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

/** 惯例最近一次运行的结果标记；用于界面区分“排队中”和“已静默归档”。 */
object RoutineRunStatus {
    const val QUEUED = "QUEUED"
    const val SKILL_UNAVAILABLE = "SKILL_UNAVAILABLE"

    /**
     * 无异常、无实质变化、无需确认的例行运行。
     *
     * 这类运行只在本地留档，不向主对话发布摘要，避免每天固定动作反复刷屏。
     */
    const val ARCHIVED = "ARCHIVED"
}

/** 该惯例是否由共享技能库提供执行剧本（技能负责“怎么做”，惯例负责“何时做”）。 */
val BotRoutineEntity.isSkillBacked: Boolean
    get() = skillId.isNotBlank()

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

    /** 查询引用了同一技能的惯例；技能跨队友共享，结果可能横跨多个 botId。 */
    @Query("SELECT * FROM bot_routines WHERE skillId = :skillId ORDER BY updatedAt DESC")
    suspend fun listBySkillId(skillId: String): List<BotRoutineEntity>

    @Query("UPDATE bot_routines SET enabled = :enabled, updatedAt = :updatedAt WHERE routineId = :routineId")
    suspend fun setEnabled(routineId: String, enabled: Boolean, updatedAt: Long): Int

    @Query(
        "UPDATE bot_routines SET lastRunAt = :runAt, lastRunStatus = :status, runCount = runCount + 1, updatedAt = :runAt " +
            "WHERE routineId = :routineId",
    )
    suspend fun recordExecution(routineId: String, runAt: Long, status: String): Int

    @Query(
        "UPDATE bot_routines SET lastRunAt = :runAt, lastRunStatus = :status, " +
            "runCount = runCount + 1, updatedAt = :runAt WHERE routineId = :routineId AND lastRunAt < :runAt",
    )
    suspend fun recordExecutionIfNewer(routineId: String, runAt: Long, status: String): Int

    @Query(
        "UPDATE bot_routines SET lastRunStatus = :status, updatedAt = :runAt " +
            "WHERE routineId = :routineId AND lastRunAt = :expectedRunAt AND lastRunStatus = 'QUEUED'",
    )
    suspend fun settleExecution(routineId: String, expectedRunAt: Long, runAt: Long, status: String): Int

    @Query("DELETE FROM bot_routines WHERE routineId = :routineId")
    suspend fun deleteById(routineId: String)

    @Query("DELETE FROM bot_routines WHERE botId = :botId")
    suspend fun deleteByBot(botId: String)

    @Query("SELECT * FROM bot_routines")
    suspend fun getAll(): List<BotRoutineEntity>

    @Query("DELETE FROM bot_routines")
    suspend fun deleteAll()
}
