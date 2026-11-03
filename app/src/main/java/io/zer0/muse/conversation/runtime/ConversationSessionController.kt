package io.zer0.muse.conversation.runtime

import io.zer0.muse.conversation.core.CommandResult
import io.zer0.muse.conversation.core.ConversationAddress
import io.zer0.muse.conversation.core.ConversationCommand
import io.zer0.muse.conversation.core.ConversationWindowReader
import io.zer0.muse.conversation.core.SelectionSetId
import io.zer0.muse.conversation.core.SlotKind
import io.zer0.muse.conversation.render.LegacyConversationProjector
import io.zer0.muse.conversation.ui.ConversationTimelineRows
import io.zer0.muse.conversation.ui.ConversationPageModel
import io.zer0.muse.conversation.ui.TimelineRow
import io.zer0.muse.data.conversation.RoomSubmitCommandPort
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface ConversationMessageSource {
    suspend fun readMessages(messageIds: List<String>): List<io.zer0.ai.core.UIMessage>
}

/**
 * Coordinates durable command acceptance with the new page model. It does not
 * read the whole conversation or mutate any legacy ChatUiState.
 */
class ConversationSessionController(
    val sessionId: String,
    private val submitCommand: RoomSubmitCommandPort,
    private val windowReader: ConversationWindowReader,
    private val messageSource: ConversationMessageSource = ConversationMessageSource { emptyList() },
    private val pageModel: ConversationPageModel,
) {
    init {
        require(sessionId.isNotBlank()) { "controller session id must not be blank" }
    }

    private val submitMutex = Mutex()

    suspend fun submit(command: ConversationCommand.Submit): CommandResult {
        require(command.sessionId == sessionId) { "submission belongs to another session" }
        return submitMutex.withLock {
            submitCommand.accept(command)
        }
    }

    suspend fun load(
        sessionId: String,
        selectionSetId: String,
        around: String? = null,
        limit: Int = 50,
    ) {
        require(sessionId == this.sessionId) { "load belongs to another session" }
        val window = windowReader.load(
            address = ConversationAddress("main", sessionId),
            selectionSetId = SelectionSetId(selectionSetId),
            around = around,
            limit = limit,
        )
        val messages = messageSource.readMessages(window.slots.map { it.messageId })
            .associateBy { it.id.toString() }
        val semanticRows =
            window.slots.map { slot ->
                when (slot.kind) {
                    SlotKind.User -> TimelineRow.User(slot.slotId, slot.messageId)
                    SlotKind.Assistant -> TimelineRow.Assistant(slot.slotId, slot.messageId)
                }
            }
        val richRows =
            window.slots.flatMap { slot ->
                messages[slot.messageId]?.let { message ->
                    ConversationTimelineRows.project(
                        slotId = slot.slotId,
                        kind = slot.kind,
                        projection = LegacyConversationProjector.project(
                            rowId = slot.slotId,
                            message = message,
                            complete = true,
                        ),
                    )
                }.orEmpty()
            }
        pageModel.replaceTimeline(rows = richRows, semanticRows = semanticRows)
    }
}
