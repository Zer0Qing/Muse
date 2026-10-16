package io.zer0.memory.fact

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** v13 (T4-1): 事实修订记录 DAO。 */
@Dao
interface FactRevisionDao {

    @Insert
    suspend fun insert(entity: FactRevisionEntity): Long

    /** 按事实 id 查询修订历史(新→旧)。 */
    @Query("SELECT * FROM fact_revisions WHERE fact_id = :factId ORDER BY changed_at DESC, id DESC LIMIT :limit")
    suspend fun getByFactId(factId: Long, limit: Int = 20): List<FactRevisionEntity>

    /** 清理指定事实的全部修订(事实删除时)。 */
    @Query("DELETE FROM fact_revisions WHERE fact_id = :factId")
    suspend fun deleteByFactId(factId: Long): Int

    /** v2.6.2（记忆审计）: 清空全部修订（记忆重置时）。 */
    @Query("DELETE FROM fact_revisions")
    suspend fun deleteAll()

    /**
     * v2.6.2（记忆审计）: 清理孤儿修订 — 对应事实已不存在的修订。
     * 用于批量衰减删除（applyDecay）后统一清理。
     */
    @Query("DELETE FROM fact_revisions WHERE fact_id NOT IN (SELECT id FROM facts)")
    suspend fun deleteOrphans(): Int

    /** 总数(防膨胀监控)。 */
    @Query("SELECT COUNT(*) FROM fact_revisions")
    suspend fun count(): Int
}
