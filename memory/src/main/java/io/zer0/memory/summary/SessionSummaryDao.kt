package io.zer0.memory.summary

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SessionSummaryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SessionSummaryEntity)

    @Query("SELECT * FROM session_summaries WHERE session_id = :sessionId")
    suspend fun get(sessionId: String): SessionSummaryEntity?

    @Query("SELECT * FROM session_summaries WHERE summary != '' ORDER BY updated_at DESC")
    suspend fun getAll(): List<SessionSummaryEntity>

    @Query("""
        SELECT * FROM session_summaries
        WHERE summary != ''
          AND updated_at >= :startISO AND updated_at <= :endISO
          AND (:since IS NULL OR updated_at > :since)
        ORDER BY updated_at DESC
    """)
    suspend fun getInRange(
        startISO: String,
        endISO: String,
        since: String?,
    ): List<SessionSummaryEntity>

    /** 获取所有"脏" session(summary !== snapshot)。 */
    @Query("SELECT * FROM session_summaries WHERE summary != '' AND summary != snapshot")
    suspend fun getDirty(): List<SessionSummaryEntity>

    @Query("UPDATE session_summaries SET snapshot = summary, snapshot_at = :now WHERE session_id = :sessionId")
    suspend fun markProcessed(sessionId: String, now: String)

    @Query("DELETE FROM session_summaries")
    suspend fun deleteAll()

    /** P2: 删除指定 session 的摘要(用于记忆页 UI 删除 Summary 层条目)。 */
    @Query("DELETE FROM session_summaries WHERE session_id = :sessionId")
    suspend fun deleteById(sessionId: String)
}
