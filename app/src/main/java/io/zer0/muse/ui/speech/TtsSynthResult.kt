package io.zer0.muse.ui.speech

/**
 * 一次语音合成的结果。
 *
 * 只回答两件事：首选引擎成没成、有没有可用的兜底。调用方据此决定对用户怎么说，
 * 不必再从 Boolean 里猜「是 key 没配、音色不对，还是只是网断了」。
 */
sealed interface TtsSynthResult {

    /**
     * 首选引擎直接合成成功。
     *
     * 引擎为云端时即云端成功；引擎就是 system 时即系统 TTS 成功 —— 两者都不算降级，
     * 所以合成入口里它们落同一个分支，默认行为与改造前一致。
     */
    data object Success : TtsSynthResult

    /**
     * 云端引擎失败，回落到系统 TTS 且合成成功（声音不是用户配的那个）。
     *
     * @param reason 云端没生效的简短原因（解析层的 fallbackReason，或合成失败的简述）
     */
    data class SystemFallback(val reason: String) : TtsSynthResult

    /** 云端与系统 TTS 都没成，没有音频可用。 */
    data class Failure(val reason: String) : TtsSynthResult
}

/**
 * 云端分支的结果判定：云成功即成功，否则看系统 TTS 能不能顶上。
 *
 * 抽成纯函数是为了让三条出口（成功 / 回落 / 失败）都能单测覆盖 —— 真起一次
 * 系统 TTS 引擎要等异步回调，测不出「系统可用但合成失败」这种分支。
 *
 * @param cloudOk 云端合成是否成功
 * @param failureReason 云端没成功时的简短原因，会原样带到 [TtsSynthResult.SystemFallback]
 * @param systemTtsReady 系统 TTS 当前是否就绪
 * @param synthWithSystem 真正去调系统 TTS 合成（仅在云端失败且系统就绪时执行）
 */
internal suspend fun resolveCloudSynthOutcome(
    cloudOk: Boolean,
    failureReason: String,
    systemTtsReady: Boolean,
    synthWithSystem: suspend () -> Boolean,
): TtsSynthResult {
    if (cloudOk) return TtsSynthResult.Success
    if (!systemTtsReady) return TtsSynthResult.Failure("$failureReason（系统 TTS 不可用）")
    return if (synthWithSystem()) {
        TtsSynthResult.SystemFallback(failureReason)
    } else {
        TtsSynthResult.Failure("$failureReason（系统 TTS 合成失败）")
    }
}
