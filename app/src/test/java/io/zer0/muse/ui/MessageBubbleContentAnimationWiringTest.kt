package io.zer0.muse.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class MessageBubbleContentAnimationWiringTest {
    @Test
    fun `only the streaming tail bubble animates content size`() {
        val source = readUi("MessageBubble.kt")

        assertTrue(
            "content-size animation must be gated by the streaming tail state",
            source.contains("val isAnimating = isLastAssistant && isStreaming"),
        )
        assertFalse(
            "completed history bubbles must not keep a layout animation on every scroll frame",
            source.contains("val isAnimating = !(isLastAssistant && isStreaming)"),
        )
    }

    private fun readUi(relative: String): String {
        val candidates =
            listOf(
                Path.of("src/main/java/io/zer0/muse/ui/$relative"),
                Path.of("app/src/main/java/io/zer0/muse/ui/$relative"),
            )
        val path =
            candidates.firstOrNull(Files::exists)
                ?: error("Unable to locate ui/$relative from ${System.getProperty("user.dir")}")
        return Files.newBufferedReader(path).use { it.readText() }
    }
}
