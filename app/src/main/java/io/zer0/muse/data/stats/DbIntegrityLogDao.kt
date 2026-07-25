package io.zer0.muse.data.stats

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * v1.107 数据库完整性校验日志 DAO。
 */
@Dao
interface DbIntegrityLogDao {

    @Insert
    suspend fun insert(entity: DbIntegrityLogEntity): Long

    @Query("SELECT * FROM db_integrity_log ORDER BY checkedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 10): Flow<List<DbIntegrityLogEntity>>

    @Query("SELECT * FROM db_integrity_log ORDER BY checkedAt DESC LIMIT 1")
    suspend fun getLatest(): DbIntegrityLogEntity?

    @Query("DELETE FROM db_integrity_log WHERE id NOT IN (SELECT id FROM db_integrity_log ORDER BY checkedAt DESC LIMIT :keepCount)")
    suspend fun trim(keepCount: Int = 30)
}
