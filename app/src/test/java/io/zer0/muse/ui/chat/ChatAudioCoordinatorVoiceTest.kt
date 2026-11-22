package io.zer0.muse.ui.chat

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.zer0.muse.asr.ASRController
import io.zer0.muse.asr.ASRState
import io.zer0.muse.asr.ASRStatus
import io.zer0.muse.asr.AsrClientFactory
import io.zer0.muse.asr.AsrConfig
import io.zer0.muse.asr.AsrProviderType
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.speech.TtsManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-2(部分): 语音协调器纯判定层测试。
 *
 * 守护 P2-17 — 语音输入快捷方式在 SYSTEM(默认)下是否静默无响应:
 * 任意非 SYSTEM / 非文件转录 Provider 必须走 API 录音路径(缺 key 也要向用户
 * 显式报错,而不是悄悄降级到系统识别)。其余状态机/TTS 生命周期深度测试依赖
 * Robolectric,已在 memory 模块启用而 app 模块未启用,留作后续工程项。
 */
class ChatAudioCoordinatorVoiceTest {

    private fun coordinator(provider: AsrProviderType): ChatAudioCoordinator {
        val snapshot = mockk<ChatUiState>(relaxed = true)
        every { snapshot.asrConfig } returns AsrConfig(provider = provider)
        val accessor = mockk<ChatStateAccessor>(relaxed = true)
        every { accessor.snapshot } returns snapshot
        return ChatAudioCoordinator(
            accessor = accessor,
            ttsManager = mockk<TtsManager>(relaxed = true),
            settings = mockk<SettingsRepository>(relaxed = true),
            context = mockk<Context>(relaxed = true),
        )
    }

    @Test
    fun `canceling voice listening resets ASR state and ignores late controller emissions`() {
        val initial = ChatUiState(
            asrConfig = AsrConfig(provider = AsrProviderType.STEP, apiKey = "test-key"),
        )
        val accessor = InMemoryChatStateAccessor(initial)
        val controller = mockk<ASRController>(relaxed = true)
        val controllerState = MutableStateFlow(
            ASRState(status = ASRStatus.Listening, isAvailable = true),
        )
        every { controller.state } returns controllerState
        mockkObject(AsrClientFactory)
        every { AsrClientFactory.createController(any(), any()) } returns controller
        try {
            val coordinator = ChatAudioCoordinator(
                accessor = accessor,
                ttsManager = mockk<TtsManager>(relaxed = true),
                settings = mockk<SettingsRepository>(relaxed = true),
                context = mockk<Context>(relaxed = true),
            )

            coordinator.startVoiceConversationListening {}
            assertEquals(ASRStatus.Listening, accessor.snapshot.asrState.status)

            coordinator.cancelVoiceConversationListening()
            assertEquals(ASRState(), accessor.snapshot.asrState)

            controllerState.value = ASRState(status = ASRStatus.Error, errorMessage = "late event")
            assertEquals(ASRState(), accessor.snapshot.asrState)
        } finally {
            unmockkObject(AsrClientFactory)
        }
    }

    @Test
    fun `system provider does not use api recording`() {
        assertFalse(coordinator(AsrProviderType.SYSTEM).shouldUseApiRecording())
    }

    @Test
    fun `file transcript provider does not use api recording`() {
        assertFalse(coordinator(AsrProviderType.DASHSCOPE_FILE).shouldUseApiRecording())
    }

    @Test
    fun `every api provider uses api recording`() {
        listOf(
            AsrProviderType.DASHSCOPE,
            AsrProviderType.STEP,
            AsrProviderType.OPENAI_WHISPER,
            AsrProviderType.OPENAI_REALTIME,
            AsrProviderType.AGNES,
        ).forEach { p ->
            assertTrue("$p 应走 API 录音路径(P2-17)", coordinator(p).shouldUseApiRecording())
        }
    }
}
