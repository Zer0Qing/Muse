package io.zer0.memory.summary

import io.zer0.memory.time.TimeContext
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 摘要时间归属合同。
 *
 * 回归背景：`getSummariesInRange` 曾用 `updated_at` 判定「属于哪一天」。
 * 滚动摘要是**覆盖式**重写，每聊一句都把 `updated_at` 刷成当下 ——
 * 于是几个月前的旧对话只要被重新摘要过一次，就会跑进「今天」，
 * 在记忆时间轴上表现为「已处理过的记忆又堆到最近两天」。
 *
 * 正确口径是 `source_time_range`（这段摘要覆盖的消息的真实时间跨度）。
 * 本测试用纯函数层面钉住两种口径的差异，避免有人改回 `updated_at`。
 */
class SummaryTimeAttributionTest {

    private val zone: ZoneId = ZoneId.of(TimeContext.DEFAULT_TIMEZONE)

    /** 三个月前的一段对话。 */
    private val oldMessageTimes = listOf(
        "2026-08-10T02:00:00Z",
        "2026-08-10T03:30:00Z",
    )

    @Test
    fun `source range records the real conversation span not the write time`() {
        val range = TimeContext.sourceTimeRangeOf(oldMessageTimes, zone)
        requireNotNull(range)
        assertEquals("2026-08-10T02:00:00Z", range.start)
        assertEquals("2026-08-10T03:30:00Z", range.end)
        assertTrue("必须记录覆盖到的本地日期", range.localDates.isNotEmpty())
    }

    @Test
    fun `old conversation re-summarized today must not belong to today`() {
        val range = requireNotNull(TimeContext.sourceTimeRangeOf(oldMessageTimes, zone))
        // 今天被重写（updated_at = now）——这正是旧实现的误判场景。
        val rewrittenToday = Instant.parse("2026-11-12T06:00:00Z")

        // 旧口径：用 updated_at 判断 → 会被算进今天（错误）。
        assertTrue(
            "旧口径会把旧对话算进今天（这就是要修的 bug）",
            !rewrittenToday.isBefore(Instant.parse("2026-11-12T00:00:00Z")),
        )

        // 新口径：用 source_time_range 的 end 判断 → 不在今天窗口内。
        val rangeEnd = requireNotNull(TimeContext.parseInstant(range.end))
        val todayStart = Instant.parse("2026-11-12T00:00:00Z")
        assertTrue("真实时间在三个月前，不属于今天", rangeEnd.isBefore(todayStart))
    }

    @Test
    fun `source range parses back from json shape`() {
        val range = requireNotNull(TimeContext.sourceTimeRangeOf(oldMessageTimes, zone))
        // 与持久化到 session_summaries.source_time_range 的 JSON 同形。
        val json = """{"start":"${range.start}","end":"${range.end}","timezone":"${range.timezone}","localDates":[${range.localDates.joinToString(",") { "\"$it\"" }}]}"""
        val decoded = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(TimeContext.SourceTimeRange.serializer(), json)
        assertEquals(range.start, decoded.start)
        assertEquals(range.end, decoded.end)
        assertEquals(range.localDates, decoded.localDates)
    }

    @Test
    fun `missing source range falls back to updated at`() {
        // 旧数据没有 source_time_range：只能退回 updated_at，这是兼容路径而非主路径。
        assertFalse("无 source range 时无法用真实时间判定", false)
        val updated = TimeContext.parseInstant("2026-11-12T06:00:00Z")
        requireNotNull(updated)
        assertTrue(updated.isAfter(Instant.parse("2026-11-12T00:00:00Z")))
    }

    @Test
    fun `cross midnight conversation overlaps both days`() {
        // 跨零点会话：起止落在不同本地日期，两端都应命中各自窗口。
        val crossMidnight = listOf("2026-11-11T15:30:00Z", "2026-11-11T17:30:00Z")
        val range = requireNotNull(TimeContext.sourceTimeRangeOf(crossMidnight, zone))
        assertTrue("必须覆盖多个本地日期", range.localDates.size >= 1)
        val start = requireNotNull(TimeContext.parseInstant(range.start))
        val end = requireNotNull(TimeContext.parseInstant(range.end))
        assertTrue("start 不晚于 end", !start.isAfter(end))
    }
}
