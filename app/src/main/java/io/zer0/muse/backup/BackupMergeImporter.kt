package io.zer0.muse.backup

import androidx.room.withTransaction
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.session.ConversationTurnEntity
import io.zer0.muse.data.session.MessageEntity
import io.zer0.muse.data.session.MessagePartEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.UUID

/**
 * 叠加导入（[BackupImportMode.MERGE]）的被并侧。
 *
 * 与覆盖导入的分工：
 * - 覆盖导入走 [BackupService] 既有的「清空 → 写入 → 对账」链路，一行不改；
 * - 叠加导入只认对话记录（session / message / conversationTurn / messagePart），
 *   其余类型（助手、技能、知识库、自动化、设置、记忆）一律忽略。
 *
 * 为什么必须换 id：备份里的 sessions.id / messages.id 是本机的全局唯一主键。
 * 直接按原 id `insert` 会撞主键失败，按原 id `upsert` 会**覆盖用户现有会话**——
 * 那就不是叠加了。所以每条会话与消息都生成新 id，并把引用关系整体重映射。
 *
 * 为什么用「确定性映射 + 每次导入一个盐」：
 * 消息行可能先于它所属的会话行到达，若用递增序号映射就必须知道全部会话才能算。
 * 这里用 `新 id = 前缀 + 原始 id + 盐` 的哈希，任意顺序都能算出同一个结果；
 * 盐每次导入不同，因此重复导入同一份备份得到的是多份副本，互不覆盖。
 */
