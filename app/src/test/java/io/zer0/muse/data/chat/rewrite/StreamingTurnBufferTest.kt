package io.zer0.muse.data.chat.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingTurnBufferTest {

    @Test
    fun duplicateAndFinishedEventsAreIgnored() {
        val buffer = StreamingTurnBuffer("turn-1", "assistant-1", "stream-1")

        assertTrue(buffer.appendText(0, "hello"))
        assertFalse(buffer.appendText(0, "hello"))
        assertTrue(buffer.appendReasoning(1, "thinking"))
        assertFalse(buffer.appendText(1, "wrong sequence reuse"))

        val draft = buffer.finish()
        assertEquals("hello", draft.visibleText)
        assertEquals("thinking", draft.reasoningText)
        assertFalse(buffer.appendText(2, "late"))
    }

    @Test
    fun fallbackReplacesEmptyDraftButNeverAppendsToVisibleText() {
        val empty = StreamingTurnBuffer("turn-1", "assistant-1", "stream-1")
        assertEquals(FallbackDecision.ReplacedEmptyDraft, empty.applyFallback("complete"))
        assertEquals("complete", empty.snapshot().visibleText)

        val visible = StreamingTurnBuffer("turn-2", "assistant-2", "stream-2")
        visible.appendText(0, "partial")
        assertEquals(
            FallbackDecision.IgnoredBecauseVisibleContentExists,
            visible.applyFallback("complete"),
        )
        assertEquals("partial", visible.snapshot().visibleText)
    }
}
