package io.zer0.muse.conversation.ui

data class ConversationHostState(
    val sessionId: String,
    val model: ConversationPageModel? = null,
    val isSubmitting: Boolean = false,
    val error: String? = null,
)
