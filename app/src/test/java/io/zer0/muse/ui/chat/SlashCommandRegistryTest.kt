package io.zer0.muse.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-TEST-20: 斜杠命令解析测试。
 */
class SlashCommandRegistryTest {

    @Test
    fun `parse recognizes known command`() {
        assertEquals(SlashCommand.NEW, SlashCommand.parse("/new"))
    }

    @Test
    fun `parse trims surrounding whitespace`() {
        assertEquals(SlashCommand.COMPACT, SlashCommand.parse("  /compact  "))
    }

    @Test
    fun `parse is case insensitive`() {
        assertEquals(SlashCommand.RESET, SlashCommand.parse("/RESET"))
    }

    @Test
    fun `parse ignores trailing arguments`() {
        assertEquals(SlashCommand.PIN, SlashCommand.parse("/pin 12345"))
    }

    @Test
    fun `parse returns null for plain text and empty input`() {
        assertNull(SlashCommand.parse("hello"))
        assertNull(SlashCommand.parse(""))
        assertNull(SlashCommand.parse("   "))
    }

    @Test
    fun `parse returns null for unknown command`() {
        assertNull(SlashCommand.parse("/unknown"))
    }

    @Test
    fun `isSlashCommand only accepts registered commands`() {
        assertTrue(SlashCommand.isSlashCommand("/archive"))
        assertFalse(SlashCommand.isSlashCommand("archive"))
        assertFalse(SlashCommand.isSlashCommand("/nope"))
    }

    @Test
    fun `allCommandNames lists slash prefixed commands`() {
        val names = SlashCommand.allCommandNames()
        assertTrue(names.contains("/new"))
        assertTrue(names.contains("/compact"))
        assertTrue(names.contains("/reset"))
        assertTrue(names.contains("/pin"))
        assertTrue(names.contains("/archive"))
    }
}
