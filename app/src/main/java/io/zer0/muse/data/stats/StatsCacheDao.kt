package io.zer0.muse.data.stats

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * v1.107 统计缓存 DAO。
 */
@Dao
interface StatsCacheDao {

    @Query("SELECT * FROM stats_cache WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): StatsCacheEntity?

    @Query("SELECT * FROM stats_cache WHERE `key` = :key LIMIT 1")
    fun observe(key: String): Flow<StatsCacheEntity?>

    @Query("SELECT * FROM stats_cache")
    fun observeAll(): Flow<List<StatsCacheEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: StatsCacheEntity)

    @Query("DELETE FROM stats_cache WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM stats_cache")
    suspend fun deleteAll()

    @Query("UPDATE stats_cache SET value = :value, updatedAt = :updatedAt WHERE `key` = :key")
    suspend fun updateValue(key: String, value: String, updatedAt: Long)
}
