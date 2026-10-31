package io.zer0.muse.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 无渠道时的日志降噪。
 *
 * 接收循环必须每 30 秒重试一次（否则用户停用再启用渠道就再也收不到消息），
 * 所以在「一直没有渠道」这种长时间稳定状态下，唯一能做的就是别每次都记一笔。
 * 这三种状态转移是最容易写错的：初次进入、持续同状态、恢复。
 */
class WeClawChannelAvailabilityLogTest {

    @Test
    fun `three consecutive idle rounds produce a single log line`() {
        var previous: Boolean? = true
        val lines = (1..3).map {
            val line = channelAvailabilityLog(hasChannel = false, previousHasChannel = previous)
            previous = false
            line
        }

        assertEquals(1, lines.count { it != null })
        assertEquals(2, lines.count { it == null })
        assertNotNull(lines.first())
    }

    @Test
    fun `recovery logs once and then stays quiet`() {
        var previous: Boolean? = false
        val firstAfterRecovery = channelAvailabilityLog(hasChannel = true, previousHasChannel = previous)
        previous = true

        assertNotNull(firstAfterRecovery)
        assertNull(channelAvailabilityLog(hasChannel = true, previousHasChannel = previous))
    }

    @Test
    fun `first round always logs so the initial state is on record`() {
        assertNotNull(channelAvailabilityLog(hasChannel = false, previousHasChannel = null))
        assertNotNull(channelAvailabilityLog(hasChannel = true, previousHasChannel = null))
    }
}
