package io.zer0.ai.core

import io.zer0.ai.anthropic.AnthropicProvider
import io.zer0.ai.gemini.GeminiProvider
import io.zer0.ai.openai.OpenAIProvider
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * R-TEST-05: Anthropic / Gemini / Ollama(OpenAI 兼容)请求 DTO 黄金快照测试。
 *
 * 固定输入 + MockWebServer 捕获真实请求体,断言与快照 JSON 一致,
 * 防止后续 DTO/Provider 改动悄悄破坏请求格式。
 */
class ProviderRequestBodySnapshotTest {

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

    private fun request(modelId: String, toolChoice: String? = null): ChatRequest = ChatRequest(
        messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "system prompt"),
            UIMessage(role = MessageRole.USER, content = "hello"),
        ),
        model = Model(id = modelId, providerId = "test"),
        temperature = 0.7f,
        maxTokens = 256,
        tools = listOf(tool),
        toolChoice = toolChoice,
    )

    private fun assertJsonEquals(expected: String, actual: String) {
        val expectedJson: JsonElement = AppJson.parseToJsonElement(expected)
        val actualJson: JsonElement = AppJson.parseToJsonElement(actual)
        assertEquals("请求体快照不一致\n期望=$expected\n实际=$actual", expectedJson, actualJson)
    }

    @Test
    fun `Anthropic 请求体黄金快照`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":2}}
                """.trimIndent(),
            ),
        )
        val provider = AnthropicProvider(
            ProviderConfig(
                id = "anthropic-test",
                displayName = "Anthropic Test",
                type = ProviderType.ANTHROPIC,
                baseUrl = server.url("/v1").toString(),
                apiKey = "sk-ant-test",
                specific = ProviderSpecificConfig.Anthropic(),
            ),
        )

        provider.completeText(request("claude-3-5-sonnet"))

        val actual = server.takeRequest().body.readUtf8()
        assertJsonEquals(
            """
            {
              "model": "claude-3-5-sonnet",
              "messages": [{"role": "user", "content": "hello"}],
              "system": [{"type": "text", "text": "system prompt"}],
              "max_tokens": 256,
              "temperature": 0.699999988079071,
              "stream": false,
              "tools": [
                {
                  "name": "getWeather",
                  "description": "查询天气",
                  "input_schema": {"type":"object","properties":{"city":{"type":"string"}}}
                }
              ]
            }
            """.trimIndent(),
            actual,
        )
    }

    @Test
    fun `Gemini 请求体黄金快照`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}],"usageMetadata":{"totalTokenCount":7}}
                """.trimIndent(),
            ),
        )
        val provider = GeminiProvider(
            ProviderConfig(
                id = "gemini-test",
                displayName = "Gemini Test",
                type = ProviderType.GEMINI,
                baseUrl = server.url("/v1beta").toString(),
                apiKey = "gemini-test-key",
                specific = ProviderSpecificConfig.Gemini(),
            ),
        )

        provider.completeText(request("gemini-2.0-flash"))

        val actual = server.takeRequest().body.readUtf8()
        assertJsonEquals(
            """
            {
              "contents": [{"role": "user", "parts": [{"text": "hello"}]}],
              "systemInstruction": {"role": "user", "parts": [{"text": "system prompt"}]},
              "generationConfig": {
                "temperature": 0.699999988079071,
                "maxOutputTokens": 256
              },
              "tools": [
                {
                  "functionDeclarations": [
                    {
                      "name": "getWeather",
                      "description": "查询天气",
                      "parameters": {"type":"object","properties":{"city":{"type":"string"}}}
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
            actual,
        )
    }

    @Test
    fun `Ollama OpenAI 兼容请求体黄金快照`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"total_tokens":7}}
                """.trimIndent(),
            ),
        )
        val provider = OpenAIProvider(
            ProviderConfig(
                id = "ollama-test",
                displayName = "Ollama Test",
                type = ProviderType.OPENAI,
                baseUrl = server.url("/v1").toString(),
                apiKey = "",
                allowMissingApiKey = true,
                specific = ProviderSpecificConfig.OpenAI(),
            ),
        )

        provider.completeText(request("qwen2.5:7b"))

        val actual = server.takeRequest().body.readUtf8()
        assertJsonEquals(
            """
            {
              "model": "qwen2.5:7b",
              "messages": [
                {"role": "system", "content": "system prompt"},
                {"role": "user", "content": "hello"}
              ],
              "temperature": 0.7,
              "max_tokens": 256,
              "stream": false,
              "tools": [
                {
                  "type": "function",
                  "function": {
                    "name": "getWeather",
                    "description": "查询天气",
                    "parameters": {"type":"object","properties":{"city":{"type":"string"}}}
                  }
                }
              ]
            }
            """.trimIndent(),
            actual,
        )
    }

    @Test
    fun `OpenAI 请求体在明确工具意图时携带 required tool_choice`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""",
            ),
        )
        val provider = OpenAIProvider(
            ProviderConfig(
                id = "tool-choice-test",
                displayName = "Tool Choice Test",
                type = ProviderType.OPENAI,
                baseUrl = server.url("/v1").toString(),
                apiKey = "test-key",
                specific = ProviderSpecificConfig.OpenAI(),
            ),
        )

        provider.completeText(request("tool-choice-model", toolChoice = "required"))

        val actual = AppJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("required", actual["tool_choice"]?.jsonPrimitive?.content)
    }
}
