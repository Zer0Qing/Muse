package io.zer0.ai.openai

import io.zer0.ai.core.ChatRequest
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R-TEST-01: OpenAIProvider stream-guard 状态机回归测试。
 *
 * 覆盖 R-AI-01 修复后的关键判据:
 *  - 空 name tool_call 在 Done 时恢复为正文(guard 挂起/恢复)
 *  - reasoning 先到、content 滞后时正常结束,不误回退
 *  - 空 finishReason 仍能正常 Done
 *
 * 早停触发非流式回退的完整网络路径由 FirstEventWatchdogTest(回退编排)
 * 与 ProviderRequestBodySnapshotTest(非流式请求格式)共同覆盖;
 * MockWebServer 下 SSE 断连与 fallback 二次请求存在时序竞争,不在此处重复模拟。
 */
class StreamGuardTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        Logger.enabled = false
    }

    @After
    fun tearDown() {
        server.shutdown()
        Logger.enabled = true
    }

    private fun provider(): OpenAIProvider = OpenAIProvider(
        ProviderConfig(
            id = "openai-test",
            displayName = "OpenAI Test",
            type = ProviderType.OPENAI,
            baseUrl = server.url("/v1").toString(),
            apiKey = "sk-test",
        ),
    )

    private fun request(content: String = "hello"): ChatRequest = ChatRequest(
        messages = listOf(UIMessage(role = MessageRole.USER, content = content)),
        model = Model(id = "gpt-4o-mini", providerId = "openai-test"),
        maxTokens = 64,
    )

    private fun sse(vararg events: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        .setBody(events.joinToString(separator = "\n\n") { "data: $it\n" } + "\n\n")

    @Test
    fun `空 name tool call 在 Done 时恢复为正文`() = runBlocking {
        server.enqueue(
            sse(
                """{"choices":[{"index":0,"delta":{"content":"一二三四五六七八九十"},"finish_reason":null}]}""",
                """{"choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_1","function":{"name":"","arguments":"{\"city\":\"北京\"}"}}]},"finish_reason":null}]}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                """[DONE]""",
            ),
        )

        val events = withTimeout(10_000) { provider().streamChat(request()).toList() }

        assertTrue(
            "空 name tool call 的 arguments 应恢复为正文",
            events.any { it is ChatStreamEvent.ContentDelta && it.delta.contains("北京") },
        )
        assertFalse("不应输出 ToolCallDelta", events.any { it is ChatStreamEvent.ToolCallDelta })
        assertTrue("应以 Done 结束", events.last() is ChatStreamEvent.Done)
    }

    @Test
    fun `reasoning 先到 content 滞后时不误回退`() = runBlocking {
        server.enqueue(
            sse(
                """{"choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"这是很长的一段思考过程"},"finish_reason":null}]}""",
                """{"choices":[{"index":0,"delta":{"content":"最终回答内容"},"finish_reason":null}]}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                """[DONE]""",
            ),
        )

        val events = withTimeout(10_000) { provider().streamChat(request()).toList() }

        assertTrue("应输出 reasoning", events.any { it is ChatStreamEvent.ReasoningDelta })
        assertTrue("应输出 content", events.any { it is ChatStreamEvent.ContentDelta && it.delta == "最终回答内容" })
        assertTrue("应以 Done 结束", events.last() is ChatStreamEvent.Done)
    }

    @Test
    fun `空 finishReason 仍正常 Done`() = runBlocking {
        server.enqueue(
            sse(
                """{"choices":[{"index":0,"delta":{"content":"一二三四五六七八九十"},"finish_reason":""}]}""",
                """[DONE]""",
            ),
        )

        val events = withTimeout(10_000) { provider().streamChat(request()).toList() }

        assertTrue(events.any { it is ChatStreamEvent.ContentDelta })
        assertTrue("空 finishReason 后应正常 Done", events.last() is ChatStreamEvent.Done)
    }

    @Test
    fun `空工具名流断开后应完成非流式回退并回传工具调用`() = runBlocking {
        val malformedToolChunks = (0..10).map { index ->
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":$index,"id":"call_bad_$index","function":{"name":"","arguments":"{\"chunk\":\"$index-very-long\"}"}}]},"finish_reason":null}]}"""
        }
        server.enqueue(
            sse(
                *(
                    malformedToolChunks + listOf(
                        """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
                        """[DONE]""",
                    )
                    ).toTypedArray(),
            ),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"choices":[{"index":0,"message":{"role":"assistant","content":"","tool_calls":[{"id":"call_fallback","type":"function","function":{"name":"echo","arguments":"{\"text\":\"ok\"}"}}]},"finish_reason":"tool_calls"}]}""",
                ),
        )

        val events = withTimeout(10_000) { provider().streamChat(request()).toList() }

        assertTrue(
            "非流式回退应把有效工具名回传给上层",
            events.any { it is ChatStreamEvent.ToolCallDelta && it.name == "echo" },
        )
        assertTrue(
            "非流式回退应把工具参数回传给上层",
            events.any { it is ChatStreamEvent.ToolCallDelta && it.argumentsDelta?.contains("\"ok\"") == true },
        )
        assertTrue("回退完成后 Flow 必须发出 Done", events.last() is ChatStreamEvent.Done)
    }

    @Test
    fun `非流式回退连续失败时必须发出错误而不能 Done-only 收口`() = runBlocking {
        server.enqueue(
            sse(
                """{"choices":[{"index":0,"delta":{"content":"a"},"finish_reason":"stop"}]}""",
                """[DONE]""",
            ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"upstream unavailable"}}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"upstream unavailable"}}"""),
        )

        val events = withTimeout(10_000) { provider().streamChat(request()).toList() }

        assertTrue(
            "回退连续失败必须把明确错误传给上层",
            events.any { it is ChatStreamEvent.Error },
        )
        assertFalse(
            "回退失败不能伪装成正常 Done-only 完成",
            events.last() is ChatStreamEvent.Done,
        )
    }

    /**
     * v2.6.1 (#5): 非流式回退遇到 finish_reason=tool_calls 时，reasoning 不得兜底成正文。
     *
     * 用户实测：模型停下来调工具但调用未解析出来时，旧逻辑把 reasoning_content
     * v2.6.1 (#5) → v2.7 冗余措施（用户确认“吐可以，空不行”）。
     *
     * 触发路径：流式只发 reasoning（content 为 0）→ stream-guard 判定过早结束 →
     * 非流式回退 → 回退响应 content 空、reasoning_content 非空、finish_reason=tool_calls
     * **但一个工具调用都没解析出来**（协议畸形/上游降级）。
     * 此时后续无任何东西会接着产出，正文留空 = 空白回复；
     * 按 never-empty 底线，应把思考兜底成正文。
     */
    @Test
    fun `非流式回退遇 tool_calls 但无工具调用时兜底防空白`() = runBlocking {
        val thinkingText = "我先判断一下需不需要调用工具,这一步是内部推理"
        server.enqueue(
            sse(
                """{"choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"$thinkingText"},"finish_reason":null}]}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
                """[DONE]""",
            ),
        )
        // 非流式回退响应：content 空、reasoning_content 非空、finish_reason=tool_calls、无 tool_calls
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"choices":[{"index":0,"message":{"role":"assistant","content":"","reasoning_content":"$thinkingText"},""" +
                        """"finish_reason":"tool_calls"}]}""",
                ),
        )

        val events = withTimeout(15_000) { provider().streamChat(request()).toList() }

        assertTrue(
            "无工具调用可执行时，reasoning 应兜底成正文，避免空白回复",
            events.any { it is ChatStreamEvent.ContentDelta && it.delta.contains("内部推理") },
        )
    }

    /**
     * 对照：有工具调用时不得把 reasoning 当正文（#5 原始诉求）。
     *
     * 模型停下来调工具，后续轮次会接着产出；把思考当正文会直接上屏。
     */
    @Test
    fun `非流式回退遇 tool_calls 且有工具调用时不兜底`() = runBlocking {
        val thinkingText = "我先判断一下需不需要调用工具,这一步是内部推理不应上屏"
        server.enqueue(
            sse(
                """{"choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"$thinkingText"},"finish_reason":null}]}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
                """[DONE]""",
            ),
        )
        // 非流式回退响应：带完整 tool_calls
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"choices":[{"index":0,"message":{"role":"assistant","content":"","reasoning_content":"$thinkingText",""" +
                        """"tool_calls":[{"id":"call_1","type":"function","function":{"name":"echo","arguments":"{}"}}]},""" +
                        """"finish_reason":"tool_calls"}]}""",
                ),
        )

        val events = withTimeout(15_000) { provider().streamChat(request()).toList() }

        assertTrue(
            "应回传工具调用给上层",
            events.any { it is ChatStreamEvent.ToolCallDelta },
        )
        assertFalse(
            "有工具调用待执行时，reasoning 不得兜底成正文",
            events.any { it is ChatStreamEvent.ContentDelta && it.delta.contains("内部推理") },
        )
    }

    /**
     * v2.6.1 (#5) 反向对照：真·reasoning-only 模型（停止原因非 tool_calls）
     * 仍应保留兜底，否则用户看到空回复。
     */
    @Test
    fun `非流式回退遇普通停止原因时保留 reasoning 兜底`() = runBlocking {
        val thinkingText = "这是一段比较长的思考内容用于触发流式过早结束判定并保留兜底行为"
        server.enqueue(
            sse(
                """{"choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"$thinkingText"},"finish_reason":null}]}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                """[DONE]""",
            ),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"choices":[{"index":0,"message":{"role":"assistant","content":"","reasoning_content":"$thinkingText"},""" +
                        """"finish_reason":"stop"}]}""",
                ),
        )

        val events = withTimeout(15_000) { provider().streamChat(request()).toList() }

        assertTrue(
            "普通停止原因下 reasoning-only 仍应兜底成正文，避免空回复",
            events.any { it is ChatStreamEvent.ContentDelta && it.delta.contains("思考内容") },
        )
    }
}
