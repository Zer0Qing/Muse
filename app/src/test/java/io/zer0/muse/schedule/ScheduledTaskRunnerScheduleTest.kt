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

    // ── once 语义 ─────────────────────────────────────────────────────
    // 「下一次执行」与「首跑时间」是两个问题,共用同一个间隔实现:
    //   computeNextRun   = 这次执行完之后还有没有下一次 → once 为 0(无下次)
    //   initialNextRunAt = 刚建出来/刚改完间隔后什么时候首跑 → once 为 now + 一个轮询周期
    // 0 不是「马上再跑一次」,ScheduledTaskDao.recordExecutionAndScheduleNext 见 <=0 会
    // setEnabled(false),所以 once 任务执行完就退出调度,不会被算出第二次执行。

    @Test
    fun `once has no next run after it executes`() {
        val now = 1_700_000_000_000L
        // 带不带 cron 表达式都不影响:once 不看 cron
        assertEquals("once 无下次执行", 0L, ScheduledTaskRunner.computeNextRun("once", "", now))
        assertEquals("once 无下次执行", 0L, ScheduledTaskRunner.computeNextRun("once", "0 9 * * *", now))
        // 未知 interval 与 once 同口径:无下次执行(不再退回「+1 天」或「+1 分钟」)
        assertEquals(0L, ScheduledTaskRunner.computeNextRun("every-other-day", "", now))
    }

    @Test
    fun `once first run waits one poll interval`() {
        val now = 1_700_000_000_000L
        // 首跑不能落 0(0 是执行后的禁用标记),也不能落 now —— 落 now 会在下一次扫描
        // (0–60s,最坏是紧接着的那一跳)就被领取,用户点完「创建」就看到任务已跑完。
        // 留一个轮询周期作缓冲,与调度器的 POLL_INTERVAL_MS 同源。
        assertEquals(now + 60_000L, ScheduledTaskRunner.initialNextRunAt("once", "", now))
        assertTrue("首跑时间必须为正,才能进入 getDueTasks(next_run_at <= now)", ScheduledTaskRunner.initialNextRunAt("once", "", now) > 0)
    }

    @Test
    fun `recurring first run keeps the fixed offset`() {
        val now = 1_700_000_000_000L
        assertEquals(now + 3_600_000L, ScheduledTaskRunner.initialNextRunAt("hourly", "", now))
        assertEquals(now + 86_400_000L, ScheduledTaskRunner.initialNextRunAt("daily", "", now))
        assertEquals(now + 604_800_000L, ScheduledTaskRunner.initialNextRunAt("weekly", "", now))
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
    fun `cron impossible expression returns NO_MATCH not claim sentinel`() {
        // 审查修复 (2.0 C-06): 扫描窗口内无匹配的 cron(如 2 月 30 日)必须返回
        // CronExpression.NO_MATCH(= Long.MAX_VALUE - 1),不得与 ScheduledTaskRunner
        // 的领取哨兵(CLAIM_NEXT_RUN_SENTINEL = Long.MAX_VALUE)混用 —
        // 混用会把"cron 无匹配"误判为"任务已被领取",任务永不再跑。
        val base = LocalDateTime.of(2026, 1, 1, 0, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val next = CronExpression.parse("0 0 30 2 *").nextRunAfter(base)
        assertEquals("无匹配应返回 NO_MATCH", CronExpression.NO_MATCH, next)
        assertTrue(
            "NO_MATCH 必须区别于领取哨兵,否则任务调度被误判为已领取",
            next != Long.MAX_VALUE,
        )
    }

    // ── B-12: 抢占式领取(CAS)判定 ───────────────────────────────────────
    // 领取成功与否由 ScheduledTaskDao.claimTask 的单条原子 UPDATE 影响行数决定:
    //   1 = 本执行者领取成功(WHERE 条件的 next_run_at 仍等于快照);
    //   0 = 已被其他执行者抢先领取(轮询 / Worker / 手动 / 链式并发时的败者)。
    // 此处单测 claimTaskSucceeded 这一纯判定函数,覆盖"败者不重复执行"边界的核心逻辑。

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
