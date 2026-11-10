package io.zer0.muse.asr.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class SenseVoiceFrontendTest {

    private val lfrWindow = 7
    private val lfrShift = 6
    private val dim = 80 * lfrWindow

    /** 1 kHz 正弦波 1 秒：功率谱主峰必须落在 1 kHz 对应的 bin 上。 */
    @Test
    fun `rfftPower peak lands on sine frequency bin`() {
        val sampleRate = 16_000
        val nfft = 512
        val frameLen = 400
        val signal = DoubleArray(frameLen) { n -> sin(2.0 * PI * 1_000.0 * n / sampleRate) }
        val power = SenseVoiceFrontend.rfftPower(signal, nfft)
        val peakBin = power.indices.maxBy { power[it] }
        val expectedBin = 1_000.0 / (sampleRate / 2.0) * (nfft / 2)
        assertEquals(expectedBin, peakBin.toDouble(), 1.5)
        // 主峰应显著高于均值
        val mean = power.sum() / power.size
        assertTrue(power[peakBin] > mean * 50)
    }

    /** 直流信号：除 bin 0 外功率接近 0（去直流前含直流分量，谱能量集中在 bin 0）。 */
    @Test
    fun `rfftPower of constant signal concentrates at bin 0`() {
        val nfft = 256
        val power = SenseVoiceFrontend.rfftPower(DoubleArray(nfft) { 0.5 }, nfft)
        val nonZeroSum = power.drop(1).sum()
        assertTrue("bin0=${power[0]}, rest=$nonZeroSum", power[0] > nonZeroSum * 1e6)
    }

    /** nfft 非 2 的幂或小于帧长必须抛异常，防止静默截断。 */
    @Test(expected = IllegalArgumentException::class)
    fun `rfftPower rejects invalid nfft`() {
        SenseVoiceFrontend.rfftPower(DoubleArray(400), 300)
    }

    /** fbank 输出形状：帧数 ≈ len/shift（snip_edges=false），每帧 80 维。 */
    @Test
    fun `logMelFbank shape matches expectation`() {
        val pcm = ShortArray(16_000) { n -> (1000 * sin(2.0 * PI * 440.0 * n / 16_000)).toInt().toShort() }
        val feat = SenseVoiceFrontend.logMelFbank(pcm, 16_000)
        // 1 秒 @ 10ms shift ≈ 100 帧
        assertEquals(100.0, feat.size.toDouble(), 3.0)
        feat.forEach { frame -> assertEquals(80, frame.size) }
    }

    /** 空输入直接返回空，不抛。 */
    @Test
    fun `logMelFbank handles empty pcm`() {
        assertTrue(SenseVoiceFrontend.logMelFbank(ShortArray(0), 16_000).isEmpty())
    }

    /** LFR：7 帧拼 1 帧、步进 6；左补首帧、右补尾帧；输出维度 560。 */
    @Test
    fun `applyLfr shapes and padding`() {
        val n = 20
        val feat = Array(n) { i -> DoubleArray(80) { j -> (i * 80 + j).toDouble() } }
        val out = SenseVoiceFrontend.applyLfr(feat, lfrWindow, lfrShift)
        assertEquals((n + lfrShift - 1) / lfrShift, out.size) // ceil(20/6) = 4
        out.forEach { row -> assertEquals(dim, row.size) }
        // 输出帧 0 从补齐索引 0 开始取 7 帧：前 left=3 帧是首帧复制，帧 left 是 feat[0]
        val left = (lfrWindow - 1) / 2
        assertEquals(feat[0][0], out[0][0], 0.0)
        assertEquals(feat[left][79], out[0][dim - 1], 0.0)
        // 步进 6：输出帧 1 从补齐索引 6 开始，去掉左补 3 帧 = feat[3]
        assertEquals(feat[lfrShift - left][0], out[1][0], 0.0)
        // 输入帧数很小、不足一窗时也必须输出至少 1 帧（靠两侧补齐）
        val tiny = SenseVoiceFrontend.applyLfr(Array(2) { DoubleArray(80) }, lfrWindow, lfrShift)
        assertEquals(1, tiny.size)
    }

    /** CMVN：(x + neg_mean) * inv_stddev 逐维；维度不匹配抛异常。 */
    @Test
    fun `applyCmvn math and dim check`() {
        val d = 4
        val feat = arrayOf(doubleArrayOf(1.0, 2.0, 3.0, 4.0))
        val negMean = doubleArrayOf(0.5, 0.5, 0.5, 0.5)
        val invStddev = doubleArrayOf(2.0, 2.0, 1.0, 1.0)
        val out = SenseVoiceFrontend.applyCmvn(feat, negMean, invStddev)
        assertEquals(1, out.size)
        assertEquals(3.0f, out[0][0], 1e-6f)
        assertEquals(5.0f, out[0][1], 1e-6f)
        assertEquals(3.5f, out[0][2], 1e-6f)
        assertEquals(4.5f, out[0][3], 1e-6f)
        try {
            SenseVoiceFrontend.applyCmvn(feat, doubleArrayOf(1.0, 2.0), invStddev)
            throw AssertionError("维度不一致应当抛异常")
        } catch (expected: IllegalArgumentException) {
            // 预期路径
        }
    }

    /** 端到端 compute：输出每帧 560 维 Float，且数值有限。 */
    @Test
    fun `compute end to end produces finite 560-dim features`() {
        val pcm = ShortArray(16_000) { n -> (3000 * sin(2.0 * PI * 220.0 * n / 16_000)).toInt().toShort() }
        val negMean = DoubleArray(dim) { 0.0 }
        val invStddev = DoubleArray(dim) { 1.0 }
        val out = SenseVoiceFrontend.compute(pcm, 16_000, lfrWindow, lfrShift, negMean, invStddev)
        assertTrue(out.isNotEmpty())
        out.forEach { row ->
            assertEquals(dim, row.size)
            row.forEach { v -> assertTrue(abs(v) < 1e6 && !v.isNaN()) }
        }
    }
}
