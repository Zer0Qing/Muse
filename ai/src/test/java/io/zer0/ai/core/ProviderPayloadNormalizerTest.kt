package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderPayloadNormalizerTest {

    @Test
    fun `invalid tool calls are stripped from assistant message`() {
        val bad = ToolCall(id = "1", name = "", arguments = """{"query":"x"}""")
        val valid = ToolCall(id = "2", name = "web_search", arguments = """{"query":"ok"}""")
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            toolCalls = listOf(bad, valid),
        )
        val result = ProviderPayloadNormalizer.normalizeMessages(
            listOf(assistant),
            Model(id = "m", providerId = "test"),
        )
        val cleaned = result.single().toolCalls
        assertEquals(1, cleaned?.size)
        assertEquals("web_search", cleaned?.first()?.name)
    }

    @Test
    fun `assistant toolCalls become null when all are invalid`() {
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            toolCalls = listOf(
                ToolCall(id = "1", name = "", arguments = """{"query":"x"}"""),
            ),
        )
        val result = ProviderPayloadNormalizer.normalizeMessages(
            listOf(assistant),
            Model(id = "m", providerId = "test"),
        )
        assertNull(result.single().toolCalls)
    }
}
