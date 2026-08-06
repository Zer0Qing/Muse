package io.zer0.ai.image

import io.zer0.ai.ProviderConfigStore
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.ai.core.ProviderType
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-TEST-20: 图片服务轮询频率与输出转换纯逻辑测试。
 */
class ImageServicePureTest {

    private val service = ImageService(
        configStore = object : ProviderConfigStore {
            override suspend fun get(): io.zer0.ai.core.ProviderConfig? = null
        },
        registry = ImageProviderRegistry(),
    )

    @Test
    fun `shouldCheckThisTick checks every tick under two minutes`() {
        assertTrue(service.shouldCheckThisTick(ageMs = 0L, tickCount = 1))
        assertTrue(service.shouldCheckThisTick(ageMs = 119_000L, tickCount = 99))
    }

    @Test
    fun `shouldCheckThisTick checks every third tick between two and ten minutes`() {
        assertFalse(service.shouldCheckThisTick(ageMs = 121_000L, tickCount = 1))
        assertTrue(service.shouldCheckThisTick(ageMs = 121_000L, tickCount = 3))
        assertFalse(service.shouldCheckThisTick(ageMs = 599_000L, tickCount = 5))
    }

    @Test
    fun `shouldCheckThisTick checks every sixth tick after ten minutes`() {
        assertFalse(service.shouldCheckThisTick(ageMs = 601_000L, tickCount = 5))
        assertTrue(service.shouldCheckThisTick(ageMs = 601_000L, tickCount = 6))
    }

    @Test
    fun `convertToOutputStrings prefers base64 and builds data uri`() {
        val output = service.convertToOutputStrings(
            listOf(
                GeneratedImage(base64 = "abc"),
                GeneratedImage(base64 = "image/png|def"),
                GeneratedImage(url = "https://example.com/1.png"),
                GeneratedImage(),
            ),
        )

        assertEquals(
            listOf(
                "data:image/png;base64,abc",
                "data:image/png;base64,def",
                "https://example.com/1.png",
            ),
            output,
        )
    }

    @Test
    fun `resolveModelId prefers explicit param model`() {
        val config = providerConfig(
            models = listOf(Model(id = "m1", providerId = "p")),
            imageModel = "specific-model",
        )
        val result = service.resolveModelId(
            params = ImageGenParams(model = "explicit-model"),
            config = config,
            provider = OpenAIImageProvider(OkHttpClient()),
        )
        assertEquals("explicit-model", result)
    }

    @Test
    fun `resolveModelId uses provider specific image model`() {
        val config = providerConfig(
            models = listOf(Model(id = "m1", providerId = "p")),
            imageModel = "specific-model",
        )
        val result = service.resolveModelId(
            params = ImageGenParams(),
            config = config,
            provider = OpenAIImageProvider(OkHttpClient()),
        )
        assertEquals("specific-model", result)
    }

    @Test
    fun `resolveModelId uses first image capable model`() {
        val textOnly = Model(id = "text", providerId = "p")
        val imageModel = Model(
            id = "image-1",
            providerId = "p",
            outputModalities = setOf("text", "image"),
        )
        val config = providerConfig(models = listOf(textOnly, imageModel))
        val result = service.resolveModelId(
            params = ImageGenParams(),
            config = config,
            provider = OpenAIImageProvider(OkHttpClient()),
        )
        assertEquals("image-1", result)
    }

    @Test
    fun `resolveModelId falls back to agnes default`() {
        val config = providerConfig(models = emptyList())
        val result = service.resolveModelId(
            params = ImageGenParams(),
            config = config,
            provider = AgnesImageProvider(OkHttpClient()),
        )
        assertEquals(AgnesImageProvider.DEFAULT_MODEL_ID, result)
    }

    private fun providerConfig(
        models: List<Model>,
        imageModel: String? = null,
    ): ProviderConfig = ProviderConfig(
        id = "p",
        displayName = "P",
        type = ProviderType.OPENAI,
        baseUrl = "https://example.com",
        apiKey = "k",
        models = models,
        specific = ProviderSpecificConfig.OpenAI(imageModel = imageModel ?: ""),
    )
}
