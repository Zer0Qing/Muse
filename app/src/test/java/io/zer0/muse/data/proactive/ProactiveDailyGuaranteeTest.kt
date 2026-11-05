package io.zer0.muse.data.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「每日保底」的判定边界。
 *
 * 这个开关的产品承诺是「每天至少能收到一条」，但它只能越过「间隔还没到」；
 * 免打扰、冷却、用户刚说过话、正在生成、当天额度用完，仍然不能发。
 * 这些边界一旦被越过，保底就变成了闹钟或超额发送，所以逐条钉住。
 */
class ProactiveDailyGuaranteeTest {

    /** 2026-10-25 14:00 本地时间（不在默认免打扰时段内）。 */
    private val baseNow = localMillis(2026, 10, 25, 14, 0)

    private val hourMs = 60 * 60 * 1000L

    @Test
    fun `距上一条 23 小时且当天还有额度时由日保底放行`() {
        val decision = ProactiveTimingEngine.evaluate(
            input(lastProactiveSentAt = baseNow - 23 * hourMs),
        )

        assertEquals(ProactiveTimingEngine.Verdict.OK, decision.verdict)
        assertTrue(decision.dailyGuarantee)
    }

    @Test
    fun `日保底不越过当天额度`() {
        // 间隔没到 + 额度已用完：两个闸都关着，不能因为「今天还没发」就硬发。
        val decision = ProactiveTimingEngine.evaluate(
            input(lastProactiveSentAt = baseNow - 23 * hourMs, spentToday = 15),
        )

        assertEquals(ProactiveTimingEngine.Verdict.TOO_SOON, decision.verdict)
        assertFalse(decision.dailyGuarantee)
    }

    @Test
    fun `间隔已过但额度用完仍然不发`() {
        val decision = ProactiveTimingEngine.evaluate(
            input(lastProactiveSentAt = baseNow - 25 * hourMs, spentToday = 15),
        )

        assertEquals(ProactiveTimingEngine.Verdict.NO_BUDGET, decision.verdict)
        assertFalse(decision.dailyGuarantee)
    }

    @Test
    fun `未到保底窗口仍按间隔拦截`() {
        val decision = ProactiveTimingEngine.evaluate(
            input(lastProactiveSentAt = baseNow - 21 * hourMs),
        )

        assertEquals(ProactiveTimingEngine.Verdict.TOO_SOON, decision.verdict)
        assertFalse(decision.dailyGuarantee)
    }

    @Test
    fun `关闭日保底后不再越过间隔`() {
        val decision = ProactiveTimingEngine.evaluate(
            input(
                lastProactiveSentAt = baseNow - 23 * hourMs,
                dailyGuaranteeMs = 0L,
            ),
        )

        assertEquals(ProactiveTimingEngine.Verdict.TOO_SOON, decision.verdict)
        assertFalse(decision.dailyGuarantee)
    }

    @Test
    fun `日保底不越过免打扰时段`() {
        val lateNight = localMillis(2026, 10, 25, 23, 30)
        val decision = ProactiveTimingEngine.evaluate(
            input(
                now = lateNight,
                lastProactiveSentAt = lateNight - 23 * hourMs,
            ),
        )

        assertEquals(ProactiveTimingEngine.Verdict.QUIET_HOUR, decision.verdict)
        assertFalse(decision.dailyGuarantee)
    }

    @Test
    fun `日保底不越过冷却`() {
        val decision = ProactiveTimingEngine.evaluate(
            input(
                lastProactiveSentAt = baseNow - 23 * hourMs,
                cooldownUntil = baseNow + hourMs,
            ),
        )

        assertEquals(ProactiveTimingEngine.Verdict.COOLDOWN, decision.verdict)
        assertFalse(decision.dailyGuarantee)
    }

    @Test
    fun `上一轮已经开跑时不被日保底越过`() {
        // 保底不能变成「每隔几分钟重试一次」的成本风暴闸口。
        val decision = ProactiveTimingEngine.evaluate(
            input(
                lastProactiveSentAt = baseNow - 23 * hourMs,
                lastProactiveAttemptAt = baseNow - 5 * 60 * 1000L,
            ),
        )

        assertEquals(ProactiveTimingEngine.Verdict.TOO_SOON, decision.verdict)
        assertFalse(decision.dailyGuarantee)
    }

    private fun input(
        now: Long = baseNow,
        lastProactiveSentAt: Long = 0L,
        lastProactiveAttemptAt: Long = 0L,
        dailyGuaranteeMs: Long = ProactiveTimingEngine.DAILY_GUARANTEE_MS,
        spentToday: Int = 0,
        cooldownUntil: Long = 0L,
    ): ProactiveTimingEngine.Input = ProactiveTimingEngine.Input(
        now = now,
        relation = relation(spentToday, cooldownUntil),
        allowedHourStart = 8,
        allowedHourEnd = 22,
        allDayAllowed = false,
        lastUserMessageAt = 0L,
        isGenerating = false,
        lastProactiveSentAt = lastProactiveSentAt,
        lastProactiveAttemptAt = lastProactiveAttemptAt,
        // 24 小时基础间隔：只有间隔长于保底窗口时，「保底越过间隔」才会真正发生。
        pacing = ProactiveTimingEngine.Pacing(intervalMinutes = 1440),
        dailyGuaranteeMs = dailyGuaranteeMs,
    )

    private fun relation(spentToday: Int, cooldownUntil: Long): ProactiveRelationEntity = ProactiveRelationEntity(
        assistantId = "default",
        intimacy = 0.5f,
        cooldownUntil = cooldownUntil,
        intensity = ProactiveIntensity.STANDARD.name,
        budgetDate = "2026-10-25",
        spentToday = spentToday,
        earnedToday = 0f,
    )

    private fun localMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long = java.util.Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day, hour, minute, 0)
    }.timeInMillis
}