internal class BackupMergeImporter(
    private val db: MuseDb,
) {
    private val json = AppJson

    /** 本次导入的唯一盐：决定"同名再导一次"是新增副本而不是覆盖。 */
    private val salt: String = UUID.randomUUID().toString().take(8)

    private val sessionIdMap = HashMap<String, String>()
    private val messageIdMap = HashMap<String, String>()
    private val turnIdMap = HashMap<String, String>()

    /** 已落库或待落库的会话（数量远小于消息，可以整体驻留内存）。 */
    private val sessions = ArrayList<SessionEntity>()

    /** 等待其会话先落库的消息。 */
    private val pendingMessages = ArrayList<MessageEntity>()
    private val turns = ArrayList<ConversationTurnEntity>()
    private val parts = ArrayList<MessagePartEntity>()

    var mergedSessions: Int = 0
        private set
    var mergedMessages: Int = 0
        private set
    var skippedRecords: Int = 0
        private set

    /** 本次导入已分配的目标会话 id（含跨行顺序不定的情况），用于消息归属校验。 */
    private val assignedSessionIds = HashSet<String>()

    /** 消费一行 NDJSON。非对话类型直接忽略，不报错。 */
    fun ingest(type: String, obj: JsonObject) {
        when (type) {
            "session" -> obj["data"]?.let { data ->
                val source = json.decodeFromJsonElement(SessionEntity.serializer(), data)
                if (source.isMiniPhone) {
                    skippedRecords += 1
                    return@let
                }
                val rekeyed = rekey(source)
                sessions += rekeyed
                assignedSessionIds += rekeyed.id
            }
            "message" -> obj["data"]?.let { data ->
                pendingMessages += remapMessage(json.decodeFromJsonElement(MessageEntity.serializer(), data))
            }
            "conversationTurn" -> obj["data"]?.let { data ->
                turns += remapTurn(json.decodeFromJsonElement(ConversationTurnEntity.serializer(), data))
            }
            "messagePart" -> obj["data"]?.let { data ->
                val source = json.decodeFromJsonElement(MessagePartEntity.serializer(), data)
                parts += source.copy(messageId = newMessageId(source.messageId))
            }
            // 其余类型（助手 / 技能 / 知识库 / 自动化 / 设置 / 记忆…）不属于叠加范围。
            else -> Unit
        }
    }

    /**
     * 落库。会话先写（消息有外键指向它），再写消息、轮次与消息分段。
     * 全部包在同一个事务里：任一批失败整体回滚，不留下半份合并结果。
     */
    suspend fun commit(): Pair<Int, Int> {
        db.withTransaction {
            sessions.forEach { db.sessionDao().insert(it) }
            mergedSessions = sessions.size
            sessions.clear()

            pendingMessages.forEach { message ->
                if (message.sessionId in assignedSessionIds) {
                    db.messageDao().upsert(message)
                    mergedMessages += 1
                } else {
                    // 引用了本次备份里不存在的会话（备份残缺或被手工裁剪）：不写孤儿消息。
                    skippedRecords += 1
                }
            }
            pendingMessages.clear()

            turns.forEach { turn ->
                if (turn.sessionId in assignedSessionIds) {
                    db.conversationTurnDao().upsert(turn)
                } else {
                    skippedRecords += 1
                }
            }
            turns.clear()

            if (parts.isNotEmpty()) {
                db.messagePartDao().upsertAll(parts)
                parts.clear()
            }
        }
        if (skippedRecords > 0) {
            Logger.w(TAG, "叠加导入跳过 $skippedRecords 条记录（旧小手机 / 孤儿引用）")
        }
        return mergedSessions to mergedMessages
    }

    private fun rekey(source: SessionEntity): SessionEntity {
        val newId = newSessionId(source.id)
        return source.copy(
            id = newId,
            // 合并进来的历史不该抢置顶位，也不该继续挂在原文件夹下（那是本机的分类）。
            pinned = false,
            folderId = null,
            // 未读状态属于原设备的阅读进度，合并后统一视为已读。
            lastReadMessageId = null,
            lastReadCount = 0,
            // 合并的会话要在首轮重建上下文，否则历史进不去模型视野。
            warmupPending = true,
            // 原会话若还是"未完成生成"状态，合并进来会立刻被后台当成活任务，这里落定。
            updatedAt = maxOf(source.updatedAt, source.createdAt),
        )
    }

    private fun remapMessage(source: MessageEntity): MessageEntity {
        val newSessionId = newMessageSessionRef(source.sessionId)
        return source.copy(
            id = newMessageId(source.id),
            sessionId = newSessionId,
            parentMessageId = source.parentMessageId?.let { newMessageId(it) },
            parentGroupId = source.parentGroupId?.let { newMessageId(it) },
            translationSourceId = source.translationSourceId?.let { newMessageId(it) },
        )
    }

    private fun remapTurn(source: ConversationTurnEntity): ConversationTurnEntity = source.copy(
        turnId = newTurnId(source.turnId),
        sessionId = newMessageSessionRef(source.sessionId),
        inputUserMessageId = newMessageId(source.inputUserMessageId),
        assistantMessageId = newMessageId(source.assistantMessageId),
    )

    private fun newSessionId(original: String): String = if (original.isBlank()) {
        "merged-$salt-${UUID.randomUUID()}"
    } else {
        sessionIdMap.getOrPut(original) {
            "merged-$salt-${stableToken(original)}"
        }
    }

    private fun newMessageId(original: String): String = if (original.isBlank()) {
        ""
    } else {
        messageIdMap.getOrPut(original) {
            "mergedm-$salt-${stableToken(original)}"
        }
    }

    private fun newTurnId(original: String): String = if (original.isBlank()) {
        ""
    } else {
        turnIdMap.getOrPut(original) {
            "mergedt-$salt-${stableToken(original)}"
        }
    }

    /**
     * 消息可能先于会话到达，这里不能依赖 [sessionIdMap] 是否已建立：
     * 直接用同一套确定性规则算会话新 id，顺序无关。
     */
    private fun newMessageSessionRef(original: String): String = if (original.isBlank()) "" else newSessionId(original)

    /** 原始 id 较短且稳定，取哈希前 12 位足够避免同盐下的碰撞。 */
    private fun stableToken(original: String): String =
        original.hashCode().toUInt().toString(16).padStart(8, '0') + "-" + original.takeLast(6)

    private companion object {
        const val TAG = "BackupMergeImporter"
    }
}
