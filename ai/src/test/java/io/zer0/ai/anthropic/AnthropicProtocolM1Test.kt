package io.zer0.ai.anthropic

import io.zer0.ai.core.ChatRequest
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.ai.core.ProviderType
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnthropicProtocolM1Test {

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

    private fun enqueueOk() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":2}}
                """.trimIndent(),
            ),
        )
    }

    private fun provider(promptCaching: Boolean = true): AnthropicProvider = AnthropicProvider(
        ProviderConfig(
            id = "anthropic-m1-test",
            displayName = "Anthropic M1 Test",
            type = ProviderType.ANTHROPIC,
            baseUrl = server.url("/v1").toString(),
            apiKey = "sk-ant-test",
            specific = ProviderSpecificConfig.Anthropic(promptCaching = promptCaching),
        ),
    )

    private fun sampleTools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "read_file",
            description = "Read a file",
            parametersJsonSchema = """{"type":"object","properties":{"path":{"type":"string"}}}""",
        ),
        ToolDefinition(
            name = "web_search",
            description = "Search the web",
            parametersJsonSchema = """{"type":"object","properties":{"query":{"type":"string"}}}""",
        ),
    )

    @Test
    fun `system cache_control is anchored on block 0 and breakpoint with total breakpoints at most 4`() = runTest {
        enqueueOk()
        val request = ChatRequest(
            messages = listOf(
                UIMessage(role = MessageRole.SYSTEM, content = "base system"),
                UIMessage(role = MessageRole.SYSTEM, content = "digest"),
                UIMessage(role = MessageRole.SYSTEM, content = "rag context"),
                UIMessage(role = MessageRole.USER, content = "hello"),
            ),
            model = Model(id = "claude-sonnet-4-5", providerId = "test"),
            tools = sampleTools(),
            maxTokens = 512,
        )

        provider().completeText(request)

        val root = AppJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val systemBlocks = root["system"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, systemBlocks.size)
        assertNotNull("block 0 必须常驻 cache_control", systemBlocks[0]["cache_control"])
        assertNotNull("倒数第二块必须带 cache_control", systemBlocks[1]["cache_control"])
        assertNull("末尾动态 system 块不带 cache_control", systemBlocks[2]["cache_control"])

        val tools = root["tools"]!!.jsonArray.map { it.jsonObject }
        assertNull(tools[0]["cache_control"])
        assertNotNull("最后一个 tool 应带 cache_control", tools[1]["cache_control"])

        val messages = root["messages"]!!.jsonArray.map { it.jsonObject }
        assertNull("AnthropicMessage 顶层不应带 cache_control", messages.last()["cache_control"])
        val userBlocks = messages.last()["content"]!!.jsonArray.map { it.jsonObject }
        assertNotNull("user 最后一个内容块应带 cache_control", userBlocks.last()["cache_control"])
    }

    @Test
    fun `tool_choice serializes auto any and none appropriately`() = runTest {
        enqueueOk()
        enqueueOk()
        enqueueOk()
        val p = provider()
        val base = ChatRequest(
            messages = listOf(UIMessage(role = MessageRole.USER, content = "run")),
            model = Model(id = "claude-sonnet-4-5", providerId = "test"),
            tools = sampleTools(),
            maxTokens = 512,
            reasoningLevel = ReasoningLevel.OFF,
        )

        p.completeText(base.copy(toolChoice = "required"))
        val reqBody = AppJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("any", reqBody["tool_choice"]?.jsonObject?.get("type")?.jsonPrimitive?.content)

        p.completeText(base.copy(toolChoice = "none"))
        val noneBody = AppJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("none", noneBody["tool_choice"]?.jsonObject?.get("type")?.jsonPrimitive?.content)

        p.completeText(base.copy(toolChoice = "required", reasoningLevel = ReasoningLevel.HIGH))
        val thinkingFallbackBody = AppJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("auto", thinkingFallbackBody["tool_choice"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `parallel tool results merge into single user message with block cache_control`() = runTest {
        enqueueOk()
        val call1 = ToolCall("call_1", "read_file", """{"path":"a.txt"}""")
        val call2 = ToolCall("call_2", "web_search", """{"query":"muse"}""")
        val request = ChatRequest(
            messages = listOf(
                UIMessage(role = MessageRole.USER, content = "check both"),
                UIMessage(role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(call1, call2)),
                UIMessage(role = MessageRole.TOOL, content = "file content", toolCallId = call1.id),
                UIMessage(role = MessageRole.TOOL, content = "search content", toolCallId = call2.id),
            ),
            model = Model(id = "claude-sonnet-4-5", providerId = "test"),
            maxTokens = 512,
        )

        provider().completeText(request)

        val root = AppJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val messages = root["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, messages.size)
        assertEquals("user", messages[0]["role"]?.jsonPrimitive?.content)
        assertEquals("assistant", messages[1]["role"]?.jsonPrimitive?.content)
        assertEquals("user", messages[2]["role"]?.jsonPrimitive?.content)
        assertFalse(messages[2].containsKey("cache_control"))

        val mergedBlocks = (messages[2]["content"] as JsonArray).map { it.jsonObject }
        assertEquals(2, mergedBlocks.size)
        assertEquals("tool_result", mergedBlocks[0]["type"]?.jsonPrimitive?.content)
        assertEquals("call_1", mergedBlocks[0]["tool_use_id"]?.jsonPrimitive?.content)
        assertNull(mergedBlocks[0]["cache_control"])
        assertEquals("tool_result", mergedBlocks[1]["type"]?.jsonPrimitive?.content)
        assertEquals("call_2", mergedBlocks[1]["tool_use_id"]?.jsonPrimitive?.content)
        assertNotNull("合并后最后一个 tool_result 块应带 cache_control", mergedBlocks[1]["cache_control"])
        assertTrue(mergedBlocks[1]["cache_control"] is JsonObject)
    }
}
