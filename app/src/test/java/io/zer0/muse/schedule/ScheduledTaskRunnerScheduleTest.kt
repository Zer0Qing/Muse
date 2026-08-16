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
    fun `cron dom and dow both restricted use OR semantics`() {
        // B-13: Quartz 规范 — "0 0 1 * 1" = 每月 1 号 或 每周一(OR),
        // 旧实现 AND 导致只在"既是 1 号又是周一"触发。
        // 2026-08-06 是周四,下个周一是 08-10;下个 1 号是 09-01 → 期望 08-10。
        val base = LocalDateTime.of(2026, 8, 6, 0, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val next = CronExpression.parse("0 0 1 * 1").nextRunAfter(base)
        val expected = LocalDateTime.of(2026, 8, 10, 0, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals("dom+dow 双限定应 OR 匹配", expected, next)
    }

    @Test
    fun `cron dom restricted only uses AND with wildcard dow`() {
        // "0 0 1 * *" = 每月 1 号(dow 为 * 不参与 AND/OR 切换)
        val base = LocalDateTime.of(2026, 8, 6, 0, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val next = CronExpression.parse("0 0 1 * *").nextRunAfter(base)
        val expected = LocalDateTime.of(2026, 9, 1, 0, 0)
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

    // ── B-12: 抢占式领取(CAS)判定 ───────────────────────────────────────
    // 领取成功与否由 ScheduledTaskDao.claimTask 的单条原子 UPDATE 影响行数决定:
    //   1 = 本执行者领取成功(WHERE 条件的 next_run_at 仍等于快照);
    //   0 = 已被其他执行者抢先领取(轮询 / Worker / 手动 / 链式并发时的败者)。
    // 此处单测 claimTaskSucceeded 这一纯判定函数,RSL 覆盖"败者不重复执行"边界的核心逻辑。

    @Test
    fun `claim succeeds when DAO returns affected row`() {
        assertTrue(ScheduledTaskRunner.claimTaskSucceeded(1))
    }

    @Test
    fun `claim fails when already claimed by another executer`() {
        // 0 行影响 = 其他执行者抢先领取(轮询/Worker 并发),败者应跳过,防止重复 AI 调用/通知
        assertEquals(false, ScheduledTaskRunner.claimTaskSucceeded(0))
    }

    @Test
    fun `claim fails on abnormal row count conservative skip`() {
        // 负数/意外值为 DB 异常或缺省,按失败处理(保守跳过,杜绝重复执行)
        assertEquals(false, ScheduledTaskRunner.claimTaskSucceeded(-1))
        assertEquals(false, ScheduledTaskRunner.claimTaskSucceeded(-3))
    }
}
