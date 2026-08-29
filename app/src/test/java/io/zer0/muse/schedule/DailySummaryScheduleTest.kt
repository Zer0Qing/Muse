package io.zer0.muse.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** 每日总结四个固定时点的时间计算测试。 */
class DailySummaryScheduleTest {

    private fun calendar(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `each configured slot is scheduled on the same day before it`() {
        val now = calendar(2026, Calendar.AUGUST, 8, 8, 0)
        val expected = listOf(
            (9 to 0) to (1 * 3_600_000L),
            (12 to 0) to (4 * 3_600_000L),
            (21 to 0) to (13 * 3_600_000L),
        )
        expected.forEach { (slot, delayExpected) ->
            val delay = DailySummaryWorker.computeDelayToNextTarget(now, slot.first, slot.second)
            assertTrue("${slot.first}:${slot.second} 延迟应为正数", delay > 0)
            assertEquals("${slot.first}:${slot.second} 延迟错误", delayExpected, delay)
        }
    }

    @Test
    fun `after a slot schedules the next day`() {
        val now = calendar(2026, Calendar.AUGUST, 8, 21, 1)
        val delay = DailySummaryWorker.computeDelayToNextTarget(now, 21, 0)
        assertEquals(23 * 3_600_000L + 59 * 60_000L, delay)
    }

    @Test
    fun `exactly at a slot rolls to tomorrow`() {
        val now = calendar(2026, Calendar.AUGUST, 8, 12, 0)
        val delay = DailySummaryWorker.computeDelayToNextTarget(now, 12, 0)
        assertEquals(24 * 3_600_000L, delay)
    }

    @Test
    fun `midnight summarizes the previous local date`() {
        assertEquals(
            "2026-08-07",
            DailySummaryWorker.summaryDateForTarget("2026-08-08", 0).toString(),
        )
        assertEquals(
            "2026-08-08",
            DailySummaryWorker.summaryDateForTarget("2026-08-08", 21).toString(),
        )
    }

    @Test
    fun `slot keys and work names are stable`() {
        assertEquals("2026-08-08#0900", DailySummaryWorker.slotKey("2026-08-08", 9, 0))
        assertEquals("muse_daily_summary_worker_1200", DailySummaryWorker.uniqueWorkName(12, 0))
    }
}
