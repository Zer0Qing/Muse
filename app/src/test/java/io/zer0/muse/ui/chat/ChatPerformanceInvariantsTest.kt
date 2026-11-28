package io.zer0.muse.ui.chat

import io.zer0.muse.perf.MarkdownParseCache
import io.zer0.muse.ui.markdown.MarkdownBlock
import io.zer0.muse.ui.speech.VoiceMessageStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ChatPerformanceInvariantsTest {

    @After
    fun tearDown() {
        MarkdownParseCache.clear()
    }

    @Test
    fun `ChatUiState implements structural equals and hashCode`() {
        val first = ChatUiState(
            contextTokenCount = 123,
            selectedModelId = "gpt-5",
            isDrawMode = true,
        )
        val second = ChatUiState(
            contextTokenCount = 123,
            selectedModelId = "gpt-5",
            isDrawMode = true,
        )
        val third = first.copy(contextTokenCount = 456)

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(first, third)
    }

    @Test
    fun `equalsExcludingInput ignores typed text and history index only`() {
        val base = ChatUiState()
        val typed = base.copy(input = "hello world", inputHistoryIndex = 0)

        assertTrue(base.equalsExcludingInput(typed))
        assertFalse(base.equalsExcludingInput(typed.copy(isStreaming = true)))
        assertFalse(base.equalsExcludingInput(typed.copy(contextTokenCount = 42)))
        assertFalse(base.equalsExcludingInput(typed.copy(pendingImages = listOf("img"))))
        assertFalse(base.equalsExcludingInput(typed.copy(currentSessionId = "s1")))
    }

    @Test
    fun `ChatUiState copy preserves contextCheckpointBoundaryId and totalCovered`() {
        val base = ChatUiState(
            contextCheckpointBoundaryId = "msg-boundary-42",
            contextCheckpointTotalCovered = 18,
        )
        val updated = base.copy(toast = "ok", isDrawerOpen = true)

        assertEquals("msg-boundary-42", updated.contextCheckpointBoundaryId)
        assertEquals(18, updated.contextCheckpointTotalCovered)
    }

    @Test
    fun `MarkdownParseCache put serves stream completion without re-parsing`() {
        val msgId = "msg-stream-1"
        val body = "# Title\n\nStreamed paragraph"
        val preParsed = listOf(MarkdownBlock.Paragraph(body))

        MarkdownParseCache.put(msgId, body, preParsed)
        val resolved = MarkdownParseCache.getOrParse(msgId, body) {
            error("Should reuse seeded streaming parse result without re-parsing")
        }

        assertSame(preParsed, resolved)
    }

    @Test
    fun `VoiceMessageStore caches file resolution, misses, and transcripts`() = runBlocking {
        val rootDir = Files.createTempDirectory("voice-cache-test").toFile()
        try {
            val storageDir = rootDir.resolve("voice_messages")
            val store = VoiceMessageStore(storageDir)
            val missingUri = "file://${storageDir.resolve("missing.ogg").absolutePath}"

            assertFalse(store.isResolveCached(missingUri))
            assertNull(store.resolve(missingUri))
            assertTrue(store.isResolveCached(missingUri))

            val tempAudio = rootDir.resolve("sample.ogg").apply {
                writeBytes(byteArrayOf(0x4F, 0x67, 0x67, 0x53, 0x01, 0x02))
            }
            val savedUri = store.persist(tempAudio, "voice-key-1")
            val uri = requireNotNull(savedUri)
            assertTrue(store.isResolveCached(uri))
            assertNotNull(store.getCachedResolved(uri))

            store.saveTranscript(uri, "你好世界")
            assertEquals("你好世界", store.getCachedTranscript(uri))

            store.delete(uri)
            assertNull(store.getCachedTranscript(uri))
            assertNull(store.getCachedResolved(uri))
        } finally {
            rootDir.deleteRecursively()
        }
    }
}
