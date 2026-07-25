package io.zer0.muse.budget

import io.zer0.memory.budget.LlmBudget
import io.zer0.muse.util.TokenEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LlmBudget token 截断边界测试(补充 memory 模块已有测试)。
 *
 * 重点覆盖任务要求的边界场景:
 *  - 空消息列表 / 空文本
 *  - 单条消息未超限 → 原样返回
 *  - 多条消息总 token 超限 → 截断并保留头部内容
 *  - 边界:token 数刚好等于 maxTokens
 *  - 边界:单条消息已超 maxTokens
 *
 * 注:[LlmBudget.truncateToTokenBudget] 接收单个 String(记忆 markdown),
 * 非 List<UIMessage>。此处"多条消息"指拼接后的多段文本,验证截断行为。
 *
 * 截断语义:实现保留文本**头部**(前 N 个 token),尾部追加 "…(memory truncated)" 标记。
 */
class LlmBudgetTest {

    // ── 空文本 / 空消息列表 ─────────────────────────────────────────────

    @Test
    fun `空文本返回空`() {
        assertEquals("", LlmBudget.truncateToTokenBudget("", 100))
    }

    @Test
    fun `空文本 budget 为 0 也返回空`() {
        assertEquals("", LlmBudget.truncateToTokenBudget("", 0))
    }

    @Test
    fun `空白文本不截断`() {
        // 空白文本 token 数为 0,不会超限
        val result = LlmBudget.truncateToTokenBudget("   ", 10)
        assertEquals("   ", result)
    }

    // ── 单条消息未超限 ──────────────────────────────────────────────────

    @Test
    fun `单条消息未超限返回原文`() {
        val text = "Hello World"
        val result = LlmBudget.truncateToTokenBudget(text, 1000)
        assertEquals(text, result)
    }

    @Test
    fun `budget 远大于文本 token 数时返回原文`() {
        val text = "这是一段简短的文本"
        val result = LlmBudget.truncateToTokenBudget(text, 10000)
        assertEquals(text, result)
    }

    // ── 多条消息总 token 超限,验证从头部截断 ────────────────────────────

    @Test
    fun `多条消息拼接后超限被截断并追加标记`() {
        // 模拟多条记忆消息拼接
        val text = buildString {
            appendLine("记忆1:用户喜欢编程")
            appendLine("记忆2:用户使用 Kotlin")
            appendLine("记忆3:用户在开发 Android 应用")
            appendLine("记忆4:用户的项目名叫 Muse")
            appendLine("记忆5:用户喜欢深色主题")
            appendLine("记忆6:用户使用 TRAE IDE")
            appendLine("记忆7:用户喜欢喝咖啡")
            appendLine("记忆8:用户的工作时间是 9-18")
        }
        // 设一个很小的 budget(2 token),强制截断
        val result = LlmBudget.truncateToTokenBudget(text, 2)
        assertTrue("截断结果应包含标记: $result", result.contains("(memory truncated)"))
        assertTrue("截断结果应比原文短", result.length < text.length)
    }

    @Test
    fun `截断后保留头部内容`() {
        // 验证截断后结果以原文开头开始(头部被保留)
        val text = "第一段内容比较长用于测试截断行为. 第二段内容同样很长用于验证尾部被移除."
        val result = LlmBudget.truncateToTokenBudget(text, 3)
        // 截断后应以原文的前几个字符开头
        assertTrue("截断后应保留头部: result='$result'", result.startsWith(text.take(3)))
        // 应包含截断标记
        assertTrue(result.contains("(memory truncated)"))
    }

    @Test
    fun `多条消息截断后头部消息内容存在`() {
        val msg1 = "第一条记忆非常重要需要保留"
        val msg2 = "第二条记忆也很重要"
        val msg3 = "第三条记忆可以丢弃"
        val text = "$msg1\n$msg2\n$msg3"
        // 用较大但不足以容纳全部的 budget
        val totalTokens = TokenEstimator.estimate(text)
        val budget = (totalTokens / 2).coerceAtLeast(1)
        val result = LlmBudget.truncateToTokenBudget(text, budget)
        // 头部内容(msg1 的前几个字)应存在
        assertTrue("截断后应保留第一条消息的头部: $result", result.contains(msg1.take(3)))
    }

    // ── 边界:刚好等于 maxTokens ────────────────────────────────────────

    @Test
    fun `token 数刚好等于 budget 时返回原文`() {
        val text = "Hello World"
        // 用 TokenEstimator 测量精确 token 数(BPE 与 LlmBudget 同源 cl100k_base)
        val exactTokens = TokenEstimator.estimate(text)
        assertTrue("测试文本 token 数应 > 0", exactTokens > 0)
        // budget 刚好等于 token 数
        val result = LlmBudget.truncateToTokenBudget(text, exactTokens)
        assertEquals("token 数 == budget 时应返回原文", text, result)
        assertFalse("不应包含截断标记", result.contains("(memory truncated)"))
    }

    @Test
    fun `token 数刚好等于 budget 加 1 时被截断`() {
        val text = "Hello World This Is A Test"
        val exactTokens = TokenEstimator.estimate(text)
        // budget 比 token 数少 1,应触发截断
        val result = LlmBudget.truncateToTokenBudget(text, exactTokens - 1)
        assertTrue("budget = tokens - 1 时应截断: $result", result.contains("(memory truncated)"))
    }

