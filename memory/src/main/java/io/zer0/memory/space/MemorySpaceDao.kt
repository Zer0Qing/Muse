package io.zer0.memory.space

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * v1.0.52 P2-2: 记忆空间数据访问对象。
 *
 * 提供记忆空间的 CRUD + 列表查询(带事实数量统计)。
 * 与 [io.zer0.memory.fact.FactDao] 配合,实现 facts 表按 space_id 隔离。
 */
@Dao
interface MemorySpaceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(space: MemorySpaceEntity): Long

    /**
     * 列出所有 Space,按 sortIndex 升序。
     * 不含事实数量(轻量查询,用于切换器下拉)。
     */
    @Query("SELECT * FROM memory_spaces ORDER BY sort_index ASC, created_at ASC")
    suspend fun listAll(): List<MemorySpaceEntity>

    /**
     * 观察 Space 列表(Flow 形式,UI 实时刷新)。
     */
    @Query("SELECT * FROM memory_spaces ORDER BY sort_index ASC, created_at ASC")
    fun observeAll(): Flow<List<MemorySpaceEntity>>

    /**
     * 列出所有 Space + 关联事实数量(LEFT JOIN COUNT)。
     * 用于管理页展示每个 Space 的事实统计。
     */
    @Query("""
        SELECT s.id AS id, s.name AS name, s.icon AS icon, s.description AS description,
               s.created_at AS created_at, s.sort_index AS sort_index,
               COUNT(f.id) AS fact_count
        FROM memory_spaces s
        LEFT JOIN facts f ON f.space_id = s.id
        GROUP BY s.id
        ORDER BY s.sort_index ASC, s.created_at ASC
    """)
    suspend fun listAllWithCount(): List<MemorySpaceWithCount>

    /**
     * 观察 Space 列表 + 事实数量(Flow 形式)。
     */
    @Query("""
        SELECT s.id AS id, s.name AS name, s.icon AS icon, s.description AS description,
               s.created_at AS created_at, s.sort_index AS sort_index,
               COUNT(f.id) AS fact_count
        FROM memory_spaces s
        LEFT JOIN facts f ON f.space_id = s.id
        GROUP BY s.id
        ORDER BY s.sort_index ASC, s.created_at ASC
    """)
    fun observeAllWithCount(): Flow<List<MemorySpaceWithCount>>

    @Query("SELECT * FROM memory_spaces WHERE id = :id")
    suspend fun getById(id: String): MemorySpaceEntity?

    @Query("DELETE FROM memory_spaces WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT COUNT(*) FROM memory_spaces")
    suspend fun count(): Int

    /**
     * 重命名 Space(仅改 name,保留其他字段)。
     */
    @Query("UPDATE memory_spaces SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String): Int

    /**
     * 更新 Space 排序序号(批量调整顺序用)。
     */
    @Query("UPDATE memory_spaces SET sort_index = :sortIndex WHERE id = :id")
    suspend fun updateSortIndex(id: String, sortIndex: Int): Int

    /**
     * 统计指定 Space 下的事实数量。
     * 用于删除 Space 前的确认提示。
     */
    @Query("SELECT COUNT(*) FROM facts WHERE space_id = :spaceId")
    suspend fun countFactsInSpace(spaceId: String): Int

    /**
     * 把指定 Space 下的所有事实迁移到另一个 Space。
     * 用于删除 Space 前的事实迁移。
     */
    @Query("UPDATE facts SET space_id = :targetSpaceId WHERE space_id = :sourceSpaceId")
    suspend fun migrateFacts(sourceSpaceId: String, targetSpaceId: String): Int
}
