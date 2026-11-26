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
 * 长期目标（Goal）：一件要跨多轮对话慢慢推进的事。
 *
 * 与任务的差别：任务是「这一轮要办完」，目标是「几个月后回头看还在不在路上」。
 * 因此目标不绑定执行轮次，只维护标题、说明、里程碑清单与推进度；用户在对话里
 * 「聊着改」时由模型就地改写 [milestonesJson]，不用重新建一条。
 *
 * [milestonesJson] 的线格式固定为
 * `[{"id":"...","title":"...","done":false}]`，空目标为 `[]`。
 */
@Serializable
@Entity(
    tableName = "agent_goals",
    indices = [
        Index(value = ["botId", "status"]),
        Index(value = ["originSessionId"]),
        Index(value = ["updatedAt"]),
    ],
)
data class AgentGoalEntity(
    @PrimaryKey val goalId: String,
    /** 承接这个目标的 Bot；空表示主助手自己记的。 */
    @ColumnInfo(defaultValue = "") val botId: String = "",
    /** 创建这个目标的那段对话。 */
    @ColumnInfo(defaultValue = "") val originSessionId: String = "",
    /** 目标标题，列表与卡片的主行。 */
    @ColumnInfo(defaultValue = "") val title: String = "",
    /** 目标的完整说明：为什么要做、做到什么算完成。 */
    @ColumnInfo(defaultValue = "") val description: String = "",
    /** 里程碑清单 JSON；结构见类说明。 */
    @ColumnInfo(defaultValue = "[]") val milestonesJson: String = "[]",
    /** 0..100 的推进度，由里程碑完成度或模型评估推进。 */
    @ColumnInfo(defaultValue = "0") val progress: Int = 0,
    /** 见 [AgentGoalStatus]。 */
    @ColumnInfo(defaultValue = "ACTIVE") val status: String = AgentGoalStatus.ACTIVE,
    /** 期望完成时间（epoch millis）；0 表示没有期限。 */
    @ColumnInfo(defaultValue = "0") val dueAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L,
)

/** 目标的生命周期状态。 */
object AgentGoalStatus {
    const val ACTIVE = "ACTIVE"
    const val PAUSED = "PAUSED"
    const val ACHIEVED = "ACHIEVED"
    const val ABANDONED = "ABANDONED"

    val ALL: List<String> = listOf(ACTIVE, PAUSED, ACHIEVED, ABANDONED)

    fun isValid(value: String): Boolean = value in ALL
}

/**
 * 目标读写口。
 *
 * 里程碑与推进度总是一起变（勾一个里程碑就重算进度），所以合成一条 UPDATE；
 * 单独改状态走 [setStatus]，避免把模型刚改写的里程碑覆盖掉。
 */
@Dao
interface AgentGoalDao {

    @Upsert
    suspend fun upsert(goal: AgentGoalEntity)

    @Upsert
    suspend fun upsertAll(goals: List<AgentGoalEntity>)

    @Query("SELECT * FROM agent_goals WHERE goalId = :goalId LIMIT 1")
    suspend fun getById(goalId: String): AgentGoalEntity?

    @Query("SELECT * FROM agent_goals ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<AgentGoalEntity>>

    @Query("SELECT * FROM agent_goals WHERE botId = :botId ORDER BY updatedAt DESC")
    fun observeByBot(botId: String): Flow<List<AgentGoalEntity>>

    @Query("SELECT * FROM agent_goals WHERE originSessionId = :sessionId ORDER BY updatedAt DESC")
    fun observeBySession(sessionId: String): Flow<List<AgentGoalEntity>>

    @Query("SELECT * FROM agent_goals WHERE status = 'ACTIVE' ORDER BY updatedAt DESC")
    suspend fun listActive(): List<AgentGoalEntity>

    @Query(
        "UPDATE agent_goals SET milestonesJson = :milestonesJson, progress = :progress, " +
            "updatedAt = :updatedAt WHERE goalId = :goalId",
    )
    suspend fun updateMilestones(goalId: String, milestonesJson: String, progress: Int, updatedAt: Long): Int

    @Query("UPDATE agent_goals SET status = :status, updatedAt = :updatedAt WHERE goalId = :goalId")
    suspend fun setStatus(goalId: String, status: String, updatedAt: Long): Int

    @Query("DELETE FROM agent_goals WHERE goalId = :goalId")
    suspend fun deleteById(goalId: String)

    @Query("DELETE FROM agent_goals WHERE originSessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query(
        "DELETE FROM agent_goals WHERE botId IN " +
            "(SELECT botId FROM bot_bindings WHERE assistantId = :assistantId)",
    )
    suspend fun deleteByAssistant(assistantId: String)

    @Query("SELECT * FROM agent_goals")
    suspend fun getAll(): List<AgentGoalEntity>

    @Query("DELETE FROM agent_goals")
    suspend fun deleteAll()
}
