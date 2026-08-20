package io.zer0.muse.data.chat.rewrite

import io.zer0.muse.data.session.ConversationEventEntity
import java.security.MessageDigest

/** 追加式对话事件类型。 */
enum class ConversationEventType {
    USER_SUBMITTED,
    ASSISTANT_STARTED,
    CONTENT_DELTA,
    REASONING_DELTA,
    TOOL_CALLED,
    TOOL_RESULT,
    TURN_FINISHED,
    TURN_INTERRUPTED,
    TURN_FAILED,
    MESSAGE_EDITED,
    MESSAGE_DELETED,
    BRANCH_CREATED,
    BRANCH_SELECTED,
}

/** 持久化回合状态。 */
enum class ConversationTurnPhase {
    CREATED,
    STREAMING,
    TOOL_ROUND,
    COMMITTING,
    COMPLETED,
    INTERRUPTED,
    FAILED,
    CANCELLED,
}

/** 内存事件草稿，eventSeq 由数据库按 session 原子分配。 */
data class ConversationEventDraft(
    val sessionId: String,
    val turnId: String,
    val type: ConversationEventType,
    val payloadJson: String,
    val streamId: String? = null,
    val sequenceInStream: Long = 0,
    val generationSerial: Long = 0,
    val provider: String? = null,
    val modelId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(turnId.isNotBlank()) { "turnId must not be blank" }
        require(sequenceInStream >= 0) { "sequenceInStream must not be negative" }
        require(generationSerial >= 0) { "generationSerial must not be negative" }
    }

    /** 将事件正文中的敏感键替换为固定占位符，事件表不保存凭据。 */
    fun redactedPayload(): String = ConversationEventSanitizer.redact(payloadJson)

    fun toEntity(eventSeq: Long, eventId: String): ConversationEventEntity {
        val payload = redactedPayload()
        return ConversationEventEntity(
            sessionId = sessionId,
            eventSeq = eventSeq,
            eventId = eventId,
            turnId = turnId,
            streamId = streamId,
            type = type.name,
            payloadJson = payload,
            payloadHash = sha256(payload),
            payloadLength = payload.length,
            provider = provider,
            modelId = modelId,
            sequenceInStream = sequenceInStream,
            generationSerial = generationSerial,
            createdAt = createdAt,
        )
    }
}

/** 事件摘要脱敏器；仅处理常见凭据键，不改变普通用户正文。 */
object ConversationEventSanitizer {
    private val secretPattern = Regex(
        """(?i)("(?:api[_-]?key|authorization|cookie|password|token|secret)"\s*:\s*")[^"]*(")""",
    )

    fun redact(payload: String): String = secretPattern.replace(payload) { match ->
        "${match.groupValues[1]}[REDACTED]${match.groupValues[2]}"
    }
}

/** 使用 SHA-256 记录事件摘要，避免把完整 delta 写入 shadow 日志。 */
fun sha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}
