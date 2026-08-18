package io.zer0.ai.openai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatRequest
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.ProviderConfigStore
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.ai.core.ProviderType
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

class OpenAIProviderCustomTemplateTest {

    init {
        // JVM 单测没有 android.util.Log 实现,关闭全局日志避免触发 not-mocked 异常
        Logger.enabled = false
    }

    @Test
    fun customTemplateResponsePathAndHeaders() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val bodies = CopyOnWriteArrayList<String>()
        val customHeaders = CopyOnWriteArrayList<String>()
        server.createContext("/v1/chat/completions") { exchange ->
            bodies.add(exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8))
            customHeaders.add(exchange.requestHeaders.getFirst("X-Custom") ?: "")
            respond(
                exchange,
                200,
                """{"data":{"answer":"custom answer"}}""",
                "application/json",
            )
        }
        server.start()
        try {
            val provider = OpenAIProvider(
                ProviderConfig(
                    id = "custom",
                    displayName = "Custom",
                    type = ProviderType.OPENAI,
                    baseUrl = "http://127.0.0.1:${server.address.port}/v1",
                    apiKey = "key",
                    specific = ProviderSpecificConfig.Custom(
                        requestTemplate = """{"model":"{{model}}","stream":{{stream}},"input":"{{prompt}}","messages":{{messages}}}""",
                        responsePath = "$.data.answer",
                        customHeaders = mapOf("X-Custom" to "yes"),
                    ),
                ),
            )
            val completion = provider.completeText(
                ChatRequest(
                    messages = listOf(UIMessage(role = MessageRole.USER, content = "hi")),
                    model = Model(id = "custom-model", providerId = "custom"),
                    mode = ChatRequestMode.UTILITY,
                ),
            )
            assertEquals("custom answer", completion.text)
            val sent = bodies.first()
            assertTrue(sent.contains("\"model\":\"custom-model\""))
            assertTrue(sent.contains("\"stream\":false"))
            assertTrue(sent.contains("\"input\":\"hi\""))
            assertTrue(sent.contains("\"messages\""))
            assertEquals("yes", customHeaders.first())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun customStreamResponsePathExtractsDeltas() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            respond(
                exchange,
                200,
                "data: {\"delta\":{\"text\":\"hello \"}}\n\n" +
                    "data: {\"delta\":{\"text\":\"world from \"}}\n\n" +
                    "data: {\"delta\":{\"text\":\"custom stream\"}}\n\n" +
                    "data: [DONE]\n\n",
                "text/event-stream",
            )
        }
        server.start()
        try {
            val provider = OpenAIProvider(
                ProviderConfig(
                    id = "custom-stream",
                    displayName = "Custom Stream",
                    type = ProviderType.OPENAI,
                    baseUrl = "http://127.0.0.1:${server.address.port}/v1",
                    apiKey = "key",
                    specific = ProviderSpecificConfig.Custom(
                        requestTemplate = """{"model":"{{model}}","stream":{{stream}},"messages":{{messages}}}""",
                        streamResponsePath = "$.delta.text",
                    ),
                ),
            )
            val events = withTimeout(10_000) {
                provider.streamChat(
                    ChatRequest(
                        messages = listOf(UIMessage(role = MessageRole.USER, content = "hi")),
                        model = Model(id = "custom-model", providerId = "custom"),
                    ),
                ).toList()
            }
            val text = events.filterIsInstance<ChatStreamEvent.ContentDelta>()
                .joinToString("") { it.delta }
            assertEquals("hello world from custom stream", text)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun customChatCompletionsPathIsUsedForChatRequests() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requestPaths = CopyOnWriteArrayList<String>()
        server.createContext("/v1/custom/chat") { exchange ->
            requestPaths.add(exchange.requestURI.path)
            respond(
                exchange,
                200,
                """{"choices":[{"message":{"content":"custom path answer"}}]}""",
                "application/json",
            )
        }
        server.start()
        try {
            val provider = OpenAIProvider(
                ProviderConfig(
                    id = "custom-path",
                    displayName = "Custom Path",
                    type = ProviderType.OPENAI,
                    baseUrl = "http://127.0.0.1:${server.address.port}/v1",
                    apiKey = "key",
                    specific = ProviderSpecificConfig.Custom(
                        chatCompletionsPath = "/custom/chat",
                    ),
                ),
            )
            val completion = provider.completeText(
                ChatRequest(
                    messages = listOf(UIMessage(role = MessageRole.USER, content = "hi")),
                    model = Model(id = "custom-model", providerId = "custom-path"),
                    mode = ChatRequestMode.UTILITY,
                ),
            )
            assertEquals("custom path answer", completion.text)
            assertEquals(listOf("/v1/custom/chat"), requestPaths)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun resumeFromTextAppendsAssistantMessage() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val bodies = CopyOnWriteArrayList<String>()
        server.createContext("/v1/chat/completions") { exchange ->
            bodies.add(exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8))
            respond(
                exchange,
                200,
                "data: {\"choices\":[{\"delta\":{\"content\":\"continues from here with enough text to avoid fallback\"}}]}\n\n" +
                    "data: [DONE]\n\n",
                "text/event-stream",
            )
        }
        server.start()
        try {
            val config = ProviderConfig(
                id = "resume",
                displayName = "Resume",
                type = ProviderType.OPENAI,
                baseUrl = "http://127.0.0.1:${server.address.port}/v1",
                apiKey = "key",
            )
            val service = ChatService(FixedConfigStore(config))
            val events = withTimeout(10_000) {
                service.streamChat(
                    messages = listOf(UIMessage(role = MessageRole.USER, content = "hi")),
                    model = Model(id = "resume-model", providerId = "resume"),
                    providerConfig = config,
                    resumeFromText = "partial answer",
                ).toList()
            }
            assertTrue(events.filterIsInstance<ChatStreamEvent.ContentDelta>().isNotEmpty())
            val body = bodies.first()
            assertTrue(body.contains("\"role\":\"assistant\""))
            assertTrue(body.contains("partial answer"))
        } finally {
            server.stop(0)
        }
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String, contentType: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }
}

private class FixedConfigStore(
    private val config: ProviderConfig,
) : ProviderConfigStore {
    override suspend fun get(): ProviderConfig = config
}
