package io.zer0.muse.data.quickmsg

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * QuickMessage DAO — Phase 8.5。
 */
@Dao
interface QuickMessageDao {
    @Query("SELECT * FROM quick_messages WHERE enabled = 1 ORDER BY sortIndex ASC, name ASC")
    fun observeAllEnabled(): Flow<List<QuickMessageEntity>>

    @Query("SELECT * FROM quick_messages ORDER BY sortIndex ASC, name ASC")
    fun observeAll(): Flow<List<QuickMessageEntity>>

    @Query("SELECT * FROM quick_messages WHERE enabled = 1 AND (scope = 'global' OR (scope = 'assistant' AND assistantId = :assistantId)) ORDER BY sortIndex ASC, name ASC")
    fun observeForAssistant(assistantId: String): Flow<List<QuickMessageEntity>>

    @Query("SELECT * FROM quick_messages WHERE id IN (:ids) AND enabled = 1 ORDER BY sortIndex ASC, name ASC")
    suspend fun getByIdsEnabled(ids: List<String>): List<QuickMessageEntity>

    @Query("SELECT * FROM quick_messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): QuickMessageEntity?

    @Upsert
    suspend fun upsert(entity: QuickMessageEntity)

    @Delete
    suspend fun delete(entity: QuickMessageEntity)

    @Query("DELETE FROM quick_messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<QuickMessageEntity>)

    @Query("SELECT * FROM quick_messages")
    suspend fun getAll(): List<QuickMessageEntity>

    @Query("DELETE FROM quick_messages")
    suspend fun deleteAll()
}
