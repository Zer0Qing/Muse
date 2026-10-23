package io.zer0.muse.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Memory extraction must use a complete session snapshot, not the paged chat UI window.
 */
class MemoryHistoryWiringTest {

    @Test
    fun `session end memory paths load complete history from repository`() {
        val viewModel = locate("src/main/java/io/zer0/muse/ui/ChatViewModel.kt")
        val stream = locate("src/main/java/io/zer0/muse/ui/chat/ChatStreamCoordinator.kt")
        val generation = locate("src/main/java/io/zer0/muse/ui/chat/ChatGenerationController.kt")
        val settings = locate("src/main/java/io/zer0/muse/ui/settings/MemorySettingsPage.kt")

        assertTrue(viewModel.contains("sessionRepository.getAllMessagesForWarmup(sessionId)"))
        assertTrue(stream.contains("memoryTicker.notifySessionEndFromProvider"))
        assertTrue(generation.contains("deps.sessionRepository.getAllMessagesForWarmup(sessionId)"))
        assertTrue(settings.contains("conversationRecallEnabled"))
    }

    private fun locate(relativePath: String): String {
        val candidates =
            listOf(
                Path.of(relativePath),
                Path.of("app").resolve(relativePath.removePrefix("src/")),
            )
        val path = candidates.firstOrNull(Files::exists)
            ?: error("Unable to locate $relativePath from ${System.getProperty("user.dir")}")
        return Files.newBufferedReader(path).use { it.readText() }
    }
}
