package io.zer0.muse.asr.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ParseWavPcmTest {

    /** 构造一份最小合法的 16-bit PCM WAV 字节。 */
    private fun makeWav(samples: ShortArray, sampleRate: Int, channels: Int): ByteArray {
        val dataBytes = ByteArray(samples.size * 2)
        ByteBuffer.wrap(dataBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
        val out = ByteArrayOutputStream()
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2
        fun le16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        fun le32(v: Int) = out.write(
            byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()),
        )
        out.write("RIFF".toByteArray())
        le32(36 + dataBytes.size)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        le32(16)
        le16(1) // PCM
        le16(channels)
        le32(sampleRate)
        le32(byteRate)
        le16(blockAlign)
        le16(16)
        out.write("data".toByteArray())
        le32(dataBytes.size)
        out.write(dataBytes)
        return out.toByteArray()
    }

    @Test
    fun `parses mono wav and returns pcm with sample rate`() {
        val samples = shortArrayOf(0, 1000, -1000, 500)
        val result = parseWavPcm(makeWav(samples, 16_000, 1))
        assertEquals(16_000, result!!.second)
        assertEquals(samples.size, result.first.size)
        for (i in samples.indices) assertEquals(samples[i].toInt(), result.first[i].toInt())
    }

    /** 立体声按取平均混到单声道。 */
    @Test
    fun `mixes stereo down to mono by averaging`() {
        val result = parseWavPcm(makeWav(shortArrayOf(100, 300, -200, 400), 16_000, 2))
        assertEquals(2, result!!.first.size)
        assertEquals(200, result.first[0].toInt())
        assertEquals(100, result.first[1].toInt())
    }

    @Test
    fun `rejects non-wav bytes`() {
        assertNull(parseWavPcm("not a wav".toByteArray()))
    }

    @Test
    fun `rejects truncated wav`() {
        val wav = makeWav(shortArrayOf(1, 2, 3), 16_000, 1)
        assertNull(parseWavPcm(wav.copyOfRange(0, 20)))
    }

    @Test
    fun `rejects 8-bit wav`() {
        val dataBytes = byteArrayOf(0x7f.toByte(), 0x80.toByte())
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        fun le32(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun le16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        le32(36 + dataBytes.size)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        le32(16)
        le16(1)
        le16(1)
        le32(16_000)
        le32(16_000)
        le16(1)
        le16(8) // 8-bit，不支持
        out.write("data".toByteArray())
        le32(dataBytes.size)
        out.write(dataBytes)
        assertNull(parseWavPcm(out.toByteArray()))
    }
}
