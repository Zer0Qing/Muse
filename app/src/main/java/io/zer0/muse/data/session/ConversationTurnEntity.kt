package io.zer0.muse.data.session

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 持久化的生成回合状态。
 *
 * turnId 是 finalize 幂等边界；旧 streamId 或旧 generationSerial 的事件不得覆盖新回合。
 */
@Entity(
    tableName = "conversation_turns",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sessionId", "startedAt"], name = "idx_conversation_turns_session_started"),
        Index(value = ["sessionId", "phase"], name = "idx_conversation_turns_session_phase"),
        Index(value = ["assistantMessageId"], name = "idx_conversation_turns_assistant"),
    ],
)
data class ConversationTurnEntity(
    @PrimaryKey val turnId: String,
    val sessionId: String,
    val inputUserMessageId: String,
    val assistantMessageId: String,
    val phase: String,
    @ColumnInfo(defaultValue = "NULL") val streamId: String? = null,
    @ColumnInfo(defaultValue = "0") val generationSerial: Long = 0,
    val startedAt: Long,
    @ColumnInfo(defaultValue = "NULL") val finishedAt: Long? = null,
    val updatedAt: Long,
)

@Dao
interface ConversationTurnDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(turn: ConversationTurnEntity)

    @Query("SELECT * FROM conversation_turns WHERE turnId = :turnId LIMIT 1")
    suspend fun getById(turnId: String): ConversationTurnEntity?

    @Query("SELECT * FROM conversation_turns WHERE sessionId = :sessionId ORDER BY startedAt ASC")
    suspend fun getBySession(sessionId: String): List<ConversationTurnEntity>

    @Query("SELECT * FROM conversation_turns WHERE sessionId = :sessionId AND finishedAt IS NULL ORDER BY startedAt ASC")
    suspend fun getPendingBySession(sessionId: String): List<ConversationTurnEntity>

    @Query("UPDATE conversation_turns SET phase = :phase, updatedAt = :updatedAt WHERE turnId = :turnId")
    suspend fun updatePhase(turnId: String, phase: String, updatedAt: Long)

    @Query("UPDATE conversation_turns SET phase = :phase, finishedAt = :finishedAt, updatedAt = :finishedAt WHERE turnId = :turnId AND finishedAt IS NULL")
    suspend fun finishIfOpen(turnId: String, phase: String, finishedAt: Long): Int

    @Query("DELETE FROM conversation_turns WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}
