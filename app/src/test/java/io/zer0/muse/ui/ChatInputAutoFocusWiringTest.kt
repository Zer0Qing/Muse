package io.zer0.muse.ui

import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ChatInputAutoFocusWiringTest {
    @Test
    fun `main chat does not request input focus on entry`() {
        val source = readUi("ChatScreen.kt")
        val inputCall =
            source.substringAfter("RichInputBar(", missingDelimiterValue = "")
                .substringBefore("tokenEstimateEnabled", missingDelimiterValue = "")

        assertFalse(
            "entering a conversation must not open the keyboard; focus should start only after the user taps the field",
            inputCall.contains("autoFocus = !isAgentMode"),
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
