package io.zer0.muse.data.emotion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [MoodParser] 与 [EmotionStats] 的纯逻辑单测。
 *
 * MoodParser 是主动消息情绪识别的底层解析器(被 ProactiveMessageRunner / MessageDao 使用),
 * 属"零覆盖但高频在用"的关键纯函数。
 */
class MoodParserTest {

    @Test
    fun `parses value label and description`() {
        val r = MoodParser.parse("""<mood value="0.5" label="positive">happy today</mood>""")
        assertEquals(0.5f, r!!.value, 0.0001f)
        assertEquals("positive", r.label)
        assertEquals("happy today", r.description)
    }

    @Test
    fun `clamps value above range to 2`() {
        val r = MoodParser.parse("""<mood value="9" label="x">d</mood>""")
        assertEquals(2f, r!!.value, 0.0001f)
    }

    @Test
    fun `clamps value below range to -2`() {
        val r = MoodParser.parse("""<mood value="-9" label="x">d</mood>""")
        assertEquals(-2f, r!!.value, 0.0001f)
    }

    @Test
    fun `returns null when there is no mood tag`() {
        assertNull(MoodParser.parse("just a plain message"))
    }

    @Test
    fun `returns null when value is not numeric`() {
        assertNull(MoodParser.parse("""<mood value="abc" label="x">d</mood>"""))
    }

    @Test
    fun `strip removes the tag and trims the result`() {
        val stripped = MoodParser.stripMoodTags(
            """before <mood value="1" label="p">d</mood> after""",
        )
        assertEquals("before  after", stripped)
    }

    @Test
    fun `stats from entries computes average highest lowest and buckets`() {
        val entries = listOf(
            EmotionEntry(id = "1", sessionId = "s", value = 1.0f),
            EmotionEntry(id = "2", sessionId = "s", value = 0f),
            EmotionEntry(id = "3", sessionId = "s", value = -1.0f),
        )
        val s = EmotionStats.fromEntries(entries, "week")
        assertEquals("week", s.period)
        assertEquals(3, s.entries.size)
        assertEquals(0f, s.average, 0.0001f)
        assertEquals(1f, s.highest, 0.0001f)
        assertEquals(-1f, s.lowest, 0.0001f)
        assertEquals(1, s.positiveCount)
        assertEquals(1, s.neutralCount)
        assertEquals(1, s.negativeCount)
    }

    @Test
    fun `stats from empty entries returns defaults`() {
        val s = EmotionStats.fromEntries(emptyList(), "month")
        assertEquals(0, s.entries.size)
        assertEquals(0f, s.average, 0.0001f)
        assertEquals(0, s.positiveCount)
        assertEquals(0, s.negativeCount)
    }
}
