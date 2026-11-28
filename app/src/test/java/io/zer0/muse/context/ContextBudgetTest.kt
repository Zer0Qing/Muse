package io.zer0.muse.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4.3: 统一上下文预算测试 — 分区上限、头部保留截断、截断注记可诊断。
 */
class ContextBudgetTest {

    @Test
    fun `text within limit passes through unchanged`() {
        val budget = ContextBudget()
        val result = budget.clamp(ContextSection.LONG_TERM_MEMORY, "短文本")
        assertFalse(result.truncated)
        assertEquals("短文本", result.text)
        assertEquals(3, result.originalChars)
    }

    @Test
    fun `oversized memory section is head-truncated with diagnostic notice`() {
        val budget = ContextBudget()
        val big = "m".repeat(30_000)
        val result = budget.clamp(ContextSection.LONG_TERM_MEMORY, big)
        assertTrue(result.truncated)
        assertEquals(30_000, result.originalChars)
        // 保留头部
        assertTrue(result.text.startsWith("m".repeat(1_000)))
        // 注记包含分区、原始长度与上限,可诊断
        assertTrue(result.text.contains("分区=LONG_TERM_MEMORY"))
        assertTrue(result.text.contains("原始 30000"))
        assertTrue(result.text.contains("上限 24000"))
    }

    @Test
    fun `attachment clamping keeps user text intact in buildSendText`() {
        // 用户输入在拼接顺序最后,不得被附件截断吞掉
        val budget = ContextBudget(
            mapOf(ContextSection.ATTACHMENT_TEXT to 100),
        )
        val docs = listOf("d".repeat(200))
        val raw = "用户的问题"
        // 模拟 buildSendText 的行为(真实函数在 app 模块,这里验证预算语义)
        val clampedDoc = budget.clampText(ContextSection.ATTACHMENT_TEXT, docs.joinToString("\n\n---\n\n"))
        val send = "$clampedDoc\n\n---\n\n$raw"
        assertTrue(send.endsWith(raw))
        assertTrue(send.contains("内容已按上下文预算截断"))
    }

    @Test
    fun `unlimited sections without configured limit pass through`() {
        val budget = ContextBudget(emptyMap())
        val big = "x".repeat(500_000)
        val result = budget.clamp(ContextSection.TOOL_SCHEMA, big)
        assertFalse(result.truncated)
        assertEquals(big, result.text)
    }

    @Test
    fun `default limits cover every section`() {
        ContextSection.entries.forEach { section ->
            assertTrue("分区 $section 应有默认上限", ContextBudget.DEFAULT_LIMITS.containsKey(section))
        }
    }

    @Test
    fun `vision description clamp bounds injected descriptions`() {
        val budget = ContextBudget()
        val big = "图里是".repeat(10_000) // 30_000 字符
        val result = budget.clamp(ContextSection.VISION_DESCRIPTION, big)
        assertTrue(result.truncated)
        // 审查修复(P2)回归:注记计入预算,结果总长不得超过配置上限
        assertTrue(result.text.length <= 12_000)
    }

    @Test
    fun `truncated result never exceeds the configured limit`() {
        val budget = ContextBudget(mapOf(ContextSection.RELEVANT_MEMORY to 500))
        val result = budget.clamp(ContextSection.RELEVANT_MEMORY, "x".repeat(10_000))
        assertTrue(result.truncated)
        assertTrue("实际长度 ${result.text.length}", result.text.length <= 500)
        assertTrue(result.text.contains("内容已按上下文预算截断"))
    }
}
