package io.zer0.muse.doc

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.zer0.common.Result
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

class CloudDocumentParserTest {

    private val parser = CloudDocumentParser(
        httpClient = OkHttpClient.Builder().build(),
        pollIntervalMs = 5,
        maxPollAttempts = 3,
    )

    @Test
    fun extractTaskIdFindsNestedTaskId() {
        assertEquals(
            "abc-123",
            parser.extractTaskId("""{"data":{"batchId":"abc-123","state":"pending"}}"""),
        )
    }

    @Test
    fun extractResultTextPrefersMarkdownFields() {
        val body = """{"code":0,"data":{"full_ocr":{"md_content":"# 公式\n\n${'$'}${'$'}E=mc^2${'$'}${'$'}"}}}"""
        val text = parser.extractResultText(body)
        assertTrue(text != null && text.contains("E=mc"))
    }

    @Test
    fun extractPlainTextBodyDirectly() {
        assertEquals("plain markdown text", parser.extractResultText("plain markdown text"))
    }

    @Test
    fun resolveUploadAndPollUrls() {
        assertEquals("https://x/api/file_parse", parser.resolveUploadUrl("https://x/api", true))
        assertEquals("https://x/api", parser.resolveUploadUrl("https://x/api", false))
        assertEquals(
            "https://x/api/get_task_results/t-1",
            parser.buildPollUrl("https://x/api/file_parse", "t-1", true),
        )
        assertEquals(
            "https://x/api/parse/t-1",
            parser.buildPollUrl("https://x/api/parse", "t-1", false),
        )
    }

    @Test
    fun uploadAndPollMineruFlow() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val authHeaders = CopyOnWriteArrayList<String>()
        server.createContext("/file_parse") { exchange ->
            authHeaders.add(exchange.requestHeaders.getFirst("Authorization") ?: "")
            respond(
                exchange,
                200,
                """{"task_id":"abc-123"}""",
                "application/json",
            )
        }
        server.createContext("/get_task_results/abc-123") { exchange ->
            respond(
                exchange,
                200,
                """{"state":"done","data":{"full_ocr":{"md_content":"# 公式\n\n${'$'}${'$'}E=mc^2${'$'}${'$'}"}}}""",
                "application/json",
            )
        }
        server.start()
        try {
            val result = parser.parse(
                bytes = "pdf-bytes".toByteArray(),
                fileName = "paper.pdf",
                endpoint = "http://127.0.0.1:${server.address.port}/file_parse",
                token = "token-123",
                mineruMode = true,
            )
            assertTrue(result.isSuccess)
            assertTrue(result.getOrNull()!!.contains("E=mc"))
            assertEquals("Bearer token-123", authHeaders.firstOrNull())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun uploadReturnsErrorOnHttpFailure() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file_parse") { exchange ->
            respond(exchange, 500, "boom", "text/plain")
        }
        server.start()
        try {
            val result = parser.parse(
                bytes = "pdf-bytes".toByteArray(),
                fileName = "paper.pdf",
                endpoint = "http://127.0.0.1:${server.address.port}/file_parse",
                mineruMode = true,
            )
            assertTrue(result is Result.Error)
            assertTrue((result as Result.Error).message.contains("HTTP 500"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun pollTimesOutWhenTaskNeverFinishes() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file_parse") { exchange ->
            respond(exchange, 200, """{"task_id":"t-1"}""", "application/json")
        }
        server.createContext("/get_task_results/t-1") { exchange ->
            respond(exchange, 200, """{"status":"running"}""", "application/json")
        }
        server.start()
        try {
            val result = parser.parse(
                bytes = "pdf-bytes".toByteArray(),
                fileName = "paper.pdf",
                endpoint = "http://127.0.0.1:${server.address.port}/file_parse",
                mineruMode = true,
            )
            assertTrue(result is Result.Error)
            assertTrue((result as Result.Error).message.contains("超时"))
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
