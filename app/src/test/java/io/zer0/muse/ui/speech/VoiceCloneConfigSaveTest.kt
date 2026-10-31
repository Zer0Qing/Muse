package io.zer0.muse.ui.speech

import io.zer0.muse.data.MediaConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 语音克隆页保存配置的护栏。
 *
 * 克隆「成功」但 key 没落库，是线上最难查的一类静默失败：音色看起来已经换上，
 * 云端合成却因为 key 为空一直回落系统 TTS。这里只测要落库的那份配置，
 * 不拉起页面 —— 页面里能出错的只剩这一处纯计算。
 */
class VoiceCloneConfigSaveTest {

    @Test
    fun `克隆成功把引擎 音色和输入框里的 key 一起写进配置`() {
        val saved = MediaConfig(ttsEngine = "system", ttsVoice = "")
            .afterVoiceClone(engine = "elevenlabs", voiceId = "voice-abc", apiKey = "  sk-abc  ")

        assertEquals("P2-15: 引擎必须跟着克隆用的供应商走", "elevenlabs", saved.ttsEngine)
        assertEquals("voice-abc", saved.ttsVoice)
        assertEquals("输入框里的 key 要落库，前后空白去掉", "sk-abc", saved.ttsApiKey)
    }

    @Test
    fun `跟随供应商时不写手填 key`() {
        val current = MediaConfig(
            ttsEngine = "openai",
            ttsVoice = "alloy",
            ttsApiKey = "from-provider",
            ttsFollowProvider = true,
        )

        val saved = current.afterVoiceClone(engine = "elevenlabs", voiceId = "voice-abc", apiKey = "sk-abc")

        assertEquals("elevenlabs", saved.ttsEngine)
        assertEquals("voice-abc", saved.ttsVoice)
        assertEquals("跟随模式下 key 来自供应商，不能被手填值覆盖", "from-provider", saved.ttsApiKey)
    }

    @Test
    fun `跟随供应商且原本没有 key 时保持为空`() {
        val saved = MediaConfig(ttsFollowProvider = true)
            .afterVoiceClone(engine = "fish", voiceId = "ref-1", apiKey = "sk-abc")

        assertEquals("", saved.ttsApiKey)
    }
}
