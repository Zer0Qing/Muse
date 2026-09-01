package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3.2: 工具执行预算策略测试。
 *
 * 覆盖验收:工具风暴能在预算内停止;重复调用被识别;
 * 输出超限截断;时间预算可配置启停。
 */
class ToolExecutionPolicyTest {

    @Test
    fun `normal alternating calls are always allowed`() {
        val policy = ToolExecutionPolicy()
        repeat(10) { i ->
            val decision = policy.beforeExecute("tool_$i", "{\"i\":$i}")
            assertTrue("第 $i 次调用应放行", decision.allowed)
            policy.afterExecute("tool_$i", "{\"i\":$i}", success = true)
        }
        assertEquals(10, policy.executedCalls)
    }

    @Test
    fun `total call budget stops the storm`() {
        val policy = ToolExecutionPolicy(ToolExecutionLimits(maxTotalCalls = 5))
        repeat(5) { i ->
            assertTrue(policy.beforeExecute("t", "{\"n\":$i}").allowed)
            policy.afterExecute("t", "{\"n\":$i}", success = true)
        }
        val blocked = policy.beforeExecute("t", "{\"n\":6}")
        assertFalse(blocked.allowed)
        assertEquals(ToolExecutionPolicy.StopReason.MAX_TOTAL_CALLS, blocked.reason)
    }

    @Test
    fun `repeated identical calls are fingerprinted and blocked`() {
        val policy = ToolExecutionPolicy(ToolExecutionLimits(maxConsecutiveIdenticalCalls = 3))
        val args = "{\"path\":\"/data/log.txt\"}"
        repeat(3) {
            assertTrue(policy.beforeExecute("read_file", args).allowed)
            policy.afterExecute("read_file", args, success = true)
        }
        val blocked = policy.beforeExecute("read_file", args)
        assertFalse("第 4 次连续相同调用应被拦截", blocked.allowed)
        assertEquals(ToolExecutionPolicy.StopReason.REPEATED_IDENTICAL_CALL, blocked.reason)
        // 指纹详情不回显完整参数原文
        assertFalse(blocked.detail.contains("/data/log.txt"))
    }

    @Test
    fun `different call resets consecutive repeat counter`() {
        val policy = ToolExecutionPolicy(ToolExecutionLimits(maxConsecutiveIdenticalCalls = 2))
        val args = "{\"p\":1}"
        repeat(2) {
            assertTrue(policy.beforeExecute("read_file", args).allowed)
            policy.afterExecute("read_file", args, success = true)
        }
        // 换一次不同调用
        assertTrue(policy.beforeExecute("list_dir", "{}").allowed)
        policy.afterExecute("list_dir", "{}", success = true)
        // 再次连续相同调用重新计数,未达上限放行
        assertTrue(policy.beforeExecute("read_file", args).allowed)
    }

    @Test
    fun `consecutive failures stop execution until a success resets`() {
        val policy = ToolExecutionPolicy(ToolExecutionLimits(maxConsecutiveFailures = 3))
        repeat(3) {
            assertTrue(policy.beforeExecute("t", "{\"n\":$it}").allowed)
            policy.afterExecute("t", "{\"n\":$it}", success = false)
        }
        val blocked = policy.beforeExecute("t", "{\"n\":4}")
        assertFalse(blocked.allowed)
        assertEquals(ToolExecutionPolicy.StopReason.CONSECUTIVE_FAILURES, blocked.reason)

        // 新策略实例中:失败两次后成功一次,连击清零
        val other = ToolExecutionPolicy(ToolExecutionLimits(maxConsecutiveFailures = 3))
        repeat(2) { i ->
            other.beforeExecute("t", "{\"n\":$i}")
            other.afterExecute("t", "{\"n\":$i}", success = false)
        }
        other.beforeExecute("t", "{\"n\":ok}")
        other.afterExecute("t", "{\"n\":ok}", success = true)
        assertTrue(other.beforeExecute("t", "{\"n\":next}").allowed)
    }

    @Test
    fun `time budget only active when configured`() {
        // 默认关闭
        val defaultPolicy = ToolExecutionPolicy()
        assertFalse(defaultPolicy.isTimeBudgetExhausted(nowMs = defaultPolicy.startedAtMs + Long.MAX_VALUE / 2))

        val bounded = ToolExecutionPolicy(ToolExecutionLimits(totalBudgetMs = 1_000))
        assertFalse(bounded.isTimeBudgetExhausted(nowMs = bounded.startedAtMs + 500))
        assertTrue(bounded.isTimeBudgetExhausted(nowMs = bounded.startedAtMs + 2_500))
    }

