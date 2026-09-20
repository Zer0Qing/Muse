package io.zer0.muse.ui.chat

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.asr.AsrConfig
import io.zer0.muse.asr.AsrProviderType
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.ChatUiState
import io.zer0.muse.ui.speech.TtsManager
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
            AsrProviderType.DASHSCOPE, AsrProviderType.STEP,
            AsrProviderType.OPENAI_WHISPER, AsrProviderType.OPENAI_REALTIME, AsrProviderType.AGNES,
        ).forEach { p ->
            assertTrue("$p 应走 API 录音路径(P2-17)", coordinator(p).shouldUseApiRecording())
        }
    }
}
