package io.zer0.muse.data.artifact

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Artifact DAO。
 */
@Dao
interface ArtifactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ArtifactEntity)

    // L12 修复: 批量 upsert 事务,保证多条产物原子写入,避免部分写入失败导致数据不一致
    @Transaction
    suspend fun upsertAll(list: List<ArtifactEntity>) = list.forEach { upsert(it) }

    @Query("DELETE FROM artifacts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM artifacts WHERE sessionId = :sessionId ORDER BY createdAt DESC")
    fun observeBySession(sessionId: String): Flow<List<ArtifactEntity>>

    @Query("SELECT * FROM artifacts WHERE messageId = :messageId ORDER BY createdAt ASC")
    suspend fun getByMessage(messageId: String): List<ArtifactEntity>

    @Query("SELECT * FROM artifacts WHERE messageId = :messageId ORDER BY createdAt ASC")
    fun observeByMessage(messageId: String): Flow<List<ArtifactEntity>>

    @Query("SELECT * FROM artifacts WHERE id = :id")
    suspend fun getById(id: String): ArtifactEntity?

    @Query("SELECT * FROM artifacts")
    suspend fun getAll(): List<ArtifactEntity>

    @Query("DELETE FROM artifacts")
    suspend fun deleteAll()
}
