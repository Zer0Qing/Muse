package io.zer0.muse.data.stats

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * v1.107 自动备份日志 DAO。
 */
@Dao
interface AutoBackupLogDao {

    @Insert
    suspend fun insert(entity: AutoBackupLogEntity): Long

    @Query("SELECT * FROM auto_backup_log ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<AutoBackupLogEntity>>

    @Query("SELECT * FROM auto_backup_log WHERE status = 'success' ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestSuccess(): AutoBackupLogEntity?

    @Query("SELECT COUNT(*) FROM auto_backup_log WHERE status = 'success'")
    suspend fun countSuccess(): Int

    @Query("DELETE FROM auto_backup_log WHERE id NOT IN (SELECT id FROM auto_backup_log ORDER BY createdAt DESC LIMIT :keepCount)")
    suspend fun trim(keepCount: Int = 7)

    @Query("DELETE FROM auto_backup_log WHERE createdAt < :before")
    suspend fun deleteOlderThan(before: Long)
}
