package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.util.TokenEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ContextTokenUsage] 的口径测试。
 *
 * 重点钉住三件事：
 *  1. 会话里没有任何带 usage 的消息时，结果必须与纯本地估算**逐字节相同**（退化路径，不允许新增差异）；
 *  2. 有 usage 时 = 水位线实测(prompt+completion) + 水位线之后的估算，且 system prompt 不重复计；
 *  3. 实测明显低于估算（可疑值）时，以估算为下限。
 */
class ContextTokenUsageTest {

    private val systemPrompt = "You are a helpful assistant. 请用中文回答，保持简洁。"

    private fun user(content: String, promptTokens: Int? = null, completionTokens: Int? = null, cachedTokens: Int? = null): UIMessage =
        UIMessage(
            role = MessageRole.USER,
            content = content,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            cachedTokens = cachedTokens,
        )

    private fun assistant(content: String, promptTokens: Int? = null, completionTokens: Int? = null, cachedTokens: Int? = null): UIMessage =
        UIMessage(
            role = MessageRole.ASSISTANT,
            content = content,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            cachedTokens = cachedTokens,
        )

    // ── 1. 退化路径：无 usage 必须与纯估算完全一致 ──────────────────────

    @Test
    fun `无任何 usage 时与纯本地估算完全一致`() {
        val messages = listOf(
            user("帮我看看这个项目结构"),
            assistant("项目分为 app、ai、memory 三个模块。"),
            user("再详细一点，把依赖关系也说说"),
            assistant("app 依赖 ai 与 memory，ai 与 memory 互不依赖。"),
        )
        val pure = TokenEstimator.estimate(messages, systemPrompt)
        assertEquals(
            "没有 usage 时占用量必须等于纯估算，不能引入任何偏差",
            pure,
            ContextTokenUsage.estimate(messages, systemPrompt),
        )
    }

    @Test
    fun `usage 字段全为 null 的老数据同样退回纯估算`() {
        val messages = listOf(
            user("你好"),
            assistant("你好，有什么可以帮你？"),
        )
        assertEquals(-1, ContextTokenUsage.lastMeasuredIndex(messages))
        assertEquals(
            TokenEstimator.estimate(messages, systemPrompt),
            ContextTokenUsage.estimate(messages, systemPrompt),
        )
    }

    @Test
    fun `promptTokens 为 0 视为没有 usage`() {
        val messages = listOf(
            user("你好"),
            assistant("你好", promptTokens = 0, completionTokens = 128),
        )
        assertEquals(-1, ContextTokenUsage.lastMeasuredIndex(messages))
        assertEquals(
            TokenEstimator.estimate(messages, systemPrompt),
            ContextTokenUsage.estimate(messages, systemPrompt),
        )
    }

    @Test
    fun `空列表返回 0 不抛异常`() {
        assertEquals(0, ContextTokenUsage.estimate(emptyList(), ""))
        assertEquals(0, TokenEstimator.estimate(emptyList(), ""))
    }

    // ── 2. 水位线的选取 ────────────────────────────────────────────────

    @Test
    fun `水位线取最后一条带 usage 的 assistant 消息`() {
        val messages = listOf(
            user("第一个问题"),
            assistant("第一条回答", promptTokens = 1_000, completionTokens = 100),
            user("第二个问题"),
            assistant("第二条回答", promptTokens = 2_000, completionTokens = 200),
            user("第三个问题"),
            assistant("还没有 usage"),
        )
        assertEquals(3, ContextTokenUsage.lastMeasuredIndex(messages))
    }

    @Test
    fun `非 assistant 消息上的 usage 不参与水位线`() {
        val messages = listOf(
            user("带数字的输入", promptTokens = 9_000, completionTokens = 9_000),
            assistant("无 usage 的回答"),
        )
        assertEquals(-1, ContextTokenUsage.lastMeasuredIndex(messages))
        assertEquals(
            TokenEstimator.estimate(messages, systemPrompt),
            ContextTokenUsage.estimate(messages, systemPrompt),
        )
    }

