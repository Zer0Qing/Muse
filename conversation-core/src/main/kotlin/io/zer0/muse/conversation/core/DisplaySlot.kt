package io.zer0.muse.conversation.core

enum class SlotKind {
    User,
    Assistant,
}

data class DisplaySlot(
    val id: String,
    val kind: SlotKind,
    val parentSlotId: String?,
    val logicalPosition: Long,
    val selectedMessageId: String,
) {
    init {
        require(id.isNotBlank()) { "display slot id must not be blank" }
        require(logicalPosition > 0) { "logical position must be positive" }
        require(selectedMessageId.isNotBlank()) { "selected message id must not be blank" }
        require(kind != SlotKind.User || parentSlotId == null) { "user slot cannot have a parent slot" }
        require(kind != SlotKind.Assistant || !parentSlotId.isNullOrBlank()) {
            "assistant slot must have a parent slot"
        }
    }
}
