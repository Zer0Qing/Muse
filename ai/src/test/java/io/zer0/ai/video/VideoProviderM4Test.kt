package io.zer0.ai.video

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

/** M4 unit tests for video request extensions (aspectRatio, negativePrompt, Kling pro mode, nested URL parsing). */
class VideoProviderM4Test {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `kling uses pro mode for 1080p and serializes aspectRatio and negativePrompt`() {
        val kling = KlingVideoProvider(OkHttpClient())
        val body = kling.buildRequestBody(
            VideoGenRequest(
                prompt = "ocean waves",
                model = "kling-v2",
                duration = 10,
                resolution = "1080p",
                aspectRatio = "9:16",
                negativePrompt = "blurry",
            ),
            imageUrl = null,
        )
        assertEquals("pro", body["mode"]?.jsonPrimitive?.content)
        assertEquals("9:16", body["aspect_ratio"]?.jsonPrimitive?.content)
        assertEquals("blurry", body["negative_prompt"]?.jsonPrimitive?.content)
    }

    @Test
    fun `agnes maps aspectRatio when width and height are defaults`() {
        val agnes = AgnesVideoProvider(OkHttpClient())
        val body = agnes.buildRequestBody(
            VideoGenRequest(
                prompt = "vertical dance",
                model = "agnes-video-v2.0",
                aspectRatio = "9:16",
            ),
            numFrames = 121,
        )
        assertEquals(720, body["width"]?.jsonPrimitive?.int)
        assertEquals(1280, body["height"]?.jsonPrimitive?.int)
    }

    @Test
    fun `generic openai provider parses nested output video_url and finished status`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"task_99"}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"finished","output":{"video_url":"https://cdn.example/out.mp4"}}"""),
        )
        server.start()
        try {
            val provider = GenericOpenAiVideoProvider(OkHttpClient())
            val submit = provider.submit(
                VideoGenRequest(
                    prompt = "timelapse",
                    model = "sora-2",
                    aspectRatio = "16:9",
                    apiKey = "sk-test",
                    baseUrl = server.url("/v1").toString().trimEnd('/'),
                ),
            )
            assertEquals("task_99", submit.taskId)

            val submitReq = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("16:9", submitReq["aspect_ratio"]?.jsonPrimitive?.content)

            val poll = provider.poll("task_99")
            assertEquals(PollStatus.SUCCESS, poll.status)
            assertEquals("https://cdn.example/out.mp4", poll.videoUrl)
        } finally {
            server.shutdown()
        }
    }
}
