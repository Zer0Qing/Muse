package io.zer0.memory.ai

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * v1.0.52 P2-3: 记忆知识图谱边 DAO。
 *
 * 提供记忆关系的 CRUD + 按 Space/Scope 隔离查询。
 * 与 [io.zer0.memory.fact.FactDao] 配合,在事实删除时通过 [deleteByFactId] 清理孤儿边。
 */
@Dao
interface MemoryLinkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(link: MemoryLinkEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(links: List<MemoryLinkEntity>): List<Long>

    /**
     * 查询指定 Space + Scope 内的所有关系。
     * 用于知识图谱可视化/检索时加载边集合。
     */
    @Query(
        """
        SELECT * FROM memory_links
        WHERE space_id = :spaceId AND scope = :scope
        ORDER BY weight DESC, created_at DESC
        """
    )
    suspend fun listBySpaceAndScope(spaceId: String, scope: String): List<MemoryLinkEntity>

    /** 查询指定 Space 下所有作用域的关系，用于记忆中心“全部作用域”视图。 */
    @Query(
        """
        SELECT * FROM memory_links
        WHERE space_id = :spaceId
        ORDER BY weight DESC, created_at DESC
        """
    )
    suspend fun listBySpace(spaceId: String): List<MemoryLinkEntity>

    /**
     * 观察指定 Space + Scope 的关系列表(Flow 形式,UI 实时刷新)。
     */
    @Query(
        """
        SELECT * FROM memory_links
        WHERE space_id = :spaceId AND scope = :scope
        ORDER BY weight DESC, created_at DESC
        """
    )
    fun observeBySpaceAndScope(spaceId: String, scope: String): Flow<List<MemoryLinkEntity>>

    /**
     * 查询与指定事实相关的所有边(作为源或目标)。
     * 用于查看某条事实的关联记忆。
     */
    @Query(
        """
        SELECT * FROM memory_links
        WHERE source_fact_id = :factId OR target_fact_id = :factId
        ORDER BY weight DESC
        """
    )
    suspend fun listByFactId(factId: Long): List<MemoryLinkEntity>

    /**
     * 删除与指定事实相关的所有边(事实删除时调用,清理孤儿边)。
     */
    @Query("DELETE FROM memory_links WHERE source_fact_id = :factId OR target_fact_id = :factId")
    suspend fun deleteByFactId(factId: Long): Int

    /**
     * 删除指定 Space 内的所有边(Space 删除时调用,与事实迁移配合)。
     * 注意:Space 删除时事实会迁移到默认 Space,边也应迁移而非删除,
     * 此方法仅用于 Space 强制清空场景。
     */
    @Query("DELETE FROM memory_links WHERE space_id = :spaceId")
    suspend fun deleteBySpaceId(spaceId: String): Int

    /**
     * 迁移指定 Space 内的边到目标 Space(与 [MemorySpaceDao.migrateFacts] 配合)。
     */
    @Query("UPDATE memory_links SET space_id = :targetSpaceId WHERE space_id = :sourceSpaceId")
    suspend fun migrateBySpaceId(sourceSpaceId: String, targetSpaceId: String): Int

    /** 统计指定 Space + Scope 内的边数量。 */
    @Query("SELECT COUNT(*) FROM memory_links WHERE space_id = :spaceId AND scope = :scope")
    suspend fun countBySpaceAndScope(spaceId: String, scope: String): Int

    /** 按 id 删除单条边。 */
    @Query("DELETE FROM memory_links WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    /** 清空所有边(仅用于测试/重置)。 */
    @Query("DELETE FROM memory_links")
    suspend fun deleteAll(): Int
}
