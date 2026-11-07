package io.zer0.muse.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 实机走查 三-S1：定时任务表单里选了 daily/weekly 却设不了时间——只有手写 cron 才能定"每天 8 点"。
 * 界面改为写 cronExpr 承载时刻，这里钉住调度口径真的读它，并且没填时刻时行为一字不变。
 */
class ScheduledTaskFixedTimeTest {

    private fun at(millis: Long, field: Int): Int = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = millis }.get(field)

    @Test
    fun `daily 带时刻时按墙钟定点，不再等于现在加二十四小时`() {
        val now = System.currentTimeMillis()
        val next = ScheduledTaskRunner.computeNextRun("daily", "0 8 * * *", now)
        assertTrue("下一次必须晚于现在", next > now)
        assertEquals(8, at(next, Calendar.HOUR_OF_DAY))
        assertEquals(0, at(next, Calendar.MINUTE))
    }

    @Test
    fun `daily 没填时刻与过去完全同口径`() {
        val now = System.currentTimeMillis()
        assertEquals(now + 86_400_000L, ScheduledTaskRunner.computeNextRun("daily", "", now))
        assertEquals(now + 86_400_000L, ScheduledTaskRunner.computeNextRun("daily", "   ", now))
    }

    @Test
    fun `weekly 带时刻同样定点`() {
        val now = System.currentTimeMillis()
        val next = ScheduledTaskRunner.computeNextRun("weekly", "30 7 * * 1", now)
        assertEquals(7, at(next, Calendar.HOUR_OF_DAY))
        assertEquals(30, at(next, Calendar.MINUTE))
    }

    @Test
    fun `执行后推进也走定点口径，不会漂成上次加二十四小时`() {
        // 锚点：昨天 8:00 的计划时刻；现在：今天 9:30 —— 定点口径下下一次应是今天 8:00 之后那天
        val tz = TimeZone.getDefault()
        val scheduled = Calendar.getInstance(tz).apply {
            set(2026, Calendar.NOVEMBER, 5, 8, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val now = Calendar.getInstance(tz).apply {
            set(2026, Calendar.NOVEMBER, 6, 9, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val advance = ScheduledTaskRunner.computeNextRunAnchored("daily", "0 8 * * *", scheduled, now)

        assertEquals(8, at(advance.nextRunAt, Calendar.HOUR_OF_DAY))
        assertEquals(Calendar.NOVEMBER, at(advance.nextRunAt, Calendar.MONTH))
        assertEquals(7, at(advance.nextRunAt, Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `非法或空表达式退回周期口径而不是崩`() {
        val now = System.currentTimeMillis()
        // 表达式写坏时仍按"现在 + 一天"跑，不让一个坏 cron 把任务变成永不执行
        val next = ScheduledTaskRunner.computeNextRun("daily", "not a cron", now)
        assertTrue(next == 0L || next >= now)
    }

    @Test
    fun `哨兵与无匹配都不是下次执行时刻`() {
        assertTrue(ScheduledTaskRunner.isPlaceholderNextRun(Long.MAX_VALUE))
        assertTrue(ScheduledTaskRunner.isPlaceholderNextRun(CronExpression.NO_MATCH))
        assertFalse(ScheduledTaskRunner.isPlaceholderNextRun(System.currentTimeMillis()))
        assertFalse(ScheduledTaskRunner.isPlaceholderNextRun(0L))
    }
}
