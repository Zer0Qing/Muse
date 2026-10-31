package io.zer0.ai.openai

import io.zer0.ai.core.ChatRequest
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.ai.core.ProviderType
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v2.6.14: `reasoning_content` 回放的来源守卫（走真实请求组装链路）。
 *
 * 用 MockWebServer 捕获实际请求体，锁定：
 *  - 跨模型历史思考块不出现在请求里（外加：协议要求字段必存在时只留空串占位）；
 *  - 同模型历史思考块正常回放；
 *  - 签名型协议产出的思考块（带 signature / encrypted_content）不经 reasoning_content 回放。
 */
class ReasoningReplayModelGuardWireTest {

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

    private val tool = ToolDefinition(
        name = "getWeather",
        description = "查询天气",
        parametersJsonSchema = """{"type":"object","properties":{"city":{"type":"string"}}}""",
    )

    private fun provider(): OpenAIProvider = OpenAIProvider(
        ProviderConfig(
            id = "reasoning-guard-test",
            displayName = "Reasoning Guard Test",
            type = ProviderType.OPENAI,
            baseUrl = server.url("/v1").toString(),
            apiKey = "test-key",
            specific = ProviderSpecificConfig.OpenAI(),
        ),
    )

    private fun enqueueOk() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"total_tokens":7}}""",
            ),
        )
    }

    private fun request(
        history: UIMessage,
        modelId: String,
        withTools: Boolean,
    ): ChatRequest = ChatRequest(
        messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "system prompt"),
            UIMessage(role = MessageRole.USER, content = "hello"),
            history,
            UIMessage(role = MessageRole.USER, content = "next"),
        ),
        model = Model(id = modelId, providerId = "test"),
        maxTokens = 256,
        tools = if (withTools) listOf(tool) else null,
    )

    private fun capturedAssistantMessage(): JsonObject {
        val body = server.takeRequest().body.readUtf8()
        val messages: JsonArray = AppJson.parseToJsonElement(body).jsonObject["messages"] as JsonArray
        return messages.map { it.jsonObject }.first { it["content"]?.jsonPrimitive?.content == "answer" }
    }

    @Test
    fun `same model replays reasoning content`() = runTest {
        enqueueOk()
        val history = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "answer",
            reasoning = "own thinking",
            modelId = "deepseek-v4",
        )

        provider().completeText(request(history, "deepseek-v4", withTools = true))

        val message = capturedAssistantMessage()
        assertEquals("own thinking", message["reasoning_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `cross model reasoning never leaves the client`() = runTest {
        enqueueOk()
        val history = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "answer",
            reasoning = "foreign thinking",
            thinkingSignature = "sig-anthropic",
            modelId = "claude-sonnet-4-5",
        )

        provider().completeText(request(history, "deepseek-v4", withTools = true))

        val message = capturedAssistantMessage()
        // 内容不外发；DeepSeek 协议要求带 tools 时字段必须存在，故只允许空串占位。
        assertEquals("", message["reasoning_content"]?.jsonPrimitive?.content)
        assertFalse(message.toString().contains("foreign thinking"))
    }

    @Test
    fun `cross model reasoning is omitted when request carries no tools`() = runTest {
        enqueueOk()
        val history = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "answer",
            reasoning = "foreign thinking",
            modelId = "claude-sonnet-4-5",
        )

        provider().completeText(request(history, "deepseek-v4", withTools = false))

        val message = capturedAssistantMessage()
        assertNull("无 tools 时不发 reasoning_content", message["reasoning_content"])
        assertFalse(message.toString().contains("foreign thinking"))
    }

    @Test
    fun `signature borne thinking is not replayed through reasoning content`() = runTest {
        enqueueOk()
        // 历史未记录产出模型（旧数据），但载荷形态已经暴露它来自签名型协议。
        val history = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "answer",
            reasoning = "responses reasoning",
            thinkingEncryptedContent = "enc-payload",
            modelId = null,
        )

        provider().completeText(request(history, "deepseek-v4", withTools = true))

        val message = capturedAssistantMessage()
        assertEquals("", message["reasoning_content"]?.jsonPrimitive?.content)
        assertFalse(message.toString().contains("responses reasoning"))
        assertTrue("降级后请求仍合法（正文照发）", message["content"]?.jsonPrimitive?.content == "answer")
    }
}
