package io.zer0.muse.data.schedule

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ScheduledTaskExecutionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(execution: ScheduledTaskExecutionEntity)

    /** 查询某任务最近 10 次执行记录(按执行时间倒序)。 */
    @Query("SELECT * FROM scheduled_task_executions WHERE task_id = :taskId ORDER BY executed_at DESC LIMIT 10")
    suspend fun queryByTaskId(taskId: String): List<ScheduledTaskExecutionEntity>

    /** 清空某任务的所有执行历史。 */
    @Query("DELETE FROM scheduled_task_executions WHERE task_id = :taskId")
    suspend fun deleteByTaskId(taskId: String)

    @Query("SELECT * FROM scheduled_task_executions")
    suspend fun getAll(): List<ScheduledTaskExecutionEntity>

    @Query("DELETE FROM scheduled_task_executions")
    suspend fun deleteAll()
}
