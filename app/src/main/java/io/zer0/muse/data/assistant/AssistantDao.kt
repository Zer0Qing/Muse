package io.zer0.muse.data.assistant

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Assistant 数据访问对象(Phase 8.2)。
 */
@Dao
interface AssistantDao {

    @Upsert
    suspend fun upsert(assistant: AssistantEntity)

    /**
     * M-AST1: INSERT OR IGNORE — 用于 [AssistantRepository.ensureDefaultExists] 的原子插入,
     * 避免 check-then-insert TOCTOU 竞态。冲突时静默忽略(返回 rowId=-1)。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(assistant: AssistantEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(assistants: List<AssistantEntity>)

    @Query("SELECT * FROM assistants ORDER BY sortIndex ASC, createdAt ASC")
    fun observeAll(): Flow<List<AssistantEntity>>

    @Query("SELECT * FROM assistants ORDER BY sortIndex ASC, createdAt ASC")
    suspend fun getAll(): List<AssistantEntity>

    @Query("SELECT * FROM assistants WHERE id = :id")
    suspend fun getById(id: String): AssistantEntity?

    @Query("SELECT COUNT(*) FROM assistants")
    suspend fun count(): Int

    @Query("DELETE FROM assistants WHERE id = :id")
    suspend fun deleteById(id: String)

    @Delete
    suspend fun delete(assistant: AssistantEntity)

    @Query("DELETE FROM assistants")
    suspend fun deleteAll()

    // ── v1.107 冗余字段维护方法 ──

    /** v1.107: 原子增减 Assistant 消息计数。 */
    @Query("UPDATE assistants SET messageCount = messageCount + :delta WHERE id = :id")
    suspend fun incrementMessageCount(id: String, delta: Int)

    /** v1.107: 更新 Assistant 最后使用时间。 */
    @Query("UPDATE assistants SET lastUsedAt = :timestamp WHERE id = :id")
    suspend fun updateLastUsedAt(id: String, timestamp: Long)
}
