package io.zer0.ai.video

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-TEST-20: 视频 Provider 请求体 DTO 黄金字段测试。
 */
class VideoProviderRequestTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val generic = GenericOpenAiVideoProvider(OkHttpClient())
    private val agnes = AgnesVideoProvider(OkHttpClient())
    private val kling = KlingVideoProvider(OkHttpClient())

    @Test
    fun `generic openai body includes required and optional fields`() {
        val body = generic.buildRequestBody(
            VideoGenRequest(
                prompt = "a cat on a skateboard",
                model = "kimi-video",
                duration = 10,
                resolution = "1080p",
                referenceImages = listOf("https://img.example/1.png"),
            ),
        )
        val root = json.parseToJsonElement(body).jsonObject
        assertEquals("kimi-video", root["model"]?.jsonPrimitive?.content)
        assertEquals("a cat on a skateboard", root["prompt"]?.jsonPrimitive?.content)
        assertEquals(10, root["duration"]?.jsonPrimitive?.int)
        assertEquals("1080p", root["resolution"]?.jsonPrimitive?.content)
        assertEquals("https://img.example/1.png", root["image"]?.jsonPrimitive?.content)
    }

    @Test
    fun `generic openai body serializes multiple reference images as array`() {
        val body = generic.buildRequestBody(
            VideoGenRequest(
                prompt = "multi image",
                model = "m",
                referenceImages = listOf("https://img.example/1.png", "https://img.example/2.png"),
            ),
        )
        val root = json.parseToJsonElement(body).jsonObject
        val images = root["image"]
        assertTrue(images is JsonArray)
        assertEquals(
            listOf("https://img.example/1.png", "https://img.example/2.png"),
            images?.jsonArray?.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `generic openai body omits blank optional fields`() {
        val body = generic.buildRequestBody(
            VideoGenRequest(prompt = "minimal", model = "", duration = 0, resolution = ""),
        )
        val root = json.parseToJsonElement(body).jsonObject
        assertFalse(root.containsKey("model"))
        assertFalse(root.containsKey("duration"))
        assertFalse(root.containsKey("resolution"))
        assertFalse(root.containsKey("image"))
        assertEquals("minimal", root["prompt"]?.jsonPrimitive?.content)
    }

    @Test
    fun `kling body applies defaults and clamps invalid duration`() {
        val body = kling.buildRequestBody(
            VideoGenRequest(prompt = "city", model = "", duration = 7, resolution = ""),
            imageUrl = null,
        )
        assertEquals("kling-v1", body["model"]?.jsonPrimitive?.content)
        assertEquals("city", body["prompt"]?.jsonPrimitive?.content)
        assertEquals(5, body["duration"]?.jsonPrimitive?.int)
        assertEquals("720p", body["resolution"]?.jsonPrimitive?.content)
        assertEquals("std", body["mode"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("image"))
    }

    @Test
    fun `kling body keeps supported duration and includes image url`() {
        val body = kling.buildRequestBody(
            VideoGenRequest(prompt = "painting", model = "kling-pro", duration = 10, resolution = "1080p"),
            imageUrl = "https://img.example/paint.png",
        )
        assertEquals("kling-pro", body["model"]?.jsonPrimitive?.content)
        assertEquals(10, body["duration"]?.jsonPrimitive?.int)
        assertEquals("1080p", body["resolution"]?.jsonPrimitive?.content)
        assertEquals("https://img.example/paint.png", body["image"]?.jsonPrimitive?.content)
    }

    @Test
    fun `agnes body includes text2video fields`() {
        val body = agnes.buildRequestBody(
            VideoGenRequest(
                prompt = "city",
                model = "agnes-video-v2.0",
                width = 1152,
                height = 768,
                frameRate = 24,
                numFrames = 121,
            ),
            numFrames = 121,
        )
        assertEquals("agnes-video-v2.0", body["model"]?.jsonPrimitive?.content)
        assertEquals("city", body["prompt"]?.jsonPrimitive?.content)
        assertEquals(1152, body["width"]?.jsonPrimitive?.int)
        assertEquals(768, body["height"]?.jsonPrimitive?.int)
        assertEquals(24, body["frame_rate"]?.jsonPrimitive?.int)
        assertEquals(121, body["num_frames"]?.jsonPrimitive?.int)
        assertFalse(body.containsKey("image"))
        assertFalse(body.containsKey("extra_body"))
    }

    @Test
    fun `agnes body includes single image`() {
        val body = agnes.buildRequestBody(
            VideoGenRequest(
                prompt = "img2video",
                model = "m",
                referenceImages = listOf("https://img.example/1.png"),
            ),
            numFrames = 121,
        )
        assertEquals("https://img.example/1.png", body["image"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("extra_body"))
    }

    @Test
    fun `agnes body includes multiframe extra_body array`() {
        val body = agnes.buildRequestBody(
            VideoGenRequest(
                prompt = "multi",
                model = "m",
                referenceImages = listOf("https://img.example/1.png", "https://img.example/2.png"),
            ),
            numFrames = 121,
        )
        assertFalse(body.containsKey("image"))
        val extra = body["extra_body"]?.jsonObject
        val images = extra?.get("image")
        assertTrue(images is JsonArray)
        assertEquals(
            listOf("https://img.example/1.png", "https://img.example/2.png"),
            images?.jsonArray?.map { it.jsonPrimitive.content },
        )
    }

}
