package io.zer0.memory.fact

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FactProjectionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(projection: FactProjectionEntity)

    @Query("SELECT * FROM fact_projections WHERE generation_id = :generationId")
    suspend fun get(generationId: String): FactProjectionEntity?

    @Query("SELECT * FROM fact_projections WHERE session_id = :sessionId AND state = 'ACTIVE' ORDER BY source_revision DESC LIMIT 1")
    suspend fun activeForSession(sessionId: String): FactProjectionEntity?

    @Query("UPDATE fact_projections SET state = :state, updated_at = :updatedAt WHERE generation_id = :generationId")
    suspend fun updateState(generationId: String, state: String, updatedAt: String): Int

    @Query("SELECT * FROM fact_projections WHERE state = 'ORPHANED'")
    suspend fun orphaned(): List<FactProjectionEntity>

    @Query("UPDATE fact_projections SET state = 'ORPHANED', updated_at = :updatedAt WHERE state = 'STAGED' AND created_at < :before")
    suspend fun orphanStaleStaged(before: String, updatedAt: String): Int

    /**
     * Space 合并：投影 generation 也带空间。
     *
     * 不迁的话，这些 generation 后续发布时会把新事实写回已经不存在的空间，
     * 而读者（facts / 注入）只在 default 里找得到内容。
     */
    @Query("UPDATE fact_projections SET space_id = :targetSpaceId WHERE space_id = :sourceSpaceId")
    suspend fun migrateSpace(sourceSpaceId: String, targetSpaceId: String): Int
}
