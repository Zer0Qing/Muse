package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2.6: SSE fixture replay — 空正文/仅 reasoning/重复帧/乱序帧/错误后帧/
 * finish 后 delta/工具续接/断流 各场景的归一化行为测试。
 *
 * 同时覆盖 M2 验收核心:同一逻辑响应的流式事件聚合与非流式
 * [ChatCompletion.toEventSequence] 转换,最终 [NormalizedChatResult] 等价。
 */
class ChatEventNormalizationTest {

    // ── toEventSequence:非流式 → 事件序列 ───────────────────────────

    @Test
    fun `empty text with reasoning only still yields valid event sequence`() {
        val completion = ChatCompletion(
            text = "",
            reasoningContent = "internal scratchpad",
        )
        val events = completion.toEventSequence()
        assertTrue(events.first() is ChatStreamEvent.ReasoningDelta)
        assertTrue(events.last() is ChatStreamEvent.Done)
        // 仅 reasoning 也产生有效聚合结果(不误报空文本)
        val result = events.normalize()
        assertEquals("", result.text)
        assertEquals("internal scratchpad", result.reasoning)
    }

    @Test
    fun `non-streaming completion converts to full event sequence in contract order`() {
        val completion = ChatCompletion(
            text = "answer",
            finishReason = "stop",
            toolCalls = listOf(ToolCall(id = "call_1", name = "lookup", arguments = "{\"q\":\"x\"}")),
            reasoningContent = "think",
            usageTokens = UsageTokens(promptTokens = 3, completionTokens = 5),
            citationUrls = listOf("https://a.example"),
            thinkingSignature = "sig",
        )
        val kinds = completion.toEventSequence().map { it::class.simpleName }
        assertEquals(
            listOf(
                "ReasoningDelta",
                "ContentDelta",
                "ToolCallDelta",
                "UsageDelta",
                "CitationDelta",
                "Done",
            ),
            kinds,
        )
    }

    @Test
    fun `empty completion still emits done`() {
        val events = ChatCompletion(text = "").toEventSequence()
        assertEquals(listOf("Done"), events.map { it::class.simpleName })
    }

    // ── 流式聚合规则 ──────────────────────────────────────────────────

    @Test
    fun `content and reasoning deltas accumulate in arrival order`() {
        val result = listOf<ChatStreamEvent>(
            ChatStreamEvent.ReasoningDelta("a"),
            ChatStreamEvent.ReasoningDelta("b"),
            ChatStreamEvent.ContentDelta("hel"),
            ChatStreamEvent.ContentDelta("lo"),
            ChatStreamEvent.Done("stop"),
        ).normalize()
        assertEquals("hello", result.text)
        assertEquals("ab", result.reasoning)
        assertEquals("stop", result.finishReason)
    }

    @Test
    fun `duplicate snapshot frames are replaced not appended`() {
        val reducer = ChatEventReducer()
        val snapshot = ChatStreamEvent.ToolCallDelta(
            index = 0,
            id = "call_1",
            name = "lookup",
            argumentsDelta = "{\"q\":\"x\"}",
            isSnapshot = true,
        )
        reducer.reduce(snapshot)
        // 重放同一快照帧(重复 frame 场景):替换语义天然去重
        reducer.reduce(snapshot)
        val result = reducer.toResult()
        assertEquals(1, result.toolCalls.size)
        assertEquals("{\"q\":\"x\"}", result.toolCalls.single().arguments)
    }

    @Test
    fun `incremental argument fragments accumulate across frames`() {
        val result = listOf<ChatStreamEvent>(
            ChatStreamEvent.ToolCallDelta(index = 0, id = "call_1", name = "f", argumentsDelta = "{\"a\":"),
            ChatStreamEvent.ToolCallDelta(index = 0, argumentsDelta = "1}"),
            ChatStreamEvent.Done("tool_calls"),
        ).normalize()
        assertEquals(1, result.toolCalls.size)
        assertEquals("{\"a\":1}", result.toolCalls.single().arguments)
        assertEquals("call_1", result.toolCalls.single().id)
        assertEquals("f", result.toolCalls.single().name)
    }

    @Test
    fun `out of order tool call indexes are sorted by index`() {
        val result = listOf<ChatStreamEvent>(
            tc(2, "c", "third"),
            tc(0, "a", "first"),
            tc(1, "b", "second"),
            ChatStreamEvent.Done("tool_calls"),
        ).normalize()
        assertEquals(listOf("a", "b", "c"), result.toolCalls.map { it.id })
    }

    /** 乱序测试辅助:构造完整快照帧。 */
    private fun tc(index: Int, id: String, name: String): ChatStreamEvent.ToolCallDelta =
        ChatStreamEvent.ToolCallDelta(index = index, id = id, name = name, argumentsDelta = "{}", isSnapshot = true)

    @Test
    fun `deltas after done are dropped and counted`() {
        val result = listOf<ChatStreamEvent>(
            ChatStreamEvent.ContentDelta("final"),
            ChatStreamEvent.Done("stop"),
            // finish 后 delta(协议异常):不得追加进正文
            ChatStreamEvent.ContentDelta("GHOST"),
            ChatStreamEvent.ReasoningDelta("GHOST"),
            ChatStreamEvent.ToolCallDelta(index = 0, id = "x", name = "y", argumentsDelta = "{}", isSnapshot = true),
        ).normalize()
        assertEquals("final", result.text)
        assertNull(result.reasoning)
        assertTrue(result.toolCalls.isEmpty())
        assertEquals(3, result.postFinishFramesDropped)
    }

