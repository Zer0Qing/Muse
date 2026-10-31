package io.zer0.muse.miniphone.ui.chat

import io.zer0.muse.miniphone.data.voice.MpVoiceCapture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音输入状态机。
 *
 * 断言的是一次点击引发的状态迁移与回调次数,不碰麦克风也不碰网络:
 * 录音器与转写入口都是假实现,所以「有没有调转写」「回调了几次文字」可以被数出来。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MpVoiceInputControllerTest {

    @Test
    fun `空闲时点麦克风进入录音并从零秒开始计数`() = runTest {
        val rig = Rig()
        val controller = rig.controller(backgroundScope)

        assertEquals(MpVoiceInputState.Idle, controller.state.value)

        controller.start()

        assertEquals(MpVoiceInputState.Recording(0), controller.state.value)
        assertEquals("起采只调一次", 1, rig.capture.startCount)
    }

    @Test
    fun `录音中每秒加一`() = runTest {
        val rig = Rig()
        val controller = rig.controller(backgroundScope)
        controller.start()

        advanceTimeBy(3_100)

        assertEquals(MpVoiceInputState.Recording(3), controller.state.value)
    }

    @Test
    fun `结束录音先进入转写再把文字交出去并回到空闲`() = runTest {
        val rig = Rig()
        val controller = rig.controller(backgroundScope)
        controller.start()

        controller.stop()

        assertEquals("停采后立刻应是转写中", MpVoiceInputState.Transcribing, controller.state.value)

        runCurrent()

        assertEquals(MpVoiceInputState.Idle, controller.state.value)
        assertEquals("文字只回调一次", listOf("你好"), rig.transcripts)
        assertEquals("转写只调一次", 1, rig.transcribeCount)
        assertEquals("停采只调一次", 1, rig.capture.stopCount)
        assertSame("交给转写的必须是录音器交出的那段 WAV", rig.capture.wav, rig.lastWav)
    }

    @Test
    fun `取消只丢音频不调转写`() = runTest {
        val rig = Rig()
        val controller = rig.controller(backgroundScope)
        controller.start()

        controller.cancel()

        assertEquals(MpVoiceInputState.Idle, controller.state.value)
        assertEquals("取消要通知录音器丢弃", 1, rig.capture.cancelCount)
        assertEquals("取消不该调转写", 0, rig.transcribeCount)
        assertNull("取消不该交文字", rig.transcripts.firstOrNull())

        runCurrent()

        assertEquals("等一会儿也不该冒出文字", 0, rig.transcripts.size)
    }

    @Test
    fun `转写中再次点麦克风被忽略`() = runTest {
        val rig = Rig()
        val gate = CompletableDeferred<String?>()
        rig.onTranscribe = { gate.await() }
        val controller = rig.controller(backgroundScope)
        controller.start()

        controller.stop()
        runCurrent()
        assertEquals(MpVoiceInputState.Transcribing, controller.state.value)

        controller.start()

        assertEquals("转写没结束不该开新的一段", MpVoiceInputState.Transcribing, controller.state.value)
        assertEquals("录音器不该被再次起采", 1, rig.capture.startCount)

        gate.complete("转写好了")
        runCurrent()

        assertEquals(MpVoiceInputState.Idle, controller.state.value)
        assertEquals(listOf("转写好了"), rig.transcripts)
    }

    @Test
    fun `识别结果为空时提示没听清并回到空闲`() = runTest {
        val rig = Rig()
        rig.onTranscribe = { "   " }
        val controller = rig.controller(backgroundScope)
        controller.start()

        controller.stop()
        runCurrent()

        assertEquals(MpVoiceInputState.Idle, controller.state.value)
        assertEquals("空白结果要提示", 1, rig.emptyCount)
        assertTrue("空白结果不能进输入框", rig.transcripts.isEmpty())
    }

    @Test
    fun `没有采到音频时也走提示并回到空闲`() = runTest {
        val rig = Rig(capture = FakeCapture(wav = null))
        val controller = rig.controller(backgroundScope)
        controller.start()

        controller.stop()
        runCurrent()

        assertEquals(MpVoiceInputState.Idle, controller.state.value)
        assertEquals(1, rig.emptyCount)
        assertEquals("没有音频就不该请求转写", 0, rig.transcribeCount)
    }

    @Test
    fun `录满一分钟后自动停止并转写`() = runTest {
        val rig = Rig()
        val controller = rig.controller(backgroundScope)
        controller.start()

        advanceTimeBy(MpVoiceInputController.MAX_RECORDING_SECONDS * 1_000L + 100L)
        runCurrent()

        assertEquals("到点后必须自己停下来", 1, rig.capture.stopCount)
        assertEquals(MpVoiceInputState.Idle, controller.state.value)
        assertEquals(listOf("你好"), rig.transcripts)
    }

    @Test
    fun `录音器起不来时状态仍为空闲并发一次失败回调`() = runTest {
        val rig = Rig(capture = FakeCapture(startResult = false))
        val controller = rig.controller(backgroundScope)

        controller.start()

        assertEquals("起不来就不该进录音态", MpVoiceInputState.Idle, controller.state.value)
        assertEquals("要告诉用户麦克风没开起来", 1, rig.startFailedCount)
        assertEquals("失败时不许开始转写", 0, rig.transcribeCount)
    }

    @Test
    fun `设置不支持录音时点麦克风不进入录音`() = runTest {
        val rig = Rig(canRecord = false)
        val controller = rig.controller(backgroundScope)

        controller.start()

        assertEquals(MpVoiceInputState.Idle, controller.state.value)
        assertEquals("不支持就不该碰麦克风", 0, rig.capture.startCount)
        assertEquals("这是设置问题,不该报成启动失败", 0, rig.startFailedCount)
    }

    @Test
    fun `离开页面时丢掉正在进行的录音`() = runTest {
        val rig = Rig()
        val controller = rig.controller(backgroundScope)
        controller.start()

        controller.release()

        assertEquals(MpVoiceInputState.Idle, controller.state.value)
        assertEquals("离页要删掉临时文件", 1, rig.capture.cancelCount)
        assertEquals(0, rig.transcribeCount)
    }

    /** 一次用例的现场:假录音器 + 记录下来的回调。 */
    private class Rig(
        val capture: FakeCapture = FakeCapture(),
        private val canRecord: Boolean = true,
    ) {

        val transcripts = mutableListOf<String>()
        var emptyCount: Int = 0
        var startFailedCount: Int = 0
        var transcribeCount: Int = 0
        var lastWav: ByteArray? = null

        /** 转写入口;用例可以换成挂起版本,用来观察「转写中」这一态。 */
        var onTranscribe: suspend (ByteArray) -> String? = { "你好" }

        fun controller(scope: CoroutineScope): MpVoiceInputController = MpVoiceInputController(
            recorder = capture,
            transcribe = { wav ->
                transcribeCount += 1
                lastWav = wav
                onTranscribe(wav)
            },
            onTranscript = { text -> transcripts += text },
            onEmptyTranscript = { emptyCount += 1 },
            onStartFailed = { startFailedCount += 1 },
            canRecord = { canRecord },
            scope = scope,
        )
    }

    private class FakeCapture(
        private val startResult: Boolean = true,
        val wav: ByteArray? = ByteArray(8),
    ) : MpVoiceCapture {

        var startCount: Int = 0
            private set
        var stopCount: Int = 0
            private set
        var cancelCount: Int = 0
            private set

        override fun start(): Boolean {
            startCount += 1
            return startResult
        }

        override suspend fun stop(): ByteArray? {
            stopCount += 1
            return wav
        }

        override fun cancel() {
            cancelCount += 1
        }
    }
}
