package io.zer0.muse.conversation.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed interface TimelineRow {
    val slotId: String
    val messageId: String

    data class User(
        override val slotId: String,
        override val messageId: String,
    ) : TimelineRow

    data class Assistant(
        override val slotId: String,
        override val messageId: String,
    ) : TimelineRow
}

data class ConversationPageState(
    val input: String = "",
    val timeline: List<TimelineRow> = emptyList(),
    val richTimeline: List<ConversationTimelineRow> = emptyList(),
    val timelineRevision: Long = 0,
    val streamingParts: Map<String, String> = emptyMap(),
)

/**
 * Owns independent page-level state. Composer updates and streaming content do
 * not rewrite timeline structure, so unrelated UI cannot invalidate all rows.
 */
class ConversationPageModel {
    private val mutableState = MutableStateFlow(ConversationPageState())
    val state: StateFlow<ConversationPageState> = mutableState.asStateFlow()

    fun updateInput(value: String) {
        mutableState.update { it.copy(input = value) }
    }

    fun replaceTimeline(rows: List<TimelineRow>) {
        mutableState.update {
            it.copy(
                timeline = rows,
                timelineRevision = it.timelineRevision + 1,
            )
        }
    }

    fun replaceTimeline(rows: List<ConversationTimelineRow>, semanticRows: List<TimelineRow>) {
        mutableState.update {
            it.copy(
                timeline = semanticRows,
                richTimeline = rows,
                timelineRevision = it.timelineRevision + 1,
            )
        }
    }

    fun updateStreamingPart(messageId: String, partId: String, text: String) {
        mutableState.update {
            it.copy(streamingParts = it.streamingParts + ("$messageId:$partId" to text))
        }
    }
}
