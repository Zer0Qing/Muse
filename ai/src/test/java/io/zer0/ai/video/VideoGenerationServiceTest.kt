package io.zer0.ai.video

import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.common.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R-TEST-20: 视频生成服务轮询频率、同步/异步完成、超时与取消围栏测试。
 */
class VideoGenerationServiceTest {

    private val generic = GenericOpenAiVideoProvider(OkHttpClient())

    @Before
    fun disableAndroidLogging() {
        Logger.enabled = false
    }

    @After
    fun restoreLogging() {
        Logger.enabled = true
    }

    @Test
    fun `shouldCheckThisTick adapts polling frequency`() {
        assertTrue(VideoGenerationService.shouldCheckThisTick(0L, 1))
        assertTrue(VideoGenerationService.shouldCheckThisTick(119_000L, 99))
        assertFalse(VideoGenerationService.shouldCheckThisTick(121_000L, 1))
        assertTrue(VideoGenerationService.shouldCheckThisTick(121_000L, 3))
        assertFalse(VideoGenerationService.shouldCheckThisTick(601_000L, 5))
        assertTrue(VideoGenerationService.shouldCheckThisTick(601_000L, 6))
    }

    @Test
    fun `generateVideo returns sync url without polling`() = runBlocking {
        val provider = FakeVideoProvider(
            providerId = "sync",
            submitImpl = { VideoSubmitResult(videoUrl = "https://example.com/sync.mp4", isAsync = false) },
            pollImpl = { error("sync task should not poll") },
        )
        val service = serviceWith(provider)
        val result = service.generateVideo(config(specId = "sync"), request())
        assertEquals("https://example.com/sync.mp4", result.getOrThrow())
    }

    @Test
    fun `generateVideo polls async task until success`() = runBlocking {
        val provider = FakeVideoProvider(
            providerId = "async",
            submitImpl = { VideoSubmitResult(taskId = "t1") },
            pollImpl = { VideoPollResult(PollStatus.SUCCESS, videoUrl = "https://example.com/a.mp4") },
        )
        val service = serviceWith(provider)
        val result = service.generateVideo(config(specId = "async"), request())
        assertEquals("https://example.com/a.mp4", result.getOrThrow())
    }

    @Test
    fun `generateVideo returns failure on failed poll`() = runBlocking {
        val provider = FakeVideoProvider(
            providerId = "failed",
            submitImpl = { VideoSubmitResult(taskId = "t1") },
            pollImpl = { VideoPollResult(PollStatus.FAILED, errorMessage = "boom") },
        )
        val service = serviceWith(provider)
        val result = service.generateVideo(config(specId = "failed"), request())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("boom") == true)
    }

    @Test
    fun `generateVideo fails on timeout`() = runBlocking {
        val provider = FakeVideoProvider(
            providerId = "slow",
            submitImpl = { VideoSubmitResult(taskId = "t1") },
            pollImpl = { VideoPollResult(PollStatus.PENDING) },
        )
        val service = serviceWith(provider)
        val result = service.generateVideo(config(specId = "slow"), request(), timeoutMs = 1)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("任务超时") == true)
    }

    @Test
    fun `generateVideo discards in flight result after cancel`() = runBlocking {
        val pollStarted = CompletableDeferred<Unit>()
        val releasePoll = CompletableDeferred<VideoPollResult>()
        val provider = FakeVideoProvider(
            providerId = "cancel",
            submitImpl = { VideoSubmitResult(taskId = "t1") },
            pollImpl = {
                pollStarted.complete(Unit)
                releasePoll.await()
            },
        )
        val service = serviceWith(provider)
        val deferred = async { service.generateVideo(config(specId = "cancel"), request()) }
        pollStarted.await()
        service.cancel("t1")
        releasePoll.complete(VideoPollResult(PollStatus.SUCCESS, videoUrl = "https://example.com/x.mp4"))
        val thrown = runCatching { deferred.await() }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
    }

    private fun serviceWith(provider: VideoProvider): VideoGenerationService {
        val registry = VideoProviderRegistry(generic)
        registry.register(provider)
        return VideoGenerationService(registry)
    }

    private fun config(
        id: String = "p",
        specId: String? = null,
    ): ProviderConfig = ProviderConfig(
        id = id,
        displayName = id,
        type = ProviderType.OPENAI,
        baseUrl = "https://example.com",
        apiKey = "k",
        specId = specId,
    )

    private fun request(): VideoGenRequest = VideoGenRequest(prompt = "test", model = "m")

    private class FakeVideoProvider(
        override val providerId: String,
        private val submitImpl: suspend (VideoGenRequest) -> VideoSubmitResult = { VideoSubmitResult() },
        private val pollImpl: suspend (String) -> VideoPollResult = { VideoPollResult(PollStatus.PENDING) },
    ) : VideoProvider {
        override val supportsImageToVideo: Boolean = false
        override val supportsMultiFrameToVideo: Boolean = false
        override suspend fun submit(request: VideoGenRequest): VideoSubmitResult = submitImpl(request)
        override suspend fun poll(taskId: String): VideoPollResult = pollImpl(taskId)
    }
}
