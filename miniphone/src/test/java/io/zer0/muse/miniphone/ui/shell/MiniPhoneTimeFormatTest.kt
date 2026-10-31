package io.zer0.muse.miniphone.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 时间文本:相对时间(朋友圈、评论、消息列表共用)与状态栏、聊天分隔这两处另有口径的格式。
 */
class MiniPhoneTimeFormatTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun missingTimestampRendersEmpty() {
        assertEquals("", relativeTimeOf(epochMs = 0L, now = at(2026, 10, 29, 21, 51)))
    }

    @Test
    fun withinAMinuteShowsJustNow() {
        val now = at(2026, 10, 29, 21, 51)
        assertEquals("刚刚", relativeTimeOf(epochMs = now - 30_000L, now = now))
        assertEquals("差几秒也算刚刚", "刚刚", relativeTimeOf(epochMs = now - 59_999L, now = now))
    }

    @Test
    fun withinAnHourShowsMinutesAgo() {
        val now = at(2026, 10, 29, 21, 51)
        assertEquals("1 分钟前", relativeTimeOf(epochMs = now - 60_000L, now = now))
        assertEquals("12 分钟前", relativeTimeOf(epochMs = now - 12L * 60_000L, now = now))
        assertEquals("59 分钟前", relativeTimeOf(epochMs = now - 59L * 60_000L, now = now))
    }

    @Test
    fun earlierTodayShowsHoursAgo() {
        val now = at(2026, 10, 29, 21, 51)
        assertEquals("3 小时前", relativeTimeOf(epochMs = at(2026, 10, 29, 18, 0), now = now))
        assertEquals("跨过整点也按小时算", "11 小时前", relativeTimeOf(epochMs = at(2026, 10, 29, 10, 0), now = now))
    }

    @Test
    fun yesterdayCarriesTheClockIncludingBothEdges() {
        val now = at(2026, 10, 29, 21, 51)
        assertEquals("昨天 21:30", relativeTimeOf(epochMs = at(2026, 10, 28, 21, 30), now = now))
        assertEquals("昨天最早的一刻", "昨天 00:00", relativeTimeOf(epochMs = at(2026, 10, 28, 0, 0), now = now))
        assertEquals("昨天最后一刻", "昨天 23:59", relativeTimeOf(epochMs = at(2026, 10, 28, 23, 59), now = now))
    }

    @Test
    fun olderDateInTheSameYearShowsMonthDayAndClock() {
        val now = at(2026, 10, 29, 21, 51)
        assertEquals("10月26日 08:00", relativeTimeOf(epochMs = at(2026, 10, 26, 8, 0), now = now))
        assertEquals("1月1日 00:00", relativeTimeOf(epochMs = at(2026, 1, 1, 0, 0), now = now))
    }

    @Test
    fun previousYearShowsFullDateWithoutClock() {
        val now = at(2026, 10, 29, 21, 51)
        assertEquals("2025年12月31日", relativeTimeOf(epochMs = at(2025, 12, 31, 20, 0), now = now))
        assertEquals("2025年1月1日", relativeTimeOf(epochMs = at(2025, 1, 1, 9, 30), now = now))
    }

    @Test
    fun clockUsesTwoDigitHourAndMinute() {
        assertEquals("09:05", MiniPhoneTimeFormat.clock(at(2026, 10, 29, 9, 5)))
        assertEquals("21:51", MiniPhoneTimeFormat.clock(at(2026, 10, 29, 21, 51)))
    }

    @Test
    fun separatorCarriesDateOnlyWhenTheDayDiffers() {
        val now = at(2026, 10, 29, 21, 51)
        assertEquals("09:05", MiniPhoneTimeFormat.separator(epochMs = at(2026, 10, 29, 9, 5), now = now))
        assertEquals("昨天 23:30", MiniPhoneTimeFormat.separator(epochMs = at(2026, 10, 28, 23, 30), now = now))
        assertEquals("10 月 26 日 08:00", MiniPhoneTimeFormat.separator(epochMs = at(2026, 10, 26, 8, 0), now = now))
        assertEquals(
            "2025 年 12 月 31 日 20:00",
            MiniPhoneTimeFormat.separator(epochMs = at(2025, 12, 31, 20, 0), now = now),
        )
    }
}
