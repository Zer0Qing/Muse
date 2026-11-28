package io.zer0.muse.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelSlashCommandAndTriggerTest {

    @Test
    fun `slash command parser recognizes all supported remote commands`() {
        assertEquals(ChannelSlashCommand.NEW_SESSION, ChannelSlashCommandParser.parse("/new"))
        assertEquals(ChannelSlashCommand.NEW_SESSION, ChannelSlashCommandParser.parse("/reset@MuseBot"))
        assertEquals(ChannelSlashCommand.STATUS, ChannelSlashCommandParser.parse("  /status "))
        assertEquals(ChannelSlashCommand.PAUSE_AI, ChannelSlashCommandParser.parse("/pause"))
        assertEquals(ChannelSlashCommand.RESUME_AI, ChannelSlashCommandParser.parse("/resume"))
        assertEquals(ChannelSlashCommand.HELP, ChannelSlashCommandParser.parse("/help"))
        assertNull(ChannelSlashCommandParser.parse("hello world"))
        assertNull(ChannelSlashCommandParser.parse("/unknown"))
    }

    @Test
    fun `group trigger policy enforces mention and keyword modes while direct chats pass`() {
        val mentionCfg = ChannelConfig(
            id = "ch-1",
            platform = ChannelPlatform.FEISHU,
            groupTriggerMode = ChannelConfig.GROUP_TRIGGER_MENTION,
            botDisplayName = "@MuseHelper",
        )
        assertTrue(ChannelGroupTriggerPolicy.shouldRespond(mentionCfg, isGroup = false, isMentioned = false, text = "hi"))
        assertFalse(ChannelGroupTriggerPolicy.shouldRespond(mentionCfg, isGroup = true, isMentioned = false, text = "random chat"))
        assertTrue(ChannelGroupTriggerPolicy.shouldRespond(mentionCfg, isGroup = true, isMentioned = true, text = "check this"))
        assertTrue(ChannelGroupTriggerPolicy.shouldRespond(mentionCfg, isGroup = true, isMentioned = false, text = "hi @musehelper"))

        val keywordCfg = mentionCfg.copy(
            groupTriggerMode = ChannelConfig.GROUP_TRIGGER_KEYWORD,
            triggerKeywords = listOf("总结", "muse"),
        )
        assertFalse(ChannelGroupTriggerPolicy.shouldRespond(keywordCfg, isGroup = true, isMentioned = true, text = "hello"))
        assertTrue(ChannelGroupTriggerPolicy.shouldRespond(keywordCfg, isGroup = true, isMentioned = false, text = "帮我总结一下"))
    }
}
