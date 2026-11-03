package io.zer0.muse.data.conversation

import io.zer0.muse.data.chat.ConversationTree
import io.zer0.muse.data.session.MessageEntity

data class ImportedSlot(
    val slotId: String,
    val messageId: String,
    val role: String,
    val logicalPosition: Long,
    val parentSlotId: String?,
)

/**
 * Builds stable logical slots from the existing message projection. It keeps
 * the selected variant of each user/assistant group and never rewrites message
 * content.
 */
object LegacySlotImporter {
    fun import(messages: List<MessageEntity>): List<ImportedSlot> {
        val originalIdByProjectionId = messages.associate { message ->
            runCatching { kotlin.uuid.Uuid.parse(message.id).toString() }
                .getOrElse { stableProjectionId(message.id) } to message.id
        }
        val tree = ConversationTree.build(messages.map { it.toUiProjection() })
        var position = 0L
        val result = mutableListOf<ImportedSlot>()
        tree.userNodes.forEachIndexed { userIndex, user ->
            val userVariant = user.currentVariant ?: return@forEachIndexed
            val userSlotId = "slot-$position"
            val userMessage = userVariant.message
            val originalUserId = originalIdByProjectionId[userMessage.id.toString()] ?: userMessage.id.toString()
            result += ImportedSlot(
                slotId = userSlotId,
                messageId = originalUserId,
                role = "USER",
                logicalPosition = ++position,
                parentSlotId = null,
            )
            userVariant.assistantNodes.forEach { assistant ->
                val selected = assistant.currentVariant ?: return@forEach
                val originalAssistantId = originalIdByProjectionId[selected.id.toString()] ?: selected.id.toString()
                result += ImportedSlot(
                    slotId = "slot-$position",
                    messageId = originalAssistantId,
                    role = "ASSISTANT",
                    logicalPosition = ++position,
                    parentSlotId = userSlotId,
                )
            }
        }
        return result
    }

    private fun MessageEntity.toUiProjection(): io.zer0.ai.core.UIMessage =
        io.zer0.ai.core.UIMessage(
            id = runCatching { kotlin.uuid.Uuid.parse(id) }.getOrElse { kotlin.uuid.Uuid.parse(stableProjectionId(id)) },
            role = io.zer0.ai.core.MessageRole.valueOf(role),
            content = content,
            createdAt = createdAt,
            parentGroupId = parentMessageId ?: parentGroupId,
            parentMessageId = parentMessageId,
            variantGroupId = variantGroupId,
            variantIndex = variantIndex,
            variantCount = variantCount,
        )

    private fun stableProjectionId(rawId: String): String =
        java.util.UUID.nameUUIDFromBytes("muse-legacy-message:$rawId".toByteArray()).toString()
}
