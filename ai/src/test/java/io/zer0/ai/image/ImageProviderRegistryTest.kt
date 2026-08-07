package io.zer0.ai.image

import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.common.Logger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R-TEST-20: 图片 Provider 注册中心路由与覆盖逻辑测试。
 */
class ImageProviderRegistryTest {

    private val openAi = fakeProvider(OpenAIImageProvider.PROVIDER_ID)
    private val agnes = fakeProvider(AgnesImageProvider.PROVIDER_ID)
    private val custom = fakeProvider("custom")

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
        val registry = ImageProviderRegistry()
        assertTrue(registry.all().isEmpty())

        registry.register(openAi)
        registry.register(agnes)
        registry.register(custom)
        registry.register(fakeProvider(OpenAIImageProvider.PROVIDER_ID))

        assertEquals(3, registry.all().size)
        assertEquals(openAi.providerId, registry.get(OpenAIImageProvider.PROVIDER_ID)?.providerId)
        assertSame(agnes, registry.get(AgnesImageProvider.PROVIDER_ID))
        assertSame(custom, registry.get("custom"))
        assertNull(registry.get("missing"))
    }

    @Test
    fun `selectFor matches specId first`() {
        val registry = registryWith(openAi, agnes, custom)
        val config = config(specId = AgnesImageProvider.PROVIDER_ID, baseUrl = "https://unknown.example")
        assertSame(agnes, registry.selectFor(config))
    }

    @Test
    fun `selectFor matches preset prefixed id`() {
        val registry = registryWith(openAi, agnes)
        val config = config(id = "preset_${OpenAIImageProvider.PROVIDER_ID}", specId = null)
        assertSame(openAi, registry.selectFor(config))
    }

    @Test
    fun `selectFor matches agnes base url host`() {
        val registry = registryWith(openAi, agnes)
        val config = config(id = "custom", specId = null, baseUrl = "https://apihub.agnes-ai.com/v1")
        assertSame(agnes, registry.selectFor(config))
    }

    @Test
    fun `selectFor matches openai types and falls back to openai`() {
        val registry = registryWith(openAi, agnes)
        assertSame(openAi, registry.selectFor(config(type = ProviderType.OPENAI)))
        assertSame(openAi, registry.selectFor(config(type = ProviderType.OPENAI_RESPONSES)))
        assertSame(
            openAi,
            registry.selectFor(config(type = ProviderType.ANTHROPIC, baseUrl = "https://unknown.example")),
        )
    }

    @Test
    fun `selectFor returns null when registry empty`() {
        assertNull(ImageProviderRegistry().selectFor(config()))
    }

    private fun registryWith(vararg providers: ImageProvider): ImageProviderRegistry {
        val registry = ImageProviderRegistry()
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

    private fun fakeProvider(id: String): ImageProvider = object : ImageProvider {
        override val providerId: String = id
        override val supportsImageEdit: Boolean = false
        override val supportsAsync: Boolean = false
        override suspend fun submit(request: ImageGenRequest): ImageSubmitResult = ImageSubmitResult()
        override suspend fun poll(taskId: String): ImagePollResult = ImagePollResult(PollStatus.PENDING)
    }
}
