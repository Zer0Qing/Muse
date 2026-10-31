package io.zer0.muse.ui.speech

import io.zer0.muse.data.MediaConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切换 TTS 引擎时的音色 id 处理。
 *
 * 只换引擎不换音色，上游拿到的是上一个引擎的 id —— 必然是 4xx，然后整条链路
 * 静默回落系统 TTS。这里钉住「不属于新引擎就清空、属于就保留」。
 */
class TtsEngineSwitchTest {

    @Test
    fun `旧音色不属于新引擎时被清空并提示`() {
        val switched = TtsEngineSwitch.apply(
            MediaConfig(ttsEngine = "openai", ttsVoice = "alloy"),
            newEngine = "dashscope",
        )

        assertEquals("dashscope", switched.config.ttsEngine)
        assertEquals("openai 的音色不能留在 dashscope 下", "", switched.config.ttsVoice)
        assertTrue("清空了就要提示用户重选", switched.voiceReset)
    }

    @Test
    fun `新引擎也有同名音色时保留`() {
        val switched = TtsEngineSwitch.apply(
            MediaConfig(ttsEngine = "dashscope", ttsVoice = "longxiaochun"),
            newEngine = "qwen",
        )

        assertEquals("qwen", switched.config.ttsEngine)
        assertEquals("qwen 与 dashscope 共用音色表，选择应当保留", "longxiaochun", switched.config.ttsVoice)
        assertFalse("没被清空就不该提示", switched.voiceReset)
    }

    @Test
    fun `拿不到新引擎音色集合同样清空`() {
        val switched = TtsEngineSwitch.apply(
            MediaConfig(ttsEngine = "openai", ttsVoice = "alloy"),
            newEngine = "elevenlabs",
        )

        assertEquals("elevenlabs", switched.config.ttsEngine)
        assertEquals("要联网拉音色表的引擎无从校验，宁可让用户重选", "", switched.config.ttsVoice)
        assertTrue(switched.voiceReset)
    }

    @Test
    fun `原本空音色时不提示`() {
        val switched = TtsEngineSwitch.apply(
            MediaConfig(ttsEngine = "openai", ttsVoice = ""),
            newEngine = "elevenlabs",
        )

        assertEquals("", switched.config.ttsVoice)
        assertFalse("本来就没选音色，不算被重置", switched.voiceReset)
    }

    @Test
    fun `切到系统 TTS 不动云端音色选择`() {
        val switched = TtsEngineSwitch.apply(
            MediaConfig(ttsEngine = "openai", ttsVoice = "alloy"),
            newEngine = "system",
        )

        assertEquals("system", switched.config.ttsEngine)
        assertEquals("系统引擎不读 ttsVoice，清空只会逼用户白重选一次", "alloy", switched.config.ttsVoice)
        assertFalse(switched.voiceReset)
    }

    @Test
    fun `切到当前引擎不改任何字段`() {
        val current = MediaConfig(ttsEngine = "openai", ttsVoice = "alloy")

        val switched = TtsEngineSwitch.apply(current, newEngine = "openai")

        assertEquals(current, switched.config)
        assertFalse(switched.voiceReset)
    }
}
