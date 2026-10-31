package io.zer0.muse.ui.chat

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
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
 * 语音对话的「完全手动端点」契约。
 *
 * 回归的缺陷:ASR 会话一结束(服务端静音收尾、识别报错)就被当成用户说完了,
 * 半句话直接发给模型,而且 TTS 播报期间麦克风仍在采集,把自己的朗读声识别成用户说话。
 *
 * 新契约:
 *  - 只有用户点「结束」([ChatMediaController.finishVoiceConversationListening])才算提交
 *  - ASR 自行结束 → 不发消息,回 LISTENING 重新收音;已识别文字保留在累积文本里继续累积,
 *    所以只在累积文本仍为空时才给「再说一次」轻提示
 *  - THINKING / SPEAKING 期间麦克风保持关闭,只在回到 LISTENING 时重开
 */
class VoiceConversationManualCommitTest {

    private companion object {
        const val RETRY_HINT = "没听清，再说一次"
    }

    private lateinit var scope: CoroutineScope
    private lateinit var voiceState: ChatVoiceState
    private lateinit var stateStore: ChatStateStore
    private lateinit var ttsManager: TtsManager
    private lateinit var audioCoordinator: ChatAudioCoordinator
    private lateinit var controller: ChatMediaController

    private val sent = mutableListOf<String>()
    private val inputUpdates = mutableListOf<String>()

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

        ttsManager = mockk(relaxed = true)
        every { ttsManager.speak(any(), any(), any()) } returns true
        every { ttsManager.isSpeaking() } returns false

        audioCoordinator = mockk(relaxed = true)
        every { audioCoordinator.shouldUseApiRecording() } returns true

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
            // 看门狗用不到本测试的断言,给一个足够长的超时避免干扰
            speakingWatchdogTimeoutMs = { 60_000L },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun uiState(
        status: ASRStatus,
        transcript: String = "",
        isStreaming: Boolean = false,
        isSpeaking: Boolean = false,
    ): ChatUiState {
        val state = mockk<ChatUiState>(relaxed = true)
        every { state.asrState } returns ASRState(status = status, transcript = transcript)
        every { state.isStreaming } returns isStreaming
        every { state.isSpeaking } returns isSpeaking
        return state
    }

    @Test
    fun `asr ending on its own keeps accumulated text and restarts without hint`() {
        controller.startVoiceConversation()
        assertEquals(VoiceConversationState.LISTENING, voiceState.state.value)

        stateStore.state.value = uiState(ASRStatus.Listening)
        voiceState.transcript.value = "帮我查一下"
        // 服务端静音收尾:ASR 自己回到 Idle,用户并没有点结束
        stateStore.state.value = uiState(ASRStatus.Idle, transcript = "帮我查一下")

        assertTrue("ASR 自行结束不得发消息给模型", sent.isEmpty())
        assertEquals(VoiceConversationState.LISTENING, voiceState.state.value)
        assertEquals("已说出的文字必须留着", "帮我查一下", voiceState.transcript.value)
        assertNull("有累积文本时不提示重说", voiceState.hint.value)
        verify(exactly = 2) { audioCoordinator.startVoiceConversationListening(any()) }
    }

    @Test
    fun `asr error returns to listening without sending and keeps accumulated text`() {
        controller.startVoiceConversation()

        stateStore.state.value = uiState(ASRStatus.Listening)
        voiceState.transcript.value = "你好"
        stateStore.state.value = uiState(ASRStatus.Error, transcript = "你好")

        assertTrue("ASR 报错不得发消息给模型", sent.isEmpty())
        assertEquals(VoiceConversationState.LISTENING, voiceState.state.value)
        assertEquals("你好", voiceState.transcript.value)
        assertNull("有累积文本时不提示重说", voiceState.hint.value)
        // Error 状态下不自动重开,避免 start → Error 死循环
        verify(exactly = 1) { audioCoordinator.startVoiceConversationListening(any()) }
    }

    @Test
    fun `explicit finish submits the whole transcript exactly once`() {
        controller.startVoiceConversation()
        stateStore.state.value = uiState(ASRStatus.Listening)

        // 未点结束:识别文本怎么变都不发送
        voiceState.transcript.value = "帮我"
        stateStore.state.value = uiState(ASRStatus.Listening)
        voiceState.transcript.value = "帮我查一下明天北京天气"
        stateStore.state.value = uiState(ASRStatus.Listening)
        assertTrue("用户没说结束时不得发送", sent.isEmpty())

        controller.finishVoiceConversationListening()
        verify { audioCoordinator.stopVoiceConversationListening() }

        stateStore.state.value = uiState(ASRStatus.Idle, transcript = "帮我查一下明天北京天气")

        assertEquals(listOf("帮我查一下明天北京天气"), sent)
        assertEquals(VoiceConversationState.THINKING, voiceState.state.value)
        assertNull("提交后清掉轻提示", voiceState.hint.value)
        assertEquals("提交后清空累积文本", "", voiceState.transcript.value)
        // THINKING 期间不重开麦克风
        verify(exactly = 1) { audioCoordinator.startVoiceConversationListening(any()) }
    }

