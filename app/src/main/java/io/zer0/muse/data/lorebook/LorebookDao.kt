package io.zer0.muse.data.lorebook

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Lorebook DAO — Phase 8.5。
 * - observeAll: 全部世界书(管理页用,按 sortIndex + name 排序)
 * - observeEnabled: 仅启用的(注入时用)
 * - getByIds: 按 id 列表查(Assistant 绑定的)
 */
@Dao
interface LorebookDao {
    @Query("SELECT * FROM lorebooks ORDER BY priority DESC, name ASC")
    fun observeAll(): Flow<List<LorebookEntity>>

    @Query("SELECT * FROM lorebooks WHERE enabled = 1 ORDER BY priority DESC, name ASC")
    fun observeEnabled(): Flow<List<LorebookEntity>>

    @Query("SELECT * FROM lorebooks WHERE id IN (:ids) AND enabled = 1 ORDER BY priority DESC, name ASC")
    suspend fun getByIdsEnabled(ids: List<String>): List<LorebookEntity>

    @Query("SELECT * FROM lorebooks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): LorebookEntity?

    @Upsert
    suspend fun upsert(entity: LorebookEntity)

    @Delete
    suspend fun delete(entity: LorebookEntity)

    @Query("DELETE FROM lorebooks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<LorebookEntity>)

    @Query("SELECT * FROM lorebooks")
    suspend fun getAll(): List<LorebookEntity>

    @Query("DELETE FROM lorebooks")
    suspend fun deleteAll()
}
