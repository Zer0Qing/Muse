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
}
