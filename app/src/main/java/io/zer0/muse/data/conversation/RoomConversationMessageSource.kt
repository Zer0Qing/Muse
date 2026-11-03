package io.zer0.muse.data.conversation

import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.session.SessionRepository

/**
 * Reads the full legacy message payload for one bounded display window.
 * Ordering follows the selection-aware slot ids supplied by the caller.
 */
class RoomConversationMessageSource(
    private val sessionRepository: SessionRepository,
) : io.zer0.muse.conversation.runtime.ConversationMessageSource {
    override suspend fun readMessages(messageIds: List<String>): List<UIMessage> =
        sessionRepository.getMessagesForConversationWindow(messageIds)
}
