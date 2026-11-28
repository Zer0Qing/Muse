package io.zer0.ai.gemini

import io.zer0.ai.core.ChatRequest
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.ai.core.ProviderType
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeminiProtocolM1Test {

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

    private fun provider(): GeminiProvider = GeminiProvider(
        ProviderConfig(
            id = "gemini-m1-test",
            displayName = "Gemini M1 Test",
            type = ProviderType.GEMINI,
            baseUrl = server.url("/v1beta").toString(),
            apiKey = "AIzaTestKey",
            specific = ProviderSpecificConfig.Gemini(),
        ),
    )

    private fun sampleTools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "read_file",
            description = "Read file",
            parametersJsonSchema = """{"type":"object","properties":{"path":{"type":"string"}}}""",
        ),
    )

    @Test
    fun `streamChat separates thought from content preserves thoughtSignature and generates unique tool ids`() = runTest {
        val ssePayload = buildString {
            append("data: ")
            append(
                """
                {"candidates":[{"content":{"role":"model","parts":[
                  {"text":"internal chain","thought":true,"thoughtSignature":"sig-gem-1"},
                  {"functionCall":{"name":"read_file","args":{"path":"a.txt"}},"thoughtSignature":"sig-gem-2"},
                  {"functionCall":{"name":"read_file","args":{"path":"b.txt"}}},
                  {"text":"calling read_file"}
                ]},"finishReason":"STOP"}]}
                """.trimIndent().replace("\n", ""),
            )
            append("\n\n")
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(ssePayload),
        )

        val events = provider().streamChat(
            ChatRequest(
                messages = listOf(UIMessage(role = MessageRole.USER, content = "read a and b")),
                model = Model(id = "gemini-2.5-pro", providerId = "test"),
                tools = sampleTools(),
            ),
        ).toList()

        val reasoning = events.filterIsInstance<ChatStreamEvent.ReasoningDelta>()
        val content = events.filterIsInstance<ChatStreamEvent.ContentDelta>()
        val toolCalls = events.filterIsInstance<ChatStreamEvent.ToolCallDelta>()

        assertTrue(reasoning.any { it.delta == "internal chain" && it.signature == "sig-gem-1" })
        assertTrue(reasoning.any { it.signature == "sig-gem-2" })
        assertEquals(listOf("calling read_file"), content.map { it.delta })
        assertEquals(2, toolCalls.size)
        assertEquals("gemini_0_read_file", toolCalls[0].id)
        assertEquals("gemini_1_read_file", toolCalls[1].id)
        assertEquals(0, toolCalls[0].index)
        assertEquals(1, toolCalls[1].index)
    }

    @Test
    fun `completeText excludes thought parts and emits unique tool call ids`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {
                  "candidates": [{
                    "content": {
                      "role": "model",
                      "parts": [
                        {"text": "hidden thought", "thought": true},
                        {"functionCall": {"name": "read_file", "args": {"path": "x"}}},
                        {"functionCall": {"name": "read_file", "args": {"path": "y"}}},
                        {"text": "visible answer"}
                      ]
                    },
                    "finishReason": "STOP"
                  }]
                }
                """.trimIndent(),
            ),
        )

        val completion = provider().completeText(
            ChatRequest(
                messages = listOf(UIMessage(role = MessageRole.USER, content = "go")),
                model = Model(id = "gemini-2.5-pro", providerId = "test"),
                tools = sampleTools(),
            ),
        )

        assertEquals("visible answer", completion.text)
        val ids = completion.toolCalls!!.map { it.id }
        assertEquals(listOf("gemini_1_read_file", "gemini_2_read_file"), ids)
    }

    @Test
    fun `request merges parallel tool responses resolves names replays thoughtSignature and maps toolConfig`() = runTest {
        repeat(3) {
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}""",
                ),
            )
        }

        val call1 = ToolCall("gemini_0_read_file", "read_file", """{"path":"a.txt"}""")
        val call2 = ToolCall("gemini_1_read_file", "read_file", """{"path":"b.txt"}""")
        val base = ChatRequest(
            messages = listOf(
                UIMessage(role = MessageRole.USER, content = "read both"),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    content = "",
                    thinkingSignature = "sig-replay-1",
                    toolCalls = listOf(call1, call2),
                ),
                UIMessage(role = MessageRole.TOOL, content = """{"ok":1}""", toolCallId = call1.id),
                UIMessage(role = MessageRole.TOOL, content = """{"ok":2}""", toolCallId = call2.id),
            ),
            model = Model(id = "gemini-2.5-pro", providerId = "test"),
            tools = sampleTools(),
            toolChoice = "required",
        )

        val p = provider()
        p.completeText(base)
        val requiredBody = AppJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(
            "ANY",
            requiredBody["toolConfig"]?.jsonObject?.get("functionCallingConfig")?.jsonObject?.get("mode")?.jsonPrimitive?.content,
        )

        val contents = requiredBody["contents"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, contents.size)
        assertEquals("user", contents[0]["role"]?.jsonPrimitive?.content)
        assertEquals("model", contents[1]["role"]?.jsonPrimitive?.content)
        assertEquals("user", contents[2]["role"]?.jsonPrimitive?.content)

        val modelParts = contents[1]["parts"]!!.jsonArray.map { it.jsonObject }
        assertEquals("sig-replay-1", modelParts[0]["thoughtSignature"]?.jsonPrimitive?.content)
        assertEquals(null, modelParts[1]["thoughtSignature"])

        val mergedRespParts = contents[2]["parts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, mergedRespParts.size)
        assertEquals(
            "read_file",
            mergedRespParts[0]["functionResponse"]?.jsonObject?.get("name")?.jsonPrimitive?.content,
        )
        assertEquals(
            "read_file",
            mergedRespParts[1]["functionResponse"]?.jsonObject?.get("name")?.jsonPrimitive?.content,
        )

        p.completeText(base.copy(toolChoice = "none"))
        val noneBody = AppJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(
            "NONE",
            noneBody["toolConfig"]?.jsonObject?.get("functionCallingConfig")?.jsonObject?.get("mode")?.jsonPrimitive?.content,
        )

        p.completeText(base.copy(toolChoice = "auto"))
        val autoBody = AppJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(
            "AUTO",
            autoBody["toolConfig"]?.jsonObject?.get("functionCallingConfig")?.jsonObject?.get("mode")?.jsonPrimitive?.content,
        )
    }
}
