package io.zer0.muse.conversation.runtime

import io.zer0.ai.core.ChatStreamEvent
import io.zer0.muse.conversation.core.ProviderEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Maps provider-specific stream frames into the platform-neutral event contract.
 * Every emitted event receives a monotonic local sequence for the current
 * provider connection.
 */
class ProviderStreamMapper {
    fun map(source: Flow<ChatStreamEvent>): Flow<ProviderEvent> = flow {
        var sequence = 0L
        source.collect { event ->
            sequence += 1
            when (event) {
                is ChatStreamEvent.ContentDelta ->
                    emit(ProviderEvent.Content(sequence, event.delta))
                is ChatStreamEvent.ReasoningDelta ->
                    emit(
                        ProviderEvent.Reasoning(
                            sequence = sequence,
                            text = event.delta,
                            signature = event.signature,
                            encryptedContent = event.encryptedContent,
                        ),
                    )
                is ChatStreamEvent.ToolCallDelta ->
                    emit(
                        ProviderEvent.ToolCall(
                            sequence = sequence,
                            index = event.index,
                            id = event.id,
                            name = event.name,
                            argumentsDelta = event.argumentsDelta,
                            snapshot = event.isSnapshot,
                        ),
                    )
                is ChatStreamEvent.Done ->
                    emit(ProviderEvent.Completed(sequence, event.finishReason))
                is ChatStreamEvent.StreamInterrupted ->
                    emit(ProviderEvent.Interrupted(sequence, event.message))
                is ChatStreamEvent.Error ->
                    emit(ProviderEvent.Failed(sequence, event.message))
                is ChatStreamEvent.ImageDelta ->
                    emit(ProviderEvent.Image(sequence, event.imageBase64, event.mimeType))
                is ChatStreamEvent.FallbackNotice ->
                    emit(ProviderEvent.Fallback(sequence, event.message))
                is ChatStreamEvent.UsageDelta ->
                    emit(
                        ProviderEvent.Usage(
                            sequence = sequence,
                            inputTokens = event.usage.promptTokens,
                            outputTokens = event.usage.completionTokens,
                            totalTokens = event.usage.total,
                        ),
                    )
                is ChatStreamEvent.CitationDelta ->
                    emit(ProviderEvent.Citation(sequence, event.urls))
            }
        }
    }
}
