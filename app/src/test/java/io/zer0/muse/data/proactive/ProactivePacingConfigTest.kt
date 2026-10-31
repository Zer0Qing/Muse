package io.zer0.muse.data.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 节奏配置如何真正进入判定。
 *
 * 这些字段以前只有设置页在写、判定层从不读：用户把间隔调到 1 小时，实际
 * 仍然按写死的 3 小时判定。这里逐条盯着「配置改了，判定结论跟着改」，
 * 顺带盯住当日抖动的确定性 —— 它必须是「同一天固定、跨天变化」，
 * 每次随机会把用户调好的间隔变成一个区间。
 */
class ProactivePacingConfigTest {

    private val minute = 60_000L

    /** 2026-10-25 14:00 本地时间（下午，不在默认免打扰时段内）。 */
    private val baseNow = localMillis(2026, 10, 25, 14, 0)

    // ══════════════════════════════════════════════════════════════
    // 间隔
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `配置 60 分钟时间隔真的生效`() {
        // 不注入抖动，单独验间隔本身
        val pacing = pacing(intervalMinutes = 60, randomOffsetMinutes = 0)
        assertEquals(
            ProactiveTimingEngine.Verdict.TOO_SOON,
            evaluate(minutesSinceLastSent = 59, pacing = pacing).verdict,
        )
        assertEquals(
            ProactiveTimingEngine.Verdict.OK,
            evaluate(minutesSinceLastSent = 61, pacing = pacing).verdict,
        )
    }

    @Test
    fun `默认间隔是 240 分钟`() {
        val pacing = ProactiveTimingEngine.Pacing()
        assertEquals(240, ProactiveTimingEngine.effectiveIntervalMinutes(input(0, pacing)))
        assertEquals(
            ProactiveTimingEngine.Verdict.TOO_SOON,
            evaluate(minutesSinceLastSent = 239, pacing = pacing).verdict,
        )
        assertEquals(
            ProactiveTimingEngine.Verdict.OK,
            evaluate(minutesSinceLastSent = 241, pacing = pacing).verdict,
        )
    }

    @Test
    fun `间隔被夹到 30 到 1440 分钟`() {
        // 老配置里可能存着 15 分钟或好几天，判定层不能让它们生效
        assertEquals(
            ProactiveTimingEngine.MIN_INTERVAL_MINUTES,
            ProactiveTimingEngine.effectiveIntervalMinutes(input(0, pacing(5, 0))),
        )
        assertEquals(
            ProactiveTimingEngine.MAX_INTERVAL_MINUTES,
            ProactiveTimingEngine.effectiveIntervalMinutes(input(0, pacing(5000, 0))),
        )
    }

    // ══════════════════════════════════════════════════════════════
    // 当日抖动
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `同一天同一助手得到同一个偏移`() {
        val key = ProactiveTimingEngine.dayKey(baseNow)
        val first = ProactiveTimingEngine.dailyOffsetMinutes(key, "a1", 60)
        val second = ProactiveTimingEngine.dailyOffsetMinutes(key, "a1", 60)

        assertEquals(first, second)
        assertTrue("偏移应落在 0..60：$first", first in 0..60)
    }

    @Test
    fun `换一天偏移跟着变`() {
        // 用固定哈希输入验证：日期是唯一变量
        val today = ProactiveTimingEngine.dailyOffsetMinutes("2026-10-25", "default", 60)
        val tomorrow = ProactiveTimingEngine.dailyOffsetMinutes("2026-10-26", "default", 60)

        assertNotEquals(today, tomorrow)
    }

    @Test
    fun `偏移上限为 0 时不抖动`() {
        assertEquals(0, ProactiveTimingEngine.dailyOffsetMinutes("2026-10-25", "default", 0))
    }

    @Test
    fun `抖动参与判定`() {
        val pacing = pacing(intervalMinutes = 60, randomOffsetMinutes = 60, assistantId = "a1")
        val offset = ProactiveTimingEngine.dailyOffsetMinutes(
            ProactiveTimingEngine.dayKey(baseNow),
            "a1",
            60,
        )
        // 实际间隔 = 60 + 当日偏移：差一分钟仍太近，正好到点才放行
        assertEquals(
            ProactiveTimingEngine.Verdict.TOO_SOON,
            evaluate(minutesSinceLastSent = 59L + offset, pacing = pacing).verdict,
        )
        assertEquals(
            ProactiveTimingEngine.Verdict.OK,
            evaluate(minutesSinceLastSent = 60L + offset, pacing = pacing).verdict,
        )
        assertEquals(60 + offset, ProactiveTimingEngine.effectiveIntervalMinutes(input(0, pacing)))
    }

    // ══════════════════════════════════════════════════════════════
    // 构造辅助
    // ══════════════════════════════════════════════════════════════

    private fun pacing(
        intervalMinutes: Int,
        randomOffsetMinutes: Int,
        assistantId: String = "default",
    ): ProactiveTimingEngine.Pacing = ProactiveTimingEngine.Pacing(
        intervalMinutes = intervalMinutes,
        randomOffsetMinutes = randomOffsetMinutes,
        assistantId = assistantId,
    )

    private fun input(
        minutesSinceLastSent: Long,
        pacing: ProactiveTimingEngine.Pacing,
    ): ProactiveTimingEngine.Input = ProactiveTimingEngine.Input(
        now = baseNow,
        relation = relation(),
        allowedHourStart = 8,
        allowedHourEnd = 22,
        allDayAllowed = false,
        lastUserMessageAt = 0L,
        isGenerating = false,
        lastProactiveSentAt = if (minutesSinceLastSent <= 0) 0L else baseNow - minutesSinceLastSent * minute,
        pacing = pacing,
    )

    private fun evaluate(
        minutesSinceLastSent: Long,
        pacing: ProactiveTimingEngine.Pacing,
    ): ProactiveTimingEngine.Decision = ProactiveTimingEngine.evaluate(input(minutesSinceLastSent, pacing))

    private fun relation(): ProactiveRelationEntity = ProactiveRelationEntity(
        assistantId = "default",
        intimacy = 0.5f,
        cooldownUntil = 0L,
        intensity = ProactiveIntensity.STANDARD.name,
        budgetDate = "2026-10-25",
        spentToday = 0,
        earnedToday = 0f,
    )

    private fun localMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        java.util.Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis
}
