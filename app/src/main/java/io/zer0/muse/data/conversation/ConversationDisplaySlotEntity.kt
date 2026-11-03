package io.zer0.muse.data.conversation

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import io.zer0.muse.data.session.SessionEntity
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "conversation_display_slots",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sessionId", "logicalPosition"], unique = true),
        Index(value = ["parentSlotId"]),
    ],
)
data class ConversationDisplaySlotEntity(
    @PrimaryKey val slotId: String,
    val sessionId: String,
    val kind: String,
    @ColumnInfo(defaultValue = "NULL") val parentSlotId: String? = null,
    val logicalPosition: Long,
    val messageId: String,
)

@Dao
interface ConversationDisplaySlotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(slot: ConversationDisplaySlotEntity)

    @Query("SELECT * FROM conversation_display_slots WHERE slotId = :slotId LIMIT 1")
    suspend fun find(slotId: String): ConversationDisplaySlotEntity?

    @Query(
        "SELECT COALESCE(MAX(logicalPosition), 0) FROM conversation_display_slots " +
            "WHERE sessionId = :sessionId",
    )
    suspend fun maxLogicalPosition(sessionId: String): Long

    @Query(
        "SELECT * FROM conversation_display_slots " +
            "WHERE sessionId = :sessionId ORDER BY logicalPosition ASC LIMIT :limit",
    )
    suspend fun readWindow(sessionId: String, limit: Int): List<ConversationDisplaySlotEntity>

    @Query(
        "SELECT * FROM conversation_display_slots " +
            "WHERE sessionId = :sessionId AND logicalPosition < :beforePosition " +
            "ORDER BY logicalPosition DESC LIMIT :limit",
    )
    suspend fun readBefore(sessionId: String, beforePosition: Long, limit: Int): List<ConversationDisplaySlotEntity>

    @Query(
        "SELECT * FROM conversation_display_slots " +
            "WHERE sessionId = :sessionId AND logicalPosition > :afterPosition " +
            "ORDER BY logicalPosition ASC LIMIT :limit",
    )
    suspend fun readAfter(sessionId: String, afterPosition: Long, limit: Int): List<ConversationDisplaySlotEntity>

    @Query(
        "SELECT * FROM conversation_display_slots " +
            "WHERE sessionId = :sessionId AND logicalPosition >= :position " +
            "ORDER BY logicalPosition ASC LIMIT :limit",
    )
    suspend fun readFrom(sessionId: String, position: Long, limit: Int): List<ConversationDisplaySlotEntity>

    @Query(
        "SELECT * FROM conversation_display_slots " +
            "WHERE sessionId = :sessionId AND logicalPosition <= :position " +
            "ORDER BY logicalPosition DESC LIMIT :limit",
    )
    suspend fun readThrough(sessionId: String, position: Long, limit: Int): List<ConversationDisplaySlotEntity>

    @Query("SELECT * FROM conversation_display_slots")
    suspend fun getAll(): List<ConversationDisplaySlotEntity>

    @Query("DELETE FROM conversation_display_slots")
    suspend fun deleteAll()
}
