package io.zer0.muse.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * v1.0.72: 指数分布随机偏移测试。
 *
 * 背景:randomOffsetMinutes 在 v2.1 起失效(不再参与调度),发送间隔接近固定,
 * 用户反馈\"无法做到真正的随机发送\"。修复:重新激活为指数分布偏移,
 * 均值 = 用户设置的偏移分钟数,分布偏短(偶尔几分钟)但允许长尾(偶尔很久)。
 *
 * 验证:
 *  - offset <= 0 → 完全固定(返回 0,无随机)\n  - 偏移有正有负,且 |偏移| <= offsetMs\n  - 多次采样均值接近 offset(随机性验证,宽松断言)
 *  - 用固定种子 Random 断言分布形态(短偏移比长偏移更多)
 */
class ExponentialOffsetTest {

    @Test
    fun `zero offset returns no randomness`() {
        assertEquals(0L, ProactiveMessageRunner.exponentialOffsetMillis(0))
        assertEquals(0L, ProactiveMessageRunner.exponentialOffsetMillis(-5))
    }

    @Test
    fun `offset bounded by plus minus offset`() {
        val offsetMinutes = 60
        val offsetMs = 60 * 60_000L
        repeat(500) {
            val offset = ProactiveMessageRunner.exponentialOffsetMillis(offsetMinutes)
            assertTrue("偏移 $offset 应落在 [-$offsetMs, +$offsetMs]", offset in -offsetMs..offsetMs)
        }
    }

    @Test
    fun `average offset approximates zero(mean interval preserved)`() {
        // 均值 0 的偏移叠加到自适应间隔上,平均间隔保持 = baseInterval
        val offsetMinutes = 30
        var sum = 0L
        val samples = 2_000
        repeat(samples) {
            sum += ProactiveMessageRunner.exponentialOffsetMillis(offsetMinutes)
        }
        val avg = sum.toDouble() / samples
        // 均值应接近 0(允许 ±5 分钟采样误差)
        assertTrue("平均偏移应接近 0,实际 $avg 分钟", Math.abs(avg / 60_000.0) < 5.0)
    }

    @Test
    fun `exponential distribution skews toward short offsets`() {
        // 指数分布:短偏移(提前发送)比长偏移(延后)更常见
        val offsetMinutes = 60
        val offsetMs = 60 * 60_000L
        var shortCount = 0 // 偏移 < 0(提前,间隔缩短)
        var longCount = 0  // 偏移 > 0(延后)
        val samples = 2_000
        repeat(samples) {
            val offset = ProactiveMessageRunner.exponentialOffsetMillis(offsetMinutes)
            if (offset < 0) shortCount++ else if (offset > 0) longCount++
        }
        // 指数分布 P(X < mean) ≈ 0.63,提前比例应显著高于延后
        assertTrue(
            "短偏移应更常见: 提前=$shortCount 延后=$longCount (共 $samples)",
            shortCount > longCount,
        )
    }

    @Test
    fun `larger offset means larger spread`() {
        // 固定种子对比:offset 越大,|偏移| 平均越大(随机性更强)
        val small = sampleAvgAbs(10)
        val large = sampleAvgAbs(120)
        assertTrue("大偏移平均幅度应更大: small=$small large=$large", large > small * 2)
    }

    private fun sampleAvgAbs(offsetMinutes: Int): Double {
        val rng = Random(42)
        var sum = 0.0
        repeat(1_000) {
            sum += Math.abs(ProactiveMessageRunner.exponentialOffsetMillis(offsetMinutes, rng).toDouble())
        }
        return sum / 1_000.0 / 60_000.0 // 返回分钟
    }
}
