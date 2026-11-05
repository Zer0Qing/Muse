package io.zer0.muse.proactive

import io.zer0.muse.data.proactive.OutcomeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 智能时机打分(P1)的纯函数回归。
 *
 * 钉住三条不变量:
 *  1. 无任何信号修正时与原掷骰闸逐字等价(roll < configured 即发送,100 恒发,0 恒不发);
 *  2. 每条降概率修正都有人话理由(排查「为什么没发」不需要猜);
 *  3. 「账上有事」的回补只回到用户配置的基准,不顶破 —— 概率闸的语义守恒。
 */
class ProactiveSendScoringTest {

    private val neutral = ProactiveSendScoring.Signals(
        configuredPercent = 100,
        lastOutcomeState = null,
        minutesSinceLastProactive = null,
        userUnreadCount = 0,
        hasOpenThoughts = false,
        hourOfDay = 14,
    )

    private fun evaluate(
        configured: Int = 100,
        lastState: OutcomeState? = null,
        minutesSinceLast: Long? = null,
        unread: Int = 0,
        openThoughts: Boolean = false,
        hour: Int = 14,
        roll: Int = 50,
    ): ProactiveSendScoring.Decision = ProactiveSendScoring.evaluate(
        neutral.copy(
            configuredPercent = configured,
            lastOutcomeState = lastState,
            minutesSinceLastProactive = minutesSinceLast,
            userUnreadCount = unread,
            hasOpenThoughts = openThoughts,
            hourOfDay = hour,
        ),
        rollPercent = roll,
    )

    @Test
    fun `配置为 0 恒不发送并带理由`() {
        val d = evaluate(configured = 0)
        assertFalse(d.send)
        assertEquals(0, d.effectivePercent)
    }

    @Test
    fun `无信号时与原掷骰闸等价`() {
        // configured=60, roll=59 应发、roll=60 不 — 与原概率闸同判定
        assertTrue(evaluate(configured = 60, roll = 59).send)
        assertFalse(evaluate(configured = 60, roll = 60).send)
        assertEquals(listOf<String>(), evaluate(configured = 60).reasons)
    }

    @Test
    fun `上一条没人回且没过隔夜窗口时概率减半`() {
        val d = evaluate(configured = 100, lastState = OutcomeState.PENDING, minutesSinceLast = 60, roll = 40)
        assertEquals(50, d.effectivePercent)
        assertTrue(d.reasons.any { it.contains("没有回应") })
    }

    @Test
    fun `上一条被划掉同样按无回应处理`() {
        val d = evaluate(configured = 100, lastState = OutcomeState.DISMISSED, minutesSinceLast = 60)
        assertEquals(50, d.effectivePercent)
    }

    @Test
    fun `已回应的上一条不再压制`() {
        val d = evaluate(configured = 100, lastState = OutcomeState.REPLIED, minutesSinceLast = 60)
        assertEquals(100, d.effectivePercent)
    }

    @Test
    fun `超过隔夜窗口不再压制`() {
        val d = evaluate(configured = 100, lastState = OutcomeState.PENDING, minutesSinceLast = 240)
        assertEquals(100, d.effectivePercent)
    }

    @Test
    fun `消息堆着没读时概率减半`() {
        val d = evaluate(configured = 100, unread = 4, roll = 40)
        assertEquals(50, d.effectivePercent)
        assertTrue(d.reasons.any { it.contains("没读") })
    }

    @Test
    fun `深夜时段减半但顶格配置恒发语义不被压制`() {
        // 80% 深夜 → 40%
        val d = evaluate(configured = 80, hour = 3, roll = 30)
        assertEquals(40, d.effectivePercent)
        assertTrue(d.reasons.any { it.contains("深夜") })
        // 100% = 用户明说"通过就发",深夜不该悄悄改他调好的顶格语义
        assertEquals(100, evaluate(configured = 100, hour = 3).effectivePercent)
        // 22 点不算深夜(免打扰另有其闸)
        assertEquals(80, evaluate(configured = 80, hour = 22).effectivePercent)
    }

    @Test
    fun `账上有事只回补到基准概率顶`() {
        val d = evaluate(configured = 100, lastState = OutcomeState.PENDING, minutesSinceLast = 60, openThoughts = true, roll = 70)
        // 100→50→×1.5=75,不超过基准 100
        assertEquals(75, d.effectivePercent)
        assertTrue(d.reasons.any { it.contains("账本") })
        val capped = evaluate(configured = 40, openThoughts = true)
        assertEquals(40, capped.effectivePercent)
    }

    @Test
    fun `多重压制逐条记录理由`() {
        val d = evaluate(configured = 80, lastState = OutcomeState.PENDING, minutesSinceLast = 60, unread = 5, hour = 2)
        // 80×0.5×0.5×0.5 = 10
        assertEquals(10, d.effectivePercent)
        assertEquals(3, d.reasons.size)
    }
}
