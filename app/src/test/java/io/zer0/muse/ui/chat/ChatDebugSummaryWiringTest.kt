package io.zer0.muse.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ChatDebugSummaryWiringTest {

    private fun source(): String = listOf(
        Path.of("src/main/java/io/zer0/muse/ui/chat/ChatGenerationController.kt"),
        Path.of("app/src/main/java/io/zer0/muse/ui/chat/ChatGenerationController.kt"),
    )
        .firstOrNull(Files::exists)
        ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
        ?: error("ChatGenerationController.kt not found")

    @Test
    fun `debug summary is recorded on completed failed and cancelled turns`() {
        val text = source()
        val streamBody = text.substringAfter("fun launchStream(")
            .substringBefore("suspend fun finalizeResponse")
        val finalizeBody = text.substringAfter("suspend fun finalizeResponse")
            .substringBefore("companion object")

        assertTrue(text.contains("suspend fun recordDebugSummary"))
        assertTrue(streamBody.contains("recordDebugSummary(state, \"failed\")"))
        assertTrue(streamBody.contains("recordDebugSummary(state, \"cancelled\")"))
        assertTrue(finalizeBody.contains("recordDebugSummary(state, \"completed\")"))
    }
}