    @Test
    fun `deltas after done with null finish reason are dropped too`() {
        // 审查修复(P2)回归:Done(null) 同样是终态,后续增量帧必须丢弃
        val result = listOf<ChatStreamEvent>(
            ChatStreamEvent.ContentDelta("final"),
            ChatStreamEvent.Done(null),
            ChatStreamEvent.ContentDelta("GHOST"),
            ChatStreamEvent.ReasoningDelta("GHOST"),
            ChatStreamEvent.ToolCallDelta(index = 0, id = "x", name = "y", argumentsDelta = "{}", isSnapshot = true),
        ).normalize()
        assertEquals("final", result.text)
        assertNull(result.finishReason)
        assertNull(result.reasoning)
        assertTrue(result.toolCalls.isEmpty())
        assertEquals(3, result.postFinishFramesDropped)
    }

    @Test
    fun `error after partial content keeps partial text`() {
        val result = listOf<ChatStreamEvent>(
            ChatStreamEvent.ContentDelta("partial answer"),
            ChatStreamEvent.Error("connection reset"),
        ).normalize()
        assertEquals("partial answer", result.text)
        assertEquals("connection reset", result.error)
        assertFalse(result.interrupted)
    }

    @Test
    fun `stream interrupted preserves content and sets interrupted flag`() {
        val result = listOf<ChatStreamEvent>(
            ChatStreamEvent.ContentDelta("half of the answer"),
            ChatStreamEvent.StreamInterrupted("network switched"),
        ).normalize()
        assertEquals("half of the answer", result.text)
        assertTrue(result.interrupted)
        assertEquals("network switched", result.error)
    }

    @Test
    fun `usage last wins and citations dedupe preserving order`() {
        val result = listOf<ChatStreamEvent>(
            ChatStreamEvent.UsageDelta(UsageTokens(promptTokens = 1, completionTokens = 2)),
            ChatStreamEvent.UsageDelta(UsageTokens(promptTokens = 4, completionTokens = 8)),
            ChatStreamEvent.CitationDelta(listOf("https://a", "https://b")),
            ChatStreamEvent.CitationDelta(listOf("https://b", "https://c")),
            ChatStreamEvent.Done("stop"),
        ).normalize()
        assertEquals(UsageTokens(promptTokens = 4, completionTokens = 8), result.usage)
        assertEquals(listOf("https://a", "https://b", "https://c"), result.citationUrls)
    }

    @Test
    fun `tool continuation frames across rounds aggregate into one call`() {
        // 工具续接:参数分片跨多个 SSE chunk,且 id/name 只在首帧出现
        val result = listOf<ChatStreamEvent>(
            ChatStreamEvent.ToolCallDelta(index = 0, id = "call_9", name = "run_sql", argumentsDelta = "{\"sql\":\"SE"),
            ChatStreamEvent.ToolCallDelta(index = 0, argumentsDelta = "LECT 1\"}"),
            ChatStreamEvent.Done("tool_calls"),
        ).normalize()
        assertEquals(
            listOf(ToolCall(id = "call_9", name = "run_sql", arguments = "{\"sql\":\"SELECT 1\"}")),
            result.toolCalls,
        )
        assertEquals("tool_calls", result.finishReason)
    }

    // ── 流式 / 非流式最终 projection 等价(M2 验收) ──────────────────

    @Test
    fun `streaming aggregation equals non-streaming normalization for the same logical response`() {
        // 非流式:一次性完整结果
        val completion = ChatCompletion(
            text = "最终答案",
            finishReason = "stop",
            toolCalls = listOf(ToolCall(id = "call_1", name = "lookup", arguments = "{\"q\":\"muse\"}")),
            reasoningContent = "思考过程",
            usageTokens = UsageTokens(promptTokens = 10, completionTokens = 20),
            citationUrls = listOf("https://a.example"),
            thinkingSignature = "sig-1",
            thinkingEncryptedContent = "enc-1",
        )

        // 流式:同一逻辑响应按分片到达(乱序 index + 引用去重;签名随 reasoning 帧)
        val streamed = listOf<ChatStreamEvent>(
            ChatStreamEvent.ReasoningDelta("思考", signature = "sig-1", encryptedContent = "enc-1"),
            ChatStreamEvent.ReasoningDelta("过程"),
            ChatStreamEvent.ContentDelta("最终"),
            ChatStreamEvent.ContentDelta("答案"),
            ChatStreamEvent.ToolCallDelta(
                index = 0, id = "call_1", name = "lookup", argumentsDelta = "{\"q\":",
            ),
            ChatStreamEvent.ToolCallDelta(index = 0, argumentsDelta = "\"muse\"}"),
            ChatStreamEvent.UsageDelta(UsageTokens(promptTokens = 10, completionTokens = 20)),
            ChatStreamEvent.CitationDelta(listOf("https://a.example")),
            ChatStreamEvent.Done("stop"),
        )

        val fromNonStreaming = completion.toEventSequence().normalize()
        val fromStreaming = streamed.normalize()

        // 文本/思考/工具/引用等价;finishReason 等价
        assertEquals(fromStreaming.text, fromNonStreaming.text)
        assertEquals(fromStreaming.reasoning, fromNonStreaming.reasoning)
        assertEquals(fromStreaming.toolCalls, fromNonStreaming.toolCalls)
        assertEquals(fromStreaming.finishReason, fromNonStreaming.finishReason)
        assertEquals(fromStreaming.usage, fromNonStreaming.usage)
        assertEquals(fromStreaming.citationUrls, fromNonStreaming.citationUrls)
        assertEquals(fromStreaming.thinkingSignature, fromNonStreaming.thinkingSignature)
        assertEquals(fromStreaming.thinkingEncryptedContent, fromNonStreaming.thinkingEncryptedContent)
        // 流式没有错误/中断/协议异常
        assertNull(fromStreaming.error)
        assertFalse(fromStreaming.interrupted)
        assertEquals(0, fromStreaming.postFinishFramesDropped)
    }
}
