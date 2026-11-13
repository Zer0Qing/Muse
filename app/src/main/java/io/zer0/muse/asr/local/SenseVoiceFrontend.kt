package io.zer0.muse.asr.local

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * SenseVoice 音频前处理：80 维 log-mel fbank + LFR 拼帧 + CMVN 归一化。
 *
 * ONNX 模型只接受已经算好的特征，识别质量完全取决于这条链路是否与训练时一致。
 * 中间计算全程用 Double —— mel 求和与 FFT 用 Float 累加会有精度损失，会让识别乱码。
 *
 * 参数与模型训练配置一致：16 kHz、25 ms 窗 / 10 ms 移、Povey 窗、去直流、
 * 预加重 0.97、snip_edges=false（两端反射补齐）、20 Hz ~ (Nyquist-400) Hz、
 * ln(max(mel, 1e-10))。LFR / CMVN 的维度参数由模型 metadata 提供，见
 * [SenseVoiceEngine.ModelParams]。
 */
internal object SenseVoiceFrontend {

    private const val FRAME_LENGTH_MS = 25.0
    private const val FRAME_SHIFT_MS = 10.0
    private const val NUM_MEL_BINS = 80
    private const val LOW_FREQ = 20.0
    /** 负值表示相对 Nyquist 的偏移（8000 - 400 = 7600 Hz）。 */
    private const val HIGH_FREQ_OFFSET = -400.0
    private const val PREEMPH = 0.97
    private const val LOG_FLOOR = 1e-10

    /** Mel 三角滤波器组缓存：同参数下只算一次（构建成本约 80×257 次三角函数）。 */
    private var cachedBanks: Array<DoubleArray>? = null
    private var cachedBanksKey: Pair<Int, Int>? = null

    /**
     * 完整前端：PCM 16-bit → [T, 560] 归一化特征。
     *
     * @param lfrWindow LFR 拼帧窗口（模型 metadata lfr_window_size，通常 7）
     * @param lfrShift LFR 帧移（模型 metadata lfr_window_shift，通常 6）
     * @param negMean CMVN 均值（模型 metadata，lfrWindow×80 维）
     * @param invStddev CMVN 标准差倒数（模型 metadata，lfrWindow×80 维）
     */
    fun compute(
        pcm: ShortArray,
        sampleRate: Int,
        lfrWindow: Int,
        lfrShift: Int,
        negMean: DoubleArray,
        invStddev: DoubleArray,
    ): Array<FloatArray> = applyCmvn(applyLfr(logMelFbank(pcm, sampleRate), lfrWindow, lfrShift), negMean, invStddev)

    /**
     * 80 维 log-mel fbank，返回 [帧数][80]。
     *
     * snip_edges=false：先在两端各反射补 frame_len/2 个采样点再按 10 ms 步进取帧，
     * 保证首尾音频都被完整覆盖（帧数 ≈ len/shift）。
     */
    internal fun logMelFbank(pcm: ShortArray, sampleRate: Int): Array<DoubleArray> {
        if (pcm.isEmpty()) return emptyArray()
        val frameLen = (FRAME_LENGTH_MS * sampleRate / 1000.0).roundToInt()
        val frameShift = (FRAME_SHIFT_MS * sampleRate / 1000.0).roundToInt()
        val pad = frameLen / 2
        var nfft = 1
        while (nfft < frameLen) nfft = nfft shl 1
        val banks = melBanks(sampleRate, nfft)

        val numFrames = 1 + (pcm.size + pad * 2 - frameLen) / frameShift
        val windowed = DoubleArray(frameLen)
        val frames = Array(numFrames) { DoubleArray(NUM_MEL_BINS) }
        for (f in 0 until numFrames) {
            val start = f * frameShift
            // 1. 取帧 + 去直流
            var mean = 0.0
            for (n in 0 until frameLen) mean += sampleAt(pcm, start + n - pad)
            mean /= frameLen
            // 2. 预加重 + Povey 加窗（同帧内完成，prev 只在同帧内使用）
            var prev = sampleAt(pcm, start - pad) - mean
            windowed[0] = prev * povey(0, frameLen)
            for (n in 1 until frameLen) {
                val cur = sampleAt(pcm, start + n - pad) - mean
                windowed[n] = (cur - PREEMPH * prev) * povey(n, frameLen)
                prev = cur
            }
            // 3. 功率谱 + mel 滤波 + 取对数
            val power = rfftPower(windowed, nfft)
            val mel = frames[f]
            for (b in 0 until NUM_MEL_BINS) {
                var sum = 0.0
                val bank = banks[b]
                for (k in power.indices) sum += power[k] * bank[k]
                mel[b] = ln(max(sum, LOG_FLOOR))
            }
        }
        return frames
    }

    /**
     * LFR（Lower Frame Rate）：把连续 [window] 帧拼成 1 帧，步进 [shift] 帧。
     *
     * 左补 (window-1)/2 帧首帧复制、右补 window 帧尾帧复制 —— 补齐方向错会让
     * 所有输出帧相对音频的时间戳整体偏移，识别仍能出字但句首句尾丢字。
     * 80 维 × window = 560 维输出。
     */
    internal fun applyLfr(feat: Array<DoubleArray>, window: Int, shift: Int): Array<DoubleArray> {
        if (feat.isEmpty()) return emptyArray()
        val dim = feat[0].size
        val left = (window - 1) / 2
        val paddedLen = left + feat.size + window
        val frameAt = { i: Int ->
            when {
                i < left -> feat[0]
                i >= left + feat.size -> feat[feat.size - 1]
                else -> feat[i - left]
            }
        }
        val numOut = (feat.size + shift - 1) / shift
        return Array(numOut) { o ->
            val start = o * shift
            DoubleArray(dim * window) { j ->
                frameAt(start + j / dim)[j % dim]
            }
        }
    }

