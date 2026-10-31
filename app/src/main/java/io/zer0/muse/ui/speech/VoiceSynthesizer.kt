package io.zer0.muse.ui.speech

import java.io.File

/**
 * 语音合成端口。
 *
 * [TtsManager] 是单例且构造即拉起系统引擎，工具层（`send_voice`）直接依赖它会让
 * 单测必须真起引擎。抽成接口后，单测注入假实现即可验证工具流程（与
 * [VoiceDurationProbe] 同一个理由：把不可测的 Android 服务挡在端口外）。
 *
 * 故意不做成 `TtsManager` 的父接口：`TtsManager` 在 detekt 基线里有
 * `TooManyFunctions` 豁免，而基线按类签名匹配 —— 给它加父类型会让这条豁免失效。
 */
fun interface VoiceSynthesizer {
    /**
     * 把 [text] 合成为音频写入 [outputFile]。
     *
     * @param emotionOverride 本次合成的情绪覆盖；空/空白时由实现回落到全局 TTS 配置的情绪。
     *   **只影响本次调用**，不写全局配置 —— TtsManager 是单例，朗读链路可能同时在跑。
     * @return 三种结果之一：首选引擎成功 / 回落系统音色成功 / 彻底失败（见 [TtsSynthResult]）。
     *   回落也算成功（音频可用），但调用方需要能把「不是用户配的音色」讲清楚。
     */
    suspend fun synthesizeTo(text: String, outputFile: File, emotionOverride: String?): TtsSynthResult
}
