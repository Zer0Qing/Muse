package io.zer0.muse.data.chat.rewrite

import io.zer0.muse.data.session.MessageEntity
import io.zer0.muse.data.session.MessagePartEntity

/** UI 展示使用的结构化 part。 */
data class MessageDisplayPart(
    val kind: String,
    val text: String,
    val metadataJson: String = "{}",
)

data class ProjectedMessage(
    val id: String,
    val sessionId: String,
    val role: String,
    val parts: List<MessageDisplayPart>,
    val content: String,
    val reasoning: String?,
    val seq: Long,
    val commitSeq: Long,
    val parentMessageId: String?,
    val variantGroupId: String?,
    val isLegacyProjection: Boolean,
)

/**
 * 可重复执行的消息投影器。
 * 新消息优先读取 message_parts；没有 parts 的旧消息由 legacy 字段派生。
 */
object MessageProjector {
    fun project(message: MessageEntity, storedParts: List<MessagePartEntity>): ProjectedMessage {
        val parts = if (storedParts.isNotEmpty()) {
            storedParts.sortedBy { it.partIndex }.map { MessageDisplayPart(it.kind, it.text, it.metadataJson) }
        } else {
            buildLegacyParts(message)
        }
        val visibleContent = parts.filter { it.kind == "text" }.joinToString("") { it.text }
        val reasoning = parts.filter { it.kind == "reasoning" }.joinToString("") { it.text }.ifBlank { message.reasoning }
        return ProjectedMessage(
            id = message.id,
            sessionId = message.sessionId,
            role = message.role,
            parts = parts,
            content = visibleContent,
            reasoning = reasoning,
            seq = message.seq,
            commitSeq = message.commitSeq,
            parentMessageId = message.parentMessageId,
            variantGroupId = message.variantGroupId,
            isLegacyProjection = storedParts.isEmpty(),
        )
    }

    fun order(messages: List<MessageEntity>, useCommitSeq: Boolean): List<MessageEntity> =
        messages
            .asSequence()
            .filter { it.deletedAt == null }
            .sortedWith(
                compareBy<MessageEntity> { if (useCommitSeq && it.commitSeq > 0) it.commitSeq else it.seq }
                    .thenBy { it.createdAt }
                    .thenBy { it.id },
            )
            .toList()

    private fun buildLegacyParts(message: MessageEntity): List<MessageDisplayPart> = buildList {
        if (!message.reasoning.isNullOrEmpty()) add(MessageDisplayPart("reasoning", message.reasoning))
        if (message.content.isNotEmpty()) add(MessageDisplayPart("text", message.content))
        if (!message.toolCallInfoJson.isNullOrEmpty()) {
            add(MessageDisplayPart("tool", "", message.toolCallInfoJson))
        }
    }
}


data class ConversationProjection(
    val sessionId: String,
    val projectionVersion: Long,
    val messages: List<ProjectedMessage>,
    val contentHash: String,
)

/** 会话级纯函数投影，供旧 UI 与新 UI 对账。 */
object ConversationProjector {
    fun project(
        messages: List<MessageEntity>,
        parts: List<MessagePartEntity>,
        useCommitSeq: Boolean,
        projectionVersion: Long = 0,
    ): ConversationProjection {
        val ordered = MessageProjector.order(messages, useCommitSeq)
        val partsByMessage = parts.groupBy { it.messageId }
        val projected = ordered.map { message ->
            MessageProjector.project(message, partsByMessage[message.id].orEmpty())
        }
        val hashInput = projected.joinToString("|") { "${it.id}:${it.role}:${it.content}" }
        return ConversationProjection(
            sessionId = messages.firstOrNull()?.sessionId.orEmpty(),
            projectionVersion = projectionVersion,
            messages = projected,
            contentHash = sha256(hashInput),
        )
    }
}
