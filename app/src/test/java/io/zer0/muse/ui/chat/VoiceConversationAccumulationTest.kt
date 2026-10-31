package io.zer0.muse.ui.chat

import android.content.Context
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.zer0.muse.R
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 语音对话跨 ASR 会话的文本累积契约。
 *
 * 回归的缺陷:ASR 因非用户原因结束(DashScope 服务端静音收尾、Realtime 断线/Error)时,
 * 之前的实现把整段识别文本丢掉,接着说三十秒也可能只剩最后一句 —— 用户点「结束」时,
 * 说过的内容必须整段提交。
 */
class VoiceConversationAccumulationTest {

    private companion object {
        const val RETRY_HINT = "没听清，再说一次"
    }

    private lateinit var scope: CoroutineScope
    private lateinit var voiceState: ChatVoiceState
    private lateinit var stateStore: ChatStateStore
    private lateinit var audioCoordinator: ChatAudioCoordinator
    private lateinit var controller: ChatMediaController

    private val sent = mutableListOf<String>()
    private val inputUpdates = mutableListOf<String>()

    /** 每次 startVoiceConversationListening 注册的回调,用于模拟 ASR 识别结果。 */
    private val transcriptCallbacks = mutableListOf<(String) -> Unit>()

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Unconfined)
        voiceState = ChatVoiceState()
        stateStore = ChatStateStore()

        val snapshot = mockk<ChatUiState>(relaxed = true)
        every { snapshot.isStreaming } returns false
        every { snapshot.isSpeaking } returns false
        val accessor = mockk<ChatStateAccessor>(relaxed = true)
        every { accessor.snapshot } returns snapshot
        every { accessor.coroutineScope } returns scope

        val ttsManager = mockk<TtsManager>(relaxed = true)
        every { ttsManager.speak(any(), any(), any()) } returns true
        every { ttsManager.isSpeaking() } returns false

        audioCoordinator = mockk(relaxed = true)
        every { audioCoordinator.shouldUseApiRecording() } returns true
        every { audioCoordinator.startVoiceConversationListening(capture(transcriptCallbacks)) } just runs

        val appContext = mockk<Context>(relaxed = true)
        every { appContext.getString(R.string.voice_conversation_retry_hint) } returns RETRY_HINT

        controller = ChatMediaController(
            accessor = accessor,
            artifactRepository = mockk<ArtifactRepository>(relaxed = true),
            voiceState = voiceState,
            stateStore = stateStore,
            audioCoordinator = audioCoordinator,
            ttsManager = ttsManager,
            appContext = appContext,
            onError = { _, _, _ -> },
            onSend = { sent.add(inputUpdates.lastOrNull().orEmpty()) },
            onStop = { },
            onUpdateInput = { inputUpdates.add(it) },
            settings = mockk<SettingsRepository>(relaxed = true),
            speakingWatchdogTimeoutMs = { 60_000L },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun uiState(status: ASRStatus, transcript: String = ""): ChatUiState {
        val state = mockk<ChatUiState>(relaxed = true)
        every { state.asrState } returns ASRState(status = status, transcript = transcript)
        every { state.isStreaming } returns false
        every { state.isSpeaking } returns false
        return state
    }

    /** 模拟一次 ASR 回调(同一次录音会话内)。 */
    private fun asrCallback(text: String) {
        transcriptCallbacks.last().invoke(text)
    }

    /** 模拟 ASR 自行结束(服务端静音收尾 / 断线)。 */
    private fun asrEndsByItself(finalText: String) {
        stateStore.state.value = uiState(ASRStatus.Idle, transcript = finalText)
    }

    @Test
    fun `speech survives an asr session that ends on its own`() {
        controller.startVoiceConversation()
        stateStore.state.value = uiState(ASRStatus.Listening)
        asrCallback("今天天气")

        // DashScope 服务端静音收尾:用户没点结束
        asrEndsByItself("今天天气")

        assertEquals("今天天气", voiceState.transcript.value)
        assertTrue("非用户原因结束不得发消息", sent.isEmpty())
        assertNull("有累积文本时不提示「再说一次」", voiceState.hint.value)
        assertEquals(VoiceConversationState.LISTENING, voiceState.state.value)
        // 录音已重开,继续累积
        verify(exactly = 2) { audioCoordinator.startVoiceConversationListening(any()) }
    }

    @Test
    fun `reopened session accumulates into the same round`() {
        controller.startVoiceConversation()
        stateStore.state.value = uiState(ASRStatus.Listening)
        asrCallback("今天天气")
        asrEndsByItself("今天天气")

        // 重开后的会话把含旧内容的完整文本重发一次:前缀去重,不重复拼接
        asrCallback("今天天气不错")
        assertEquals("今天天气不错", voiceState.transcript.value)

        // 同一会话里接着说:直接拼上
        asrCallback("明天呢")
        assertEquals("今天天气不错明天呢", voiceState.transcript.value)
        assertTrue("还没有人点结束", sent.isEmpty())
    }

    @Test
    fun `finish submits the whole accumulated text once and clears it`() {
        controller.startVoiceConversation()
        stateStore.state.value = uiState(ASRStatus.Listening)
        asrCallback("今天天气")
        asrEndsByItself("今天天气")
        asrCallback("今天天气不错")
        asrCallback("明天呢")

        // 录音重开后 ASR 正在收音
        stateStore.state.value = uiState(ASRStatus.Listening)
        controller.finishVoiceConversationListening()
        verify { audioCoordinator.stopVoiceConversationListening() }
        // 收尾状态只携带本会话最后一段,提交文本应是整轮累积
        asrEndsByItself("明天呢")

        assertEquals(listOf("今天天气不错明天呢"), sent)
        assertEquals(VoiceConversationState.THINKING, voiceState.state.value)
        assertEquals("提交后累积清空", "", voiceState.transcript.value)
    }

    @Test
    fun `asr error keeps the accumulated text without reopening the microphone`() {
        controller.startVoiceConversation()
        stateStore.state.value = uiState(ASRStatus.Listening)
        asrCallback("今天天气")

        stateStore.state.value = uiState(ASRStatus.Error, transcript = "今天天气")

        assertEquals("今天天气", voiceState.transcript.value)
        assertTrue(sent.isEmpty())
        assertNull("有累积文本时不提示「再说一次」", voiceState.hint.value)
        assertEquals(VoiceConversationState.LISTENING, voiceState.state.value)
        // Error 后不自动重开(防止 start → Error 死循环),用户点按钮即可继续
        verify(exactly = 1) { audioCoordinator.startVoiceConversationListening(any()) }
    }

    @Test
    fun `empty asr ending still asks the user to repeat`() {
        controller.startVoiceConversation()
        stateStore.state.value = uiState(ASRStatus.Listening)

        // 一个字都没识别到就自己结束:没有任何内容可保留,提示重说
        asrEndsByItself("")

        assertEquals("", voiceState.transcript.value)
        assertEquals(RETRY_HINT, voiceState.hint.value)
        verify(exactly = 2) { audioCoordinator.startVoiceConversationListening(any()) }
    }

    @Test
    fun `cancel clears the accumulated text without sending`() {
        controller.startVoiceConversation()
        stateStore.state.value = uiState(ASRStatus.Listening)
        asrCallback("今天天气")
        asrEndsByItself("今天天气")
        asrCallback("今天天气不错")
        assertEquals("今天天气不错", voiceState.transcript.value)

        controller.interruptVoiceConversation()

        assertEquals(VoiceConversationState.IDLE, voiceState.state.value)
        assertEquals("取消后累积清空", "", voiceState.transcript.value)
        assertTrue("取消不发送", sent.isEmpty())
        verify { audioCoordinator.cancelVoiceConversationListening() }
    }

    @Test
    fun `exit clears the accumulated text`() {
        controller.startVoiceConversation()
        stateStore.state.value = uiState(ASRStatus.Listening)
        asrCallback("今天天气")

        controller.stopVoiceConversation()

        assertEquals("", voiceState.transcript.value)
        assertTrue(sent.isEmpty())
    }
}
