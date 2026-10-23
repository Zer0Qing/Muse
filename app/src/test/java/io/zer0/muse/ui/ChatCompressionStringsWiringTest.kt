package io.zer0.muse.ui

import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ChatCompressionStringsWiringTest {

    @Test
    fun `chat screen no longer exposes compression parameter strings`() {
        val source = locateChatScreenSource()
        assertFalse(source.contains("chat_compress_token_estimate"))
        assertFalse(source.contains("chat_compress_keep_label"))
        assertFalse(source.contains("chat_compress_instruction_label"))
    }

    private fun locateChatScreenSource(): String {
        val candidates =
            listOf(
                Path.of("src/main/java/io/zer0/muse/ui/ChatScreen.kt"),
                Path.of("app/src/main/java/io/zer0/muse/ui/ChatScreen.kt"),
            )
        val path = candidates.firstOrNull(Files::exists)
            ?: error("Unable to locate ChatScreen.kt from ${System.getProperty("user.dir")}")
        return Files.newBufferedReader(path).use { it.readText() }
    }
}
