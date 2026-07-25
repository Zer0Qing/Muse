package io.zer0.muse.data.promptinjection

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * PromptInjection DAO — Phase 8.5。
 */
@Dao
interface PromptInjectionDao {
    @Query("SELECT * FROM prompt_injections ORDER BY mode ASC, priority DESC, name ASC")
    fun observeAll(): Flow<List<PromptInjectionEntity>>

    @Query("SELECT * FROM prompt_injections WHERE enabled = 1 AND mode = :mode ORDER BY priority DESC, name ASC")
    suspend fun getEnabledByMode(mode: String): List<PromptInjectionEntity>

    @Query("SELECT * FROM prompt_injections WHERE id IN (:ids) AND enabled = 1 ORDER BY priority DESC, name ASC")
    suspend fun queryByIdsEnabled(ids: List<String>): List<PromptInjectionEntity>

    /**
     * 按 id 批量取启用条目。空列表短路返回空,避免 `IN ()` SQL 语法错误(L-PID6)。
     * Repository 层亦有空列表防护,此处为 DAO 层兜底(defense in depth)。
     */
    suspend fun getByIdsEnabled(ids: List<String>): List<PromptInjectionEntity> =
        if (ids.isEmpty()) emptyList() else queryByIdsEnabled(ids)

    @Query("SELECT * FROM prompt_injections WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PromptInjectionEntity?

    @Upsert
    suspend fun upsert(entity: PromptInjectionEntity)

    @Delete
    suspend fun delete(entity: PromptInjectionEntity)

    @Query("DELETE FROM prompt_injections WHERE id = :id")
    suspend fun deleteById(id: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<PromptInjectionEntity>)

    @Query("SELECT * FROM prompt_injections")
    suspend fun getAll(): List<PromptInjectionEntity>

    @Query("DELETE FROM prompt_injections")
    suspend fun deleteAll()
}
