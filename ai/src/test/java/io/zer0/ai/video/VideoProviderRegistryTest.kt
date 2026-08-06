package io.zer0.ai.video

import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.common.Logger
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R-TEST-20: 视频 Provider 注册中心路由、覆盖与 host 提取测试。
 */
class VideoProviderRegistryTest {

    private val generic = GenericOpenAiVideoProvider(OkHttpClient())
    private val agnes = fakeProvider(AgnesVideoProvider.PROVIDER_ID)
    private val kling = fakeProvider(KlingVideoProvider.PROVIDER_ID)

    @Before
    fun disableAndroidLogging() {
        Logger.enabled = false
    }

    @After
    fun restoreLogging() {
        Logger.enabled = true
    }

    @Test
    fun `register get all and override by provider id`() {
        val registry = registryWith(agnes, kling)
        assertTrue(registry.all().isNotEmpty())

        registry.register(fakeProvider(KlingVideoProvider.PROVIDER_ID))
        assertEquals(2, registry.all().size)
        assertEquals(agnes.providerId, registry.get(AgnesVideoProvider.PROVIDER_ID)?.providerId)
        assertEquals(kling.providerId, registry.get(KlingVideoProvider.PROVIDER_ID)?.providerId)
        assertNull(registry.get("missing"))
    }

    @Test
    fun `selectFor matches specId first`() {
        val registry = registryWith(agnes, kling)
        val config = config(specId = KlingVideoProvider.PROVIDER_ID, baseUrl = "https://unknown.example")
        assertSame(kling, registry.selectFor(config))
    }

    @Test
    fun `selectFor matches preset prefixed id`() {
        val registry = registryWith(agnes, kling)
        val config = config(id = "preset_${AgnesVideoProvider.PROVIDER_ID}", specId = null)
        assertSame(agnes, registry.selectFor(config))
    }

    @Test
    fun `selectFor matches base url host patterns`() {
        val registry = registryWith(agnes, kling)
        assertSame(agnes, registry.selectFor(config(baseUrl = "https://apihub.agnes-ai.com/v1")))
        assertSame(kling, registry.selectFor(config(baseUrl = "https://api.klingai.com/v1")))
        assertSame(kling, registry.selectFor(config(baseUrl = "https://api.kuaishou.com/v1")))
    }

    @Test
    fun `selectFor falls back to generic provider`() {
        val registry = registryWith(agnes)
        assertSame(generic, registry.selectFor(config(baseUrl = "https://unknown.example")))
    }

    @Test
    fun `extractHost handles blank malformed and case`() {
        assertEquals("", VideoProviderRegistry.extractHost(""))
        assertEquals("apihub.agnes-ai.com", VideoProviderRegistry.extractHost("https://apihub.agnes-ai.com/v1"))
        assertEquals("not a url", VideoProviderRegistry.extractHost("not a url"))
        assertEquals("api.example.com", VideoProviderRegistry.extractHost("HTTPS://API.EXAMPLE.COM/path"))
    }

    private fun registryWith(vararg providers: VideoProvider): VideoProviderRegistry {
        val registry = VideoProviderRegistry(generic)
        providers.forEach { registry.register(it) }
        return registry
    }

    private fun config(
        id: String = "p",
        type: ProviderType = ProviderType.OPENAI,
        baseUrl: String = "https://example.com",
        specId: String? = null,
    ): ProviderConfig = ProviderConfig(
        id = id,
        displayName = id,
        type = type,
        baseUrl = baseUrl,
        apiKey = "k",
        specId = specId,
    )

    private fun fakeProvider(id: String): VideoProvider = object : VideoProvider {
        override val providerId: String = id
        override val supportsImageToVideo: Boolean = false
        override val supportsMultiFrameToVideo: Boolean = false
        override suspend fun submit(request: VideoGenRequest): VideoSubmitResult = VideoSubmitResult()
        override suspend fun poll(taskId: String): VideoPollResult = VideoPollResult(PollStatus.PENDING)
    }
}
