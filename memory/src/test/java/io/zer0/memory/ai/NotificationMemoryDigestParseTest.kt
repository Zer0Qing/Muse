package io.zer0.memory.ai

import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.Model
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.llm.MemoryLlmClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.6.2（用户反馈）: 通知 → 记忆 梳理器的解析与提示词回归测试。
 *
 * 锁死：LLM 输出的 JSON（含 think/围栏/前后噪声）都能正确解析为事实列表；
 * 提示词在中文环境下包含「值得记」与「不值得记」的判定准则。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationMemoryDigestParseTest {

    private val noopLlm = object : MemoryLlmClient {
        override suspend fun callText(
            systemPrompt: String,
            userContent: String,
            model: Model?,
            temperature: Float,
            maxTokens: Int,
            timeoutMs: Long,
        ): String = "{}"
    }

    private fun newDigest(): NotificationMemoryDigest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return NotificationMemoryDigest(
            factDbProvider = FactDbProvider(context),
            llmClient = noopLlm,
        )
    }

    @Test
    fun `plain json parses`() {
        val r = newDigest().parseResult("""{"facts": ["快递已到菜鸟驿站", "明天 10 点开会"]}""")
        assertEquals(listOf("快递已到菜鸟驿站", "明天 10 点开会"), r?.facts)
    }

    @Test
    fun `json inside markdown fence parses`() {
        val raw = "```json\n{\"facts\": [\"还款日 5 号\"]}\n```"
        assertEquals(listOf("还款日 5 号"), newDigest().parseResult(raw)?.facts)
    }

    @Test
    fun `json after think block parses`() {
        val raw = "<think>先看看有哪些通知…</think>{\"facts\": [\"体检预约周三\"]}"
        assertEquals(listOf("体检预约周三"), newDigest().parseResult(raw)?.facts)
    }

    @Test
    fun `json with leading noise parses`() {
        val raw = "好的，以下是结果：{\"facts\": [\"取件码 1234\"]} 完毕"
        assertEquals(listOf("取件码 1234"), newDigest().parseResult(raw)?.facts)
    }

    @Test
    fun `empty facts list parses`() {
        assertEquals(emptyList<String>(), newDigest().parseResult("""{"facts": []}""")?.facts)
    }

    @Test
    fun `missing facts field defaults to empty`() {
        assertEquals(emptyList<String>(), newDigest().parseResult("""{"other": 1}""")?.facts)
    }

    @Test
    fun `no json returns null`() {
        assertNull(newDigest().parseResult("完全没有 JSON"))
        assertNull(newDigest().parseResult(""))
    }

    @Test
    fun `system prompt contains keep and drop guidance`() {
        val prompt = NotificationMemoryDigest.buildSystemPrompt("zh-CN", null)
        assertTrue(prompt.contains("值得记"))
        assertTrue(prompt.contains("不值得记"))
        assertTrue(prompt.contains("facts"))
    }

    @Test
    fun `system prompt embeds existing memories when provided`() {
        val prompt = NotificationMemoryDigest.buildSystemPrompt("zh-CN", "- 用户喜欢喝茶")
        assertTrue(prompt.contains("用户喜欢喝茶"))
    }

    @Test
    fun `english prompt works too`() {
        val prompt = NotificationMemoryDigest.buildSystemPrompt("en-US", null)
        assertTrue(prompt.contains("Worth remembering"))
        assertTrue(prompt.contains("Not worth remembering"))
    }
}
