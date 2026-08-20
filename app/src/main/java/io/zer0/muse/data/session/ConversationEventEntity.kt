package io.zer0.muse.data.session

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 对话链路的追加式影子事件。
 *
 * 事件用于审计、去重和重放诊断，不替代现有 messages 表。默认只保存结构化摘要、
 * 长度和 hash，避免把每个流式 delta 再复制一份到数据库。
 */
@Entity(
    tableName = "conversation_events",
    primaryKeys = ["sessionId", "eventSeq"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sessionId", "turnId", "eventSeq"], name = "idx_conversation_events_session_turn_seq"),
        Index(value = ["turnId", "streamId"], name = "idx_conversation_events_turn_stream"),
        Index(value = ["eventId"], unique = true, name = "idx_conversation_events_event_id"),
    ],
)
data class ConversationEventEntity(
    val sessionId: String,
    val eventSeq: Long,
    val eventId: String,
    val turnId: String,
    @ColumnInfo(defaultValue = "NULL") val streamId: String? = null,
    val type: String,
    val payloadJson: String,
    val payloadHash: String,
    val payloadLength: Int,
    @ColumnInfo(defaultValue = "NULL") val provider: String? = null,
    @ColumnInfo(defaultValue = "NULL") val modelId: String? = null,
    @ColumnInfo(defaultValue = "0") val sequenceInStream: Long = 0,
    @ColumnInfo(defaultValue = "0") val generationSerial: Long = 0,
    val createdAt: Long,
)

@Dao
interface ConversationEventDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: ConversationEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(events: List<ConversationEventEntity>)

    @Query("SELECT COALESCE(MAX(eventSeq), 0) + 1 FROM conversation_events WHERE sessionId = :sessionId")
    suspend fun nextEventSeq(sessionId: String): Long

    @Query("SELECT * FROM conversation_events WHERE turnId = :turnId ORDER BY eventSeq ASC")
    suspend fun getByTurn(turnId: String): List<ConversationEventEntity>

    @Query("SELECT * FROM conversation_events WHERE sessionId = :sessionId ORDER BY eventSeq ASC LIMIT :limit")
    suspend fun getBySession(sessionId: String, limit: Int): List<ConversationEventEntity>

    @Query("SELECT COUNT(*) FROM conversation_events")
    suspend fun count(): Int

    @Query("DELETE FROM conversation_events WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}
