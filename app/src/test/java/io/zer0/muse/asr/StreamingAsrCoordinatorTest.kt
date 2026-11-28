package io.zer0.muse.asr

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingAsrCoordinatorTest {

    private class FakeAsrController : ASRController {
        val _state = MutableStateFlow(ASRState())
        override val state: StateFlow<ASRState> = _state
        var onTranscript: ((String) -> Unit)? = null
        var stopCount = 0
        var disposeCount = 0

        override fun start(onTranscriptChange: ((String) -> Unit)?) {
            onTranscript = onTranscriptChange
            _state.value = ASRState(status = ASRStatus.Listening, isAvailable = true)
        }

        override fun stop() {
            stopCount++
            _state.value = ASRState(status = ASRStatus.Stopping, isAvailable = true)
        }

        override fun dispose() {
            disposeCount++
            _state.value = ASRState(status = ASRStatus.Idle, isAvailable = true)
        }

        fun emitTranscript(text: String) {
            onTranscript?.invoke(text)
        }

        fun finishIdle() {
            _state.value = ASRState(status = ASRStatus.Idle, isAvailable = true)
        }

        fun fail(msg: String) {
            _state.value = ASRState(status = ASRStatus.Error, isAvailable = true, errorMessage = msg)
        }
    }

    /**
     * 协调器内部的等待协程必须用 Unconfined 派发：它要能在外部的
     * [FakeAsrController.finishIdle] 一发射状态时就同步恢复，
     * 否则断言只能观察到「等待协程从未被唤醒」，测不出自动发送逻辑本身。
     */
    private fun TestScope.unconfinedScope(): CoroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

    private fun mockContext(): Context {
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.getString(R.string.asr_local_error_model_missing) } returns "LOCAL_MODEL_MISSING"
        every { ctx.getString(R.string.asr_missing_api_key_error) } returns "API_KEY_MISSING"
        return ctx
    }

    @Test
    fun `shouldUseApiRecording is true for LOCAL_SENSEVOICE and cloud providers, false for SYSTEM`() = runTest {
        var cfg = AsrConfig(provider = AsrProviderType.LOCAL_SENSEVOICE)
        val coord = StreamingAsrCoordinator(
            context = mockContext(),
            coroutineScope = unconfinedScope(),
            configProvider = { cfg },
            inputProvider = { "" },
            updateInput = {},
        )
        assertTrue(coord.shouldUseApiRecording())

        cfg = AsrConfig(provider = AsrProviderType.OPENAI_WHISPER, apiKey = "sk-test")
        assertTrue(coord.shouldUseApiRecording())

        cfg = AsrConfig(provider = AsrProviderType.SYSTEM)
        assertFalse(coord.shouldUseApiRecording())

        cfg = AsrConfig(provider = AsrProviderType.DASHSCOPE_FILE)
        assertFalse(coord.shouldUseApiRecording())
    }

    @Test
    fun `startStreamingAsr publishes local model missing error when SenseVoice not ready`() = runTest {
        val coord = StreamingAsrCoordinator(
            context = mockContext(),
            coroutineScope = unconfinedScope(),
            configProvider = { AsrConfig(provider = AsrProviderType.LOCAL_SENSEVOICE) },
            inputProvider = { "" },
            updateInput = {},
            localModelReadyProvider = { false },
            systemRecognizerAvailableProvider = { false },
        )

        assertFalse(coord.startStreamingAsr())
        assertEquals(ASRStatus.Error, coord.state.value.status)
        assertEquals("LOCAL_MODEL_MISSING", coord.state.value.errorMessage)
    }

    @Test
    fun `local SenseVoice starts, streams transcript, and auto-sends once controller settles Idle`() = runTest {
        val fake = FakeAsrController()
        var input = "hello"
        var autoSent = 0
        val coord = StreamingAsrCoordinator(
            context = mockContext(),
            coroutineScope = unconfinedScope(),
            configProvider = { AsrConfig(provider = AsrProviderType.LOCAL_SENSEVOICE) },
            inputProvider = { input },
            updateInput = { transform -> input = transform(input) },
            controllerFactory = { _, _ -> fake },
            localModelReadyProvider = { true },
            systemRecognizerAvailableProvider = { false },
        )

        assertTrue(coord.startStreamingAsr())
        assertEquals(ASRStatus.Listening, coord.state.value.status)

        fake.emitTranscript("world")
        assertEquals("hello world", input)
        fake.emitTranscript("world again")
        assertEquals("hello world again", input)

        // 停止后必须等控制器自己落到 Idle 才自动发送，不能立刻发。
        coord.stopStreamingAsrAndSend { autoSent++ }
        assertEquals(1, fake.stopCount)
        assertEquals(0, autoSent)

        fake.finishIdle()
        assertEquals(1, autoSent)
    }

    @Test
    fun `cancelStreamingAsr restores pre-recording input and blocks stale callbacks`() = runTest {
        val fake = FakeAsrController()
        var input = "draft"
        var autoSent = 0
        val coord = StreamingAsrCoordinator(
            context = mockContext(),
            coroutineScope = unconfinedScope(),
            configProvider = { AsrConfig(provider = AsrProviderType.LOCAL_SENSEVOICE) },
            inputProvider = { input },
            updateInput = { transform -> input = transform(input) },
            controllerFactory = { _, _ -> fake },
            localModelReadyProvider = { true },
            systemRecognizerAvailableProvider = { false },
        )

        assertTrue(coord.startStreamingAsr())
        fake.emitTranscript("partial voice")
        assertEquals("draft partial voice", input)

        coord.stopStreamingAsrAndSend { autoSent++ }
        coord.cancelStreamingAsr()
        assertEquals("draft", input)
        assertEquals(1, fake.disposeCount)

        // 取消后迟到的回调与状态跳变都必须被 generation 令牌挡掉。
        fake.emitTranscript("late ghost text")
        assertEquals("draft", input)
        fake.finishIdle()
        assertEquals(0, autoSent)
    }

    @Test
    fun `stopStreamingAsrAndSend does not auto-send when controller settles on Error`() = runTest {
        val fake = FakeAsrController()
        var autoSent = 0
        val coord = StreamingAsrCoordinator(
            context = mockContext(),
            coroutineScope = unconfinedScope(),
            configProvider = { AsrConfig(provider = AsrProviderType.OPENAI_WHISPER, apiKey = "sk-1") },
            inputProvider = { "text" },
            updateInput = {},
            controllerFactory = { _, _ -> fake },
            localModelReadyProvider = { false },
            systemRecognizerAvailableProvider = { false },
        )

        assertTrue(coord.startStreamingAsr())
        coord.stopStreamingAsrAndSend { autoSent++ }
        fake.fail("network error")
        assertEquals(0, autoSent)
    }
}
