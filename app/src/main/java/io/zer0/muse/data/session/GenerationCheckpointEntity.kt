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
 * B5-01: 流式生成检查点（生成 outbox）。
 *
 * 每次流式生成开始时写入一条记录，周期性落盘已产出内容；
 * 生成正常结束后删除。进程被杀后，残留记录用于：
 * - 恢复已产出内容（messages 表可能缺最新分片）
 * - 标记该 assistant 消息为 [已中断]
 * - 后续 B7-04 的「继续生成」从该记录找到续写起点
 */
@Entity(
    tableName = "generation_checkpoints",
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
data class GenerationCheckpointEntity(
    @PrimaryKey val assistantMessageId: String,
    val sessionId: String,
    val userMessageId: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface GenerationCheckpointDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: GenerationCheckpointEntity)

    @Query("DELETE FROM generation_checkpoints WHERE assistantMessageId = :assistantMessageId")
    suspend fun deleteByAssistantMessageId(assistantMessageId: String)

    /**
     * B-23: 按用户消息删除全部检查点 — 多轮工具循环每轮各写一条检查点,
     * 收尾时按本轮用户消息批量清理,避免中间轮次检查点残留(重启后被
     * recoverInterruptedGenerations 误标 [已中断])。
     */
    @Query("DELETE FROM generation_checkpoints WHERE userMessageId = :userMessageId")
    suspend fun deleteByUserMessageId(userMessageId: String)

    @Query("DELETE FROM generation_checkpoints WHERE sessionId = :sessionId AND createdAt >= :fromCreatedAt")
    suspend fun deleteBySessionAndCreatedAtFrom(sessionId: String, fromCreatedAt: Long)

    /**
     * 审查修复 (2.0 C-14/C-20/B-01): 按"会话 + 精确 createdAt"删除检查点 —
     * 同一代生成的所有轮次共享同一 createdAt(launchStream 的 streamStartedAt),
     * 精确匹配只清理本代生成:
     * - C-20: 并发 regenerate 的检查点 createdAt 不同,不会被误删;
     * - C-14: 不依赖 USER 消息 id,无 USER 消息时也能清理全部轮次(不再退化为仅删单条)。
     */
    @Query("DELETE FROM generation_checkpoints WHERE sessionId = :sessionId AND createdAt = :createdAt")
    suspend fun deleteBySessionAndCreatedAt(sessionId: String, createdAt: Long)

    @Query("SELECT * FROM generation_checkpoints ORDER BY createdAt ASC")
    suspend fun getAllPending(): List<GenerationCheckpointEntity>

    @Query(
        "SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId AND role = 'ASSISTANT' AND createdAt > :afterCreatedAt"
    )
    suspend fun countNewerAssistantMessages(sessionId: String, afterCreatedAt: Long): Int
}
