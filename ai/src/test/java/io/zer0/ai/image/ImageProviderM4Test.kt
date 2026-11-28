package io.zer0.ai.image

import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M4 unit tests for Gemini aspect ratio mapping, ImageModelCatalog additions, and OpenAI image provider updates. */
class ImageProviderM4Test {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `gemini resolves both direct ratios and pixel sizes to aspectRatio`() {
        assertEquals("16:9", GeminiImageProvider.resolveAspectRatio("16:9"))
        assertEquals("9:16", GeminiImageProvider.resolveAspectRatio("9:16"))
        assertEquals("1:1", GeminiImageProvider.resolveAspectRatio("1024x1024"))
        assertEquals("16:9", GeminiImageProvider.resolveAspectRatio("1792x1024"))
        assertEquals("2:3", GeminiImageProvider.resolveAspectRatio("1024x1536"))
        assertNull(GeminiImageProvider.resolveAspectRatio("800x600"))
    }

    @Test
    fun `gemini buildImageRequestBody emits imageConfig when aspectRatio provided`() {
        val provider = GeminiImageProvider(OkHttpClient())
        val raw = provider.buildImageRequestBody(
            prompt = "sunset",
            referenceImagesBase64 = emptyList(),
            aspectRatio = "16:9",
        )
        val genCfg = json.parseToJsonElement(raw).jsonObject["generationConfig"]?.jsonObject
        val imgCfg = genCfg?.get("imageConfig")?.jsonObject
        assertEquals("16:9", imgCfg?.get("aspectRatio")?.jsonPrimitive?.content)
    }

    @Test
    fun `image model catalog exposes new M4 models with expected capabilities`() {
        val gpt15 = ImageModelCatalog.findById("gpt-image-1.5")
        val gemini3Pro = ImageModelCatalog.findById("gemini-3-pro-image-preview")
        val imagen3 = ImageModelCatalog.findById("imagen-3.0-generate-002")
        val flux = ImageModelCatalog.findById("flux-pro-1.1")

        assertNotNull(gpt15)
        assertEquals(4, gpt15?.maxN)
        assertTrue(gpt15?.supportsReferenceImage == true)

        assertNotNull(gemini3Pro)
        assertTrue("21:9" in gemini3Pro?.supportedSizes.orEmpty())

        assertNotNull(imagen3)
        assertEquals(4, imagen3?.maxN)

        assertNotNull(flux)
        assertEquals(4, flux?.maxN)
    }

    @Test
    fun `openai custom uncatalogued model honors requested n up to 4`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":[{"url":"https://img.test/1.png"},{"url":"https://img.test/2.png"}]}"""),
        )
        server.start()
        try {
            val provider = OpenAIImageProvider(OkHttpClient())
            val config = ProviderConfig(
                id = "custom",
                displayName = "Custom",
                type = ProviderType.OPENAI,
                apiKey = "sk-test",
                baseUrl = server.url("/v1").toString().trimEnd('/'),
            )
            provider.submit(
                ImageGenRequest(
                    prompt = "robot",
                    model = "custom-flux-model",
                    size = "1024x1024",
                    n = 2,
                    responseFormat = "url",
                    config = config,
                ),
            )
            val recorded = server.takeRequest()
            val reqRoot = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
            assertEquals("2", reqRoot["n"]?.jsonPrimitive?.content)
        } finally {
            server.shutdown()
        }
    }
}
