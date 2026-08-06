package io.zer0.ai.video

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R-TEST-20: Agnes / Kling 视频 Provider 状态映射与响应解析测试。
 */
class VideoProviderParsingTest {

    private val agnes = AgnesVideoProvider(OkHttpClient())
    private val kling = KlingVideoProvider(OkHttpClient())
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `agnes maps known statuses`() {
        assertEquals(PollStatus.FAILED, agnes.mapStatus("failed"))
        assertEquals(PollStatus.FAILED, agnes.mapStatus("error"))
        assertEquals(PollStatus.FAILED, agnes.mapStatus("cancelled"))
        assertEquals(PollStatus.SUCCESS, agnes.mapStatus("completed"))
        assertEquals(PollStatus.SUCCESS, agnes.mapStatus("succeeded"))
        assertEquals(PollStatus.SUCCESS, agnes.mapStatus("done"))
        assertEquals(PollStatus.PENDING, agnes.mapStatus("processing"))
    }

    @Test
    fun `agnes normalizes base urls`() {
        assertEquals("https://apihub.agnes-ai.com/v1", agnes.agnesV1Base(null))
        assertEquals("https://apihub.agnes-ai.com/v1", agnes.agnesV1Base("https://apihub.agnes-ai.com"))
        assertEquals("https://x.example/v1", agnes.agnesV1Base("https://x.example/v1"))
        assertEquals("https://apihub.agnes-ai.com", agnes.agnesRootBase(null))
        assertEquals("https://x.example", agnes.agnesRootBase("https://x.example/v1"))
    }

    @Test
    fun `agnes extracts video url from top level and nested data`() {
        val root = json.parseToJsonElement("""{"video_url": "https://example.com/a.mp4"}""").jsonObject
        assertEquals("https://example.com/a.mp4", agnes.extractVideoUrl(root))

        val nested = json.parseToJsonElement(
            """{"data": [{"x": "y"}, {"url": "https://example.com/b.mp4"}]}""",
        ).jsonObject
        assertEquals("https://example.com/b.mp4", agnes.extractVideoUrl(nested))

        val empty = json.parseToJsonElement("{}").jsonObject
        assertNull(agnes.extractVideoUrl(empty))
    }

    @Test
    fun `agnes parses api error messages`() {
        assertEquals("boom", agnes.parseApiErrorMessage("""{"error": {"message": "boom"}}"""))
        assertEquals("top", agnes.parseApiErrorMessage("""{"message": "top"}"""))
        assertNull(agnes.parseApiErrorMessage(""))
        assertNull(agnes.parseApiErrorMessage("not json"))
    }

    @Test
    fun `kling maps known statuses`() {
        assertEquals(PollStatus.PENDING, kling.mapStatus("submit"))
        assertEquals(PollStatus.PENDING, kling.mapStatus("processing"))
        assertEquals(PollStatus.PENDING, kling.mapStatus("running"))
        assertEquals(PollStatus.SUCCESS, kling.mapStatus("succeed"))
        assertEquals(PollStatus.SUCCESS, kling.mapStatus("success"))
        assertEquals(PollStatus.FAILED, kling.mapStatus("failed"))
        assertEquals(PollStatus.FAILED, kling.mapStatus("error"))
    }

    @Test
    fun `kling parses api error message`() {
        assertEquals("boom", kling.parseApiErrorMessage("""{"code": 1, "message": "boom"}"""))
        assertNull(kling.parseApiErrorMessage(""))
        assertNull(kling.parseApiErrorMessage("not json"))
    }
}
