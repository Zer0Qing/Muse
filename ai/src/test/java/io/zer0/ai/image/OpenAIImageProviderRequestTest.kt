package io.zer0.ai.image

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * R-TEST-20: OpenAI 文生图请求体 DTO 黄金字段测试。
 */
class OpenAIImageProviderRequestTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `generations body contains required and optional fields`() {
        val body = OpenAIImageProvider.buildGenerationsBody(
            prompt = "a red apple",
            n = 2,
            size = "1024x1024",
            model = "dall-e-3",
            quality = "hd",
            style = "vivid",
            responseFormat = "url",
        )
        val root = json.parseToJsonElement(body).jsonObject
        assertEquals("a red apple", root["prompt"]?.jsonPrimitive?.content)
        assertEquals(2, root["n"]?.jsonPrimitive?.int)
        assertEquals("1024x1024", root["size"]?.jsonPrimitive?.content)
        assertEquals("dall-e-3", root["model"]?.jsonPrimitive?.content)
        assertEquals("hd", root["quality"]?.jsonPrimitive?.content)
        assertEquals("vivid", root["style"]?.jsonPrimitive?.content)
        assertEquals("url", root["response_format"]?.jsonPrimitive?.content)
    }

    @Test
    fun `generations body omits blank optional fields`() {
        val body = OpenAIImageProvider.buildGenerationsBody(
            prompt = "minimal",
            n = 1,
            size = "512x512",
            model = "m",
            quality = "",
            style = "",
            responseFormat = "",
        )
        val root = json.parseToJsonElement(body).jsonObject
        assertFalse(root.containsKey("quality"))
        assertFalse(root.containsKey("style"))
        assertFalse(root.containsKey("response_format"))
        assertEquals(1, root["n"]?.jsonPrimitive?.int)
    }

    @Test
    fun `generations body n is serialized as number not string`() {
        val body = OpenAIImageProvider.buildGenerationsBody(
            prompt = "p",
            n = 3,
            size = "1024x1024",
            model = "m",
            quality = "",
            style = "",
            responseFormat = "",
        )
        val root = json.parseToJsonElement(body).jsonObject
        assertEquals(3, root["n"]?.jsonPrimitive?.int)
        assertFalse(root["n"]?.jsonPrimitive?.content?.startsWith("\"") == true)
    }
}
