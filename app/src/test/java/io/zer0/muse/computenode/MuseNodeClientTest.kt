package io.zer0.muse.computenode

import io.zer0.muse.data.computenode.RemoteComputeNodeEntity
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * MuseNodeClient 的协议契约测试。
 *
 * 用 MockWebServer 顶替 muse-node：覆盖 Bearer 头、401 映射、apt 审批映射、
 * outbox 拉取与 ack 请求体、配对码解析，以及 /exec 的 WebSocket 流式帧。
 * 所有 Token 都是占位字符串，不包含任何真实凭据。
 */
class MuseNodeClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: MuseNodeClient

    private val token = "test-token-placeholder"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = MuseNodeClient(
            baseClient = OkHttpClient.Builder()
                .readTimeout(5, TimeUnit.SECONDS)
                .build(),
            endpointOverride = NodeEndpointOverride(
                baseUrl = server.url("/").toString().trimEnd('/'),
                bearerToken = token,
            ),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun healthRequestCarriesBearerTokenAndParsesPayload() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"ok":true,"nodeVersion":"v22.1.0","pythonVersion":"3.12.2","cpuPercent":12.5,
                 "memUsedMb":420,"memTotalMb":2048,"diskFreeMb":12000,
                 "packs":["core","browser"],"runningJobs":1,"outboxCount":2}
                """.trimIndent(),
            ),
        )

        val health = runBlocking { client.health(node()) }

        assertEquals("v22.1.0", health.nodeVersion)
        assertEquals(12.5, health.cpuPercent, 0.001)
        assertEquals(2048L, health.memTotalMb)
        assertEquals(listOf("core", "browser"), health.packs)
        val request = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(request)
        request ?: return
        assertEquals("Bearer " + token, request.getHeader("Authorization"))
        assertEquals("/health", request.path)
    }

    @Test
    fun unauthorizedResponseMapsToAuthError() {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"ok":false,"code":"AUTH_REQUIRED","message":"missing or wrong token"}""",
            ),
        )

        val error = try {
            runBlocking { client.health(node()) }
            null
        } catch (e: MuseNodeException) {
            e
        }

        assertNotNull(error)
        error ?: return
        assertEquals(MuseNodeError.AUTH_REQUIRED, error.error)
        assertEquals("AUTH_REQUIRED", error.serverCode)
        assertEquals(401, error.httpStatus)
    }

    @Test
    fun aptWithoutSystemApprovalMapsToApprovalRequired() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"ok":false,"code":"APPROVAL_REQUIRED","message":"需要用户审批系统级安装"}""",
            ),
        )

        val result = runBlocking {
            client.envEnsure(
                node(),
                EnvEnsureRequest(
                    ecosystem = EnvEcosystem.APT,
                    packages = listOf("curl"),
                ),
            )
        }

        when (result) {
            is EnvEnsureResult.ApprovalRequired -> assertEquals("需要用户审批系统级安装", result.message)
            is EnvEnsureResult.Installed -> fail("apt without allowSystem must require approval")
            is EnvEnsureResult.Failed -> fail("apt without allowSystem must map to approval, not failure")
        }
        val request = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(request)
        request ?: return
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"ecosystem\":\"apt\""))
        assertTrue(body.contains("\"allowSystem\":false"))
    }

    @Test
    fun outboxListAndAckRoundTrip() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"ok":true,"count":2,"serverTime":105,"events":[
                  {"eventId":"evt-1","createdAt":100,"type":"watcher",
                   "watcherId":"w1","status":"changed","output":"value"},
                  {"eventId":"evt-2","createdAt":101,"type":"exec",
                   "runId":"run-1","status":"completed","exitCode":0,"outputTail":"ok"}
                ]}
                """.trimIndent(),
            ),
        )

        val page = runBlocking { client.listOutbox(node(), since = 50L) }

        assertEquals(2, page.events.size)
        assertEquals("evt-1", page.events[0].eventId)
        assertEquals("watcher", page.events[0].type)
        assertEquals("run-1", page.events[1].runId)
        val listRequest = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(listRequest)
        listRequest ?: return
        assertEquals("50", listRequest.requestUrl?.queryParameter("since"))
        assertEquals("Bearer " + token, listRequest.getHeader("Authorization"))

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"acked":2}"""))
        val acked = runBlocking { client.ackOutbox(node(), listOf("evt-1", "evt-2")) }

        assertEquals(2, acked)
        val ackRequest = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(ackRequest)
        ackRequest ?: return
        assertEquals("/outbox/ack", ackRequest.path)
        val ackBody = ackRequest.body.readUtf8()
        assertTrue(ackBody.contains("\"eventIds\":[\"evt-1\",\"evt-2\"]"))
    }

    @Test
    fun pairingPayloadParsesBase64UrlAndNormalizesFingerprint() {
        val fingerprint = "ab".repeat(32)
        val pairingJson = """
            {"v":1,"host":"example.com","port":18765,
             "token":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
             "fp":"SHA256:$fingerprint","name":"my-linux"}
        """.trimIndent()
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(pairingJson.toByteArray(Charsets.UTF_8))

        val result = NodePairingPayload.parse(encoded)

        when (result) {
            is NodePairingResult.Success -> {
                val payload = result.payload
                assertEquals(1, payload.v)
                assertEquals("example.com", payload.host)
                assertEquals(18765, payload.port)
                assertEquals(fingerprint, payload.fingerprintHex())
                assertEquals("my-linux", payload.name)
                assertFalse(payload.toString().contains(payload.token))
            }
            is NodePairingResult.Failure -> fail("expected success: " + result.reason)
        }
    }

    @Test
    fun pairingPayloadRejectsInvalidFingerprint() {
        val pairingJson = """
            {"v":1,"host":"example.com","port":18765,
             "token":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
             "fp":"SHA256:not-a-fingerprint","name":"my-linux"}
        """.trimIndent()
        val encoded = Base64.getUrlEncoder().encodeToString(pairingJson.toByteArray(Charsets.UTF_8))

        val result = NodePairingPayload.parse(encoded)

        when (result) {
            is NodePairingResult.Success -> fail("invalid fingerprint must be rejected")
            is NodePairingResult.Failure -> assertTrue(result.reason.contains("fingerprint"))
        }
    }

    @Test
    fun execStreamDeliversStdoutAndExitOverWebSocket() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"ok":true,"runId":"run-1","status":"running"}""",
            ),
        )
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type":"stdout","text":"hello","ts":100}""")
                    webSocket.send("""{"type":"exit","text":"","exitCode":0,"ts":101}""")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }
            }),
        )

        val events = runBlocking {
            val session = client.startExec(
                node(),
                ExecRequest(kind = ExecKind.SHELL, command = "echo hello", runId = "run-1"),
            )
            try {
                withTimeout(5_000L) { session.events.take(2).toList() }
            } finally {
                session.close()
            }
        }

        assertEquals(2, events.size)
        val output = events[0]
        assertTrue(output is ExecEvent.Output)
        if (output is ExecEvent.Output) {
            assertEquals(ExecStream.STDOUT, output.stream)
            assertEquals("hello", output.text)
        }
        val exit = events[1]
        assertTrue(exit is ExecEvent.Exit)
        if (exit is ExecEvent.Exit) {
            assertEquals(0, exit.exitCode)
        }
        val startRequest = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(startRequest)
        startRequest ?: return
        assertEquals("/exec", startRequest.path)
        val socketRequest = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(socketRequest)
        socketRequest ?: return
        assertEquals("Bearer " + token, socketRequest.getHeader("Authorization"))
        assertTrue(socketRequest.path?.startsWith("/exec/run-1") == true)
    }

    private fun node(): RemoteComputeNodeEntity = RemoteComputeNodeEntity(
        nodeId = "node-test",
        name = "test-node",
        connectionMode = "DIRECT_TLS",
        host = "127.0.0.1",
        daemonPort = server.port,
        authType = "PAIR_TOKEN",
        tlsFingerprint = "SHA256:" + "a".repeat(64),
    )
}