    // ── 边界:单条消息已超 maxTokens ────────────────────────────────────

    @Test
    fun `单条消息超 maxTokens 时被截断`() {
        val text = "This is a very long single message that definitely exceeds the token budget " +
            "and should be truncated with the memory truncated marker appended at the end"
        val result = LlmBudget.truncateToTokenBudget(text, 3)
        assertTrue("单条超限消息应被截断: $result", result.contains("(memory truncated)"))
        assertTrue("截断后应比原文短", result.length < text.length)
    }

    @Test
    fun `单条超长消息截断后保留头部 token`() {
        val text = "abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuvwxyz"
        val result = LlmBudget.truncateToTokenBudget(text, 2)
        // 头部前几个字符应保留
        assertTrue("应保留头部: $result", result.isNotEmpty())
        assertTrue("应包含截断标记", result.contains("(memory truncated)"))
    }

    // ── budget 为 0 或负数(不限制) ────────────────────────────────────

    @Test
    fun `budget 为 0 返回原文(不限制)`() {
        val text = "Hello World"
        assertEquals(text, LlmBudget.truncateToTokenBudget(text, 0))
    }

    @Test
    fun `budget 为负数返回原文(不限制)`() {
        val text = "Hello World"
        assertEquals(text, LlmBudget.truncateToTokenBudget(text, -1))
    }

    // ── 中文文本截断 ───────────────────────────────────────────────────

    @Test
    fun `中文超长文本截断正确`() {
        val text = "你好世界这是一段很长的中文文本用于测试截断功能是否正常工作" +
            "追加更多中文内容确保超过预算限制触发截断逻辑"
        val result = LlmBudget.truncateToTokenBudget(text, 3)
        assertTrue("中文截断应包含标记: $result", result.contains("(memory truncated)"))
    }

    // ── withMemoryReasoningBuffer 边界 ─────────────────────────────────

    @Test
    fun `withMemoryReasoningBuffer null 模型返回原值`() {
        assertEquals(4096, LlmBudget.withMemoryReasoningBuffer(4096, null))
    }

    @Test
    fun `withMemoryReasoningBuffer 非 reasoning 模型返回原值`() {
        val model = io.zer0.ai.core.Model(
            id = "gpt-4o",
            name = "GPT-4o",
            providerId = "openai",
            contextWindow = 128000,
        )
        assertEquals(4096, LlmBudget.withMemoryReasoningBuffer(4096, model))
    }

    @Test
    fun `withMemoryReasoningBuffer reasoning 模型增加缓冲且不超过上限`() {
        val model = io.zer0.ai.core.Model(
            id = "o1-preview",
            name = "O1 Preview",
            providerId = "openai",
            contextWindow = 128000,
            maxOutputTokens = 32768,
        )
        val result = LlmBudget.withMemoryReasoningBuffer(4096, model)
        assertTrue("reasoning 模型应增加缓冲: $result", result > 4096)
        assertTrue("不应超过模型上限: $result", result <= 32768)
        // 验证缓冲值 = 4096 + DEFAULT_REASONING_BUFFER_TOKENS(1024)
        assertEquals(4096 + LlmBudget.DEFAULT_REASONING_BUFFER_TOKENS, result)
    }

    @Test
    fun `withMemoryReasoningBuffer 缓冲超过模型上限时 clamp`() {
        // visibleMaxTokens + buffer > maxOutputTokens 时应 clamp 到 maxOutputTokens
        val model = io.zer0.ai.core.Model(
            id = "o3-mini",
            name = "O3 Mini",
            providerId = "openai",
            contextWindow = 128000,
            maxOutputTokens = 5000, // 小于 visibleMaxTokens + 1024
        )
        val result = LlmBudget.withMemoryReasoningBuffer(4096, model)
        assertEquals("应 clamp 到 maxOutputTokens", 5000, result)
    }

    @Test
    fun `withMemoryReasoningBuffer reasoning 模型无 maxOutputTokens 时不 clamp`() {
        val model = io.zer0.ai.core.Model(
            id = "deepseek-r1",
            name = "DeepSeek R1",
            providerId = "deepseek",
            contextWindow = 64000,
            maxOutputTokens = null,
        )
        val result = LlmBudget.withMemoryReasoningBuffer(4096, model)
        assertEquals(4096 + LlmBudget.DEFAULT_REASONING_BUFFER_TOKENS, result)
    }

    @Test
    fun `withMemoryReasoningBuffer 识别各种 reasoning 模型 id`() {
        val reasoningIds = listOf("o1-preview", "o3-mini", "o4-mini", "model-thinking", "reasoning-v1", "deepseek-r1")
        for (id in reasoningIds) {
            val model = io.zer0.ai.core.Model(
                id = id,
                name = id,
                providerId = "test",
                contextWindow = 128000,
                maxOutputTokens = 32768,
            )
            val result = LlmBudget.withMemoryReasoningBuffer(4096, model)
            assertTrue("$id 应被识别为 reasoning 模型(buffer > 4096): $result", result > 4096)
        }
    }
}
