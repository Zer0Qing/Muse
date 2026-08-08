package io.zer0.muse.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * v1.0.72: 每日总结调度时间计算测试。
 *
 * 覆盖 [DailySummaryWorker.computeDelayToNextTarget]:
 *  - 目标时间(19:30)未到 → 延迟到当天 19:30
 *  - 目标时间已过 → 顺延到明天 19:30
 *  - 恰好等于目标时间 → 顺延到明天(避免 0 延迟死循环)
 *
 * 纯时间逻辑,不依赖 WorkManager/网络,CI 稳定运行。
 */
class DailySummaryScheduleTest {

    private fun calendar(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `before target schedules to today`() {
        // 2026-08-08 10:00 → 目标 19:30 当天 → 延迟约 9.5h
        val now = calendar(2026, Calendar.AUGUST, 8, 10, 0)
        val delay = DailySummaryWorker.computeDelayToNextTarget(now)

        assertTrue("延迟应为正数,实际 $delay", delay > 0)
        // 9h30m = 34200000ms,允许 ±2s 偏差(Calendar 秒/毫秒处理)
        val expected = 9 * 3_600_000L + 30 * 60_000L
        assertTrue("延迟 ${delay}ms,期望约 ${expected}ms", Math.abs(delay - expected) < 2_000)
    }

    @Test
    fun `after target schedules to tomorrow`() {
        // 2026-08-08 21:00 → 目标 19:30 已过 → 明天(8-09)19:30 → 约 22.5h
        val now = calendar(2026, Calendar.AUGUST, 8, 21, 0)
        val delay = DailySummaryWorker.computeDelayToNextTarget(now)

        assertTrue("延迟应为正数,实际 $delay", delay > 0)
        val expected = 22 * 3_600_000L + 30 * 60_000L
        assertTrue("延迟 ${delay}ms,期望约 ${expected}ms", Math.abs(delay - expected) < 2_000)
    }

    @Test
    fun `exactly at target rolls to tomorrow`() {
        // 恰好 19:30 → 应顺延到明天(0 延迟会导致 WorkManager 死循环)
        val now = calendar(2026, Calendar.AUGUST, 8, 19, 30)
        val delay = DailySummaryWorker.computeDelayToNextTarget(now)

        assertTrue("恰好目标时间应顺延到明天,延迟 ${delay}ms", delay > 0)
        val expected = 24 * 3_600_000L
        assertTrue("延迟 ${delay}ms,期望约 ${expected}ms", Math.abs(delay - expected) < 2_000)
    }

    @Test
    fun `before target early morning same day`() {
        // 00:05 → 目标 19:30 当天 → 约 19h25m
        val now = calendar(2026, Calendar.AUGUST, 8, 0, 5)
        val delay = DailySummaryWorker.computeDelayToNextTarget(now)

        assertTrue(delay > 0)
        val expected = 19 * 3_600_000L + 25 * 60_000L
        assertTrue("延迟 ${delay}ms,期望约 ${expected}ms", Math.abs(delay - expected) < 2_000)
    }
}
