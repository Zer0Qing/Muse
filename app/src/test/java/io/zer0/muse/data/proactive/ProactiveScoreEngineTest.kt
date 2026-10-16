package io.zer0.muse.data.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.2: 主动消息修复的回归测试。
 *
 * 覆盖两处用户反馈：
 *  1. 开了「全天允许」后，评分引擎不得再因硬编码安静时段拦截（夜间活跃用户一条不发）。
 *  2. 「极多」档位存在，且能与固定间隔档位区分开。
 */
class ProactiveScoreEngineTest {

    private val engine = ProactiveScoreEngine()

    private fun ctx(hoursSinceLastMessage: Float = 48f) = ScoreContext(
        hoursSinceLastMessage = hoursSinceLastMessage,
        accountAgeDays = 40,
        recentMood = Mood.NEUTRAL,
        todaySentCount = 0,
        hasNewMemories = true,
        maxDailyMessages = 8,
    )

    @Test
    fun `quiet hour blocks sending by default`() {
        // 凌晨 3 点，未开全天 → 拦截
        assertFalse(engine.shouldSend(ctx(), currentHour = 3, allDayAllowed = false))
    }

    @Test
    fun `all day allowed bypasses quiet hours`() {
        // 凌晨 3 点，开了全天 → 不再被安静时段拦截（评分够高时应放行）
        val result = engine.shouldSend(ctx(hoursSinceLastMessage = 72f), currentHour = 3, allDayAllowed = true)
        assertTrue("全天允许后凌晨也应可发", result)
    }

    @Test
    fun `all day allowed still respects daily limit`() {
        val atLimit = ctx(hoursSinceLastMessage = 72f).copy(todaySentCount = 8, maxDailyMessages = 8)
        assertFalse(engine.shouldSend(atLimit, currentHour = 3, allDayAllowed = true))
    }

    @Test
    fun `daytime sending unaffected`() {
        assertTrue(engine.shouldSend(ctx(hoursSinceLastMessage = 72f), currentHour = 14, allDayAllowed = false))
    }

    @Test
    fun `extreme pace exists and is distinct`() {
        val extreme = ProactivePace.EXTREME
        assertNotNull(extreme)
        // 与「多」档区分：间隔更短、日上限更高
        assertTrue(extreme.intervalMinutes < ProactivePace.HEAVY.intervalMinutes)
        assertTrue(extreme.maxDailyMessages > ProactivePace.HEAVY.maxDailyMessages)
    }

    @Test
    fun `every pace including extreme round trips`() {
        ProactivePace.values().forEach { pace ->
            assertEquals(
                pace,
                ProactivePace.from(pace.intervalMinutes, pace.sendProbability, pace.maxDailyMessages),
            )
        }
    }
}