    @Test
    fun `empty transcript commit returns to listening without sending`() {
        controller.startVoiceConversation()
        stateStore.state.value = uiState(ASRStatus.Listening)

        controller.finishVoiceConversationListening()
        stateStore.state.value = uiState(ASRStatus.Idle, transcript = "")

        assertTrue("空文本不得发消息", sent.isEmpty())
        assertEquals(VoiceConversationState.LISTENING, voiceState.state.value)
        assertEquals(RETRY_HINT, voiceState.hint.value)
        verify(exactly = 2) { audioCoordinator.startVoiceConversationListening(any()) }
    }

    @Test
    fun `commit followed by asr error still does not send`() {
        controller.startVoiceConversation()
        stateStore.state.value = uiState(ASRStatus.Listening)
        voiceState.transcript.value = "帮我看"

        controller.finishVoiceConversationListening()
        // 收尾过程中识别报错:这段文本不可信,不能发给模型
        stateStore.state.value = uiState(ASRStatus.Error, transcript = "帮我看")

        assertTrue("Error 收尾不得发消息", sent.isEmpty())
        assertEquals(VoiceConversationState.LISTENING, voiceState.state.value)
        assertEquals("累积文本保留,用户再点一次结束即可", "帮我看", voiceState.transcript.value)
        assertNull("有累积文本时不提示重说", voiceState.hint.value)
    }

    @Test
    fun `microphone stays closed while speaking and reopens only on listening`() {
        controller.startVoiceConversation()
        stateStore.state.value = uiState(ASRStatus.Listening)
        voiceState.transcript.value = "在吗"
        controller.finishVoiceConversationListening()
        stateStore.state.value = uiState(ASRStatus.Idle, transcript = "在吗")
        assertEquals(VoiceConversationState.THINKING, voiceState.state.value)

        // THINKING → SPEAKING:播报前必须关麦,且不重开
        stateStore.messages.value = listOf(UIMessage(role = MessageRole.ASSISTANT, content = "在的。"))
        stateStore.state.value = uiState(ASRStatus.Idle, isStreaming = true)
        stateStore.state.value = uiState(ASRStatus.Idle, isStreaming = false)
        assertEquals(VoiceConversationState.SPEAKING, voiceState.state.value)
        // 一次提交路径上的 stop + 进入 SPEAKING 时的 stop,都可能命中;关键是此时没有 start
        verify(exactly = 1) { audioCoordinator.startVoiceConversationListening(any()) }

        // 播报期间再来几次状态抖动,依然不重开麦克风
        stateStore.state.value = uiState(ASRStatus.Idle, isSpeaking = true)
        stateStore.state.value = uiState(ASRStatus.Idle, isSpeaking = true)
        verify(exactly = 1) { audioCoordinator.startVoiceConversationListening(any()) }

        // 朗读结束 → LISTENING,这时才重开一次
        stateStore.state.value = uiState(ASRStatus.Idle, isSpeaking = false)
        assertEquals(VoiceConversationState.LISTENING, voiceState.state.value)
        verify(exactly = 2) { audioCoordinator.startVoiceConversationListening(any()) }
    }

    @Test
    fun `main button toggles between start recording and finish`() {
        controller.startVoiceConversation()
        verify(exactly = 1) { audioCoordinator.startVoiceConversationListening(any()) }

        // LISTENING 但 ASR 未在录音:点一下 = 重新开始录音,不提交
        stateStore.state.value = uiState(ASRStatus.Idle)
        controller.toggleVoiceConversation()
        verify(exactly = 2) { audioCoordinator.startVoiceConversationListening(any()) }
        assertTrue(sent.isEmpty())

        // LISTENING 且录音中:点一下 = 结束并提交
        stateStore.state.value = uiState(ASRStatus.Listening)
        voiceState.transcript.value = "你好呀"
        controller.toggleVoiceConversation()
        verify { audioCoordinator.stopVoiceConversationListening() }
        stateStore.state.value = uiState(ASRStatus.Idle, transcript = "你好呀")
        assertEquals(listOf("你好呀"), sent)
        assertEquals(VoiceConversationState.THINKING, voiceState.state.value)

        // THINKING 下点一下 = 中断(保持既有语义)
        controller.toggleVoiceConversation()
        assertEquals(VoiceConversationState.IDLE, voiceState.state.value)
        verify { audioCoordinator.cancelVoiceConversationListening() }
    }
}
