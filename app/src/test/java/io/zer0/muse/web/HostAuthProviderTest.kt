package io.zer0.muse.web

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Date

/**
 * 生产侧的 JWT 闸门：[installHostAuthProvider] 装出来的 provider，走真 HTTP。
 *
 * 这里不再有任何替身 —— 之前 JVM 测试绕开 ktor 的 `jwt()` provider，是因为它在取凭证时
 * 要载被排除的 `com.auth0.jwk.JwkException`；现在生产侧也不用那个模块了，
 * 测试装的就是 WebServer 里同一段代码。
 *
 * 重点断言「401 而不是 500」：缺凭证、签名不符、过期，都必须是干净的 401，
 * 否则 `/ws` 握手在浏览器看来就是「连不上」。
 */
class HostAuthProviderTest {

    private val jwtSecret = "muse-auth-test-secret"
    private val algorithm: Algorithm = Algorithm.HMAC256(jwtSecret)

    private fun token(
        secret: String = jwtSecret,
        expiresAt: Long = System.currentTimeMillis() + 60_000L,
        subject: String = HOST_AUTH_SUBJECT,
    ): String = JWT.create()
        .withSubject(subject)
        .withIssuedAt(Date())
        .withExpiresAt(Date(expiresAt))
        .sign(Algorithm.HMAC256(secret))

    /** 与 WebServer 同构的最小服务：同一段 provider 代码，一条受保护 REST 路由 + 一条受保护 /ws。 */
    private fun withServer(block: (port: Int) -> Unit) {
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            install(WebSockets)
            install(Authentication) {
                installHostAuthProvider(providerName = AUTH_NAME, realm = "muse-auth-test", algorithm = algorithm)
            }
            routing {
                authenticate(AUTH_NAME) {
                    get("/api/sessions") { call.respondText("[]") }
                    webSocket("/ws") { /* 握手成功即可，不跑命令 */ }
                }
            }
        }
        server.start(wait = false)
        try {
            waitUntilReady(port)
            block(port)
        } finally {
            server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
        }
    }

    @Test
    fun `protected rest route without credentials is a clean 401`() {
        withServer { port ->
            val response = get(port, "/api/sessions")

            assertEquals("缺凭证必须 401，不能是 500: ${response.text()}", HttpURLConnection.HTTP_UNAUTHORIZED, response.status)
        }
    }

    @Test
    fun `websocket handshake without credentials is a clean 401`() {
        withServer { port ->
            assertEquals("握手缺凭证必须 401，不能是 500", HttpURLConnection.HTTP_UNAUTHORIZED, rawHandshake(port))
        }
    }

    @Test
    fun `valid token in the authorization header is let through`() {
        withServer { port ->
            val response = get(port, "/api/sessions", authorization = "Bearer ${token()}")

            assertEquals("合法 token 应放行: ${response.text()}", HttpURLConnection.HTTP_OK, response.status)
            assertEquals("[]", response.text())
        }
    }

    @Test
    fun `valid token in the cookie is let through`() {
        withServer { port ->
            val response = get(port, "/api/sessions", cookie = "${HOST_AUTH_COOKIE}=${token()}")

            assertEquals("Cookie 里的合法 token 同样应放行: ${response.text()}", HttpURLConnection.HTTP_OK, response.status)
        }
    }

    @Test
    fun `expired token is a 401`() {
        withServer { port ->
            val expired = token(expiresAt = System.currentTimeMillis() - 60_000L)

            assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, get(port, "/api/sessions", "Bearer $expired").status)
            assertEquals(
                "过期 token 放在 Cookie 里也该 401",
                HttpURLConnection.HTTP_UNAUTHORIZED,
                get(port, "/api/sessions", cookie = "${HOST_AUTH_COOKIE}=$expired").status,
            )
        }
    }

    @Test
    fun `token signed with another secret is a 401`() {
        withServer { port ->
            val forged = token(secret = "not-the-server-secret")

            assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, get(port, "/api/sessions", "Bearer $forged").status)
        }
    }

    @Test
    fun `token with a foreign subject is a 401`() {
        withServer { port ->
            val foreign = token(subject = "somebody-else")

            assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, get(port, "/api/sessions", "Bearer $foreign").status)
        }
    }

    @Test
    fun `websocket handshake with a valid token upgrades`() {
        withServer { port ->
            assertEquals(
                "合法 token 应完成握手",
                SWITCHING_PROTOCOLS,
                rawHandshake(port, authorization = "Bearer ${token()}"),
            )
        }
    }

    @Test
    fun `websocket handshake with the token in a cookie upgrades too`() {
        withServer { port ->
            assertEquals(
                "Cookie 也是合法凭证来源",
                SWITCHING_PROTOCOLS,
                rawHandshake(port, cookie = "${HOST_AUTH_COOKIE}=${token()}"),
            )
        }
    }

    /**
     * 用裸 socket 发一次真正的 WebSocket 升级请求，返回响应状态码。
     *
     * 不用 HttpURLConnection：它会默默丢掉 `Connection: Upgrade` 这类受限头，
     * 请求到了服务端已不是升级请求，WebSockets 插件先回 400，鉴权根本没跑 —— 那样
     * 就验不出「缺凭证是 401 而不是 500」。
     */
    private fun rawHandshake(port: Int, authorization: String? = null, cookie: String? = null): Int {
        val extra = buildString {
            if (authorization != null) append("Authorization: ").append(authorization).append("\r\n")
            if (cookie != null) append("Cookie: ").append(cookie).append("\r\n")
        }
        val request = buildString {
            append("GET /ws HTTP/1.1\r\n")
            append("Host: 127.0.0.1:").append(port).append("\r\n")
            append("Connection: Upgrade\r\n")
            append("Upgrade: websocket\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
            append(extra)
            append("\r\n")
        }
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }
            val statusLine = socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine().orEmpty()
            // 形如 `HTTP/1.1 401 Unauthorized`
            return statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: error("无法解析响应行: $statusLine")
        }
    }

    private class Response(val status: Int, val body: ByteArray) {
        fun text(): String = String(body, Charsets.UTF_8)
    }

    private fun get(
        port: Int,
        path: String,
        authorization: String? = null,
        cookie: String? = null,
    ): Response {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.requestMethod = "GET"
        authorization?.let { connection.setRequestProperty("Authorization", it) }
        cookie?.let { connection.setRequestProperty("Cookie", it) }
        val status = connection.responseCode
        val body = (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes() }
            ?: ByteArray(0)
        connection.disconnect()
        return Response(status, body)
    }

    private fun waitUntilReady(port: Int) {
        repeat(100) {
            val ready = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) } }.isSuccess
            if (ready) return
            Thread.sleep(10)
        }
        error("鉴权测试服务未启动")
    }

    private companion object {
        const val AUTH_NAME = "auth-jwt"

        /** 升级成功的状态码。 */
        const val SWITCHING_PROTOCOLS = 101
    }
}
