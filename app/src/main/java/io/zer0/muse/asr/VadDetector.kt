package io.zer0.muse.asr

import android.os.SystemClock

/**
 * 简单能量阈值 VAD(Voice Activity Detection)静音检测器。
 *
 * 原理:
 *  - 计算每帧 PCM 的 RMS 能量(归一化 0-1f,复用 [AudioAmplitude.calculateRmsAmplitude])
 *  - 低于 [threshold] 视为静音帧,高于视为语音帧
 *  - 静音帧累计时长超过 [silenceDurationMs] 时,[processFrame] 返回 true(触发上层 flush/停止)
 *  - 检测到语音帧时重置静音计时
 *
 * 用途:
 *  - 在分段批量 ASR(Whisper/Step/DashScope)中模拟句子分割:静音超阈值 -> 触发 flush
 *  - 在 Realtime ASR 中:可选触发本地 stop(服务端 VAD 已可处理,通常不需要本地)
 *
 * 使用方式:
 * ```
 * val vad = VadDetector(threshold = 0.05f, silenceDurationMs = 1500L)
 * while (recording) {
 *     val read = recorder.read(buf, 0, buf.size)
 *     val amp = AudioAmplitude.calculateRmsAmplitude(buf, read)
 *     if (vad.processFrame(buf, read, amp)) {
 *         // 静音超阈值,触发 flush 或停止
 *     }
 * }
 * ```
 *
 * 注:本实现是简单能量阈值法,不适合复杂噪声环境。如需更精准的 VAD,可考虑
 * WebRTC VAD 或 Silero VAD 模型(需引入 native 库或 ONNX Runtime)。
 *
 * @param threshold 静音判定阈值(归一化 RMS 0-1f,默认 0.05f ≈ -46dB)
 * @param silenceDurationMs 静音自动触发的累计时长(毫秒,默认 1500ms)
 * @param minSpeechDurationMs 最小语音时长(毫秒,默认 300ms);
 *   语音段短于此值视为噪声脉冲,不重置静音计时(避免短暂噪声打断静音累计)
 */
class VadDetector(
    private val threshold: Float = DEFAULT_THRESHOLD,
    private val silenceDurationMs: Long = DEFAULT_SILENCE_DURATION_MS,
    private val minSpeechDurationMs: Long = DEFAULT_MIN_SPEECH_DURATION_MS,
) {
    /** 当前是否处于静音状态。 */
    @Volatile private var inSilence: Boolean = true
    /** 静音开始的 elapsedRealtime(进入静音时记录)。 */
    @Volatile private var silenceStartMs: Long = 0L
    /** 当前语音段开始的 elapsedRealtime(语音帧首次出现时记录)。 */
    @Volatile private var speechStartMs: Long = 0L
    /** 上一次触发后是否已重置(避免连续触发)。 */
    @Volatile private var triggered: Boolean = false

    /**
     * 处理一帧 PCM 音频,返回是否触发静音超时事件。
     *
     * @param buffer PCM 16-bit 字节(本方法不重新计算 RMS,直接用 [amplitude] 入参)
     * @param readBytes 实际读取字节数(本方法不使用,保留参数便于扩展)
     * @param amplitude 当前帧归一化 RMS 振幅(0-1f,由 [AudioAmplitude.calculateRmsAmplitude] 计算)
     * @return true 表示静音已持续超过 [silenceDurationMs],上层应触发 flush/停止;
     *         同一次静音只触发一次,后续静音帧不再重复返回 true,直到检测到新的语音段
     */
    fun processFrame(buffer: ByteArray, readBytes: Int, amplitude: Float): Boolean {
        val now = SystemClock.elapsedRealtime()
        val isSilent = amplitude < threshold

        if (isSilent) {
            if (!inSilence) {
                // 语音 -> 静音 转换:记录静音开始
                inSilence = true
                silenceStartMs = now
                triggered = false
            }
            // 静音累计:首次超阈值触发一次
            if (!triggered && (now - silenceStartMs) >= silenceDurationMs) {
                triggered = true
                return true
            }
        } else {
            // 语音帧
            if (inSilence) {
                // 静音 -> 语音 转换:记录语音开始
                inSilence = false
                speechStartMs = now
            }
            // 语音持续超过 minSpeechDurationMs 才视为有效语音(避免噪声脉冲重置触发标志)
            if (triggered && (now - speechStartMs) >= minSpeechDurationMs) {
                triggered = false
            }
        }
        return false
    }

    /** 重置状态(下次 start() 前调用)。 */
    fun reset() {
        inSilence = true
        silenceStartMs = 0L
        speechStartMs = 0L
        triggered = false
    }

    /** 当前静音累计时长(毫秒),非静音态返回 0。 */
    fun currentSilenceDurationMs(): Long {
        if (!inSilence) return 0L
        return SystemClock.elapsedRealtime() - silenceStartMs
    }

    companion object {
        /** 默认静音阈值(归一化 RMS,约 -46dB,适合 VOICE_COMMUNICATION 源)。 */
        const val DEFAULT_THRESHOLD = 0.05f
        /** 默认静音自动触发时长:1500ms。 */
        const val DEFAULT_SILENCE_DURATION_MS = 1_500L
        /** 默认最小语音时长:300ms。 */
        const val DEFAULT_MIN_SPEECH_DURATION_MS = 300L
    }
}
