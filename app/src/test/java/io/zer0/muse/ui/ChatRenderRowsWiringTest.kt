package io.zer0.muse.ui

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRenderRowsWiringTest {
    @Test
    fun `main chat renders message projection rows instead of raw messages`() {
        val source = readUi("ChatScreen.kt")
        val rowBlock =
            source.substringAfter("itemsIndexed(", missingDelimiterValue = "")
                .take(2_000)

        assertTrue(rowBlock.contains("renderRows"))
        assertTrue(rowBlock.contains("row.key"))
        assertFalse(rowBlock.contains("key = { _, it -> it.id }"))
        val continuationIndex = rowBlock.indexOf("ChatRenderContinuationRow(")
        val messageLevelIndex = rowBlock.indexOf("val prevMsg")
        assertTrue(continuationIndex >= 0)
        assertTrue(
            "continuation rows must not execute header, footer, or callback setup",
            messageLevelIndex < 0 || continuationIndex < messageLevelIndex,
        )
    }

    @Test
    fun `continuation receives search highlight and body typography preferences`() {
        val source = readUi("ChatScreen.kt")
        val continuationCall =
            source.substringAfter("ChatRenderContinuationRow(")
                .substringBefore("return@itemsIndexed", missingDelimiterValue = "")

        assertTrue(continuationCall.contains("chatPreferences = state.chatPreferences"))
        assertTrue(
            continuationCall.contains("highlightText =") &&
                continuationCall.contains("state.highlightedMessageId") &&
                continuationCall.contains("state.searchHighlightQuery"),
        )
    }

    @Test
    fun `lazy rows use distinct content types per projected row kind`() {
        val source = readUi("ChatScreen.kt")
        val contentType =
            source.substringAfter("contentType = { _, row ->")
                .substringBefore("},")

        assertTrue(contentType.contains("row.kind"))
        assertTrue(contentType.contains("ChatMessageRenderRowKind.Header"))
        assertTrue(contentType.contains("ChatMessageRenderRowKind.Body"))
        assertTrue(contentType.contains("ChatMessageRenderRowKind.Footer"))
        assertTrue(contentType.contains("ChatMessageRenderRowKind.Complete"))
    }

    @Test
    fun `projection cache observes every render row input`() {
        val source = readUi("ChatScreen.kt")
        val cacheKeys =
            source.substringAfter("val renderRows =")
                .substringAfter("remember(")
                .substringBefore(") {")

        assertTrue(cacheKeys.contains("visibleMessages"))
        assertTrue(cacheKeys.contains("isStreaming"))
        assertTrue(cacheKeys.contains("state.chatPreferences"))
        assertTrue(cacheKeys.contains("state.taskCards"))
        assertTrue(cacheKeys.contains("state.selectionMode"))
        assertTrue(cacheKeys.contains("state.isTranslating"))
        assertTrue(cacheKeys.contains("state.translatingMessageId"))
        assertTrue(cacheKeys.contains("state.tokenEstimateEnabled"))
        assertTrue(cacheKeys.contains("truncatedMarker"))
    }

    private fun readUi(relative: String): String {
        val candidates = listOf(
            Path.of("src/main/java/io/zer0/muse/ui/$relative"),
            Path.of("app/src/main/java/io/zer0/muse/ui/$relative"),
        )
        val path =
            candidates.firstOrNull(Files::exists)
                ?: error("Unable to locate ui/$relative from ${System.getProperty("user.dir")}")
        return Files.newBufferedReader(path).use { it.readText() }
    }
}
