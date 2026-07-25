package io.zer0.muse.asr

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 音量振幅计算工具(借鉴 rikkahub)。
 * 将 16-bit PCM 的 RMS 值转换为归一化的 0-1f 振幅,符合人耳对数感知。
 */
object AudioAmplitude {
    /**
     * 计算 PCM 16-bit 数据的 RMS 振幅,归一化到 0-1f。
     * -60dB~0dB 映射到 0~1f,低于 -60dB 返回 0。
     */
    fun calculateRmsAmplitude(buffer: ByteArray, readBytes: Int): Float {
        if (readBytes < 2) return 0f
        val shorts = ByteBuffer.wrap(buffer, 0, readBytes)
            .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sum = 0.0
        val count = shorts.remaining()
        if (count == 0) return 0f
        for (i in 0 until count) {
            val sample = shorts[i].toDouble()
            sum += sample * sample
        }
        val rms = sqrt(sum / count)
        val linear = (rms / Short.MAX_VALUE).toFloat()
        if (linear < 1e-6f) return 0f
        val db = 20f * log10(linear)
        return ((db + 60f) / 60f).coerceIn(0f, 1f)
    }

    /** 滑动窗口:追加一个振幅值,超过 maxSize 时丢弃最旧的。 */
    fun List<Float>.appendAmplitude(amplitude: Float, maxSize: Int = 32): List<Float> {
        val list = if (size >= maxSize) drop(1) else this
        return list + amplitude
    }
}
