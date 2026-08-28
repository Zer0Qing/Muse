package io.zer0.muse.ui.chat

import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.UsageTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatResponsePolicyTest {

    @Test
    fun `thinking tool choice error can be retried only once before output`() {
        val error = "HTTP 400: Thinking mode does not support this tool_choice"

        assertTrue(
            shouldRetryToolChoiceCompatibility(
                error,
                "required",
                retryUsed = false,
                hasMeaningfulOutput = false,
            ),
        )
        assertFalse(
            shouldRetryToolChoiceCompatibility(
                error,
                "required",
                retryUsed = true,
                hasMeaningfulOutput = false,
            ),
        )
        assertFalse(
            shouldRetryToolChoiceCompatibility(
                error,
                "required",
                retryUsed = false,
                hasMeaningfulOutput = true,
            ),
        )
        assertFalse(
            shouldRetryToolChoiceCompatibility(
                error,
                null,
                retryUsed = false,
                hasMeaningfulOutput = false,
            ),
        )
    }

    @Test
    fun `non streaming completion maps reasoning tools citations usage and done`() {
        val events = completionToStreamEvents(
            ChatCompletion(
                text = "",
                reasoningContent = "reasoning only",
                toolCalls = listOf(ToolCall("call-1", "calculator", "{\"x\":1}")),
                usageTokens = UsageTokens(promptTokens = 1, completionTokens = 2),
                citationUrls = listOf("https://example.com"),
            ),
        )

        assertEquals(5, events.size)
        assertTrue(events[0] is ChatStreamEvent.ReasoningDelta)
        assertTrue(events[1] is ChatStreamEvent.ToolCallDelta)
        assertTrue(events[2] is ChatStreamEvent.UsageDelta)
        assertTrue(events[3] is ChatStreamEvent.CitationDelta)
        assertTrue(events[4] is ChatStreamEvent.Done)
        assertEquals("reasoning only", (events[0] as ChatStreamEvent.ReasoningDelta).delta)
        assertEquals("calculator", (events[1] as ChatStreamEvent.ToolCallDelta).name)
    }
}
