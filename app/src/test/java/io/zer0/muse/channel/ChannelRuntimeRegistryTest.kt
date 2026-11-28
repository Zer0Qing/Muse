package io.zer0.muse.channel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelRuntimeRegistryTest {

    @After
    fun tearDown() {
        ChannelRuntimeRegistry.resetForTest()
    }

    @Test
    fun `state transitions track online and issue counts accurately`() {
        val feishu = ChannelConfig(id = "ch-feishu", platform = ChannelPlatform.FEISHU, enabled = true)
        val tg = ChannelConfig(id = "ch-tg", platform = ChannelPlatform.TELEGRAM, enabled = true)
        val disabled = ChannelConfig(id = "ch-off", platform = ChannelPlatform.QQ, enabled = false)
        val channels = listOf(feishu, tg, disabled)

        ChannelRuntimeRegistry.markConnecting(feishu.id, feishu.platform)
        assertEquals(0, ChannelRuntimeRegistry.onlineCount(channels))

        ChannelRuntimeRegistry.markConnected(feishu.id, feishu.platform, "FeishuBot")
        ChannelRuntimeRegistry.markAuthError(tg.id, tg.platform, "HTTP 401 Unauthorized")
        assertEquals(1, ChannelRuntimeRegistry.onlineCount(channels))
        assertEquals(1, ChannelRuntimeRegistry.issueCount(channels))

        ChannelRuntimeRegistry.markStopped(feishu.id, feishu.platform)
        assertEquals(0, ChannelRuntimeRegistry.onlineCount(channels))

        ChannelRuntimeRegistry.prune(setOf(feishu.id))
        assertEquals(setOf(feishu.id), ChannelRuntimeRegistry.statuses.value.keys)
    }

    @Test
    fun `markInbound recovers reconnecting channel to connected`() {
        val ch = ChannelConfig(id = "ch-1", platform = ChannelPlatform.DINGTALK, enabled = true)
        ChannelRuntimeRegistry.markReconnecting(ch.id, ch.platform, "timeout")
        assertEquals(ChannelConnectionState.RECONNECTING, ChannelRuntimeRegistry.statuses.value[ch.id]?.state)

        ChannelRuntimeRegistry.markInbound(ch.id, ch.platform)
        val status = ChannelRuntimeRegistry.statuses.value[ch.id]
        assertEquals(ChannelConnectionState.CONNECTED, status?.state)
        assertEquals("", status?.lastError)
        assertEquals(1, ChannelRuntimeRegistry.onlineCount(listOf(ch)))
    }
}