    /** CMVN：(feat + neg_mean) * inv_stddev，逐维。 */
    internal fun applyCmvn(feat: Array<DoubleArray>, negMean: DoubleArray, invStddev: DoubleArray): Array<FloatArray> {
        require(negMean.size == invStddev.size) { "CMVN 参数维度不一致: ${negMean.size} vs ${invStddev.size}" }
        val dim = negMean.size
        return Array(feat.size) { f ->
            val row = feat[f]
            require(row.size == dim) { "特征维度 ${row.size} 与 CMVN 维度 $dim 不一致" }
            FloatArray(dim) { d -> ((row[d] + negMean[d]) * invStddev[d]).toFloat() }
        }
    }

    /** 构建 Mel 三角滤波器组（带缓存）。返回 [80][nfft/2+1]。 */
    private fun melBanks(sampleRate: Int, nfft: Int): Array<DoubleArray> {
        cachedBanksKey?.let { (sr, fft) ->
            if (sr == sampleRate && fft == nfft) return cachedBanks!!
        }
        val bins = nfft / 2 + 1
        val nyquist = sampleRate / 2.0
        val highFreq = nyquist + HIGH_FREQ_OFFSET
        val lowMel = melScale(LOW_FREQ)
        val highMel = melScale(highFreq)
        // num_bins+2 个边界点，中间 num_bins 个是三角窗中心
        val boundaries = DoubleArray(NUM_MEL_BINS + 2) { i ->
            melScaleInv(lowMel + (highMel - lowMel) * i / (NUM_MEL_BINS + 1))
        }
        val banks = Array(NUM_MEL_BINS) { DoubleArray(bins) }
        for (b in 0 until NUM_MEL_BINS) {
            val left = boundaries[b]
            val center = boundaries[b + 1]
            val right = boundaries[b + 2]
            for (k in 0 until bins) {
                val freq = nyquist * k / (bins - 1)
                val up = (freq - left) / max(center - left, 1e-9)
                val down = (right - freq) / max(right - center, 1e-9)
                banks[b][k] = max(0.0, minOf(up, down))
            }
        }
        cachedBanks = banks
        cachedBanksKey = sampleRate to nfft
        return banks
    }

    /** kaldi HTK 刻度：mel = 1127 ln(1 + f/700)。 */
    private fun melScale(f: Double): Double = 1127.0 * ln(1.0 + f / 700.0)

    private fun melScaleInv(m: Double): Double = 700.0 * (exp(m / 1127.0) - 1.0)

    /** Povey 窗：(0.5 - 0.5·cos(2πn/(N-1)))^0.85。 */
    private fun povey(n: Int, frameLen: Int): Double =
        (0.5 - 0.5 * cos(2.0 * PI * n / (frameLen - 1))).pow(0.85)

    /** 反射取样（对称反射，kaldi snip_edges=false 的补齐行为）。 */
    private fun sampleAt(pcm: ShortArray, index: Int): Double {
        val n = pcm.size
        if (n == 1) return pcm[0].toDouble()
        val period = 2 * (n - 1)
        var i = ((index % period) + period) % period
        if (i >= n) i = period - i
        return pcm[i].toDouble()
    }

    private fun Double.pow(p: Double): Double = exp(p * ln(this))

    /**
     * 实数 FFT 的功率谱：p[k] = (re² + im²) / nfft（kaldi 约定除以 nfft）。
     *
     * radix-2 迭代 Cooley-Tukey；[frame] 不足 nfft 时尾部视为 0。
     */
    internal fun rfftPower(frame: DoubleArray, nfft: Int): DoubleArray {
        require(nfft >= frame.size && (nfft and (nfft - 1)) == 0) {
            "nfft($nfft) 必须是 ≥ 帧长(${frame.size})的 2 的幂"
        }
        val re = DoubleArray(nfft)
        frame.copyInto(re)
        val im = DoubleArray(nfft)
        // 位反转置换
        var j = 0
        for (i in 1 until nfft) {
            var bit = nfft shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        // 蝶形
        var len = 2
        while (len <= nfft) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang)
            val wIm = kotlin.math.sin(ang)
            for (start in 0 until nfft step len) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = re[start + k]
                    val uIm = im[start + k]
                    val vRe = re[start + k + len / 2] * curRe - im[start + k + len / 2] * curIm
                    val vIm = re[start + k + len / 2] * curIm + im[start + k + len / 2] * curRe
                    re[start + k] = uRe + vRe
                    im[start + k] = uIm + vIm
                    re[start + k + len / 2] = uRe - vRe
                    im[start + k + len / 2] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
            }
            len = len shl 1
        }
        val bins = nfft / 2 + 1
        val power = DoubleArray(bins)
        for (k in 0 until bins) {
            // rfft 的第 0 与 Nyquist 项是实数，im 为 0；其余利用共轭对称直接取正频率分量
            power[k] = (re[k] * re[k] + im[k] * im[k]) / nfft
        }
        return power
    }
}
