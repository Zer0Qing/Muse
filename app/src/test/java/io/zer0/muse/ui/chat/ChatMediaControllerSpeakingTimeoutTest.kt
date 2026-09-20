package io.zer0.muse.ui.chat

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.muse.asr.ASRState
import io.zer0.muse.asr.ASRStatus
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.artifact.ArtifactRepository
import io.zer0.muse.ui.ChatUiState
import io.zer0.muse.ui.speech.TtsManager
import io.zer0.muse.ui.speech.VoiceConversationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-33: 语音 SPEAKING 状态超时兜底。
 *
 * 缺陷场景:`ttsManager.speak()` 返回 true(引擎接受请求),但 TTS 状态回调始终未到达
 * (回调丢失 / StateFlow 合并),`state.isSpeaking` 永远不为 true,步骤 3 的
 * SPEAKING → LISTENING 迁移永不触发,状态机永久卡在 SPEAKING。
 *
 * 修复后:进入 SPEAKING 即武装看门狗(按文本长度自适应超时,测试注入短超时),
 * 超时未收到任何 TTS 状态回调 → 回落 LISTENING 并记录原因。
 */
class ChatMediaControllerSpeakingTimeoutTest {

    private lateinit var voiceState: ChatVoiceState
    private lateinit var stateStore: ChatStateStore
    private lateinit var ttsManager: TtsManager
    private lateinit var audioCoordinator: ChatAudioCoordinator
    private lateinit var scope: CoroutineScope

    /** 构造可注入看门狗超时(60ms)的 controller;回退路径与生产代码一致。 */
    private fun controller(timeoutMs: Long = 60L): ChatMediaController {
        val uiState = mockk<ChatUiState>(relaxed = true)
        every { uiState.asrState } returns ASRState()
        every { uiState.isStreaming } returns false
        every { uiState.isSpeaking } returns false
        val accessor = mockk<ChatStateAccessor>(relaxed = true)
        every { accessor.snapshot } returns uiState
        every { accessor.coroutineScope } returns scope
        return ChatMediaController(
            accessor = accessor,
            artifactRepository = mockk<ArtifactRepository>(relaxed = true),
            voiceState = voiceState,
            stateStore = stateStore,
            audioCoordinator = audioCoordinator,
            ttsManager = ttsManager,
            appContext = mockk<Context>(relaxed = true),
            onError = { _, _, _ -> },
            onSend = { },
            onStop = { },
            onUpdateInput = { },
            settings = mockk<SettingsRepository>(relaxed = true),
            speakingWatchdogTimeoutMs = { timeoutMs },
        )
    }

    private fun uiState(isRecording: Boolean, isStreaming: Boolean, isSpeaking: Boolean = false): ChatUiState {
        val state = mockk<ChatUiState>(relaxed = true)
        every { state.asrState } returns ASRState(status = if (isRecording) ASRStatus.Listening else ASRStatus.Idle)
        every { state.isStreaming } returns isStreaming
        every { state.isSpeaking } returns isSpeaking
        return state
    }

    private suspend fun awaitState(expected: VoiceConversationState, timeoutMs: Long = 3_000) {
        withTimeout(timeoutMs) {
            while (voiceState.state.value != expected) delay(5)
        }
    }

    @Test
    fun `speaking without any tts callback falls back on watchdog timeout`() = runBlocking {
        val logs = mutableListOf<String>()
        val previousSink = Logger.sink
        Logger.sink = { _, tag, msg, _ -> synchronized(logs) { logs.add("$tag: $msg") } }
        try {
            scope = CoroutineScope(Dispatchers.Unconfined)
            voiceState = ChatVoiceState()
            stateStore = ChatStateStore()
            ttsManager = mockk(relaxed = true)
            every { ttsManager.speak(any(), any(), any()) } returns true
            // 关键缺陷条件:引擎没回报任何 isSpeaking 状态(回调丢失)
            every { ttsManager.isSpeaking() } returns false
            audioCoordinator = mockk(relaxed = true)
            every { audioCoordinator.shouldUseApiRecording() } returns true

            val controller = controller(timeoutMs = 60L)
            controller.startVoiceConversation()
            awaitState(VoiceConversationState.LISTENING)

            // 1) ASR 录音结束并有识别文本 → THINKING,自动发送
            stateStore.state.value = uiState(isRecording = true, isStreaming = false)
            voiceState.transcript.value = "你好"
            stateStore.state.value = uiState(isRecording = false, isStreaming = false)
            awaitState(VoiceConversationState.THINKING)

            // 2) 流式回复完成 → SPEAKING(speak() 成功,但没有 TTS 状态回调)
            val reply = UIMessage(role = MessageRole.ASSISTANT, content = "好的,我在。")
            stateStore.messages.value = listOf(reply)
            stateStore.state.value = uiState(isRecording = false, isStreaming = true)
            stateStore.state.value = uiState(isRecording = false, isStreaming = false)
            awaitState(VoiceConversationState.SPEAKING)

            // 3) 看门狗超时 → 回落 LISTENING(而不是永久停在 SPEAKING)
            awaitState(VoiceConversationState.LISTENING)
            assertEquals(VoiceConversationState.LISTENING, voiceState.state.value)
            assertTrue("回落时应清空 aiReply", voiceState.aiReply.value.isEmpty())
            assertTrue(
                "必须记录回落原因,实际日志=$logs",
                logs.any { it.contains("SPEAKING 看门狗触发") && it.contains("未收到任何 TTS 状态回调") },
            )
        } finally {
            Logger.sink = previousSink
            scope.cancel()
        }
    }

    @Test
    fun `tts callback before timeout keeps speaking state and no fallback`() = runBlocking {
        scope = CoroutineScope(Dispatchers.Unconfined)
        voiceState = ChatVoiceState()
        stateStore = ChatStateStore()
        ttsManager = mockk(relaxed = true)
        every { ttsManager.speak(any(), any(), any()) } returns true
        every { ttsManager.isSpeaking() } returns false
        audioCoordinator = mockk(relaxed = true)
        every { audioCoordinator.shouldUseApiRecording() } returns true

        val controller = controller(timeoutMs = 400L)
        controller.startVoiceConversation()
        awaitState(VoiceConversationState.LISTENING)

        stateStore.state.value = uiState(isRecording = true, isStreaming = false)
        voiceState.transcript.value = "在吗"
        stateStore.state.value = uiState(isRecording = false, isStreaming = false)
        awaitState(VoiceConversationState.THINKING)

        stateStore.messages.value = listOf(UIMessage(role = MessageRole.ASSISTANT, content = "在的。"))
        stateStore.state.value = uiState(isRecording = false, isStreaming = true)
        stateStore.state.value = uiState(isRecording = false, isStreaming = false)
        awaitState(VoiceConversationState.SPEAKING)
        assertEquals("SPEAKING 期间展示待朗读文本", "在的。", voiceState.aiReply.value)

        // TTS 状态回调正常到达并正常结束 → 走原有 SPEAKING → LISTENING 迁移,看门狗被取消
        stateStore.state.value = uiState(isRecording = false, isStreaming = false, isSpeaking = true)
        stateStore.state.value = uiState(isRecording = false, isStreaming = false, isSpeaking = false)
        awaitState(VoiceConversationState.LISTENING)
        assertTrue("正常结束后按原语义清空 aiReply", voiceState.aiReply.value.isEmpty())

        // 超过注入的看门狗超时:正常结束已取消看门狗,不应再有状态漂移
        delay(2 * 400L)
        assertEquals(VoiceConversationState.LISTENING, voiceState.state.value)
        scope.cancel()
    }
}
