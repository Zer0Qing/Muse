package io.zer0.muse.data.conversation

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.serialization.Serializable

/**
 * Durable result of an accepted conversation command.
 *
 * This record outlives the outbox work item so a retried command returns its
 * original result instead of executing a second time.
 */
@Entity(tableName = "conversation_command_receipts")
@Serializable
data class ConversationCommandReceiptEntity(
    @PrimaryKey val commandId: String,
    val fingerprint: String,
    val payloadJson: String,
    val sessionId: String,
    val messageId: String,
    val turnId: String?,
    val acceptedAt: Long,
    val resultJson: String,
)

@Dao
interface ConversationCommandReceiptDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(receipt: ConversationCommandReceiptEntity): Long

    @Query("SELECT * FROM conversation_command_receipts WHERE commandId = :commandId LIMIT 1")
    suspend fun find(commandId: String): ConversationCommandReceiptEntity?

    @Query("SELECT * FROM conversation_command_receipts")
    suspend fun getAll(): List<ConversationCommandReceiptEntity>

    @Query("DELETE FROM conversation_command_receipts")
    suspend fun deleteAll()
}
