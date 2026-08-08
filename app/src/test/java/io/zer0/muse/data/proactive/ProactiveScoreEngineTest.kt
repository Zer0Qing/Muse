package io.zer0.muse.data.proactive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.72: 主动消息评分引擎测试。
 *
 * 背景:旧公式为乘法(score = timeW × silenceW × emotionW × noveltyW),
 * 典型场景得分 0.01~0.05,阈值 0.6 几乎永远无法触发 → 用户手机上主动消息\"一直不发\"。
 * 修复:加权平均 + 阈值降到 0.2(用户决策)。
 *
 * 本测试锁定新行为:
 *  - 正常用户(24h 没聊、情绪未知、无新内容)应能过阈值 → 每天都能触发
 *  - 刚聊完(1h 内)应低于阈值 → 不打扰
 *  - 极端安静(7 天)应高分
 */
class ProactiveScoreEngineTest {

    private val engine = ProactiveScoreEngine()

    /** 非安静时段(下午 2 点),避免测试在 22:00-08:00 运行时被时段拦截。 */
    private val NON_QUIET_HOUR = 14

    /** 24h 没聊、情绪未知、无新内容 — 旧公式 0.013 永远不过;新公式应过。 */
    @Test
    fun `typical daily user passes threshold after one day silence`() {
        val ctx = ScoreContext(
            hoursSinceLastMessage = 24f,   // 昨天聊过
            accountAgeDays = 30,           // 老用户(理想间隔 7 天 → silenceW 偏低)
            recentMood = Mood.UNKNOWN,     // 无情绪标签 → emotionW 0.5
            hasNewMilestones = false,
            hasNewMemories = false,
            hasNewTopics = false,          // noveltyW 0.3
            todaySentCount = 0,
        )
        val score = engine.calculateScore(ctx)
        assertTrue("加权平均后 24h 沉默应过阈值 0.2,实际 $score", score >= 0.2f)
        assertTrue("shouldSend 应为 true", engine.shouldSend(ctx, NON_QUIET_HOUR))
    }

    /** 刚聊完(10 分钟) — v1.0.72 无时间门槛,评分仍按加权平均计算。 */
    @Test
    fun `just chatted still computes weighted score without time gate`() {
        val ctx = ScoreContext(
            hoursSinceLastMessage = 0.16f, // 10 分钟前
            accountAgeDays = 30,
            recentMood = Mood.POSITIVE,
            todaySentCount = 0,
        )
        // 无硬性时间门槛:发不发由评分 + 概率 + 发送间隔共同决定,不在此处拦截
        val score = engine.calculateScore(ctx)
        // 加权平均:timeW≈0.007 + silenceW≈0.001 + emotionW=0.8 + noveltyW=0.3 → ≈0.28
        assertTrue("刚聊完也应计算加权评分(不设门槛),实际 $score", score > 0f)
        assertTrue("刚聊完加权平均可过阈值(用户选择不设时间门槛)", score >= 0.2f)
    }

    /** 几天没聊 + 有新内容 → 高分。 */
    @Test
    fun `long silence with new content scores high`() {
        val ctx = ScoreContext(
            hoursSinceLastMessage = 72f,   // 3 天
            accountAgeDays = 30,
            recentMood = Mood.POSITIVE,
            hasNewMemories = true,
            hasNewTopics = true,
            todaySentCount = 0,
        )
        val score = engine.calculateScore(ctx)
        assertTrue("3 天沉默 + 新内容应高分,实际 $score", score >= 0.5f)
    }

    /** 每日上限到达后不再发送(即使评分高)。 */
    @Test
    fun `daily limit stops sending`() {
        val ctx = ScoreContext(
            hoursSinceLastMessage = 72f,
            accountAgeDays = 30,
            recentMood = Mood.POSITIVE,
            todaySentCount = 3, // 已达默认上限
            maxDailyMessages = 3,
        )
        assertFalse("已达每日上限不应发送", engine.shouldSend(ctx, NON_QUIET_HOUR))
    }

    /** 新用户(7 天)24h 沉默也应触发 — 理想间隔 3 天,更宽松。 */
    @Test
    fun `new user passes threshold too`() {
        val ctx = ScoreContext(
            hoursSinceLastMessage = 24f,
            accountAgeDays = 7,
            recentMood = Mood.UNKNOWN,
            todaySentCount = 0,
        )
        val score = engine.calculateScore(ctx)
        assertTrue("新用户 24h 沉默应过阈值,实际 $score", score >= 0.2f)
        assertTrue(engine.shouldSend(ctx, NON_QUIET_HOUR))
    }
}
