package io.zer0.muse.mcp

import android.content.Context
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.zer0.common.AppJson
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolSchemaRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class McpFirstCallTest {
    @Test
    fun `saved service loads a tool and only the selected assistant can discover it`() = verifyFirstCall(requiresAuth = false)

    @Test
    fun `OAuth challenge callback and assistant binding reach the first remote call`() = verifyFirstCall(requiresAuth = true)

    private fun verifyFirstCall(requiresAuth: Boolean) = runBlocking {
        val releaseList = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val origin = "http://127.0.0.1:" + server.address.port
        server.createContext("/resource-metadata") { exchange ->
            reply(exchange, 200, """{"resource":"$origin/mcp","authorization_servers":["$origin"]}""")
        }
        server.createContext("/.well-known/oauth-authorization-server") { exchange ->
            reply(
                exchange,
                200,
                """{"issuer":"$origin","authorization_endpoint":"$origin/authorize","token_endpoint":"$origin/token","registration_endpoint":"$origin/register","code_challenge_methods_supported":["S256"]}""",
            )
        }
        server.createContext("/register") { exchange -> reply(exchange, 201, """{"client_id":"fixture-client"}""") }
        server.createContext("/token") { exchange ->
            val request = exchange.requestBody.bufferedReader().use { it.readText() }
            if ("code=fixture-code" in request && "code_verifier=" in request) {
                reply(exchange, 200, """{"access_token":"fixture-token","token_type":"Bearer","expires_in":3600}""")
            } else {
                reply(exchange, 400, """{"error":"invalid_request"}""")
            }
        }
        server.createContext("/mcp") { exchange ->
            if (requiresAuth && exchange.requestHeaders.getFirst("Authorization") != "Bearer fixture-token") {
                exchange.responseHeaders.add(
                    "WWW-Authenticate",
                    """Bearer resource_metadata="$origin/resource-metadata", scope="tools:read"""",
                )
                reply(exchange, 401, """{"error":"authorization_required"}""")
                return@createContext
            }
            val request = AppJson.parseToJsonElement(
                exchange.requestBody.bufferedReader().use { it.readText() },
            ).jsonObject
            val id = request["id"]
            val payload = when (request["method"]?.jsonPrimitive?.content) {
                "initialize" -> """{"protocolVersion":"2025-03-26","capabilities":{"tools":{}},"serverInfo":{"name":"fixture","version":"1"}}"""
                "tools/list" -> {
                    check(releaseList.await(8, TimeUnit.SECONDS))
                    """{"tools":[{"name":"echo","description":"Echo a message","inputSchema":{"type":"object","properties":{"message":{"type":"string"}}}}]}"""
                }
                "tools/call" -> {
                    val args = request["params"]?.jsonObject?.get("arguments")
                    val message = args?.jsonObject?.get("message")
                    if (message is kotlinx.serialization.json.JsonPrimitive && message.isString) {
                        """{"content":[{"type":"text","text":$message}]}"""
                    } else {
                        """{"structuredContent":{"echo":$message}}"""
                    }
                }
                else -> null
            }
            exchange.responseHeaders.add("Content-Type", "application/json")
            if (payload == null) {
                exchange.sendResponseHeaders(202, -1)
                exchange.close()
            } else {
                val bytes = """{"jsonrpc":"2.0","id":$id,"result":$payload}""".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        val configs = MutableStateFlow<List<McpServerConfig>>(emptyList())
        var token: McpTokenInfo? = null
        val settings = mockk<SettingsRepository> {
            every { mcpServersFlow } returns configs
            coEvery { saveMcpServers(any()) } answers { configs.value = firstArg() }
            coEvery { saveMcpToken("fixture", any()) } answers { token = secondArg() }
            coEvery { getMcpToken("fixture") } answers { token }
        }
        val context = mockk<Context>(relaxed = true)
        val default = AssistantEntity(id = "default", name = "Default")
        var selected = AssistantEntity(id = "selected", name = "Selected")
        val assistants = mockk<AssistantRepository> {
            coEvery { getAll() } answers { listOf(default, selected) }
            every { parseMcpServerIds(any()) } answers {
                AppJson.decodeFromString<List<String>>(firstArg<AssistantEntity>().mcpServerIdsJson)
            }
            every { serializeStringList(any()) } answers {
                AppJson.encodeToString(firstArg<List<String>>())
            }
            coEvery { updateMcpServerIds("selected", any()) } answers {
                selected = selected.copy(mcpServerIdsJson = secondArg())
            }
        }
        val tools = ToolRegistry(context, mockk(relaxed = true))
        val registry = McpRegistry(tools, settings, context, assistants)
        val config = McpServerConfig(
            id = "fixture",
            name = "Fixture",
            url = "$origin/mcp",
            autoReconnect = false,
        )
        try {
            assertTrue(registry.addServer(config))
            if (requiresAuth) {
                withTimeout(5_000) {
                    registry.serverStatuses.first { it[config.id]?.connection == McpConnectionState.NEEDS_AUTH }
                }
                val authUrl = checkNotNull(registry.authorize(config.id)).toHttpUrl()
                assertEquals("fixture-client", authUrl.queryParameter("client_id"))
                assertEquals("tools:read", authUrl.queryParameter("scope"))
                val state = checkNotNull(authUrl.queryParameter("state"))
                assertFalse(registry.completeOAuthCallback("io.zer0.muse://oauth/callback?state=wrong&code=fixture-code"))
                assertTrue(registry.completeOAuthCallback("io.zer0.muse://oauth/callback?state=$state&code=fixture-code"))
            }
            withTimeout(5_000) {
                registry.serverStatuses.first { it[config.id]?.connection == McpConnectionState.CONNECTED }
            }
            assertEquals(null, registry.serverStatuses.value[config.id]?.tools)
            releaseList.countDown()
            assertTrue(registry.awaitToolsForServers(setOf(config.id)))
            registry.setAssistantBindings(config.id, setOf(selected.id))
            val toolName = "mcp_fixture__echo"
            val definitions = tools.listToolsAsToolDefinitions()
            assertFalse(ToolSchemaRules.selectLocalTools(definitions, ToolSchemaRules.criteriaFor(default)).any { it.name == toolName })
            assertTrue(ToolSchemaRules.selectLocalTools(definitions, ToolSchemaRules.criteriaFor(selected)).any { it.name == toolName })
            assertEquals("hello", tools.executeFromJson(toolName, """{"message":"hello"}"""))
            assertEquals(
                """{"echo":{"tags":[null,7,true]}}""",
                tools.executeFromJson(toolName, """{"message":{"tags":[null,7,true]}}"""),
            )
        } finally {
            releaseList.countDown()
            registry.shutdown()
            server.stop(0)
        }
    }

    private fun reply(exchange: HttpExchange, status: Int, payload: String) {
        exchange.responseHeaders.add("Content-Type", "application/json")
        val bytes = payload.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
