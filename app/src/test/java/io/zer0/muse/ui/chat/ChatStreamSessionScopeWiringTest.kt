package io.zer0.muse.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ChatStreamSessionScopeWiringTest {

    @Test
    fun `prompt finalize hook receives the generation session`() {
        val source = locateCoordinatorSource()
        assertTrue(
            "PromptFinalizeEvent must use the StreamRunState session id",
            source.contains("sessionId = sessionId,"),
        )
        assertFalse(
            "PromptFinalizeEvent must not use task-session UI state in Agent mode",
            source.contains("sessionId = accessor.snapshot.currentSessionId,"),
        )
    }

    private fun locateCoordinatorSource(): String {
        val candidates =
            listOf(
                Path.of("src/main/java/io/zer0/muse/ui/chat/ChatStreamCoordinator.kt"),
                Path.of("app/src/main/java/io/zer0/muse/ui/chat/ChatStreamCoordinator.kt"),
            )
        val path = candidates.firstOrNull(Files::exists)
            ?: error("Unable to locate ChatStreamCoordinator.kt from ${System.getProperty("user.dir")}")
        return Files.newBufferedReader(path).use { it.readText() }
    }
}
