package io.zer0.muse.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/** date_range 软过滤的日期解析：解析不到就降权，不误杀。 */
class WebSearchDateParserTest {

    private val now: Long = Calendar.getInstance(Locale.ROOT).apply {
        clear()
        set(2026, Calendar.NOVEMBER, 10)
    }.timeInMillis

    @Test
    fun `绝对日期 中英文格式`() {
        val t = WebSearchDateParser.parse("发布于 2026-09-22，来源科技网", now)
        val expected = Calendar.getInstance(Locale.ROOT).apply {
            clear()
            set(2026, Calendar.SEPTEMBER, 22)
        }.timeInMillis
        assertEquals(expected, t)
        assertEquals(expected, WebSearchDateParser.parse("2026年9月22日", now))
    }

    @Test
    fun `相对日期 X天前 与 hours ago`() {
        assertEquals(now - TimeUnit.DAYS.toMillis(3), WebSearchDateParser.parse("3天前发布", now))
        assertEquals(now - TimeUnit.HOURS.toMillis(5), WebSearchDateParser.parse("5小时前", now))
        assertEquals(now - TimeUnit.DAYS.toMillis(2), WebSearchDateParser.parse("2 days ago", now))
        assertEquals(now - TimeUnit.DAYS.toMillis(14), WebSearchDateParser.parse("2周前", now))
    }

    @Test
    fun `没有明确日期返回 null 供调用方降权`() {
        assertNull(WebSearchDateParser.parse("四川省专升本考试政策解读", now))
        assertNull(WebSearchDateParser.parse("最新消息汇总", now))
        assertNull(WebSearchDateParser.parse("", now))
    }

    @Test
    fun `非法日期不崩`() {
        assertNull(WebSearchDateParser.parse("2026-13-45", now))
        assertNull(WebSearchDateParser.parse("2026-00-00", now))
    }

    @Test
    fun `时间范围阈值`() {
        assertEquals(now - TimeUnit.DAYS.toMillis(1), WebSearchDateParser.earliestMillis("past_day", now))
        assertEquals(now - TimeUnit.DAYS.toMillis(7), WebSearchDateParser.earliestMillis("past_week", now))
        assertNull(WebSearchDateParser.earliestMillis("unknown", now))
        assertNull(WebSearchDateParser.earliestMillis(null, now))
    }

    @Test
    fun `超范围判定 老页面确实早于阈值`() {
        val threshold = WebSearchDateParser.earliestMillis("past_day", now)!!
        val old = WebSearchDateParser.parse("2025-09-16 发布", now)
        assertTrue("2025 年页面应早于 past_day 阈值", old != null && old < threshold)
    }
}
