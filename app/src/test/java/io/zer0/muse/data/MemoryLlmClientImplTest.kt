package io.zer0.muse.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryLlmClientImplTest {

    @Test
    fun `visible text always wins over reasoning`() {
        val raw = resolveMemoryLlmRawText(
            systemPrompt = "输出合并后的列表",
            text = "用户的名字是子奇",
            reasoningContent = "我们需要 answer in Chinese",
        )
        assertEquals("用户的名字是子奇", raw)
    }

    @Test
    fun `reasoning only rejected when prompt does not require JSON`() {
        val raw = resolveMemoryLlmRawText(
            systemPrompt = "输出合并后的列表,不要使用 Markdown 标题",
            text = "",
            reasoningContent = "我们需要 answer in Chinese. Need merge facts.",
        )
        assertNull(raw)
    }

    @Test
    fun `reasoning only rejected when JSON prompt has no JSON payload`() {
        val raw = resolveMemoryLlmRawText(
            systemPrompt = "只输出 JSON 数组",
            text = "",
            reasoningContent = "我们需要 answer in Chinese. Need merge facts.",
        )
        assertNull(raw)
    }

    @Test
    fun `reasoning accepted when JSON prompt contains JSON payload`() {
        val reasoning = """[
          {"fact": "用户的名字是子奇", "tags": ["子奇"], "importance": 2}
        ]"""
        val raw = resolveMemoryLlmRawText(
            systemPrompt = "只输出 JSON 数组",
            text = "",
            reasoningContent = reasoning,
        )
        assertEquals(reasoning.trim(), raw)
    }

    @Test
    fun `both text and reasoning blank rejected`() {
        val raw = resolveMemoryLlmRawText(
            systemPrompt = "只输出 JSON 数组",
            text = "",
            reasoningContent = null,
        )
        assertNull(raw)
    }

    @Test
    fun `english structured prompt also recognizes JSON reasoning`() {
        val reasoning = """[{"fact": "name is ZiQi"}]"""
        val raw = resolveMemoryLlmRawText(
            systemPrompt = "Output Format\nOutput a strict JSON array only",
            text = "",
            reasoningContent = reasoning,
        )
        assertEquals(reasoning, raw)
    }
}
