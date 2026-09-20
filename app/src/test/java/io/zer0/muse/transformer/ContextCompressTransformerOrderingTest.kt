package io.zer0.muse.transformer

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回归测试：通用上下文压缩保留优先级消息时不得重排工具调用链。 */
class ContextCompressTransformerOrderingTest {

    @Test
    fun `retained priority messages keep original tool call result order`() {
        val call = ToolCall("call-1", "search", "{}")
        val userBefore = UIMessage(role = MessageRole.USER, content = "before")
        val assistantCall = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            toolCalls = listOf(call),
        )
        val toolResult = UIMessage(
            role = MessageRole.TOOL,
            content = "result",
            toolCallId = call.id,
        )
        val recentUser = UIMessage(role = MessageRole.USER, content = "recent")
        val messages = listOf(userBefore, assistantCall, toolResult, recentUser)

        // 模拟 assistant(tool_call) 在待压缩区、TOOL(result) 在 recent 区的跨切点场景。
        val retained = retainMessagesInOriginalOrder(
            messages = messages,
            recent = listOf(toolResult, recentUser),
            priorityMessages = listOf(assistantCall),
        )

        assertEquals(listOf(assistantCall, toolResult, recentUser), retained)
        assertTrue(retained.indexOf(assistantCall) < retained.indexOf(toolResult))
        assertEquals(call.id, retained[1].toolCallId)
    }
}
