package io.zer0.muse.data.agentbot

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Bot 标注的读写口。只做存取，判定在 [BotAnnotationService]。 */
@Dao
interface BotAnnotationDao {

    @Upsert
    suspend fun upsert(annotation: BotAnnotationEntity)

    @Upsert
    suspend fun upsertAll(annotations: List<BotAnnotationEntity>)

    @Query("SELECT * FROM bot_annotations WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): BotAnnotationEntity?

    @Query("SELECT * FROM bot_annotations WHERE targetMessageId = :messageId AND status = 'ACTIVE' ORDER BY createdAt ASC")
    suspend fun activeForMessage(messageId: String): List<BotAnnotationEntity>

    @Query("SELECT * FROM bot_annotations WHERE targetMessageId = :messageId AND status = 'ACTIVE' ORDER BY createdAt ASC")
    fun observeActiveForMessage(messageId: String): Flow<List<BotAnnotationEntity>>

    @Query("SELECT * FROM bot_annotations WHERE originSessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun listBySession(sessionId: String): List<BotAnnotationEntity>

    /** 同一条消息上、同一类型的标注：用于"改表情"而不是"再挂一个"。 */
    @Query(
        "SELECT * FROM bot_annotations WHERE targetMessageId = :messageId " +
            "AND actorType = :actorType AND kind = :kind AND status = 'ACTIVE' LIMIT 1",
    )
    suspend fun findActive(messageId: String, actorType: String, kind: String): BotAnnotationEntity?

    /** 把某条消息的全部标注标为失效（正文被编辑时调用）。 */
    @Query("UPDATE bot_annotations SET status = 'STALE' WHERE targetMessageId = :messageId AND status = 'ACTIVE'")
    suspend fun markStaleForMessage(messageId: String): Int

    @Query("UPDATE bot_annotations SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String): Int

    @Query("SELECT * FROM bot_annotations")
    suspend fun getAll(): List<BotAnnotationEntity>

    @Query("DELETE FROM bot_annotations WHERE originSessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM bot_annotations")
    suspend fun deleteAll()
}
