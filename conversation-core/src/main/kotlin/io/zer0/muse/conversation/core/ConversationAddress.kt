package io.zer0.muse.conversation.core

/**
 * Stable conversation identity across UI, entry points and persistence.
 *
 * The namespace prevents unrelated entry systems that share the same string ID
 * from addressing one another.
 */
data class ConversationAddress(
    val namespace: String,
    val id: String,
) {
    init {
        require(namespace.isNotBlank()) { "namespace must not be blank" }
        require(id.isNotBlank()) { "conversation id must not be blank" }
    }
}
