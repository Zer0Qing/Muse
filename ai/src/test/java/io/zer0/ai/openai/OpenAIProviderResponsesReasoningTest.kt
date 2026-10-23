package io.zer0.ai.openai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.zer0.ai.core.ChatRequest
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.ai.core.ProviderType
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

class OpenAIProviderResponsesReasoningTest {

    init {
        Logger.enabled = false
    }

    @Test
    fun `responses OFF omits reasoning config instead of requesting minimal effort`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val bodies = CopyOnWriteArrayList<String>()
        server.createContext("/v1/responses") { exchange ->
            bodies += exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            respond(
                exchange,
                200,
                """{"output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]}""",
            )
        }
        server.start()
        try {
            val provider = OpenAIProvider(
                ProviderConfig(
                    id = "responses-reasoning-test",
                    displayName = "Responses reasoning test",
                    type = ProviderType.OPENAI,
                    baseUrl = "http://127.0.0.1:${server.address.port}/v1",
                    apiKey = "key",
                    specific = ProviderSpecificConfig.OpenAI(useResponseApi = true),
                ),
            )

            provider.completeText(
                ChatRequest(
                    messages = listOf(UIMessage(role = MessageRole.USER, content = "hello")),
                    model = Model(id = "gpt-5", providerId = "responses-reasoning-test"),
                    reasoningLevel = ReasoningLevel.OFF,
                ),
            )

            val root = AppJson.parseToJsonElement(bodies.single()).jsonObject
            assertFalse("OFF must not serialize Responses reasoning config", root.containsKey("reasoning"))
            assertTrue("request should still be accepted", root["input"] != null)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `chat completions keeps explicit reasoning effort for enabled levels`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val bodies = CopyOnWriteArrayList<String>()
        server.createContext("/v1/chat/completions") { exchange ->
            bodies += exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            respond(
                exchange,
                200,
                """{"choices":[{"message":{"content":"ok"}}]}""",
            )
        }
        server.start()
        try {
            val provider = OpenAIProvider(
                ProviderConfig(
                    id = "chat-reasoning-test",
                    displayName = "Chat reasoning test",
                    type = ProviderType.OPENAI,
                    baseUrl = "http://127.0.0.1:${server.address.port}/v1",
                    apiKey = "key",
                ),
            )

            provider.completeText(
                ChatRequest(
                    messages = listOf(UIMessage(role = MessageRole.USER, content = "hello")),
                    model = Model(id = "gpt-5", providerId = "chat-reasoning-test"),
                    reasoningLevel = ReasoningLevel.LOW,
                ),
            )

            val root = AppJson.parseToJsonElement(bodies.single()).jsonObject
            assertEquals("low", root["reasoning_effort"]?.toString()?.trim('"'))
        } finally {
            server.stop(0)
        }
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }
}
