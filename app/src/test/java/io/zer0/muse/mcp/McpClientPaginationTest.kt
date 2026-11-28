package io.zer0.muse.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.zer0.common.AppJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class McpClientPaginationTest {

    @Test
    fun allListMethodsFollowCursorsAndDeduplicateEntries() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val seen = mutableListOf<String>()
        server.createContext("/mcp") { exchange ->
            val request = readRequest(exchange)
            val method = request["method"]?.jsonPrimitive?.content
            val id = request["id"]?.jsonPrimitive?.content
            val cursor = request["params"]?.jsonObject?.get("cursor")?.jsonPrimitive?.content
            synchronized(seen) { seen += "$method:$cursor" }
            when (method) {
                "initialize" -> respond(exchange, 200, initialize(id))
                "notifications/initialized" -> respond(exchange, 202, "")
                "tools/list" -> if (cursor == null) {
                    respond(exchange, 200, """{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"one"}],"nextCursor":"next"}}""")
                } else {
                    respond(exchange, 200, """{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"one"},{"name":"two"}]}}""")
                }
                "resources/list" -> if (cursor == null) {
                    respond(exchange, 200, """{"jsonrpc":"2.0","id":$id,"result":{"resources":[{"uri":"a"}],"nextCursor":"next"}}""")
                } else {
                    respond(exchange, 200, """{"jsonrpc":"2.0","id":$id,"result":{"resources":[{"uri":"b"}]}}""")
                }
                "prompts/list" -> if (cursor == null) {
                    respond(exchange, 200, """{"jsonrpc":"2.0","id":$id,"result":{"prompts":[{"name":"one"}],"nextCursor":"next"}}""")
                } else {
                    respond(exchange, 200, """{"jsonrpc":"2.0","id":$id,"result":{"prompts":[{"name":"two"}]}}""")
                }
                else -> respond(exchange, 400, "unexpected method")
            }
        }
        server.executor = executor
        server.start()
        val client = client(server)
        try {
            client.start()
            withTimeout(5_000L) { client.state.first { it == McpConnectionState.CONNECTED } }
            assertTrue(client.serverCapabilities.value?.containsKey("tools") == true)
            assertEquals(listOf("one", "two"), client.listToolsOrNull()?.map { it.name })
            assertEquals(listOf("a", "b"), client.listResourcesOrNull()?.map { it.uri })
            assertEquals(listOf("one", "two"), client.listPromptsOrNull()?.map { it.name })
            assertEquals(3, seen.count { it.endsWith(":next") })
        } finally {
            client.close()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun streamableSseDispatchesEventsAndReturnsMatchingResponseBeforeStreamCloses() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val releaseStream = CountDownLatch(1)
        val streamOpened = CountDownLatch(1)
        val notificationCount = AtomicInteger()
        server.createContext("/mcp") { exchange ->
            val request = readRequest(exchange)
            val method = request["method"]?.jsonPrimitive?.content
            val id = request["id"]?.jsonPrimitive?.content
            when (method) {
                "initialize" -> respond(exchange, 200, initialize(id))
                "notifications/initialized" -> respond(exchange, 202, "")
                "tools/list" -> {
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                        writer.appendLine("data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}")
                        writer.appendLine()
                        writer.appendLine("data: {\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"ping\"}")
                        writer.appendLine()
                        writer.appendLine("data: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"tools\":[{\"name\":\"streamed\"}]}}")
                        writer.appendLine()
                        writer.flush()
                        streamOpened.countDown()
                        releaseStream.await(5, TimeUnit.SECONDS)
                    }
                }
                else -> respond(exchange, 202, "")
            }
        }
        server.executor = executor
        server.start()
        val client = client(server)
        client.onToolsListChanged = { notificationCount.incrementAndGet() }
        try {
            client.start()
            withTimeout(5_000L) { client.state.first { it == McpConnectionState.CONNECTED } }
            val tools = withTimeout(2_000L) { client.listToolsOrNull() }
            assertEquals("streamed", tools?.singleOrNull()?.name)
            assertTrue(streamOpened.await(1, TimeUnit.SECONDS))
            assertEquals(1, notificationCount.get())
        } finally {
            releaseStream.countDown()
            client.close()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun client(server: HttpServer) = McpClient(
        McpServerConfig(
            id = "pagination-test",
            name = "Pagination Test",
            transportType = McpTransportType.STREAMABLE_HTTP,
            url = "http://127.0.0.1:${server.address.port}/mcp",
            autoReconnect = false,
            requestTimeoutMs = 4_000L,
        ),
    )

    private fun initialize(id: String?): String =
        """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-03-26","serverInfo":{"name":"test","version":"1"},"capabilities":{"tools":{},"resources":{},"prompts":{}}}}"""

    private fun readRequest(exchange: HttpExchange) = AppJson.parseToJsonElement(
        exchange.requestBody.use { it.readBytes().toString(StandardCharsets.UTF_8) },
    ).jsonObject

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        exchange.responseHeaders.add("Content-Type", "application/json")
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