    @Test
    fun `oversized output is truncated with notice`() {
        val policy = ToolExecutionPolicy(ToolExecutionLimits(maxOutputChars = 100))
        val big = "x".repeat(250)
        val (clamped, truncated) = policy.clampOutput(big)
        assertTrue(truncated)
        assertTrue(clamped.startsWith("x".repeat(100)))
        assertTrue(clamped.contains("输出已截断"))

        // 未超限原样返回
        val (same, wasTruncated) = policy.clampOutput("small")
        assertFalse(wasTruncated)
        assertEquals("small", same)
    }

    @Test
    fun `fingerprint is stable for identical calls and differs otherwise`() {
        val policy = ToolExecutionPolicy()
        val a1 = policy.fingerprint("read_file", "{\"p\":1}")
        val a2 = policy.fingerprint("read_file", "{\"p\":1}")
        val b = policy.fingerprint("read_file", "{\"p\":2}")
        val c = policy.fingerprint("write_file", "{\"p\":1}")
        assertEquals(a1, a2)
        assertFalse(a1 == b)
        assertFalse(a1 == c)
    }

    @Test
    fun `consecutive failure threshold is exposed for loop abort`() {
        val policy = ToolExecutionPolicy(ToolExecutionLimits(maxConsecutiveFailures = 3))
        assertFalse(policy.shouldAbortOnConsecutiveFailures())
        assertEquals(0, policy.consecutiveFailuresCount)
        repeat(3) { i ->
            policy.beforeExecute("t", "{\"n\":$i}")
            policy.afterExecute("t", "{\"n\":$i}", success = false)
        }
        assertTrue(policy.shouldAbortOnConsecutiveFailures())
        assertEquals(3, policy.consecutiveFailuresCount)
    }

    @Test
    fun `round progress blocks only after repeated signature hits limit`() {
        val policy = ToolExecutionPolicy(ToolExecutionLimits(maxNoProgressRounds = 2))
        assertTrue(policy.checkRoundProgress("sig-A").allowed)
        assertTrue(policy.checkRoundProgress("sig-A").allowed)
        val blocked = policy.checkRoundProgress("sig-A")
        assertFalse(blocked.allowed)
        assertEquals(ToolExecutionPolicy.StopReason.REPEATED_IDENTICAL_CALL, blocked.reason)
        assertEquals(2, policy.noProgressRoundsCount)
    }

    @Test
    fun `round progress resets on changed signature`() {
        val policy = ToolExecutionPolicy(ToolExecutionLimits(maxNoProgressRounds = 2))
        assertTrue(policy.checkRoundProgress("sig-A").allowed)
        assertTrue(policy.checkRoundProgress("sig-B").allowed)
        assertTrue(policy.checkRoundProgress("sig-B").allowed)
        assertTrue(policy.checkRoundProgress("sig-A").allowed)
        assertTrue(policy.checkRoundProgress("sig-A").allowed)
    }

    @Test
    fun `empty round signature never triggers stuck detection`() {
        val policy = ToolExecutionPolicy(ToolExecutionLimits(maxNoProgressRounds = 1))
        repeat(5) { assertTrue(policy.checkRoundProgress("").allowed) }
    }

    @Test
    fun `max rounds is owned and can expand dynamically`() {
        val policy = ToolExecutionPolicy(initialMaxRounds = 10)
        assertEquals(10, policy.maxRounds)
        policy.updateMaxRounds(17)
        assertEquals(17, policy.maxRounds)
    }

    @Test
    fun `skipped call is not recorded as failure`() {
        // 审批拒绝/预算拦截路径:beforeExecute 放行后若未真正执行,不调用 afterExecute,计数不变。
        val policy = ToolExecutionPolicy(ToolExecutionLimits(maxConsecutiveFailures = 3))
        policy.afterExecute("t", "{\"a\":1}", success = true)
        assertEquals(1, policy.executedCalls)
        assertEquals(0, policy.consecutiveFailuresCount)
        assertTrue(policy.beforeExecute("t", "{\"a\":2}").allowed)
        // 未调用 afterExecute(模拟未执行)
        assertEquals(1, policy.executedCalls)
        assertEquals(0, policy.consecutiveFailuresCount)
    }

    @Test
    fun `turn stats accumulate emitted calls and streamed chars`() {
        val policy = ToolExecutionPolicy()
        assertEquals(0, policy.emittedToolCallCount)
        assertEquals(0, policy.streamedCharCount)
        policy.recordEmittedToolCalls(3)
        policy.recordStreamedChars(120)
        policy.recordEmittedToolCalls(2)
        policy.recordStreamedChars(40)
        assertEquals(5, policy.emittedToolCallCount)
        assertEquals(160, policy.streamedCharCount)
        // 统计与预算执行计数彼此独立
        assertEquals(0, policy.executedCalls)
    }
}
