package io.zer0.muse.data.conversation

import io.zer0.muse.conversation.core.ConversationAddress
import io.zer0.muse.conversation.core.ConversationWindowSource
import io.zer0.muse.conversation.core.MessagePart
import io.zer0.muse.conversation.core.PartKind
import io.zer0.muse.conversation.core.SelectionSetId
import io.zer0.muse.conversation.core.SlotKind
import io.zer0.muse.conversation.core.WindowSlotReference
import io.zer0.muse.data.session.MuseDb

/**
 * Provides bounded timeline reads to the conversation core without exposing
 * Room types or loading the whole message history.
 */
class RoomConversationWindowSource(
    private val database: MuseDb,
    private val store: RoomConversationStore,
) : ConversationWindowSource {
    override suspend fun readSlots(
        address: ConversationAddress,
        selectionSetId: SelectionSetId,
        around: String?,
        limit: Int,
    ): List<WindowSlotReference> {
        require(address.namespace == "main") { "unsupported conversation namespace: ${address.namespace}" }
        val slots =
            if (around == null) {
                store.readWindow(address.id, selectionSetId.value, limit)
            } else {
                store.readAround(address.id, selectionSetId.value, around, limit)
            }
        return slots.map {
            WindowSlotReference(
                slotId = it.slotId,
                messageId = it.messageId,
                kind = when (it.kind) {
                    "USER" -> SlotKind.User
                    "ASSISTANT" -> SlotKind.Assistant
                    else -> error("unsupported display slot kind: ${it.kind}")
                },
            )
        }
    }

    override suspend fun readParts(messageIds: List<String>): Map<String, List<MessagePart>> =
        database.messagePartDao().getByMessages(messageIds).groupBy { it.messageId }.mapValues { (_, parts) ->
            parts.map { part ->
                MessagePart(
                    partId = "${part.messageId}:${part.partIndex}",
                    kind = when (part.kind) {
                        "reasoning" -> PartKind.Reasoning
                        "tool" -> PartKind.Tool
                        else -> PartKind.Text
                    },
                    revision = part.createdAt,
                    text = part.text,
                    metadata = emptyMap(),
                )
            }
        }
}
