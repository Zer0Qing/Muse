package io.zer0.muse.ui.speech

import io.zer0.muse.asr.ASRState
import io.zer0.muse.asr.ASRStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentVoiceBridgeTest {

    @Test
    fun `agent bridge routes trimmed utterance and exposes latest reply`() = runTest {
        val names = MutableStateFlow("Muse")
        val generating = MutableStateFlow(false)
        val reply = MutableStateFlow("")
        val sent = mutableListOf<String>()
        var stopped = 0

        val bridge = AgentVoiceBridge(
            scope = this,
            assistantNameFlow = names,
            isGeneratingFlow = generating,
            latestAssistantReplyFlow = reply,
            onSendUtterance = { sent += it },
            onStopGeneration = { stopped++ },
        )
        advanceUntilIdle()

        assertEquals("Muse", bridge.assistantName.value)
        bridge.sendVoiceUtterance("  你好  ")
        bridge.sendVoiceUtterance("   ")
        assertEquals(listOf("你好"), sent)

        reply.value = "收到"
        generating.value = true
        advanceUntilIdle()
        assertTrue(bridge.isGenerating.value)
        assertEquals("收到", bridge.latestAssistantReply.value)

        bridge.stopGeneration()
        assertEquals(1, stopped)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `voice controller submits transcript then speaks completed reply`() = runTest {
        val bridge = FakeBridge()
        val asr = FakeAsr()
        val tts = FakeTts()
        val controller = VoiceConversationController(
            bridge = bridge,
            asr = asr,
            tts = tts,
            unavailableMessage = "voice unavailable",
            retryHint = "retry",
            scope = this,
        )

        assertTrue(controller.startConversation())
        advanceUntilIdle()
        assertEquals(VoiceConversationState.LISTENING, controller.state.value)

        asr.emit("你好")
        advanceUntilIdle()
        assertEquals("你好", controller.transcript.value)

        controller.finishListening()
        asr.finish("你好")
        advanceUntilIdle()
        assertEquals(listOf("你好"), bridge.sent)
        assertEquals(VoiceConversationState.THINKING, controller.state.value)

        bridge.replyState.value = "你好，我在"
        bridge.generatingState.value = true
        advanceUntilIdle()
        bridge.generatingState.value = false
        advanceUntilIdle()
        assertEquals(VoiceConversationState.SPEAKING, controller.state.value)
        assertEquals(listOf("你好，我在"), tts.spoken)
        assertEquals("你好，我在", controller.aiReply.value)

        tts.finishPlayback()
        advanceUntilIdle()
        assertEquals(VoiceConversationState.LISTENING, controller.state.value)
        assertEquals(2, asr.startCount)

        controller.stopConversation()
        assertEquals(VoiceConversationState.IDLE, controller.state.value)
        assertTrue(asr.cancelled)
        assertTrue(tts.stopped)
    }

    @Test
    fun `voice controller refuses to start when realtime ASR is unavailable`() = runTest {
        val asr = FakeAsr(available = false)
        val controller = VoiceConversationController(
            bridge = FakeBridge(),
            asr = asr,
            tts = FakeTts(),
            unavailableMessage = "语音识别未配置",
            retryHint = "retry",
            scope = this,
        )

        assertFalse(controller.startConversation())
        assertEquals("语音识别未配置", controller.hint.value)
        assertEquals(VoiceConversationState.IDLE, controller.state.value)
        assertEquals(0, asr.startCount)
    }

    private class FakeBridge : VoiceConversationBridge {
        val nameState = MutableStateFlow("Muse")
        val generatingState = MutableStateFlow(false)
        val replyState = MutableStateFlow("")
        val sent = mutableListOf<String>()
        var stopped = 0

        override val assistantName: StateFlow<String> = nameState
        override val isGenerating: StateFlow<Boolean> = generatingState
        override val latestAssistantReply: StateFlow<String> = replyState

        override fun sendVoiceUtterance(text: String) {
            sent += text
        }

        override fun stopGeneration() {
            stopped++
        }
    }

    private class FakeAsr(
        private val available: Boolean = true,
    ) : VoiceConversationAsr {
        private val stateFlow = MutableStateFlow(ASRState())
        override val state: StateFlow<ASRState> = stateFlow

        private var callback: ((String) -> Unit)? = null
        var startCount = 0
            private set
        var cancelled = false
            private set

        override fun shouldUseApiRecording(): Boolean = available

        override fun startListening(onTranscript: (String) -> Unit) {
            startCount++
            callback = onTranscript
            stateFlow.value = ASRState(status = ASRStatus.Connecting)
        }

        override fun stopListening() {
            stateFlow.value = stateFlow.value.copy(status = ASRStatus.Stopping)
        }

        override fun cancelListening() {
            cancelled = true
            callback = null
            stateFlow.value = ASRState()
        }

        fun emit(text: String) {
            callback?.invoke(text)
        }

        fun finish(finalTranscript: String) {
            stateFlow.value = ASRState(status = ASRStatus.Idle, transcript = finalTranscript)
        }
    }

    private class FakeTts : VoiceConversationTts {
        private val stateFlow = MutableStateFlow(PlaybackState())
        override val playbackState: StateFlow<PlaybackState> = stateFlow

        val spoken = mutableListOf<String>()
        var stopped = false
            private set

        override fun speak(text: String, utteranceId: String): Boolean {
            spoken += text
            stateFlow.value = PlaybackState(status = PlaybackStatus.Playing, totalChunks = 1)
            return true
        }

        override fun stop() {
            stopped = true
            stateFlow.value = PlaybackState(status = PlaybackStatus.Idle)
        }

        fun finishPlayback() {
            stateFlow.value = PlaybackState(status = PlaybackStatus.Ended, totalChunks = 1)
        }
    }
}
