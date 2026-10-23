package io.zer0.muse.transformer

import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.UIMessage
import io.zer0.muse.util.TokenEstimator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompressBudgetTest {

    @Test
    fun `compression budget counts reasoning tools and images`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "short",
            reasoning = "hidden reasoning that still occupies context",
            toolCalls = listOf(ToolCall("call-1", "search", "{\"query\":\"Muse\"}")),
            imageBase64List = listOf("image"),
        )
        val actualTokens = TokenEstimator.estimate(listOf(message))

        assertTrue(isOverContextCompressionBudget(listOf(message), actualTokens - 1))
        assertFalse(isOverContextCompressionBudget(listOf(message), actualTokens))
    }

    @Test
    fun `manual compression falls back to a visible truncation marker when summary fails`() = runTest {
        val compressor = mockk<ConversationCompressor>()
        coEvery { compressor.compress(any(), any()) } returns null
        val transformer = ContextCompressTransformer(
            chatService = mockk(relaxed = true),
            compressor = compressor,
        )
        val messages = List(4) { index -> UIMessage(role = MessageRole.USER, content = "message-$index") }

        val result = transformer.transform(
            messages,
            TransformContext(
                sessionId = "session-1",
                extras = mapOf(
                    "compress_enabled" to true,
                    "compress_threshold" to 1,
                    "compress_keep_recent" to 1,
                    "compress_force_fallback" to true,
                ),
            ),
        )

        assertTrue(result.size < messages.size)
        assertTrue(result.any { it.content.contains("历史暂不可用") })
    }
}
