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
 * 新对话链路的结构化消息 part。
 *
 * 旧消息不会被强制转换；投影层在没有 parts 时从旧字段派生兼容 parts。
 */
@Entity(
    tableName = "message_parts",
    primaryKeys = ["messageId", "partIndex"],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["messageId", "partIndex"], name = "idx_message_parts_message_index"),
        Index(value = ["kind"], name = "idx_message_parts_kind"),
    ],
)
data class MessagePartEntity(
    val messageId: String,
    val partIndex: Int,
    val kind: String,
    val text: String,
    @ColumnInfo(defaultValue = "{}") val metadataJson: String = "{}",
    val createdAt: Long,
)

@Dao
interface MessagePartDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(parts: List<MessagePartEntity>)

    @Query("SELECT * FROM message_parts WHERE messageId = :messageId ORDER BY partIndex ASC")
    suspend fun getByMessage(messageId: String): List<MessagePartEntity>

    @Query("SELECT * FROM message_parts WHERE messageId IN (:messageIds) ORDER BY messageId ASC, partIndex ASC")
    suspend fun getByMessages(messageIds: List<String>): List<MessagePartEntity>

    @Query("DELETE FROM message_parts WHERE messageId = :messageId")
    suspend fun deleteByMessage(messageId: String)

    @Query("DELETE FROM message_parts WHERE messageId IN (SELECT id FROM messages WHERE sessionId = :sessionId)")
    suspend fun deleteBySession(sessionId: String)
}
