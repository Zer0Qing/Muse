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

    @Query(
        """
        SELECT * FROM session_summaries
        WHERE summary != ''
          AND updated_at >= :startISO AND updated_at <= :endISO
          AND (:since IS NULL OR updated_at > :since)
          AND (
            :assistantId IS NULL OR
            assistant_id = '' OR assistant_id IS NULL OR assistant_id = :assistantId
          )
          AND (
            :spaceId IS NULL OR
            space_id = '' OR space_id IS NULL OR space_id = :spaceId
          )
        ORDER BY updated_at DESC
    """,
    )
    suspend fun getInRange(
        startISO: String,
        endISO: String,
        since: String?,
        assistantId: String? = null,
        spaceId: String? = null,
    ): List<SessionSummaryEntity>

    /** 获取所有"脏" session(summary !== snapshot)。 */
    @Query("SELECT * FROM session_summaries WHERE summary != '' AND summary != snapshot")
    suspend fun getDirty(): List<SessionSummaryEntity>

    @Query("UPDATE session_summaries SET snapshot = summary, snapshot_at = :now WHERE session_id = :sessionId")
    suspend fun markProcessed(sessionId: String, now: String)

    @Query("SELECT summary_revision FROM session_summaries WHERE session_id = :sessionId")
    suspend fun revisionOf(sessionId: String): Long?

    @Query(
        """
        UPDATE session_summaries
        SET summary_revision = summary_revision + 1,
            source_fingerprint = :sourceFingerprint
        WHERE session_id = :sessionId
          AND summary_revision = :expectedRevision
        """,
    )
    suspend fun bumpRevisionIfCurrent(sessionId: String, expectedRevision: Long, sourceFingerprint: String): Int

    @Query(
        """
        UPDATE session_summaries
        SET snapshot = summary,
            snapshot_at = :now,
            fact_replacement_required = 0
        WHERE session_id = :sessionId
          AND summary_revision = :expectedRevision
        """,
    )
    suspend fun markProcessedIfCurrent(sessionId: String, expectedRevision: Long, now: String): Int

    @Query(
        """
        UPDATE session_summaries
        SET summary_revision = summary_revision + 1,
            message_count = :retainedMessageCount,
            fact_replacement_required = 1
        WHERE session_id = :sessionId
        """,
    )
    suspend fun markBranchChanged(sessionId: String, retainedMessageCount: Int): Int

    @Query("DELETE FROM session_summaries")
    suspend fun deleteAll()

    /** P2: 删除指定 session 的摘要(用于记忆页 UI 删除 Summary 层条目)。 */
    @Query("DELETE FROM session_summaries WHERE session_id = :sessionId")
    suspend fun deleteById(sessionId: String)

    /**
     * 助手被删除时清掉它名下的全部会话摘要。
     *
     * 会话行本身不在这里删 —— 已属于该助手的对话保留下来,只是失去绑定。
     *
     * @return 实际删除的行数
     */
    @Query("DELETE FROM session_summaries WHERE assistant_id = :assistantId")
    suspend fun deleteByAssistant(assistantId: String): Int

    /** 指定 Space 下有哪些助手（合并前读，用来推导受影响的作用域）。 */
    @Query("SELECT DISTINCT assistant_id FROM session_summaries WHERE space_id = :spaceId")
    suspend fun assistantIdsForSpace(spaceId: String): List<String>

    /**
     * Space 合并：摘要本身不拆不改，只把归属空间换掉。
     *
     * 摘要正文是按会话写的，与空间无关；丢掉空间归属反而会让它从默认空间的取数窗口里消失。
     */
    @Query("UPDATE session_summaries SET space_id = :targetSpaceId WHERE space_id = :sourceSpaceId")
    suspend fun migrateSpace(sourceSpaceId: String, targetSpaceId: String): Int
}
