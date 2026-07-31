package io.zer0.muse.worldbook

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * P1-2: Worldbook DAO。
 *
 * 查询排序统一为 priority DESC, name ASC(与 LorebookDao 一致)。
 */
@Dao
interface WorldBookDao {
    @Query("SELECT * FROM worldbook_entries ORDER BY priority DESC, name ASC")
    fun observeAll(): Flow<List<WorldBookEntryEntity>>

    @Query("SELECT * FROM worldbook_entries WHERE enabled = 1 ORDER BY priority DESC, name ASC")
    fun observeEnabled(): Flow<List<WorldBookEntryEntity>>

    /** 取全部启用条目(注入器用,含 alwaysActive 和关键词触发)。 */
    @Query("SELECT * FROM worldbook_entries WHERE enabled = 1 ORDER BY priority DESC, name ASC")
    suspend fun getEnabled(): List<WorldBookEntryEntity>

    /** 取常驻激活条目(alwaysActive=1 且 enabled=1),按 assistantId 过滤(null = 全局)。 */
    @Query(
        """
        SELECT * FROM worldbook_entries
        WHERE enabled = 1 AND alwaysActive = 1
          AND (assistantId IS NULL OR assistantId = :assistantId)
        ORDER BY priority DESC, name ASC
        """
    )
    suspend fun getAlwaysActive(assistantId: String?): List<WorldBookEntryEntity>

    @Query("SELECT * FROM worldbook_entries WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): WorldBookEntryEntity?

    @Upsert
    suspend fun upsert(entity: WorldBookEntryEntity)

    @Delete
    suspend fun delete(entity: WorldBookEntryEntity)

    @Query("DELETE FROM worldbook_entries WHERE id = :id")
    suspend fun deleteById(id: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<WorldBookEntryEntity>)

    @Query("SELECT * FROM worldbook_entries")
    suspend fun getAll(): List<WorldBookEntryEntity>

    @Query("DELETE FROM worldbook_entries")
    suspend fun deleteAll()
}
