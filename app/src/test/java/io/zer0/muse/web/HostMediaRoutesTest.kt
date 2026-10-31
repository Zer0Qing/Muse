package io.zer0.muse.web

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.util.Date

/**
 * `/api/media/{token}` 的读取行为：鉴权、正确的 Content-Type、越界与缺失一律 404。
 *
 * 这里用真 HTTP 请求打真 Ktor 服务，验的是「路由装在 `authenticate` 块里」这条约束：
 * 不带凭证的请求应该被鉴权管道拦在路由外面，连处理函数都进不去。
 *
 * 闸门装的就是生产那一份 [installHostAuthProvider]（bearer provider + java-jwt HMAC 校验），
 * 不是替身。
 */
class HostMediaRoutesTest {

    private val workDir = Files.createTempDirectory("host-media-routes").toFile()
    private val imageDir = File(workDir, "muse_images").apply { mkdirs() }
    private val voiceDir = File(workDir, "voice_messages").apply { mkdirs() }
    private val outsideDir = File(workDir, "shared_prefs").apply { mkdirs() }
    private val jwtSecret = "muse-media-test-secret"
    private val algorithm: Algorithm = Algorithm.HMAC256(jwtSecret)

    /** 与 WebServer.issueJwt 同口径:subject=muse-web、24h 有效期内。 */
    private fun signedToken(): String = JWT.create()
        .withSubject(HOST_AUTH_SUBJECT)
        .withIssuedAt(Date())
        .withExpiresAt(Date(System.currentTimeMillis() + 60_000L))
        .sign(algorithm)

    /** 起一个真 Ktor 服务:媒体路由装在 `authenticate` 块里,与 WebServer 的摆放同一层。 */
    private fun withServer(store: HostMediaStore, block: (port: Int, authorization: String) -> Unit) {
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            install(Authentication) {
                installHostAuthProvider(providerName = AUTH_NAME, realm = "muse-media-test", algorithm = algorithm)
            }
            routing {
                authenticate(AUTH_NAME) {
                    installHostMediaRoutes(store)
                }
            }
        }
        server.start(wait = false)
        try {
            waitUntilReady(port)
            block(port, "Bearer ${signedToken()}")
        } finally {
            server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
        }
    }

    @Test
    fun `request without jwt never reaches the media handler`() {
        val store = HostMediaStore(imageDir, voiceDir)
        val token = store.tokenForFile(File(imageDir, "a.png").also { it.writeBytes(PNG_BYTES) }.absolutePath)
        assertNotNull(token)

        withServer(store) { port, _ ->
            val response = get(port, mediaPath(token))

            assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, response.status)
            assertTrue("未鉴权不该把图片字节带出去: ${response.text()}", response.body.isEmpty())
        }
    }

    @Test
    fun `forged jwt is refused too`() {
        val store = HostMediaStore(imageDir, voiceDir)
        val token = store.tokenForFile(File(imageDir, "b.png").also { it.writeBytes(PNG_BYTES) }.absolutePath)

        withServer(store) { port, _ ->
            val response = get(port, mediaPath(token), "Bearer not.a.real.token")

            assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, response.status)
            assertTrue(response.body.isEmpty())
        }
    }

    @Test
    fun `registered token returns the bytes with the detected content type`() {
        val store = HostMediaStore(imageDir, voiceDir)
        val file = File(imageDir, "gen_0_dead.bin").apply { writeBytes(PNG_BYTES) }
        val token = store.tokenForFile("file://${file.absolutePath}")
        assertNotNull(token)

        withServer(store) { port, authorization ->
            val response = get(port, mediaPath(token), authorization)

            assertEquals("body=${response.text()}", HttpURLConnection.HTTP_OK, response.status)
            assertTrue(
                "Content-Type 应由文件头判定,实际: ${response.contentType}",
                response.contentType.orEmpty().startsWith("image/png"),
            )
            assertNotNull("应带 Cache-Control", response.cacheControl)
            assertTrue("响应头应允许浏览器私有缓存: ${response.cacheControl}", response.cacheControl!!.contains("private"))
            assertTrue(response.cacheControl!!.contains("max-age"))
            assertEquals(PNG_BYTES.size, response.body.size)
        }
    }

    @Test
    fun `voice file keeps its container type`() {
        val store = HostMediaStore(imageDir, voiceDir)
        val file = File(voiceDir, "say.m4a").apply { writeBytes(M4A_BYTES) }
        val token = store.tokenForFile(file.absolutePath)

        withServer(store) { port, authorization ->
            val response = get(port, mediaPath(token), authorization)

            assertEquals("body=${response.text()}", HttpURLConnection.HTTP_OK, response.status)
            assertEquals("audio/mp4", response.contentType?.substringBefore(';'))
        }
    }

    @Test
    fun `mapping that points outside the media directories is a not found`() {
        val store = HostMediaStore(imageDir, voiceDir)
        val secret = File(outsideDir, "prefs.xml").apply { writeBytes("<prefs><token>secret</token></prefs>".toByteArray()) }
        // 预置一条绕过登记校验的映射:读取侧必须自己拦住。
        store.seedMapping("escaped", secret.absolutePath)

        withServer(store) { port, authorization ->
            val response = get(port, MEDIA_PATH + "escaped", authorization)

            assertEquals("body=${response.text()}", HttpURLConnection.HTTP_NOT_FOUND, response.status)
            assertTrue("越界请求不能把文件内容带出去", response.body.isEmpty())
        }
    }

    @Test
    fun `cleaned up file is a not found instead of a server error`() {
        val store = HostMediaStore(imageDir, voiceDir)
        val file = File(imageDir, "gone.png").apply { writeBytes(PNG_BYTES) }
        val token = store.tokenForFile(file.absolutePath)
        assertTrue(file.delete())

        withServer(store) { port, authorization ->
            assertEquals(HttpURLConnection.HTTP_NOT_FOUND, get(port, mediaPath(token), authorization).status)
            assertEquals(HttpURLConnection.HTTP_NOT_FOUND, get(port, "$MEDIA_PATH${"0".repeat(32)}", authorization).status)
        }
    }

    private fun mediaPath(token: String?): String = "$MEDIA_PATH${token.orEmpty()}"

    private class Response(val status: Int, val contentType: String?, val cacheControl: String?, val body: ByteArray) {
        fun text(): String = String(body, Charsets.UTF_8)
    }

    private fun get(port: Int, path: String, authorization: String? = null): Response {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.requestMethod = "GET"
        authorization?.let { connection.setRequestProperty("Authorization", it) }
        val status = connection.responseCode
        val body = (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes() }
            ?: ByteArray(0)
        val result = Response(
            status = status,
            contentType = connection.contentType,
            cacheControl = connection.getHeaderField("Cache-Control"),
            body = body,
        )
        connection.disconnect()
        return result
    }

    private fun waitUntilReady(port: Int) {
        repeat(100) {
            val ready = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) } }.isSuccess
            if (ready) return
            Thread.sleep(10)
        }
        error("媒体路由测试服务未启动")
    }

    private companion object {
        const val AUTH_NAME = "auth-jwt"
        const val MEDIA_PATH = "/api/media/"

        val PNG_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(24)
        val M4A_BYTES = byteArrayOf(0, 0, 0, 0x20.toByte()) + "ftyp".toByteArray() + "M4A ".toByteArray() + ByteArray(16)
    }
}
