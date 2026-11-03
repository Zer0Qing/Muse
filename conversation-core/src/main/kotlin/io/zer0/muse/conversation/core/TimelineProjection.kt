package io.zer0.muse.conversation.core

object TimelineProjection {
    fun select(slots: List<DisplaySlot>, selection: ConversationSelection): List<DisplaySlot> {
        require(slots.map { it.logicalPosition }.distinct().size == slots.size) {
            "display slot logical positions must be unique"
        }
        require(slots.map { it.id }.distinct().size == slots.size) {
            "display slot ids must be unique"
        }
        val slotIds = slots.mapTo(mutableSetOf()) { it.id }
        require(slots.filter { it.kind == SlotKind.Assistant }.all { it.parentSlotId in slotIds }) {
            "assistant slot parent must exist"
        }
        return slots
            .sortedBy { it.logicalPosition }
            .map { slot ->
                val selected =
                    if (slot.kind == SlotKind.User) {
                        selection.user[slot.id]
                    } else {
                        selection.assistant[slot.id]
                    }
                if (selected == null) slot else slot.copy(selectedMessageId = selected)
            }
    }
}
