package io.zer0.ai.image

import io.zer0.common.Logger
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderKeyRotation
import io.zer0.ai.core.ProviderType
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * T1.2: [AgnesImageProvider] 429 切 key 重试测试(MockWebServer)。
 *
 * 验证:
 *  - 多 key 场景:首次请求拿到 429 后,自动切换到下一个 key 重试;
 *  - 重试成功(200)时返回图片;
 *  - 重试仍 429(如只有一个 key 或轮换后仍被限流)时抛 RATE_LIMITED。
 *
 * 通过注入定制 [ProviderKeyRotation] 工厂,保证多 key 场景先命中 sk_a、
 * 429 后切换为 sk_b,使两次请求的 Authorization 头可确定性断言。
 */
class AgnesImageProvider429KeySwitchTest {

    @org.junit.Before
    fun setUp() {
        Logger.enabled = false
    }

    @org.junit.After
    fun tearDown() {
        Logger.enabled = true
    }

    private fun agnesConfig(apiKey: String, baseUrl: String) = ProviderConfig(
        id = "test-agnes",
        displayName = "agnes-test",
        type = ProviderType.OPENAI,
        baseUrl = baseUrl,
        apiKey = apiKey,
    )

    /**
     * 构造确定性 key 轮换工厂:
     *  - 单 key:直接返回 trim 后的 key,switchToNextKey 恒 false;
     *  - 多 key:首次 effectiveApiKey 返回 "sk_a",switchToNextKey 后返回 "sk_b"。
     */
    private fun deterministicKeyRotationFactory(): ((ProviderConfig) -> ProviderKeyRotation) {
        fun factory(config: ProviderConfig): ProviderKeyRotation {
            val multi = config.apiKey.contains(",")
            if (!multi) return ProviderKeyRotation(config)
            var switched = false
            return object : ProviderKeyRotation(config) {
                override fun effectiveApiKey(): String =
                    if (switched) "sk_b" else "sk_a"

                override fun switchToNextKey(): Boolean {
                    switched = true
                    return true
                }
            }
        }
        return { cfg: ProviderConfig -> factory(cfg) }
    }

    @Test
    fun `429 switches to next key and retry succeeds`() {
        val server = MockWebServer()
        try {
            // 第一个 key(sk_a) 429,第二个 key(sk_b) 成功
            val successBody = """{"data":[{"b64_json":"AAA"}]}"""
            server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"rate limited"}}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody(successBody))

            val provider = AgnesImageProvider(
                OkHttpClient(),
                keyRotationFactory = deterministicKeyRotationFactory(),
            )
            val result = runBlocking {
                provider.submit(
                    ImageGenRequest(
                        prompt = "test",
                        model = AgnesImageProvider.DEFAULT_MODEL_ID,
                        size = "1:1",
                        config = agnesConfig("sk_a,sk_b", server.url("/").toString().trimEnd('/')),
                    ),
                )
            }

            assertEquals(1, result.images.size)
            assertEquals("AAA", result.images[0].base64)

            // 验证两次请求:第一次 Authorization 是 sk_a,第二次是 sk_b(切 key 成功)
            assertEquals(2, server.requestCount)
            val firstReq = server.takeRequest()
            val secondReq = server.takeRequest()
            assertEquals("Bearer sk_a", firstReq.getHeader("Authorization"))
            assertEquals("Bearer sk_b", secondReq.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `single key 429 does not switch and throws rate limited`() {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"rate limited"}}"""))

            val provider = AgnesImageProvider(
                OkHttpClient(),
                keyRotationFactory = deterministicKeyRotationFactory(),
            )
            try {
                runBlocking {
                    provider.submit(
                        ImageGenRequest(
                            prompt = "test",
                            model = AgnesImageProvider.DEFAULT_MODEL_ID,
                            size = "1:1",
                            config = agnesConfig("sk_single", server.url("/").toString().trimEnd('/')),
                        ),
                    )
                }
                fail("expected IllegalStateException (RATE_LIMITED)")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("429"))
            }
            // 单 key 不切 key,只发一次请求
            assertEquals(1, server.requestCount)
            assertEquals("Bearer sk_single", server.takeRequest().getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `multi key both 429 throws rate limited after switch`() {
        val server = MockWebServer()
        try {
            // 两个 key 都 429:切 key 后重试仍 429
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setResponseCode(429))

            val provider = AgnesImageProvider(
                OkHttpClient(),
                keyRotationFactory = deterministicKeyRotationFactory(),
            )
            try {
                runBlocking {
                    provider.submit(
                        ImageGenRequest(
                            prompt = "test",
                            model = AgnesImageProvider.DEFAULT_MODEL_ID,
                            size = "1:1",
                            config = agnesConfig("sk_a,sk_b", server.url("/").toString().trimEnd('/')),
                        ),
                    )
                }
                fail("expected IllegalStateException (RATE_LIMITED)")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("429"))
            }
            // 发了两次请求(首次 sk_a + 切 sk_b 重试)
            assertEquals(2, server.requestCount)
            assertEquals("Bearer sk_a", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer sk_b", server.takeRequest().getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }
}
