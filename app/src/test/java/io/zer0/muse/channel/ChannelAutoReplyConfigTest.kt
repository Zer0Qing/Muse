package io.zer0.muse.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelAutoReplyConfigTest {

    @Test
    fun `webhook reply uses the channel whose credentials authenticated the event`() {
        val first = ChannelConfig("feishu-a", ChannelPlatform.FEISHU, autoReply = true)
        val second = ChannelConfig("feishu-b", ChannelPlatform.FEISHU, autoReply = true)
        val inbound = ChannelInbox.Inbound(
            platform = ChannelPlatform.FEISHU.name,
            sourceChannelId = second.id,
        )

        assertEquals(second, selectAutoReplyConfig(listOf(first, second), inbound))
    }

    @Test
    fun `missing authenticated channel id does not fall through to another channel`() {
        val channel = ChannelConfig("qq-a", ChannelPlatform.QQ, autoReply = true)
        val inbound = ChannelInbox.Inbound(
            platform = ChannelPlatform.QQ.name,
            sourceChannelId = "qq-removed",
        )

        assertNull(selectAutoReplyConfig(listOf(channel), inbound))
    }

    @Test
    fun `legacy inbound without a source channel does not pick an arbitrary account`() {
        val channel = ChannelConfig("qq-a", ChannelPlatform.QQ, autoReply = true)
        val inbound = ChannelInbox.Inbound(platform = ChannelPlatform.QQ.name)

        assertNull(selectAutoReplyConfig(listOf(channel), inbound))
    }

    @Test
    fun `qq passive reply binding expires with the platform reply window`() {
        val now = 1_800_000_000_000L

        assertEquals("message-1", qqReplyEventIdOverride("QQ", "group:test", "message-1", now - 1_000L, now))
        // 过期必须返回 null 而不是空串：空串会命中 replyContext 的事件分支、
        // 又在 isNotBlank 处塔成 null，彻底绕开缓存里那条可用的 msg_id。
        assertNull(
            "group window must expire to null, not empty string",
            qqReplyEventIdOverride("QQ", "group:test", "expired", now - QqMsgIdCache.GROUP_WINDOW_MS - 1L, now),
        )
        assertNull(qqReplyEventIdOverride("QQ", "group:test", "", now, now))
        assertNull(qqReplyEventIdOverride("FEISHU", "event-1", "x", now, now))
    }

    @Test
    fun `c2c keeps the one hour window while group stops at five minutes`() {
        val now = 1_800_000_000_000L
        // 六分钟：单聊仍在窗口内（官方 60 分钟），群聊已过期（官方 5 分钟）。
        val sixMinutes = 6 * 60 * 1000L
        assertEquals("m", qqReplyEventIdOverride("QQ", "user-openid", "m", now - sixMinutes, now))
        assertNull(qqReplyEventIdOverride("QQ", "group:g", "m", now - sixMinutes, now))
    }
}
