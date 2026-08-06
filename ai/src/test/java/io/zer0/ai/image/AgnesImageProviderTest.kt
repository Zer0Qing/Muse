package io.zer0.ai.image

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-TEST-20: Agnes 图片 Provider 响应解析与尺寸映射测试。
 */
class AgnesImageProviderTest {

    private val provider = AgnesImageProvider(OkHttpClient())

    @Test
    fun `parseResponseImages handles b64 json and url`() {
        val body = """
            {
              "data": [
                {"b64_json": "AAA"},
                {"url": "https://example.com/1.png"},
                {"b64_json": "", "url": "https://example.com/2.png"}
              ]
            }
        """.trimIndent()

        val images = provider.parseResponseImages(body)

        assertEquals(3, images.size)
        assertEquals("AAA", images[0].base64)
        assertNull(images[0].url)
        assertNull(images[1].base64)
        assertEquals("https://example.com/1.png", images[1].url)
        assertEquals("https://example.com/2.png", images[2].url)
    }

    @Test
    fun `parseResponseImages returns empty when data missing or empty`() {
        assertTrue(provider.parseResponseImages("{}").isEmpty())
        assertTrue(provider.parseResponseImages("""{"data": []}""").isEmpty())
        assertTrue(provider.parseResponseImages("""{"data": [{"foo": "bar"}]}""").isEmpty())
    }

    @Test
    fun `parseApiErrorMessage handles nested and top level messages`() {
        assertEquals(
            "boom",
            provider.parseApiErrorMessage("""{"error": {"message": "boom"}}"""),
        )
        assertEquals(
            "top",
            provider.parseApiErrorMessage("""{"message": "top"}"""),
        )
        assertNull(provider.parseApiErrorMessage(""))
        assertNull(provider.parseApiErrorMessage("not json"))
    }

    @Test
    fun `resolveSize maps ratio and validates pixel values`() {
        assertEquals("1024x1024", AgnesImageProvider.resolveSize("1:1"))
        assertEquals("1152x768", AgnesImageProvider.resolveSize("3:2"))
        assertEquals("1152x768", AgnesImageProvider.resolveSize(""))
        assertEquals("768x1344", AgnesImageProvider.resolveSize("9:16"))
    }

    @Test
    fun `size ratios cover supported set`() {
        assertEquals(
            AgnesImageProvider.SUPPORTED_SIZES,
            AgnesImageProvider.SIZE_RATIOS.values.toSet(),
        )
    }
}
