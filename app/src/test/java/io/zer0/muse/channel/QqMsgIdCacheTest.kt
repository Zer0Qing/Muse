package io.zer0.muse.channel

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class QqMsgIdCacheTest {
    @Test
    fun `automatic reply uses its source event instead of a newer cached message`() {
        val target = "test-${UUID.randomUUID()}"
        QqMsgIdCache.put(target, "newer-message")

        val firstAttempt = QqMsgIdCache.replyContext(target, "queued-message")
        val retry = QqMsgIdCache.replyContext(target, "queued-message")
        val regularSend = QqMsgIdCache.replyContext(target, null)
        val missingSource = QqMsgIdCache.replyContext(target, "")

        assertEquals("queued-message", firstAttempt?.messageId)
        assertEquals(1, firstAttempt?.sequence)
        assertEquals(firstAttempt, retry)
        assertEquals("newer-message", regularSend?.messageId)
        // 空串不是有效事件身份，应回退到缓存，而不是塔成 null 把可用的 msg_id 一起丢掉。
        assertEquals("newer-message", missingSource?.messageId)
    }

    @Test
    fun `event-bound reply sequence advances with the persisted retry attempt`() {
        val firstAttempt = QqMsgIdCache.replyContext("target", "event-1", sequenceOverride = 1)
        val retry = QqMsgIdCache.replyContext("target", "event-1", sequenceOverride = 2)

        assertEquals(1, firstAttempt?.sequence)
        assertEquals(2, retry?.sequence)
        assertEquals(firstAttempt?.messageId, retry?.messageId)
    }
}
