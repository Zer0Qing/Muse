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
                *(malformedToolChunks + listOf(
                    """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
                    """[DONE]""",
                )).toTypedArray(),
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
}
