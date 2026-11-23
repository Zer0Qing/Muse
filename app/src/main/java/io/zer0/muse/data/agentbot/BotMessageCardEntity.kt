package io.zer0.muse.data.agentbot

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * 锚定在 Agent 对话流中的动态交互卡片。
 *
 * 将任务进度、可点选建议、富产物交付与主动简报持久化，避免进程重启后
 * 对话流里的交互卡片丢失或退化为纯文本。
 */
@Serializable
@Entity(
    tableName = "bot_message_cards",
    indices = [
        Index(value = ["botId", "originSessionId", "createdAt"]),
        Index(value = ["taskId"]),
        Index(value = ["anchorMessageId"]),
    ],
)
data class BotMessageCardEntity(
    @PrimaryKey val cardId: String,
    @ColumnInfo(defaultValue = "") val botId: String = "",
    @ColumnInfo(defaultValue = "") val originSessionId: String = "",
    /** 锚定的消息 id；空串表示按 createdAt 插入时间线。 */
    @ColumnInfo(defaultValue = "") val anchorMessageId: String = "",
    /** 关联的任务 id；非任务类卡片可为空串。 */
    @ColumnInfo(defaultValue = "") val taskId: String = "",
    /** 见 [BotCardKind]。 */
    @ColumnInfo(defaultValue = "CHOICE") val cardKind: String = BotCardKind.CHOICE,
    @ColumnInfo(defaultValue = "") val title: String = "",
    @ColumnInfo(defaultValue = "") val subtitle: String = "",
    /** 结构化 JSON 载荷（选项列表、产物摘要、简报条目或链接集合）。 */
    @ColumnInfo(defaultValue = "{}") val payloadJson: String = "{}",
    /** 见 [BotCardStatus]。 */
    @ColumnInfo(defaultValue = "ACTIVE") val status: String = BotCardStatus.ACTIVE,
    /** 决策类卡片用户已选中的选项 id。 */
    @ColumnInfo(defaultValue = "") val selectedOptionId: String = "",
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L,
)

object BotCardKind {
    const val MISSION = "MISSION"
    const val APPROVAL = "APPROVAL"
    const val CHOICE = "CHOICE"
    const val ARTIFACT = "ARTIFACT"
    const val CUE_BRIEFING = "CUE_BRIEFING"

    val all: Set<String> = setOf(MISSION, APPROVAL, CHOICE, ARTIFACT, CUE_BRIEFING)
}

object BotCardStatus {
    const val ACTIVE = "ACTIVE"
    const val RESOLVED = "RESOLVED"
    const val DISMISSED = "DISMISSED"

    val all: Set<String> = setOf(ACTIVE, RESOLVED, DISMISSED)
}

@Dao
interface BotMessageCardDao {

    @Upsert
    suspend fun upsert(card: BotMessageCardEntity)

    @Upsert
    suspend fun upsertAll(cards: List<BotMessageCardEntity>)

    @Query("SELECT * FROM bot_message_cards WHERE cardId = :cardId LIMIT 1")
    suspend fun getById(cardId: String): BotMessageCardEntity?

    @Query(
        "SELECT * FROM bot_message_cards " +
            "WHERE botId = :botId AND originSessionId = :sessionId AND status != 'DISMISSED' " +
            "ORDER BY createdAt ASC",
    )
    fun observeByScope(botId: String, sessionId: String): Flow<List<BotMessageCardEntity>>

    @Query(
        "UPDATE bot_message_cards SET status = :status, selectedOptionId = :selectedOptionId, updatedAt = :updatedAt " +
            "WHERE cardId = :cardId",
    )
    suspend fun updateInteraction(
        cardId: String,
        status: String,
        selectedOptionId: String,
        updatedAt: Long,
    ): Int

    @Query("SELECT * FROM bot_message_cards")
    suspend fun getAll(): List<BotMessageCardEntity>

    @Query("DELETE FROM bot_message_cards WHERE originSessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM bot_message_cards WHERE taskId IN (:taskIds)")
    suspend fun deleteByTasks(taskIds: List<String>)

    @Query("DELETE FROM bot_message_cards")
    suspend fun deleteAll()
}
