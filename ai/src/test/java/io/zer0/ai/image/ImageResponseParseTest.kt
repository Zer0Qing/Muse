package io.zer0.ai.image

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v1.0.75: 生图响应解析 — 过滤 JSON null 字段。
 * Agnes/OpenAI 通道故障时 b64_json 可能是 JSON null,JsonNull.content 返回 "null" 字符串,
 * 旧逻辑 takeIf isNotBlank 放行 → 拼出 data:image/png;base64,null 假图。
 */
class ImageResponseParseTest {

    private lateinit var agnes: AgnesImageProvider

    @Before
    fun setUp() {
        agnes = AgnesImageProvider(OkHttpClient())
    }

    @Test
    fun `agnes parses b64_json null as no result`() {
        val body = """{"data":[{"b64_json":null}]}"""
        val images = agnes.parseResponseImages(body)
        assertTrue("JSON null b64 应被过滤", images.isEmpty())
    }

    @Test
    fun `agnes parses url null as no result`() {
        val body = """{"data":[{"url":null}]}"""
        val images = agnes.parseResponseImages(body)
        assertTrue("JSON null url 应被过滤", images.isEmpty())
    }

    @Test
    fun `agnes parses valid b64`() {
        val body = """{"data":[{"b64_json":"aGVsbG8="}]}"""
        val images = agnes.parseResponseImages(body)
        assertEquals(1, images.size)
        assertEquals("aGVsbG8=", images[0].base64)
    }

    @Test
    fun `agnes parses valid url`() {
        val body = """{"data":[{"url":"https://example.com/img.png"}]}"""
        val images = agnes.parseResponseImages(body)
        assertEquals(1, images.size)
        assertEquals("https://example.com/img.png", images[0].url)
    }

    @Test
    fun `agnes filters empty string fields`() {
        val body = """{"data":[{"b64_json":"","url":""}]}"""
        val images = agnes.parseResponseImages(body)
        assertTrue("空串字段应被过滤", images.isEmpty())
    }

    @Test
    fun `agnes literal null string is not a valid image`() {
        // 通道偶发返回字面量 "null" 字符串(非 JSON null)
        val body = """{"data":[{"b64_json":"null"}]}"""
        val images = agnes.parseResponseImages(body)
        assertTrue("字面量 null 应被过滤", images.isEmpty())
    }

    @Test
    fun `agnes skips invalid items but keeps valid ones`() {
        val body = """{"data":[{"b64_json":null},{"url":"https://example.com/a.png"}]}"""
        val images = agnes.parseResponseImages(body)
        assertEquals(1, images.size)
        assertEquals("https://example.com/a.png", images[0].url)
    }
}
