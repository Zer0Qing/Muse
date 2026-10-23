package io.zer0.muse.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ChatCheckpointHydrationWiringTest {

    @Test
    fun `persisted context checkpoint hydrates in process compression watermark`() {
        val source =
            listOf(
                Path.of("src/main/java/io/zer0/muse/ui/ChatViewModel.kt"),
                Path.of("app/src/main/java/io/zer0/muse/ui/ChatViewModel.kt"),
            )
                .firstOrNull(Files::exists)
                ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
                ?: error("ChatViewModel.kt not found")

        assertTrue(source.contains("CompressionSummaryStore.remember("))
        assertTrue(source.contains("CompressionSummaryStore.clear(sessionId)"))
    }
}
