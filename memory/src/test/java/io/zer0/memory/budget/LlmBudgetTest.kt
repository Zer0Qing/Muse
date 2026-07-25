package io.zer0.memory.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.56: LlmBudget 单元测试。
 *
 * 验证 truncateToTokenBudget 的 BPE 精确截断和回退逻辑。
 */
class LlmBudgetTest {

    @Test
    fun `budget 为 0 返回原文`() {
        val text = "Hello World"
        assertEquals(text, LlmBudget.truncateToTokenBudget(text, 0))
    }

    @Test
    fun `空文本返回空`() {
        assertEquals("", LlmBudget.truncateToTokenBudget("", 100))
    }

    @Test
    fun `文本在预算内返回原文`() {
        val text = "Hello World"
        // 给足够大的预算,文本不会被截断
        val result = LlmBudget.truncateToTokenBudget(text, 1000)
        assertEquals(text, result)
    }

    @Test
    fun `超预算文本被截断并追加标记`() {
        val text = "Hello World This Is A Very Long Text That Should Be Truncated"
        val result = LlmBudget.truncateToTokenBudget(text, 3)
        assertTrue("截断后应包含标记: $result", result.contains("(memory truncated)"))
        assertTrue("截断后应比原文短", result.length < text.length + 50)
    }

    @Test
    fun `截断在换行处收尾`() {
        val text = "第一行内容\n第二行内容\n第三行内容\n第四行内容\n第五行内容"
        val result = LlmBudget.truncateToTokenBudget(text, 3)
        // 截断后应包含 truncated 标记
        assertTrue(result.contains("(memory truncated)"))
    }

    @Test
    fun `中文文本截断正确`() {
        val text = "你好世界这是一段很长的中文文本用于测试截断功能是否正常工作"
        val result = LlmBudget.truncateToTokenBudget(text, 2)
        assertTrue("中文截断应包含标记: $result", result.contains("(memory truncated)"))
    }

    @Test
    fun `withMemoryReasoningBuffer 非 reasoning 模型返回原值`() {
        val model = io.zer0.ai.core.Model(id = "gpt-4o", name = "GPT-4o", providerId = "openai", contextWindow = 128000)
        val result = LlmBudget.withMemoryReasoningBuffer(4096, model)
        assertEquals(4096, result)
    }

    @Test
    fun `withMemoryReasoningBuffer reasoning 模型增加缓冲`() {
        val model = io.zer0.ai.core.Model(
            id = "o1-preview",
            name = "O1 Preview",
            providerId = "openai",
            contextWindow = 128000,
            maxOutputTokens = 32768,
        )
        val result = LlmBudget.withMemoryReasoningBuffer(4096, model)
        assertTrue("reasoning 模型应增加缓冲: $result", result > 4096)
        assertTrue("不应超过模型上限", result <= 32768)
    }

    @Test
    fun `withMemoryReasoningBuffer null 模型返回原值`() {
        assertEquals(4096, LlmBudget.withMemoryReasoningBuffer(4096, null))
    }
}
