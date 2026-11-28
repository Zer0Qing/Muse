package io.zer0.muse.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class McpAttachmentSummaryParserTest {
    @Test
    fun `image and audio result summaries are classified`() {
        assertEquals(
            McpAttachmentSummary("image", "image/png", "about 3 bytes"),
            parseMcpAttachmentSummary("[MCP image: image/png, about 3 bytes; preview unavailable]"),
        )
        assertEquals(
            McpAttachmentSummary("audio", "audio/wav", "size unavailable"),
            parseMcpAttachmentSummary("[MCP audio: audio/wav, size unavailable; playback unavailable]"),
        )
    }

    @Test
    fun `ordinary text is left to the existing renderer`() {
        assertNull(parseMcpAttachmentSummary("result text"))
    }
}
