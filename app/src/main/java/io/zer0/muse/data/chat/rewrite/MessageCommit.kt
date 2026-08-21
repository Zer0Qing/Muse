package io.zer0.muse.data.chat.rewrite

import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.session.MessagePartEntity
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.data.session.ToolRoundEntity

/** 一次回合提交的输入。parts/toolRounds 可为空，旧字段仍完整保留。 */
data class MessageCommitRequest(
    val turnId: String,
    val sessionId: String,
    val userMessageId: String,
    val assistantMessageId: String,
    val message: UIMessage,
    val parts: List<MessagePartEntity> = emptyList(),
    val toolRounds: List<ToolRoundEntity> = emptyList(),
)

sealed interface MessageCommitResult {
    data object Committed : MessageCommitResult
    data object AlreadyCommitted : MessageCommitResult
    data object Rejected : MessageCommitResult
}

/**
 * 新链路提交适配器。
 *
 * 真正的幂等边界在 Room 的 ConversationTurnDao.finishIfOpen；旧链路仍可继续调用
 * SessionRepository.upsertMessage，只有显式打开 useNewConversationService 才切换。
 */
class MessageCommit(
    private val sessionRepository: SessionRepository,
) {
    suspend fun commit(request: MessageCommitRequest): MessageCommitResult =
        sessionRepository.commitConversationMessage(request)
}


/** 从兼容 UIMessage 字段生成一次提交的基础 parts；旧消息不会被强制回填。 */
fun buildCommitParts(message: UIMessage, createdAt: Long): List<MessagePartEntity> = buildList {
    val reasoning = message.reasoning
    if (!reasoning.isNullOrEmpty()) {
        add(
            MessagePartEntity(
                messageId = message.id.toString(),
                partIndex = size,
                kind = "reasoning",
                text = reasoning,
                createdAt = createdAt,
            ),
        )
    }
    if (message.content.isNotEmpty()) {
        add(
            MessagePartEntity(
                messageId = message.id.toString(),
                partIndex = size,
                kind = "text",
                text = message.content,
                createdAt = createdAt,
            ),
        )
    }
    message.toolCallInfo?.let { info ->
        add(
            MessagePartEntity(
                messageId = message.id.toString(),
                partIndex = size,
                kind = "tool",
                text = "",
                metadataJson = io.zer0.common.AppJson.encodeToString(
                    io.zer0.ai.core.ToolCallInfo.serializer(),
                    info,
                ),
                createdAt = createdAt,
            ),
        )
    }
}
