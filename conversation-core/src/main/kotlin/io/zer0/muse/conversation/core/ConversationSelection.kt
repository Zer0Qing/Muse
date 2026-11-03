package io.zer0.muse.conversation.core

data class ConversationSelection(
    val user: Map<String, String> = emptyMap(),
    val assistant: Map<String, String> = emptyMap(),
) {
    init {
        require(user.keys.none { it.isBlank() } && user.values.none { it.isBlank() }) {
            "user selection keys and values must not be blank"
        }
        require(assistant.keys.none { it.isBlank() } && assistant.values.none { it.isBlank() }) {
            "assistant selection keys and values must not be blank"
        }
    }
}

data class SelectionSet(
    val id: SelectionSetId,
    val revision: Long,
    val selection: ConversationSelection,
) {
    init {
        require(revision >= 0) { "selection revision must not be negative" }
    }
}
