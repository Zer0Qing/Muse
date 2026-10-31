package io.zer0.muse.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排队超窗预检：入站消息在队列里排到被动回复窗口关闭之后，不应该再跑一轮模型。
 *
 * 原实现的窗口判断只在 runAgent 内部做，于是「排到超窗」的消息照样跑完整轮对话，
 * 烧完 token 才发现发不出去；退避重试还会把这条消息反复拉回来再烧一遍。
 */
class ChannelReplyWindowPrecheckTest {

    private val receivedAt = 1_800_000_000_000L

    /** 官方窗口 5 分钟，发送余量 15 秒。 */
    private val windowMillis = 5 * 60 * 1000L
    private val sendMarginMillis = 15_000L

    private fun qqInbound(receivedAtMillis: Long = receivedAt): ChannelInbox.Inbound = ChannelInbox.Inbound(
        platform = ChannelPlatform.QQ.name,
        from = "group:test-group",
        timestamp = receivedAtMillis,
    )

    @Test
    fun `QQ 截止时刻等于入站时间加窗口减发送余量`() {
        assertEquals(
            receivedAt + windowMillis - sendMarginMillis,
            ChannelAutoReply.replyWindowDeadlineMillis(ChannelPlatform.QQ.name, receivedAt),
        )
        // 其它平台没有被动回复窗口，永不超窗。
        assertEquals(
            Long.MAX_VALUE,
            ChannelAutoReply.replyWindowDeadlineMillis(ChannelPlatform.FEISHU.name, receivedAt),
        )
    }

    @Test
    fun `QQ 入站排队超过被动窗口判为超窗`() {
        assertTrue(queuedReplyWindowExpired(qqInbound(), receivedAt + 6 * 60 * 1000L))
    }

    @Test
    fun `QQ 入站仍在窗口内时放行`() {
        assertFalse(queuedReplyWindowExpired(qqInbound(), receivedAt + 60 * 1000L))
    }

    @Test
    fun `QQ 入站刚好卡在截止时刻判为超窗`() {
        assertTrue(
            queuedReplyWindowExpired(qqInbound(), receivedAt + windowMillis - sendMarginMillis),
        )
    }

    @Test
    fun `非 QQ 平台不受被动窗口影响`() {
        val inbound = ChannelInbox.Inbound(
            platform = ChannelPlatform.FEISHU.name,
            from = "chat-1",
            timestamp = receivedAt,
        )
        assertFalse(queuedReplyWindowExpired(inbound, receivedAt + 60 * 60 * 1000L))
    }

    @Test
    fun `拦截终态错误码写明是排队超时`() {
        assertEquals("被动回复窗口已过期(排队超时)", REPLY_WINDOW_EXPIRED_BY_QUEUE)
    }

    /**
     * 预检位置靠源码顺序锁定：拦截必须早于 appendTurn（否则会话里留一条没有回复的用户消息），
     * 也必须早于 prepareReply（runAgent 只在那里被调用）。
     */
    @Test
    fun `预检拦截早于写会话库与调用模型`() {
        val source = java.io.File("src/main/java/io/zer0/muse/channel/ChannelAutoReply.kt").readText()
        val start = source.indexOf("private suspend fun handleMessage(")
        assertTrue("未找到 handleMessage", start >= 0)
        val end = source.indexOf("private suspend fun prepareReply(", start)
        val body = source.substring(start, if (end > start) end else source.length)

        val blockIdx = body.indexOf("return HandleResult.Blocked(REPLY_WINDOW_EXPIRED_BY_QUEUE)")
        val appendIdx = body.indexOf("ChannelConversationStore.appendTurn")
        val prepareIdx = body.indexOf("prepareReply(inbound, config)")

        assertTrue("handleMessage 里必须有排队超窗拦截", blockIdx >= 0)
        assertTrue("预检必须在 appendTurn 之前", blockIdx < appendIdx)
        assertTrue("预检必须在 prepareReply(即 runAgent) 之前", blockIdx < prepareIdx)
        assertEquals("handleMessage 不应直接调用模型", -1, body.indexOf("runAgent("))
    }
}
