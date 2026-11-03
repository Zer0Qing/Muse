package io.zer0.muse.conversation.core

import kotlinx.coroutines.flow.Flow

data class ProviderRequest(
    val identity: RunIdentity,
    val selectionSetId: String,
    val messages: List<ConversationInputMessage>,
    val tools: List<ToolDescriptor>,
)

data class ConversationInputMessage(
    val role: InputRole,
    val text: String,
)

enum class InputRole {
    System,
    User,
    Assistant,
    Tool,
}

fun interface ProviderPort {
    fun stream(request: ProviderRequest): Flow<ProviderEvent>
}
