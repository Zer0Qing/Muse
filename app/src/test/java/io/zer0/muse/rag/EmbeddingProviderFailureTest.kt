package io.zer0.muse.rag

import io.zer0.ai.core.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * R5/R6/R10 外部服务错误与取消合同(计划 R5「验证网络 Call.cancel 生效」、
 * R10「外部服务错误与取消」)。
 *
 * 用 MockWebServer 模拟:HTTP 500 快速失败、响应体超时、协程取消时真正 Call.cancel。
 * 不读取真实密钥、不访问外部网络。
 */
class EmbeddingProviderFailureTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: OpenAIEmbeddingProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val config = ProviderConfig(
            id = "test-provider",
            displayName = "Test",
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "test-key",
        )
        provider = OpenAIEmbeddingProvider(
            config = config,
            model = "text-embedding-3-small",
            client = OkHttpClient.Builder()
                .callTimeout(500, TimeUnit.MILLISECONDS)
                .build(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `http 500 fails fast with clear error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        try {
            provider.embed(listOf("hello"))
            fail("HTTP 500 必须抛出异常")
        } catch (e: Exception) {
            assertTrue("错误需含状态码: ${e.message}", e.message?.contains("500") == true)
        }
        // 失败不得污染实测维度指纹。
        assertTrue("失败后指纹仍为 unknown", provider.fingerprintDimension == "unknown")
    }

    @Test
    fun `response timeout fails within client timeout`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody("""{"data":[]}""")
                .setBodyDelay(5, TimeUnit.SECONDS),
        )
        val elapsed = kotlin.system.measureTimeMillis {
            try {
                withTimeout(3_000L) { provider.embed(listOf("slow")) }
                fail("超时必须失败")
            } catch (e: Exception) {
                // 预期:OkHttp callTimeout 或协程超时
            }
        }
        assertTrue("必须快速失败而不是等到响应体完成: ${elapsed}ms", elapsed < 3_000L)
    }

    @Test
    fun `coroutine cancellation cancels okhttp call`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody("""{"data":[]}""")
                .setHeadersDelay(5, TimeUnit.SECONDS),
        )
        val job = launch(Dispatchers.IO) {
            // 取消时 OkHttp 的 call.cancel 会让等待中的调用以异常结束;测试关注取消时效而非异常类型。
            runCatching { provider.embed(listOf("cancel-me")) }
        }
        delay(200)
        val start = System.currentTimeMillis()
        job.cancel()
        job.join()
        val elapsed = System.currentTimeMillis() - start
        assertTrue("取消必须及时生效(Call.cancel): ${elapsed}ms", elapsed < 2_000L)
        assertTrue("协程应处于取消状态", job.isCancelled)
    }
}
