package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPayloadNormalizerTest {

    @Test
    fun `reasoning payload is stripped from non assistant messages`() {
        val user = UIMessage(
            role = MessageRole.USER,
            content = "hi",
            reasoning = "leaked thinking",
            thinkingSignature = "sig-x",
            thinkingEncryptedContent = "enc",
        )
        val assistant = UIMessage(role = MessageRole.ASSISTANT, content = "ok", reasoning = "keep me")
        val result = ProviderPayloadNormalizer.normalizeMessages(
            listOf(user, assistant),
            Model(id = "m", providerId = "test"),
        )
        val cleanedUser = result.first { it.role == MessageRole.USER }
        assertNull("user 消息不应带 reasoning", cleanedUser.reasoning)
        assertNull("user 消息不应带 signature", cleanedUser.thinkingSignature)
        assertNull("user 消息不应带 encrypted_content", cleanedUser.thinkingEncryptedContent)
        // assistant 的推理保留（回放依赖它）
        assertEquals("keep me", result.first { it.role == MessageRole.ASSISTANT }.reasoning)
    }

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

    @Test
    fun `normalizer projects old successful tool trace without mutating input`() {
        val call = ToolCall(id = "old", name = "lookup", arguments = "{}")
        val input = listOf(
            UIMessage(role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(call)),
            UIMessage(role = MessageRole.TOOL, content = "value", toolCallId = call.id),
            UIMessage(role = MessageRole.USER, content = "next"),
            UIMessage(role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(ToolCall("new", "lookup", "{}"))),
            UIMessage(role = MessageRole.TOOL, content = "latest", toolCallId = "new"),
        )
        val original = input.toList()

        val result = ProviderPayloadNormalizer.normalizeMessages(input, Model(id = "m", providerId = "test"))

        assertEquals(4, result.size)
        assertTrue(result.first().role == MessageRole.SYSTEM)
        assertTrue(result.first().content.contains(ToolTraceProjection.SUMMARY_MARKER))
        assertEquals(original, input)
        assertEquals("new", result[3].toolCallId)
    }
}
