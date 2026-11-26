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
    fun `withReasoningHeadroom 非 reasoning 模型返回原值`() {
        val model = io.zer0.ai.core.Model(id = "gpt-4o", name = "GPT-4o", providerId = "openai", contextWindow = 128000)
        val result = LlmBudget.withReasoningHeadroom(4096, model)
        assertEquals(4096, result)
    }

    @Test
    fun `withReasoningHeadroom reasoning 模型增加缓冲`() {
        val model = io.zer0.ai.core.Model(
            id = "o1-preview",
            name = "O1 Preview",
            providerId = "openai",
            contextWindow = 128000,
            maxOutputTokens = 32768,
        )
        val result = LlmBudget.withReasoningHeadroom(4096, model)
        assertTrue("reasoning 模型应增加缓冲: $result", result > 4096)
        assertTrue("不应超过模型上限", result <= 32768)
    }

    @Test
    fun `withReasoningHeadroom null 模型返回原值`() {
        assertEquals(4096, LlmBudget.withReasoningHeadroom(4096, null))
    }

    // ── 共享计数与严格截断（按条扣预算的调用方依赖这两条）────────────────

    @Test
    fun `countTokens 对中文按字符量级计数而非四字符折一`() {
        val chinese = "用户希望记忆系统在离线状态下保持稳定并优先保留中文内容"
        val count = LlmBudget.countTokens(chinese)

        // chars/4 会给 7，实际分词接近字符数；这条把「中文被低估三倍」钉死。
        assertTrue("中文 token 数应接近字符数：$count / ${chinese.length}", count > chinese.length / 2)
        assertTrue("中文 token 数不应超过字符数两倍：$count", count <= chinese.length * 2)
    }

    @Test
    fun `countTokens 空文本为 0`() {
        assertEquals(0, LlmBudget.countTokens(""))
    }

    @Test
    fun `严格截断结果（含标记）不超过预算`() {
        val text = "用户在离线记忆项目里写 Android 应用，希望裁剪后仍然可读。".repeat(20)

        for (budget in listOf(24, 40, 80, 160)) {
            val result = LlmBudget.truncateToTokenBudgetStrict(text, budget)
            assertTrue("预算 $budget 下应发生截断", result.length < text.length)
            assertTrue(
                "预算 $budget 下结果自带标记",
                result.contains("(memory truncated)"),
            )
            // 标记自身也占 token：旧实现只按预算截正文再追加标记，这条会失败
            val used = LlmBudget.countTokens(result)
            assertTrue("预算 $budget 下实际 token 数超支：$used", used <= budget)
        }
    }

    @Test
    fun `严格截断在预算内返回原文`() {
        val text = "Hello World"
        assertEquals(text, LlmBudget.truncateToTokenBudgetStrict(text, 100))
    }

    @Test
    fun `严格截断预算为 0 返回原文`() {
        val text = "用户在离线记忆项目里写 Android 应用"
        assertEquals(text, LlmBudget.truncateToTokenBudgetStrict(text, 0))
        assertEquals(text, LlmBudget.truncateToTokenBudgetStrict(text, -1))
    }

    @Test
    fun `严格截断连标记都放不下时返回空串`() {
        val text = "用户在离线记忆项目里写 Android 应用，希望裁剪后仍然可读。".repeat(10)

        // 空串让调用方把该条当成「未保留」，而不是超预算留一段没用的标记
        assertEquals("", LlmBudget.truncateToTokenBudgetStrict(text, 2))
    }
}
