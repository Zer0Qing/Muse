package io.zer0.ai.core

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：非流式回退发送的完整 tool_call 参数必须**覆盖**而非追加到流式阶段已累积的参数上。
 *
 * 线上症状（群聊）：流式阶段 finish_reason=tool_calls 但 content 为空 → 触发非流式回退 →
 * 回退返回完整参数 → 聚合器把两段拼起来（日志 len=230 → argsLen=473，几乎正好两倍）→
 * 拼出的 JSON 不再合法 → 解析出空字段 → channel_reply 报「content 不能为空」→ 整轮静默。
 */
class ToolCallSnapshotTest {

    @Test
    fun `快照帧覆盖流式阶段已累积的参数`() {
        val partial = """{"content":"半截"""
        val full = """{"content":"完整的一句话"}"""

        val events = listOf(
            // 流式阶段：只到一半
            ChatStreamEvent.ToolCallDelta(
                index = 0,
                id = "call_1",
                name = "channel_reply",
                argumentsDelta = partial,
            ),
            // 非流式回退：完整参数，标记快照
            ChatStreamEvent.ToolCallDelta(
                index = 0,
                id = "call_1",
                name = "channel_reply",
                argumentsDelta = full,
                isSnapshot = true,
            ),
        )

        val result = events.normalize()
        assertEquals(1, result.toolCalls.size)
        assertEquals(
            "快照应覆盖而非追加，最终参数必须等于完整参数",
            full,
            result.toolCalls.first().arguments,
        )
    }

    @Test
    fun `不带快照时仍然是增量追加（对照）`() {
        val a = """{"content":"前"""
        val b = """半句"}"""

        val events = listOf(
            ChatStreamEvent.ToolCallDelta(index = 0, id = "call_1", name = "channel_reply", argumentsDelta = a),
            ChatStreamEvent.ToolCallDelta(index = 0, argumentsDelta = b),
        )

        val result = events.normalize()
        assertEquals(
            "普通增量帧应拼接成完整 JSON",
            """{"content":"前半句"}""",
            result.toolCalls.first().arguments,
        )
    }

    @Test
    fun `重复回退不会把参数拼两遍`() {
        val full = """{"content":"你好"}"""
        val events = listOf(
            ChatStreamEvent.ToolCallDelta(index = 0, name = "channel_reply", argumentsDelta = """{"con"""),
            ChatStreamEvent.ToolCallDelta(
                index = 0,
                name = "channel_reply",
                argumentsDelta = full,
                isSnapshot = true,
            ),
            // 极端情况：回退被触发两次
            ChatStreamEvent.ToolCallDelta(
                index = 0,
                name = "channel_reply",
                argumentsDelta = full,
                isSnapshot = true,
            ),
        )

        val result = events.normalize()
        val args = result.toolCalls.first().arguments
        assertEquals("两次快照后仍应只有一份完整参数", full, args)
        assertTrue("参数长度不应翻倍", args.length < full.length * 2)
    }
}