    // ── 3. 合并算法 ────────────────────────────────────────────────────

    @Test
    fun `实测只取 prompt 加 completion 不叠加 cached`() {
        // OpenAI 的 cached 是 prompt 的子集；UsageTokens.total 也是同一口径。
        val message = assistant("回答", promptTokens = 5_000, completionTokens = 800, cachedTokens = 4_000)
        assertEquals(5_800, ContextTokenUsage.measuredTokens(message))
    }

    @Test
    fun `合并值等于水位线实测加水位线之后的估算`() {
        val tail = user("再补一句很长的追问：" + "细节".repeat(40))
        val messages = listOf(
            user("第一个问题"),
            assistant("第一条回答", promptTokens = 40_000, completionTokens = 1_000),
            tail,
        )
        val expected = 41_000 + TokenEstimator.estimate(listOf(tail))
        assertEquals(expected, ContextTokenUsage.estimate(messages, systemPrompt))
    }

    @Test
    fun `水位线之后没有消息时只算实测`() {
        val messages = listOf(
            user("问题"),
            assistant("回答", promptTokens = 50_000, completionTokens = 2_000),
        )
        assertEquals(52_000, ContextTokenUsage.estimate(messages, systemPrompt))
    }

    @Test
    fun `system prompt 不重复计入尾段估算`() {
        // 长 system prompt 已经包含在实测 promptTokens 里；若尾段再带一遍，结果会远超实测值。
        val longSystemPrompt = "规则如下。" + "请严格遵守输出格式要求，不要附加多余解释。".repeat(60)
        val messages = listOf(
            user("问题"),
            assistant("回答", promptTokens = 60_000, completionTokens = 1_500),
        )
        val actual = ContextTokenUsage.estimate(messages, longSystemPrompt)
        assertEquals(61_500, actual)
        assertTrue(
            "尾段不得重复计 system prompt：actual=$actual",
            actual <= 61_500,
        )
    }

    // ── 4. 可疑实测：以估算为下限 ──────────────────────────────────────

    @Test
    fun `实测明显低于估算时退回估算`() {
        val messages = listOf(
            user("很长的问题：" + "请解释这段代码的作用。".repeat(60)),
            assistant("很长的回答：" + "这里先做解析，再做转换。".repeat(60), promptTokens = 1, completionTokens = 1),
        )
        val pure = TokenEstimator.estimate(messages, systemPrompt)
        assertEquals(
            "可疑的小 usage 不能让占用率被压到估算的一半以下",
            pure,
            ContextTokenUsage.estimate(messages, systemPrompt),
        )
    }

    @Test
    fun `blend 在实测为零时退回估算`() {
        assertEquals(1_000, ContextTokenUsage.blend(measured = 0, tailEstimate = 500, fullEstimate = 1_000))
        assertEquals(1_000, ContextTokenUsage.blend(measured = -1, tailEstimate = 0, fullEstimate = 1_000))
    }

    @Test
    fun `blend 的可信下限恰好是估算的一半`() {
        // 合并值 × 2 == 估算 → 不算低于下限，采信实测。
        assertEquals(500, ContextTokenUsage.blend(measured = 500, tailEstimate = 0, fullEstimate = 1_000))
        // 再小一点就低于下限，改用估算兜底。
        assertEquals(1_000, ContextTokenUsage.blend(measured = 499, tailEstimate = 0, fullEstimate = 1_000))
    }

    @Test
    fun `实测高于估算时直接采信实测`() {
        // 真实 tokenizer 与 cl100k 存在偏差，中文/多模态场景实测更大时应以实测为准。
        assertEquals(9_000, ContextTokenUsage.blend(measured = 8_000, tailEstimate = 1_000, fullEstimate = 2_000))
    }

    @Test
    fun `估算为 0 时不影响实测值`() {
        // jtokkit 初始化失败 + 空文本时估算为 0，不能把实测值也归零。
        assertEquals(3_000, ContextTokenUsage.blend(measured = 3_000, tailEstimate = 0, fullEstimate = 0))
    }
}
