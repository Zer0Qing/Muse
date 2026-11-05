package io.zer0.muse.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S4 观测设施回归:卡顿分档/窗口滚动,p95 计算与环形覆盖、样本充足性判定。
 *
 * JankTracker 的 println 协议成对性由测试直接驱动(>> 与 <<),
 * 不依赖真 Looper —— 分档与时序逻辑全部是纯计算。
 */
class DiagnosticsFacilitiesTest {

    // ── MainThreadJankTracker ──────────────────────────────────

    private class Ticker(var now: Long = 0L) {
        fun tracker() = MainThreadJankTracker(nowMs = { now })

        fun round(duration: Long, t: MainThreadJankTracker) {
            t.println(">> msg")
            now += duration
            t.println("<< msg")
        }
    }

    @Test
    fun `三档时长分别计入对应桶`() {
        val ticker = Ticker()
        val t = ticker.tracker()
        ticker.round(70, t)
        ticker.round(300, t)
        ticker.round(1_500, t)
        ticker.round(10, t) // 低于 64 不计
        val s = t.snapshot()
        assertEquals(1, s.jankCount)
        assertEquals(1, s.slowCount)
        assertEquals(1, s.blockCount)
        assertEquals(1_500L, s.worstMs)
    }

    @Test
    fun `阈值边界按包含下界归入本档`() {
        val ticker = Ticker()
        val t = ticker.tracker()
        ticker.round(MainThreadJankTracker.JANK_MS, t) // 64 → jank(>=下界)
        ticker.round(MainThreadJankTracker.JANK_MS - 1, t) // 63 → 不计
        val s = t.snapshot()
        assertEquals(1, s.jankCount)
    }

    @Test
    fun `窗口滚转清零`() {
        val ticker = Ticker()
        val t = ticker.tracker()
        ticker.round(300, t)
        assertTrue(t.snapshot().slowCount == 1)
        ticker.now += MainThreadJankTracker.WINDOW_MS + 1
        // snapshot 自身会滚动窗口
        val after = t.snapshot()
        assertEquals(0, after.slowCount)
        assertEquals(0L, after.worstMs)
    }

    // ── WebHostLatencyStats ────────────────────────────────────

    @Test
    fun `p95 取第 95 百分位且负样本被忽略`() {
        val stats = WebHostLatencyStats(capacity = 100)
        repeat(100) { i -> stats.record((i + 1).toLong()) }
        stats.record(-5)
        val p95 = stats.p95()
        assertTrue("p95 应在 90-96 区间,实际 $p95", p95 != null && p95 in 90..96)
    }

    @Test
    fun `环形覆盖不越界`() {
        val stats = WebHostLatencyStats(capacity = 4)
        repeat(10) { stats.record(it.toLong()) }
        assertEquals(4, stats.sampleCount())
    }

    @Test
    fun `空窗口无 p95`() {
        assertNull(WebHostLatencyStats(capacity = 4).p95())
    }

    @Test
    fun `样本充足性判定`() {
        val stats = WebHostLatencyStats(capacity = 64)
        repeat(WebHostLatencyStats.MIN_RELIABLE_SAMPLES - 1) { stats.record(1) }
        val (count, _) = stats.snapshot()
        assertTrue(count < WebHostLatencyStats.MIN_RELIABLE_SAMPLES)
        stats.record(1)
        assertTrue(stats.snapshot().first >= WebHostLatencyStats.MIN_RELIABLE_SAMPLES)
    }

    @Test
    fun `导出阈值不许抄死在页面`() {
        // 页面直接引用这些常量;测试锁住数值,改动必须同步 UI
        assertEquals(64L, MainThreadJankTracker.JANK_MS)
        assertEquals(256L, MainThreadJankTracker.SLOW_MS)
        assertEquals(1_000L, MainThreadJankTracker.BLOCK_MS)
        assertEquals(512, WebHostLatencyStats.CAPACITY)
    }
}
