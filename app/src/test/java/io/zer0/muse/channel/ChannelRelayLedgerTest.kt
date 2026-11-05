package io.zer0.muse.channel

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 跨渠道转发的护栏行为。
 *
 * 这些断言对应两个真实事故：两个机器人互相转述刷屏、模型一轮里连发多条到别人的 IM。
 */
class ChannelRelayLedgerTest {

    private val base = 1_700_000_000_000L

    @Before
    fun setUp() = ChannelRelayLedger.reset()

    @After
    fun tearDown() = ChannelRelayLedger.reset()

    @Test
    fun `未转发过的内容不算回声`() {
        assertFalse(ChannelRelayLedger.isEcho("ch_b", "在吗", base))
    }

    @Test
    fun `同一渠道窗口内原样回来的内容算回声`() {
        ChannelRelayLedger.recordSent("ch_b", "在吗", base)
        assertTrue(ChannelRelayLedger.isEcho("ch_b", "在吗", base + 1_000))
    }

    @Test
    fun `窗口过期后不再算回声`() {
        ChannelRelayLedger.recordSent("ch_b", "在吗", base)
        val after = base + ChannelRelayLedger.ECHO_WINDOW_MS + 1
        assertFalse(ChannelRelayLedger.isEcho("ch_b", "在吗", after))
    }

    @Test
    fun `别的渠道上出现同样的话不算本渠道回声`() {
        ChannelRelayLedger.recordSent("ch_b", "在吗", base)
        assertFalse(ChannelRelayLedger.isEcho("ch_c", "在吗", base + 1_000))
    }

    @Test
    fun `前后空白不影响判定`() {
        ChannelRelayLedger.recordSent("ch_b", "在吗", base)
        assertTrue(ChannelRelayLedger.isEcho("ch_b", "  在吗 ", base + 500))
    }

    @Test
    fun `单轮转发超过上限后拒绝`() {
        repeat(ChannelRelayLedger.MAX_RELAY_PER_TURN) {
            assertTrue(ChannelRelayLedger.tryReserveSend(base))
            ChannelRelayLedger.recordSent("ch_b", "第 $it 条", base)
        }
        assertFalse(ChannelRelayLedger.tryReserveSend(base))
    }

    @Test
    fun `新一轮开始后单轮计数清零`() {
        repeat(ChannelRelayLedger.MAX_RELAY_PER_TURN) {
            ChannelRelayLedger.tryReserveSend(base)
            ChannelRelayLedger.recordSent("ch_b", "第 $it 条", base)
        }
        ChannelRelayLedger.beginTurn()
        assertTrue(ChannelRelayLedger.tryReserveSend(base))
    }

    @Test
    fun `每小时总量上限生效`() {
        repeat(ChannelRelayLedger.MAX_RELAY_PER_HOUR) {
            ChannelRelayLedger.beginTurn()
            assertTrue(ChannelRelayLedger.tryReserveSend(base + it))
            ChannelRelayLedger.recordSent("ch_b", "第 $it 条", base + it)
        }
        ChannelRelayLedger.beginTurn()
        assertFalse(ChannelRelayLedger.tryReserveSend(base + 999))
    }

    @Test
    fun `一小时之前的记账会被清掉`() {
        repeat(ChannelRelayLedger.MAX_RELAY_PER_HOUR) {
            ChannelRelayLedger.beginTurn()
            ChannelRelayLedger.tryReserveSend(base + it)
            ChannelRelayLedger.recordSent("ch_b", "第 $it 条", base + it)
        }
        ChannelRelayLedger.beginTurn()
        val later = base + 3_600_001L
        assertTrue(ChannelRelayLedger.tryReserveSend(later))
    }
}
