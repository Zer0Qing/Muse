package io.zer0.muse.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ProactiveSessionToggleWiringTest {
    @Test
    fun `main chat proactive menu does not rewrite global enabled flag`() {
        val source = readUi("ChatScreen.kt")
        val item =
            source.substringAfter("key = \"proactive_toggle\"")
                .substringBefore("key = \"provider\"")

        assertFalse(item.contains("copy(enabled = !proactiveConfig.enabled)"))
        assertTrue(item.contains("setMuted"))
        assertTrue(item.contains("effectiveChatSessionId(state)"))
    }

    @Test
    fun `session mute toggle refreshes its checked state after writing`() {
        val source = readUi("ChatScreen.kt")
        val item =
            source.substringAfter("key = \"proactive_toggle\"")
                .substringBefore("key = \"provider\"")

        assertFalse(
            "produceState only follows session id, so it never turns on after a session-level mute",
            source.contains("val proactiveSessionMuted by produceState("),
        )
        assertTrue(item.contains("proactiveSessionMuted = newMuted"))
    }

    @Test
    fun `settings expose global plain conversation receiver switch`() {
        val source = readUi("settings/AgentSettingsPage.kt")
        assertTrue(source.contains("allowPlainChatSession"))
        assertTrue(source.contains("R.string.settings_agent_plain_chat_enabled"))
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
