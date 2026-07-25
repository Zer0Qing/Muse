package io.zer0.muse.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.56: TokenEstimator 单元测试(BPE tokenizer)。
 *
 * 验证 jtokkit 集成正确,精确计数与启发式回退均工作。
 */
class TokenEstimatorTest {

    @Test
    fun `空文本返回 0`() {
        assertEquals(0, TokenEstimator.estimate(""))
        assertEquals(0, TokenEstimator.estimate("   "))
    }

    @Test
    fun `英文文本 token 数大于 0`() {
        val tokens = TokenEstimator.estimate("Hello World")
        assertTrue("英文 token 数应 > 0,实际 $tokens", tokens > 0)
    }

    @Test
    fun `中文文本 token 数大于 0`() {
        val tokens = TokenEstimator.estimate("你好世界")
        assertTrue("中文 token 数应 > 0,实际 $tokens", tokens > 0)
    }

    @Test
    fun `长文本 token 数大于短文本`() {
        val short = TokenEstimator.estimate("Hello")
        val long = TokenEstimator.estimate("Hello World This Is A Longer Text")
        assertTrue("长文本 token 应更多: short=$short, long=$long", long > short)
    }

    @Test
    fun `英文约 4 字符 per token`() {
        // "hello" = 1 token (cl100k_base),粗略验证数量级
        val tokens = TokenEstimator.estimate("hello hello hello hello")
        assertTrue("5 个 hello 约 5+ token,实际 $tokens", tokens >= 4)
    }

    @Test
    fun `中文约 1-2 字 per token`() {
        // 中文字符在 BPE 中通常 1-2 字/token
        val tokens = TokenEstimator.estimate("你好你好你好你好") // 8 字
        assertTrue("8 个中文字约 4-8 token,实际 $tokens", tokens in 3..12)
    }

    @Test
    fun `混合文本正确计数`() {
        val tokens = TokenEstimator.estimate("Hello 你好 World 世界")
        assertTrue("混合文本 token 应 > 2,实际 $tokens", tokens > 2)
    }

    @Test
    fun `estimate messages 列表`() {
        val messages = listOf(
            io.zer0.ai.core.UIMessage(
                role = io.zer0.ai.core.MessageRole.USER,
                content = "Hello World",
            ),
            io.zer0.ai.core.UIMessage(
                role = io.zer0.ai.core.MessageRole.ASSISTANT,
                content = "Hi there! How can I help you today?",
            ),
        )
        val total = TokenEstimator.estimate(messages, systemPrompt = "You are a helpful assistant.")
        assertTrue("消息列表总 token 应 > 5,实际 $total", total > 5)
    }

    @Test
    fun `system prompt 被计入`() {
        val withoutSys = TokenEstimator.estimate(
            listOf(io.zer0.ai.core.UIMessage(role = io.zer0.ai.core.MessageRole.USER, content = "test")),
            systemPrompt = "",
        )
        val withSys = TokenEstimator.estimate(
            listOf(io.zer0.ai.core.UIMessage(role = io.zer0.ai.core.MessageRole.USER, content = "test")),
            systemPrompt = "You are a very helpful assistant that helps users with their questions.",
        )
        assertTrue("有 system prompt 时 token 应更多", withSys > withoutSys)
    }

    // ── 补充用例:空消息列表 / 每条消息开销 / 中英文混合 ─────────────────

    @Test
    fun `空消息列表且无 system prompt 返回 0`() {
        val total = TokenEstimator.estimate(emptyList(), systemPrompt = "")
        assertEquals("空消息列表 + 空 system prompt 应为 0,实际 $total", 0, total)
    }

    @Test
    fun `空消息列表仅含 system prompt 时等于 system prompt 的 token 数`() {
        val sysPrompt = "You are a helpful assistant."
        val total = TokenEstimator.estimate(emptyList(), systemPrompt = sysPrompt)
        val sysOnly = TokenEstimator.estimate(sysPrompt)
        assertEquals("空消息列表 + systemPrompt 应等于 systemPrompt 自身 token 数", sysOnly, total)
    }

    @Test
    fun `每条消息开销被正确计入总 token`() {
        // 单条消息 vs 两条相同消息:总 token 应当翻倍(去掉 system prompt 干扰)
        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.USER,
            content = "Hello World",
        )
        val single = TokenEstimator.estimate(listOf(msg), systemPrompt = "")
        val doubled = TokenEstimator.estimate(listOf(msg, msg), systemPrompt = "")
        // 两条相同消息的总 token 应是单条的两倍(无 system prompt 干扰)
        assertEquals("两条相同消息的 token 应是单条的两倍: single=$single, doubled=$doubled", single * 2, doubled)
    }

    @Test
    fun `消息列表总 token 等于 system 加各消息 content 加每条消息固定开销`() {
        // 验证 estimate(messages, systemPrompt) = estimate(systemPrompt) + Σ estimate(msg.content) + 每条消息固定开销
        // M6: 每条消息约 4 tokens 开销(OpenAI 协议每条消息的固定开销:role 标记 + 分隔符)
        val perMessageOverhead = 4
        val sysPrompt = "system"
        val m1 = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.USER,
            content = "first message",
        )
        val m2 = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.ASSISTANT,
            content = "second message",
        )
        val messages = listOf(m1, m2)
        val expected = TokenEstimator.estimate(sysPrompt) +
            TokenEstimator.estimate(m1.content) +
            TokenEstimator.estimate(m2.content) +
            messages.size * perMessageOverhead
        val actual = TokenEstimator.estimate(messages, systemPrompt = sysPrompt)
        assertEquals("消息列表总 token 应等于各部分之和(含每条消息固定开销)", expected, actual)
    }

    @Test
    fun `每条消息固定开销随消息数线性增长`() {
        // M6: 每条消息约 4 tokens 开销。N 条相同消息比 1 条多 (N-1)*4 + (N-1)*contentTokens
        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.USER,
            content = "Hello World",
        )
        val one = TokenEstimator.estimate(listOf(msg), systemPrompt = "")
        val three = TokenEstimator.estimate(listOf(msg, msg, msg), systemPrompt = "")
        // 三条比一条多 2 条消息的开销:2 * (4 + contentTokens)
        val delta = three - one
        assertTrue("三条消息比一条多 $delta,应大于 2*4=8", delta >= 2 * 4)
        // 同时验证线性:three 应等于 one + 2 * (单条贡献)
        // 单条贡献 = 4(perMessageOverhead) + estimate(msg.content)
        val singleContribution = 4 + TokenEstimator.estimate(msg.content)
        assertEquals("三条消息应等于一条 + 2*单条贡献", one + 2 * singleContribution, three)
    }

    @Test
    fun `中英文混合文本的估算大于 0 且介于纯中纯英之间`() {
        val pureEn = TokenEstimator.estimate("Hello World")
        val pureCn = TokenEstimator.estimate("你好世界")
        val mixed = TokenEstimator.estimate("Hello 你好 World 世界")
        assertTrue("混合文本 token 应 > 0,实际 $mixed", mixed > 0)
        // 混合文本字符数不少于任一单语版本,token 数也应不少于最少者
        val minPure = minOf(pureEn, pureCn)
        assertTrue("混合文本 token($mixed)应不少于纯中/纯英最小值($minPure)", mixed >= minPure)
    }
}
