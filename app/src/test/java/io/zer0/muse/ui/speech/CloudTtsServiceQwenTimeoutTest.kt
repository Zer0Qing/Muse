package io.zer0.muse.ui.speech

import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 3 (可靠性 P1): Qwen 二次音频下载超时兜底测试。
 *
 * Qwen(DashScope 原生接口)合成先 POST 拿 output.audio.url,再二次 GET 下载音频。
 * 修复前二次下载无协程超时,复用的 chat OkHttpClient readTimeout 最长 300s —— 音频 URL
 * 挂起时合成协程被长期阻塞。修复后用 withTimeoutOrNull 兜底(默认 30s,可注入),
 * 本测试注入 300ms + MockWebServer 延迟 10s 响应体,验证调用快速失败而不是等待响应。
 */
class CloudTtsServiceQwenTimeoutTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `qwen second audio download is bounded by timeout`() = runBlocking {
        val audioUrl = server.url("/slow-audio.mp3")
        // 第一次请求: 返回二次下载地址
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"output":{"audio":{"url":"$audioUrl"}}}"""),
        )
        // 第二次请求: 响应体延迟 10s 才返回,远超 300ms 兜底超时
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("mp3-bytes")
                .setBodyDelay(10, TimeUnit.SECONDS),
        )

        val service = CloudTtsService(client, audioDownloadTimeoutMs = 300)
        val out = File.createTempFile("muse-tts", ".mp3")
        try {
            val start = System.nanoTime()
            val ok = service.synthesizeToFile(
                text = "你好",
                engine = "qwen",
                apiKey = "test-key",
                model = "",
                voice = "",
                endpoint = server.url("/").toString().removeSuffix("/"),
                outputFile = out,
            )
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertFalse("二次下载超时应返回失败,而不是拿到空音频/无限等待", ok)
            assertTrue(
                "应在兜底超时(300ms)附近返回,实际 ${elapsedMs}ms",
                elapsedMs < 5_000,
            )
        } finally {
            out.delete()
        }
    }
}
