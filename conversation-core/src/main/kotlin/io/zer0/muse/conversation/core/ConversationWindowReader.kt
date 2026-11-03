package io.zer0.muse.conversation.core

data class ConversationWindowSlot(
    val slotId: String,
    val kind: SlotKind,
    val messageId: String,
    val parts: List<MessagePart>,
) {
    init {
        require(slotId.isNotBlank()) { "slot id must not be blank" }
        require(messageId.isNotBlank()) { "message id must not be blank" }
    }
}

data class ConversationWindow(
    val slots: List<ConversationWindowSlot>,
)

interface ConversationWindowSource {
    suspend fun readSlots(
        address: ConversationAddress,
        selectionSetId: SelectionSetId,
        around: String?,
        limit: Int,
    ): List<WindowSlotReference>

    suspend fun readParts(messageIds: List<String>): Map<String, List<MessagePart>>
}

data class WindowSlotReference(
    val slotId: String,
    val messageId: String,
    val kind: SlotKind,
)

/**
 * Reads a bounded set of timeline slots and batch-loads parts for that set only.
 * It intentionally has no whole-conversation or visible-page fallback.
 */
class ConversationWindowReader(
    private val source: ConversationWindowSource,
) {
    suspend fun load(address: ConversationAddress, selectionSetId: SelectionSetId, around: String?, limit: Int): ConversationWindow {
        require(limit > 0) { "window limit must be positive" }
        val slots = source.readSlots(address, selectionSetId, around, limit)
        if (slots.isEmpty()) return ConversationWindow(emptyList())
        val parts = source.readParts(slots.map { it.messageId })
        return ConversationWindow(
            slots.map { slot ->
                ConversationWindowSlot(
                    slotId = slot.slotId,
                    kind = slot.kind,
                    messageId = slot.messageId,
                    parts = parts[slot.messageId].orEmpty(),
                )
            },
        )
    }
}
