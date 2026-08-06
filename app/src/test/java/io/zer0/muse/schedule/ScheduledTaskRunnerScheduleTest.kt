package io.zer0.muse.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * R-TEST-11: 定时任务下次触发时间 + 主动消息免打扰窗口纯逻辑测试。
 */
class ScheduledTaskRunnerScheduleTest {

    @Test
    fun `interval modes compute fixed offsets`() {
        val now = 1_700_000_000_000L
        assertEquals(now + 3_600_000L, ScheduledTaskRunner.computeNextRun("hourly", "", now))
        assertEquals(now + 86_400_000L, ScheduledTaskRunner.computeNextRun("daily", "", now))
        assertEquals(now + 604_800_000L, ScheduledTaskRunner.computeNextRun("weekly", "", now))
        assertEquals(0L, ScheduledTaskRunner.computeNextRun("once", "", now))
    }

    @Test
    fun `cron blank or invalid degrades to once`() {
        val now = 1_700_000_000_000L
        assertEquals(0L, ScheduledTaskRunner.computeNextRun("cron", "", now))
        assertEquals(0L, ScheduledTaskRunner.computeNextRun("cron", "not-a-cron", now))
    }

    @Test
    fun `cron computes next daily run`() {
        val base = LocalDateTime.of(2026, 8, 6, 9, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val next = CronExpression.parse("30 9 * * *").nextRunAfter(base)
        val expected = LocalDateTime.of(2026, 8, 6, 9, 30)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, next)
    }

    @Test
    fun `cron every 15 minutes crosses into next hour`() {
        val base = LocalDateTime.of(2026, 8, 6, 9, 50)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val next = CronExpression.parse("*/15 * * * *").nextRunAfter(base)
        val expected = LocalDateTime.of(2026, 8, 6, 10, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, next)
    }

    @Test
    fun `allowed window non crossing boundaries`() {
        assertTrue(ProactiveMessageRunner.isInAllowedWindow(9, 9, 18))
        assertTrue(ProactiveMessageRunner.isInAllowedWindow(17, 9, 18))
        assertEquals(false, ProactiveMessageRunner.isInAllowedWindow(18, 9, 18))
        assertEquals(false, ProactiveMessageRunner.isInAllowedWindow(8, 9, 18))
    }

    @Test
    fun `allowed window crossing midnight`() {
        assertTrue(ProactiveMessageRunner.isInAllowedWindow(23, 22, 8))
        assertTrue(ProactiveMessageRunner.isInAllowedWindow(7, 22, 8))
        assertEquals(false, ProactiveMessageRunner.isInAllowedWindow(8, 22, 8))
        assertEquals(false, ProactiveMessageRunner.isInAllowedWindow(21, 22, 8))
    }
}
