package io.zer0.muse.transformer

import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.UIMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompressOldToolTest {

    @Test
    fun `older tool rounds compress while last two tool rounds remain intact`() = runTest {
        val compressor = mockk<ConversationCompressor>()
        coEvery { compressor.compress(any(), any(), any()) } returns listOf("旧轮次摘要")
        val transformer = ContextCompressTransformer(
            chatService = mockk(relaxed = true),
            compressor = compressor,
        )

        val longOldResult = "OLD_TOOL_PAYLOAD_MARKER " + "x".repeat(800)
        val call1 = ToolCall("c1", "read_file", """{"path":"old.txt"}""")
        val call2 = ToolCall("c2", "read_file", """{"path":"mid.txt"}""")
        val call3 = ToolCall("c3", "read_file", """{"path":"new.txt"}""")

        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "system"),
            UIMessage(role = MessageRole.USER, content = "read old"),
            UIMessage(role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(call1)),
            UIMessage(role = MessageRole.TOOL, content = longOldResult, toolCallId = call1.id),
            UIMessage(role = MessageRole.USER, content = "read mid"),
            UIMessage(role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(call2)),
            UIMessage(role = MessageRole.TOOL, content = "MID_TOOL_MARKER", toolCallId = call2.id),
            UIMessage(role = MessageRole.USER, content = "read new"),
            UIMessage(role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(call3)),
            UIMessage(role = MessageRole.TOOL, content = "NEW_TOOL_MARKER", toolCallId = call3.id),
            UIMessage(role = MessageRole.USER, content = "final question"),
        )

        val result = transformer.transform(
            messages,
            TransformContext(
                sessionId = "s-old-tools",
                extras = mapOf(
                    "compress_enabled" to true,
                    "compress_threshold" to 5,
                    "compress_keep_recent" to 2,
                    "compress_char_budget" to 50,
                    "compress_force_fallback" to true,
                ),
            ),
        )

        val contents = result.joinToString("\n") { it.content }
        assertFalse("最早第 1 轮工具原文应被压缩移除", contents.contains("OLD_TOOL_PAYLOAD_MARKER"))
        assertTrue("第 2 轮工具结果应保留", contents.contains("MID_TOOL_MARKER"))
        assertTrue("第 3 轮工具结果应保留", contents.contains("NEW_TOOL_MARKER"))
        assertTrue("压缩摘要应包含旧轮次摘要", contents.contains("旧轮次摘要"))
    }
}
