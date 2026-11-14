package io.zer0.memory.dream

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MemoryDreamDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRevision(entity: MemoryDreamRevisionEntity)

    @Query(
        "SELECT * FROM memory_dream_revisions WHERE revisionId = :revisionId AND assistantId = :assistantId AND scope = :scope AND spaceId = :spaceId",
    )
    suspend fun findRevision(revisionId: String, assistantId: String, scope: String, spaceId: String): MemoryDreamRevisionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(entity: MemoryDreamStateEntity)

    @Query("SELECT * FROM memory_dream_states WHERE assistantId = :assistantId AND scope = :scope AND spaceId = :spaceId")
    suspend fun state(assistantId: String, scope: String, spaceId: String): MemoryDreamStateEntity?
}
