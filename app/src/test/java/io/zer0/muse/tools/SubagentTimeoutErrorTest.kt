package io.zer0.muse.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子 agent 超时文案（协作语义 P4 续）。
 *
 * 这句话有两个读者：**主 Agent**（靠它判断"有成果但超时"还是"无进展卡死"，
 * 从而决定续接还是重来）与**用户**（在委派卡/任务卡上看到）。
 * 所以钉三件事：时长要能读、进展信息不能丢、负数与零不能写出怪话。
 */
class SubagentTimeoutErrorTest {

    private fun text(timeoutMs: Long, rounds: Int = 3, toolCalls: Int = 7, hasPartialOutput: Boolean = true) = subagentTimeoutError(
        timeoutMs = timeoutMs,
        rounds = rounds,
        toolCalls = toolCalls,
        hasPartialOutput = hasPartialOutput,
    )

    @Test
    fun `默认 240 秒不该写成 240000ms`() {
        val msg = text(240_000L)

        assertFalse("不该出现裸毫秒: $msg", msg.contains("ms"))
        assertFalse("不该出现 240000: $msg", msg.contains("240000"))
        assertTrue("该说成人能读的时长: $msg", msg.contains("4 分钟"))
    }

    @Test
    fun `一分钟以内直接说秒`() {
        assertTrue(text(45_000L).contains("45 秒"))
        assertTrue(text(30_000L).contains("30 秒"))
    }

    @Test
    fun `不满一分钟的零头不能丢掉`() {
        // 90 秒只写「1 分钟」会把等待上限说小，主 Agent 可能因此判定"该续接"而实际时间不够
        val msg = text(90_000L)

        assertTrue("该带上零头: $msg", msg.contains("1 分 30 秒"))
    }

    @Test
    fun `轮数与工具调用数不能丢`() {
        val msg = text(240_000L, rounds = 5, toolCalls = 12)

        assertTrue("要看得到跑了多少轮: $msg", msg.contains("5 轮"))
        assertTrue("要看得到调了多少次工具: $msg", msg.contains("12 次工具调用"))
    }

    @Test
    fun `有没有产出部分总结要说清 决定能否续接`() {
        assertTrue(text(240_000L, hasPartialOutput = true).contains("已产出部分总结"))
        val empty = text(240_000L, hasPartialOutput = false)

        assertTrue("无产出要明说: $empty", empty.contains("无任何产出"))
        assertFalse(empty.contains("部分总结"))
    }

    @Test
    fun `零与负数不该写出怪话`() {
        // 超时值来自外部参数，配坏时句子仍要读得通，且不能出现"负 1 秒"
        val zero = text(0L)
        val negative = text(-5_000L)

        assertTrue(zero.contains("0 秒"))
        assertFalse("不该出现负数时长: $negative", negative.contains("-"))
        assertTrue(negative.contains("0 秒"))
    }
}
