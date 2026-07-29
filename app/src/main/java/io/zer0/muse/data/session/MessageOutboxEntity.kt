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
 * 消息发送 outbox 实体(持久化发送队列)。
 *
 * 解决"用户刚点击发送就退出 App,进程被系统杀死导致消息丢失"的问题:
 * - [enqueueSend] 时同步写入 outbox,保证进程被杀后仍可恢复
 * - 消费端成功启动 [launchStream] 后删除对应 outbox 记录
 * - App 启动时扫描残留 outbox 记录,重新投递到发送队列
 */
@Entity(
    tableName = "message_outbox",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("sessionId"),
        Index("createdAt"),
    ],
)
data class MessageOutboxEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val text: String,
    @ColumnInfo(defaultValue = "[]") val imageBase64Json: String = "[]",
    val userMessageId: String,
    val assistantMessageId: String,
    val createdAt: Long,
)

@Dao
interface MessageOutboxDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MessageOutboxEntity)

    @Query("DELETE FROM message_outbox WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM message_outbox ORDER BY createdAt ASC")
    suspend fun getAll(): List<MessageOutboxEntity>

    @Query("SELECT * FROM message_outbox WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getBySessionId(sessionId: String): List<MessageOutboxEntity>

    @Query("SELECT COUNT(*) FROM message_outbox")
    suspend fun count(): Int
}
