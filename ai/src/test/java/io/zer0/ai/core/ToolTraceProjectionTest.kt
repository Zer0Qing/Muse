package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 Kotlin：模型上下文中的工具轨迹压缩投影测试。 */
class ToolTraceProjectionTest {

    @Test
    fun `compresses old successful tool round and keeps latest round complete`() {
        val oldCall = ToolCall("old-call", "search", "{\"q\":\"old\"}")
        val latestCall = ToolCall("latest-call", "read_file", "{\"path\":\"/tmp/a\"}")
        val messages = listOf(
            UIMessage(role = MessageRole.USER, content = "old question"),
            UIMessage(role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(oldCall)),
            UIMessage(role = MessageRole.TOOL, content = "old result", toolCallId = oldCall.id),
            UIMessage(role = MessageRole.USER, content = "new question"),
            UIMessage(role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(latestCall)),
            UIMessage(role = MessageRole.TOOL, content = "latest result", toolCallId = latestCall.id),
            UIMessage(role = MessageRole.ASSISTANT, content = "final answer"),
        )

        val projected = ToolTraceProjection.project(messages)

        assertEquals(6, projected.size)
        assertEquals(MessageRole.SYSTEM, projected[1].role)
        assertTrue(projected[1].content.contains(ToolTraceProjection.SUMMARY_MARKER))
        assertTrue(projected[1].content.contains("search"))
        assertTrue(projected[1].content.contains("成功(SUCCESS)"))
        assertTrue(projected[1].content.contains("old result"))
        assertEquals(MessageRole.ASSISTANT, projected[3].role)
        assertEquals(listOf(latestCall), projected[3].toolCalls)
        assertEquals(latestCall.id, projected[4].toolCallId)
        assertEquals(MessageRole.TOOL, projected[4].role)
    }

    @Test
    fun `keeps failed tool round complete`() {
        val call = ToolCall("failed-call", "delete_file", "{\"path\":\"/tmp/a\"}")
        val messages = listOf(
            UIMessage(role = MessageRole.USER, content = "remove it"),
            UIMessage(role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(call)),
            UIMessage(role = MessageRole.TOOL, content = "Error: permission denied", toolCallId = call.id),
            UIMessage(role = MessageRole.USER, content = "continue"),
        )

        val projected = ToolTraceProjection.project(messages, keepRecentRounds = 0)

        assertEquals(messages, projected)
    }

    @Test
    fun `does not break unmatched or interleaved tool call pairing`() {
        val call = ToolCall("call-1", "search", "{}")
        val messages = listOf(
            UIMessage(role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(call)),
            UIMessage(role = MessageRole.USER, content = "interleaved"),
            UIMessage(role = MessageRole.TOOL, content = "result", toolCallId = call.id),
            UIMessage(role = MessageRole.ASSISTANT, content = "later", toolCalls = listOf(call)),
            UIMessage(role = MessageRole.TOOL, content = "wrong id", toolCallId = "other-id"),
        )

        val projected = ToolTraceProjection.project(messages, keepRecentRounds = 0)

        assertEquals(messages, projected)
        assertEquals(MessageRole.ASSISTANT, projected.first().role)
        assertEquals(call.id, projected[2].toolCallId)
        assertFalse(projected.any { it.content.contains(ToolTraceProjection.SUMMARY_MARKER) })
    }

    @Test
    fun `truncates result preview and projection is idempotent`() {
        val call = ToolCall("call-long", "fetch", "{}")
        val longResult = "x".repeat(500)
        val messages = listOf(
            UIMessage(role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(call)),
            UIMessage(role = MessageRole.TOOL, content = longResult, toolCallId = call.id),
            UIMessage(role = MessageRole.USER, content = "next"),
            UIMessage(role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(ToolCall("call-new", "fetch", "{}"))),
            UIMessage(role = MessageRole.TOOL, content = "new", toolCallId = "call-new"),
        )

        val once = ToolTraceProjection.project(messages)
        val twice = ToolTraceProjection.project(once)

        assertEquals(4, once.size)
        assertTrue(once.first().content.contains("结果(截断)"))
        assertTrue(once.first().content.contains("…"))
        assertEquals(once, twice)
    }

    @Test
    fun `recognizes structured failure statuses and keeps the round`() {
        val call = ToolCall("json-failed", "lookup", "{}")
        val messages = listOf(
            UIMessage(role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(call)),
            UIMessage(role = MessageRole.TOOL, content = "{\"success\":false,\"value\":\"no\"}", toolCallId = call.id),
            UIMessage(role = MessageRole.USER, content = "next"),
            UIMessage(role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(ToolCall("call-ok", "lookup", "{}"))),
            UIMessage(role = MessageRole.TOOL, content = "{\"value\":\"yes\"}", toolCallId = "call-ok"),
        )

        val projected = ToolTraceProjection.project(messages, keepRecentRounds = 0)

        assertEquals(messages.size - 1, projected.size)
        assertEquals(MessageRole.ASSISTANT, projected[0].role)
        assertEquals("json-failed", projected[0].toolCalls?.single()?.id)
        assertEquals(MessageRole.TOOL, projected[1].role)
        assertTrue(projected.any { it.role == MessageRole.SYSTEM && it.content.contains("lookup") })
    }

    @Test
    fun `compresses persisted tool cards but keeps a failed card round`() {
        val oldCard = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            toolCallInfo = ToolCallInfo(
                toolName = "search",
                arguments = "{}",
                result = "old card result",
                isSuccess = true,
            ),
        )
        val failedCard = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            toolCallInfo = ToolCallInfo(
                toolName = "write_file",
                arguments = "{}",
                result = "permission denied",
                isSuccess = false,
            ),
        )
        val next = UIMessage(role = MessageRole.USER, content = "next")
        val messages = listOf(oldCard, next, failedCard)

        val projected = ToolTraceProjection.project(messages, keepRecentRounds = 0)

        assertEquals(3, projected.size)
        assertEquals(MessageRole.SYSTEM, projected[0].role)
        assertTrue(projected[0].content.contains("search"))
        assertTrue(projected[0].content.contains("成功(SUCCESS)"))
        assertEquals(failedCard, projected[2])
        assertTrue(projected.none { it.content.contains("失败(FAILED)") })
    }

    @Test
    fun `keeps all cards in the latest persisted tool round`() {
        val firstLatestCard = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            toolCallInfo = ToolCallInfo("search", "{}", "first", true),
        )
        val secondLatestCard = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            toolCallInfo = ToolCallInfo("fetch", "{}", "second", true),
        )
        val messages = listOf(
            UIMessage(role = MessageRole.ASSISTANT, content = "", toolCallInfo = ToolCallInfo("old", "{}", "old", true)),
            UIMessage(role = MessageRole.USER, content = "current"),
            firstLatestCard,
            secondLatestCard,
        )

        val projected = ToolTraceProjection.project(messages)

        assertEquals(4, projected.size)
        assertEquals(MessageRole.SYSTEM, projected[0].role)
        assertEquals(firstLatestCard, projected[2])
        assertEquals(secondLatestCard, projected[3])
    }
}
