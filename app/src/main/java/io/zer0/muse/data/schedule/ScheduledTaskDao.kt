package io.zer0.muse.data.schedule

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * v1.137: 链式任务选择时的轻量投影。
 */
data class TaskIdName(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
)

@Dao
interface ScheduledTaskDao {
    @Query("SELECT * FROM scheduled_tasks ORDER BY next_run_at ASC")
    fun observeAll(): Flow<List<ScheduledTaskEntity>>

    @Query("SELECT * FROM scheduled_tasks WHERE enabled = 1 AND next_run_at <= :timeMillis ORDER BY next_run_at ASC LIMIT 10")
    suspend fun getDueTasks(timeMillis: Long): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_tasks WHERE id = :id")
    suspend fun getById(id: String): ScheduledTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: ScheduledTaskEntity)

    @Query("DELETE FROM scheduled_tasks WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * B-12: 抢占式领取到期任务(CAS,compare-and-set)。
     *
     * 仅当该任务的 next_run_at 仍等于调用方读取到的期望值(即仍"到期未领取")时,
     * 才把 last_run_at/updated_at 原子更新为 [claimedAt] 作为领取标记,返回受影响行数
     * (1 = 本执行者领取成功;0 = 已被其他执行者抢先领取,应跳过)。
     *
     * 依赖单条原子 UPDATE 的 WHERE 条件实现进程内与跨进程(Worker)并发安全:
     * [ScheduledTaskRunner] 轮询与 WorkManager 拉起的 Worker 即使同时读到同一到期任务,
     * 也只有一个能通过 next_run_at 比对,最终只有一份 AI 调用 / 通知。
     *
     * @param id 任务 id
     * @param expectedNextRunAt 调用方从 DB 读到的 next_run_at 快照(CAS 期望值)
     * @param claimedAt 领取时间戳,写入 last_run_at 与 updated_at
     */
    @Query(
        "UPDATE scheduled_tasks SET last_run_at = :claimedAt, updated_at = :claimedAt " +
            "WHERE id = :id AND next_run_at = :expectedNextRunAt AND enabled = 1",
    )
    suspend fun claimTask(id: String, expectedNextRunAt: Long, claimedAt: Long): Int

    @Query("UPDATE scheduled_tasks SET enabled = :enabled, updated_at = :now WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE scheduled_tasks SET next_run_at = :nextRunAt, last_run_at = :lastRunAt WHERE id = :id")
    suspend fun updateRunState(id: String, nextRunAt: Long, lastRunAt: Long)

    /**
     * v1.134 P2-1: 更新任务的专用会话 ID(会话聚合模式)。
     *
     * 首次执行时创建专用会话后调用此方法持久化,后续执行复用同一会话。
     */
    @Query("UPDATE scheduled_tasks SET dedicated_session_id = :sessionId, updated_at = :now WHERE id = :id")
    suspend fun updateDedicatedSessionId(
        id: String,
        sessionId: String,
        now: Long = System.currentTimeMillis(),
    )

    /**
     * H-SC1: 原子地记录执行历史并更新下次执行时间。
     *
     * 将 execution insert 与 next_run_at/last_run_at update 放在同一事务内,
     * 避免崩溃后只写入历史但未推进 next_run_at 导致重复执行。
     *
     * @param execution 执行历史记录
     * @param taskId 任务 id
     * @param nextRunAt 下次执行时间戳;<=0 表示无下次(once/非法 cron),将禁用任务
     * @param lastRunAt 本次执行时间戳(写入 last_run_at)
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExecution(execution: ScheduledTaskExecutionEntity)

    @Query("SELECT * FROM scheduled_tasks")
    suspend fun getAll(): List<ScheduledTaskEntity>

    @Query("DELETE FROM scheduled_tasks")
    suspend fun deleteAll()

    /**
     * v1.137: 把一组任务的下次执行时间设置为现在,触发链式任务在下一轮轮询中执行。
     */
    @Query("UPDATE scheduled_tasks SET next_run_at = :now, updated_at = :now WHERE id IN (:ids)")
    suspend fun triggerNextTasks(ids: List<String>, now: Long = System.currentTimeMillis())

    /**
     * v1.0.17: 更新任务的重试次数(执行失败重试策略)。
     */
    @Query("UPDATE scheduled_tasks SET retry_count = :retryCount WHERE id = :id")
    suspend fun updateRetryCount(id: String, retryCount: Int)

    /**
     * v1.137: 查询可作为链式后续的任务候选(排除当前任务自身)。
     */
    @Query("SELECT id, name FROM scheduled_tasks WHERE id != :excludeId ORDER BY name ASC")
    suspend fun listChainCandidates(excludeId: String): List<TaskIdName>

    @Transaction
    suspend fun recordExecutionAndScheduleNext(
        execution: ScheduledTaskExecutionEntity,
        taskId: String,
        nextRunAt: Long,
        lastRunAt: Long,
    ) {
        insertExecution(execution)
        if (nextRunAt > 0) {
            updateRunState(taskId, nextRunAt, lastRunAt)
        } else {
            // 无下次执行时间(once / 非法 cron),禁用任务避免每分钟重复触发
            setEnabled(taskId, false, lastRunAt)
        }
    }
}
