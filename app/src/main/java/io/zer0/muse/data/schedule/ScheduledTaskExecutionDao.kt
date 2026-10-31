package io.zer0.muse.data.schedule

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ScheduledTaskExecutionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(execution: ScheduledTaskExecutionEntity)

    /** 每个任务最近一次执行的结局状态(任务一览下发 lastResult 用)。 */
    data class TaskLatestStatus(
        @ColumnInfo(name = "task_id") val taskId: String,
        @ColumnInfo(name = "status") val status: String,
    )

    /**
     * 一次拿全部任务最近一次执行的状态,不逐条查。
     *
     * 同一时刻可能有多条执行记录(手动立即执行与轮询撞在一起),相关子查询会把它们都带出来;
     * 调用方按 taskId 归档时后者覆盖前者,状态先后差异不影响「最近一次」的语义。
     */
    @Query(
        """
        SELECT e.task_id AS task_id, e.status AS status
        FROM scheduled_task_executions e
        WHERE e.executed_at = (
            SELECT MAX(x.executed_at) FROM scheduled_task_executions x WHERE x.task_id = e.task_id
        )
        """,
    )
    suspend fun latestStatusByTask(): List<TaskLatestStatus>

    /** 查询某任务最近 10 次执行记录(按执行时间倒序)。 */
    @Query("SELECT * FROM scheduled_task_executions WHERE task_id = :taskId ORDER BY executed_at DESC LIMIT 10")
    suspend fun queryByTaskId(taskId: String): List<ScheduledTaskExecutionEntity>

    /** U-25: 查询某任务全部执行历史(上限 100 条,供"查看全部"对话框使用)。 */
    @Query("SELECT * FROM scheduled_task_executions WHERE task_id = :taskId ORDER BY executed_at DESC LIMIT 100")
    suspend fun queryAllByTaskId(taskId: String): List<ScheduledTaskExecutionEntity>

    /** 清空某任务的所有执行历史。 */
    @Query("DELETE FROM scheduled_task_executions WHERE task_id = :taskId")
    suspend fun deleteByTaskId(taskId: String)

    @Query("SELECT * FROM scheduled_task_executions")
    suspend fun getAll(): List<ScheduledTaskExecutionEntity>

    @Query("DELETE FROM scheduled_task_executions")
    suspend fun deleteAll()
}
