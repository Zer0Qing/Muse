package io.zer0.muse.data.media

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for persisted Media Studio generation tasks and generated assets.
 *
 * 备份要整表导出与清空，入口数因此超过接口阈值；每个入口对应一种独立的 SQL 语义，
 * 合并只会让调用方猜不到副作用。
 */
@Dao
@Suppress("TooManyFunctions")
interface MediaGenDao {
    @Query("SELECT * FROM media_gen_tasks ORDER BY createdAt DESC LIMIT 100")
    fun observeAllTasks(): Flow<List<MediaGenTaskEntity>>

    @Query("SELECT * FROM media_gen_tasks WHERE status IN ('QUEUED', 'RUNNING') ORDER BY createdAt DESC")
    fun observeActiveTasks(): Flow<List<MediaGenTaskEntity>>

    @Query("SELECT * FROM media_gen_assets ORDER BY createdAt DESC LIMIT 200")
    fun observeAllAssets(): Flow<List<MediaGenAssetEntity>>

    @Query("SELECT * FROM media_gen_tasks WHERE id = :id")
    suspend fun getTaskById(id: String): MediaGenTaskEntity?

    @Query("SELECT * FROM media_gen_assets WHERE id = :id")
    suspend fun getAssetById(id: String): MediaGenAssetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: MediaGenTaskEntity)

    @Query("UPDATE media_gen_tasks SET status = :status, errorMessage = :errorMessage, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTaskStatus(id: String, status: String, errorMessage: String?, updatedAt: Long)

    @Query("UPDATE media_gen_tasks SET elapsedSeconds = :elapsedSeconds, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTaskProgress(id: String, elapsedSeconds: Int, updatedAt: Long)

    @Query(
        "UPDATE media_gen_tasks SET status = 'FAILED', errorMessage = :errorMessage, " +
            "updatedAt = :updatedAt WHERE status IN ('QUEUED', 'RUNNING')",
    )
    suspend fun markRunningTasksInterrupted(errorMessage: String, updatedAt: Long)

    @Query("DELETE FROM media_gen_tasks WHERE id = :id")
    suspend fun deleteTask(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAssets(assets: List<MediaGenAssetEntity>)

    @Query("UPDATE media_gen_assets SET favorite = :favorite WHERE id = :id")
    suspend fun setAssetFavorite(id: String, favorite: Boolean)

    @Query("DELETE FROM media_gen_assets WHERE id = :id")
    suspend fun deleteAsset(id: String)

    // 备份导出用：按 createdAt 升序保证两次导出的行顺序一致，便于对比差异。
    @Query("SELECT * FROM media_gen_tasks ORDER BY createdAt ASC")
    suspend fun getAllTasks(): List<MediaGenTaskEntity>

    @Query("SELECT * FROM media_gen_assets ORDER BY createdAt ASC")
    suspend fun getAllAssets(): List<MediaGenAssetEntity>

    // 备份恢复前整表清空，避免残留旧行与新快照混合。
    @Query("DELETE FROM media_gen_tasks")
    suspend fun deleteAllTasks()

    @Query("DELETE FROM media_gen_assets")
    suspend fun deleteAllAssets()
}
