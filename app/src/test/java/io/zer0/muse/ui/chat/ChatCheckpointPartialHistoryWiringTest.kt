package io.zer0.muse.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ChatCheckpointPartialHistoryWiringTest {

    @Test
    fun `checkpoint validation uses full session ids when the visible page misses the boundary`() {
        val source =
            listOf(
                Path.of("src/main/java/io/zer0/muse/ui/chat/ChatStreamCoordinator.kt"),
                Path.of("app/src/main/java/io/zer0/muse/ui/chat/ChatStreamCoordinator.kt"),
            )
                .firstOrNull(Files::exists)
                ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
                ?: error("ChatStreamCoordinator.kt not found")

        assertTrue(source.contains("getAllMessagesForWarmup(sessionId)"))
        assertTrue(source.contains("checkpointReader.getValid(sessionId"))
    }
}
