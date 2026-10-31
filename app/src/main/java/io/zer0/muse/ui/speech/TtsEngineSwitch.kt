package io.zer0.muse.ui.speech

import io.zer0.muse.data.MediaConfig

/**
 * 一次引擎切换的产物。
 *
 * @param config 要落库的新配置（引擎已换成新的）
 * @param voiceReset 旧音色不属于新引擎、已被清空 —— UI 据此提示「音色已重置，请重新选择」
 */
data class TtsEngineSwitchResult(
    val config: MediaConfig,
    val voiceReset: Boolean,
)

/**
 * 切换 TTS 引擎时同步处理音色 id。
 *
 * 只换 [MediaConfig.ttsEngine] 会留下一份上一个引擎的音色 id：上游按新引擎解析这个 id，
 * 得到的是 4xx，然后整条链路静默回落到系统 TTS。所以切换时校验一次，
 * 不属于新引擎就置空（各合成实现遇到空音色都用自家默认音色，不会再 4xx）。
 */
object TtsEngineSwitch {

    /** 系统 TTS 的引擎 id（见 [MediaConfig.ttsEngine] 默认值）。 */
    private const val SYSTEM_ENGINE = "system"

    fun apply(config: MediaConfig, newEngine: String): TtsEngineSwitchResult {
        // 同一引擎不算切换；切到 system 也不动 ttsVoice —— 系统音色走 ttsVoiceName，
        // ttsVoice 在系统引擎下根本不参与合成，清空只会让用户切回云端后白重选一次。
        if (newEngine == config.ttsEngine || newEngine == SYSTEM_ENGINE) {
            return TtsEngineSwitchResult(config.copy(ttsEngine = newEngine), voiceReset = false)
        }
        val voice = config.ttsVoice
        // 拿不到新引擎的音色集合（要联网拉取的 ElevenLabs / MiniMax、克隆音色）时无法校验，
        // 按「不属于」处理：宁可让用户重选一次，也不把一个必然 4xx 的 id 送上去。
        val newVoiceIds = CloudTtsService.builtInVoiceIds(newEngine)
        val reset = voice.isNotBlank() && (newVoiceIds == null || voice !in newVoiceIds)
        return TtsEngineSwitchResult(
            config = config.copy(ttsEngine = newEngine, ttsVoice = if (reset) "" else voice),
            voiceReset = reset,
        )
    }
}
