package io.zer0.ai.openai

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v2.x 日志排障: Responses API 空 content 消息的处置判定回归测试。
 *
 * 背景: Responses API 拒绝 content 为空的消息（HTTP 400 `Message content cannot be empty`）。
 * 空 assistant 消息（工具轮或上次失败残留）一旦进入历史，会让之后每一轮都 400，
 * 形成自我复现的死循环。本测试锁定分类判定，防止回归。
 */
class ResponsesEmptyMessageDispositionTest {

    private val provider = io.zer0.ai.openai.OpenAIProvider(
        config = io.zer0.ai.core.ProviderConfig(
            id = "test",
            displayName = "test",
            type = io.zer0.ai.core.ProviderType.OPENAI,
            baseUrl = "https://example.com/v1",
            apiKey = "sk-test",
        ),
    )

    @Test
    fun `non-empty content is kept`() {
        val msg = UIMessage(id = kotlin.uuid.Uuid.random(), role = MessageRole.ASSISTANT, content = "hi")
        assertEquals(ResponsesMessageDisposition.KEEP, provider.classifyResponsesMessage(msg))
    }

    @Test
    fun `blank assistant with tool calls keeps message but skips message item`() {
        val msg = UIMessage(
            id = kotlin.uuid.Uuid.random(),
            role = MessageRole.ASSISTANT,
            content = "",
            toolCalls = listOf(ToolCall(id = "call_1", name = "web_search", arguments = "{}")),
        )
        assertEquals(ResponsesMessageDisposition.SKIP_MESSAGE_ITEM, provider.classifyResponsesMessage(msg))
    }

    @Test
    fun `fully empty assistant is skipped`() {
        val msg = UIMessage(id = kotlin.uuid.Uuid.random(), role = MessageRole.ASSISTANT, content = "")
        assertEquals(ResponsesMessageDisposition.SKIP, provider.classifyResponsesMessage(msg))
    }

    @Test
    fun `blank message with image is kept`() {
        val msg = UIMessage(
            id = kotlin.uuid.Uuid.random(),
            role = MessageRole.USER,
            content = "",
            imageBase64List = listOf("AAAA"),
        )
        assertEquals(ResponsesMessageDisposition.KEEP, provider.classifyResponsesMessage(msg))
    }

    @Test
    fun `tool result message with blank content is kept`() {
        val msg = UIMessage(
            id = kotlin.uuid.Uuid.random(),
            role = MessageRole.TOOL,
            content = "",
            toolCallId = "call_1",
        )
        assertEquals(ResponsesMessageDisposition.KEEP, provider.classifyResponsesMessage(msg))
    }
}
