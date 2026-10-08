package io.zer0.muse.ui

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the long-chat message map entry point.
 *
 * The map must be composed independently of the forward-message dialog;
 * otherwise it disappears during normal chat because forwardText is null.
 */
class ChatMessageMapWiringTest {

    @Test
    fun `message map is outside the forward dialog scope`() {
        val source = locateChatScreenSource()
        val forwardStart = source.indexOf("forwardText?.let { text ->")
        val mapStart = source.indexOf("MessageMapBar(")
        val forwardEnd = source.indexOf("// A6: 消息地图", forwardStart)

        assertTrue("forward dialog scope should exist", forwardStart >= 0)
        assertTrue("message map should exist", mapStart >= 0)
        assertTrue("forward dialog scope should close before the map comment", forwardEnd > forwardStart)
        assertTrue("message map should be after the forward dialog scope", mapStart > forwardEnd)
        assertFalse(
            "message map must not be nested in forwardText?.let",
            mapStart in forwardStart until forwardEnd,
        )
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
