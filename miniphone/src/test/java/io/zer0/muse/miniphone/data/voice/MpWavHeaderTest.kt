package io.zer0.muse.miniphone.data.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WAV 头的字段布局。
 *
 * 逐字节对标准 RIFF/WAVE:偏移错了,识别服务只会回一句「格式不支持」,
 * 而本地看不出任何异常 —— 所以这里断言的是具体位置上的具体数值。
 */
class MpWavHeaderTest {

    @Test
    fun `头固定 44 字节且以 RIFF WAVE data 三段落收尾`() {
        val header = wavHeader(dataLength = 1_000, sampleRate = 16_000)

        assertEquals("WAV 头长度", 44, header.size)
        assertEquals("RIFF", ascii(header, 0, 4))
        assertEquals("WAVE", ascii(header, 8, 4))
        assertEquals("fmt ", ascii(header, 12, 4))
        assertEquals("data", ascii(header, 36, 4))
    }

    @Test
    fun `RIFF 块长度等于 dataLength 加 36`() {
        val dataLength = 3_200

        val header = wavHeader(dataLength = dataLength, sampleRate = 16_000)

        assertEquals(36 + dataLength, intLe(header, 4))
    }

    @Test
    fun `data 块长度等于 dataLength`() {
        val dataLength = 32_000

        val header = wavHeader(dataLength = dataLength, sampleRate = 16_000)

        assertEquals(dataLength, intLe(header, 40))
    }

    @Test
    fun `fmt 块写明 PCM 单声道 16 位与采样率`() {
        val header = wavHeader(dataLength = 0, sampleRate = 16_000, channels = 1, bitsPerSample = 16)

        assertEquals("fmt 块长度", 16, intLe(header, 16))
        assertEquals("PCM 格式号", 1, shortLe(header, 20))
        assertEquals("声道数", 1, shortLe(header, 22))
        assertEquals("采样率", 16_000, intLe(header, 24))
        assertEquals("每秒字节数 = 采样率 × 声道 × 位深 / 8", 32_000, intLe(header, 28))
        assertEquals("块对齐 = 声道 × 位深 / 8", 2, shortLe(header, 32))
        assertEquals("位深", 16, shortLe(header, 34))
    }

    @Test
    fun `立体声把块对齐与每秒字节数翻倍`() {
        val header = wavHeader(dataLength = 0, sampleRate = 8_000, channels = 2, bitsPerSample = 16)

        assertEquals("声道数", 2, shortLe(header, 22))
        assertEquals("采样率", 8_000, intLe(header, 24))
        assertEquals("块对齐", 4, shortLe(header, 32))
        assertEquals("每秒字节数", 32_000, intLe(header, 28))
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String = String(bytes, offset, length, Charsets.US_ASCII)

    private fun intLe(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun shortLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}
