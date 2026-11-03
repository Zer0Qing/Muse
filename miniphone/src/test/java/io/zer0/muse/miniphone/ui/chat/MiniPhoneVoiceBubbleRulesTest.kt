package io.zer0.muse.miniphone.ui.chat

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音条的两个纯函数（小手机 P2b 第三批）。
 *
 * 语音条是这个模块里最容易"看着没事、其实不对"的东西：时长显示成 `0:00`
 * 会让用户以为对方发了条空语音；宽度按秒匀速映射会让短语音宽得离谱。
 * 两处都写成纯函数，就该用纯函数的方式钉住，不需要起 Compose。
 */
class MiniPhoneVoiceBubbleRulesTest {

    @Test
    fun `时长文案按 M SS 显示 零秒不显示成有声音`() {
        assertEquals("0:05", formatVoiceDuration(5_000L))
        assertEquals("1:05", formatVoiceDuration(65_000L))
        assertEquals("10:00", formatVoiceDuration(600_000L))
        assertEquals("59:59", formatVoiceDuration(3_599_000L))
    }

    @Test
    fun `超过一小时换成 H MM SS`() {
        assertEquals("1:00:00", formatVoiceDuration(3_600_000L))
        assertEquals("2:03:04", formatVoiceDuration(7_384_000L))
    }

    @Test
    fun `时长未知给占位而不是零秒`() {
        // 「没探测出来」与「真的是 0 秒」是两件事：显示 0:00 会让人以为语音坏了
        assertEquals("--:--", formatVoiceDuration(0L))
        assertEquals("--:--", formatVoiceDuration(-1L))
    }

    @Test
    fun `语音条宽度按时长分档而不是匀速映射`() {
        val short = voiceBubbleWidth(500L)
        val medium = voiceBubbleWidth(30_000L)
        val long = voiceBubbleWidth(120_000L)

        assertTrue("短档应最窄：$short / $medium / $long", short < medium && medium < long)
        // 同一档内不随时长变化：匀速映射会让 3 秒和 25 秒的条长得不一样，扫一眼看不出谁长
        assertEquals(voiceBubbleWidth(1_000L), voiceBubbleWidth(2_000L))
        assertEquals(voiceBubbleWidth(28_000L), voiceBubbleWidth(30_000L))
        // 0 时长按短档给一个能点得中的宽度，而不是塌成 0
        assertTrue(voiceBubbleWidth(0L) > 0.dp)
    }
}
