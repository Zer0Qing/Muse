package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * 日历语义层（对比清单 P1-05）。
 *
 * 三条都对应"用户会真的踩到"的问题：
 *  - 全天事件用本地毫秒写入 → 跨时区整体前移一天；
 *  - 有多个可写日历却静默写进第一个 → 内容进了错误的日程表，比报错难查得多；
 *  - 查 `Events` 而不是 `Instances` → 重复日程只出现第一次。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CalendarSemanticsTest {

    private fun utcMidnightOf(date: String): Long = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun localMidnightOf(date: String, zone: String): Long =
        LocalDate.parse(date).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli()

    @Test
    fun `all-day events are stored at UTC midnight regardless of the user timezone`() {
        // 用户在 UTC+8 选了 11-02 全天：本地零点在 UTC 是 11-01T16:00Z
        val start = localMidnightOf("2026-11-02", "Asia/Shanghai")
        val (dtStart, dtEnd) = CalendarSemantics.normalizeAllDay(
            startMs = start,
            endMsExclusive = start + 24 * 60 * 60 * 1000L,
            zone = TimeZone.getTimeZone("Asia/Shanghai"),
        )

        assertEquals("DTSTART 必须是 11-02 的 UTC 零点", utcMidnightOf("2026-11-02"), dtStart)
        assertEquals("DTEND 不含当日，是 11-03 的 UTC 零点", utcMidnightOf("2026-11-03"), dtEnd)
    }

    @Test
    fun `a western timezone does not shift the all-day event to the previous day`() {
        // 用户在 UTC-8 选 11-02 全天：本地零点在 UTC 是 11-02T08:00Z。
        // 直接把本地毫秒写进去，在 UTC 日历里会落到 11-02 08:00，某些客户端显示成 11-01。
        val start = localMidnightOf("2026-11-02", "America/Los_Angeles")
        val (dtStart, _) = CalendarSemantics.normalizeAllDay(
            startMs = start,
            endMsExclusive = start + 24 * 60 * 60 * 1000L,
            zone = TimeZone.getTimeZone("America/Los_Angeles"),
        )

        assertEquals(utcMidnightOf("2026-11-02"), dtStart)
    }

    @Test
    fun `an end time at the last millisecond of a day belongs to that day`() {
        val start = localMidnightOf("2026-11-02", "Asia/Shanghai")
        val endOfSameDay = localMidnightOf("2026-11-03", "Asia/Shanghai") - 1

        val (_, dtEnd) = CalendarSemantics.normalizeAllDay(
            startMs = start,
            endMsExclusive = endOfSameDay,
            zone = TimeZone.getTimeZone("Asia/Shanghai"),
        )

        assertEquals("23:59:59.999 不能把结束日推到后天", utcMidnightOf("2026-11-03"), dtEnd)
    }

    @Test
    fun `a single writable calendar is chosen without asking`() {
        val choice = CalendarSemantics.chooseCalendar(listOf(1L to "个人"), requested = null)

        assertEquals(CalendarSemantics.CalendarChoice.Chosen(1L, "个人"), choice)
    }

    @Test
    fun `multiple calendars without an explicit choice are reported as ambiguous`() {
        val candidates = listOf(1L to "工作", 2L to "个人")

        val choice = CalendarSemantics.chooseCalendar(candidates, requested = null)

        assertTrue("不能替用户猜，必须让他指定", choice is CalendarSemantics.CalendarChoice.Ambiguous)
        assertEquals(candidates, (choice as CalendarSemantics.CalendarChoice.Ambiguous).candidates)
    }

    @Test
    fun `an explicit existing calendar wins even when there are several`() {
        val choice = CalendarSemantics.chooseCalendar(listOf(1L to "工作", 2L to "个人"), requested = 2L)

        assertEquals(CalendarSemantics.CalendarChoice.Chosen(2L, "个人"), choice)
    }

    @Test
    fun `an explicit missing calendar is reported instead of silently falling back`() {
        val choice = CalendarSemantics.chooseCalendar(listOf(1L to "工作"), requested = 99L)

        assertEquals(CalendarSemantics.CalendarChoice.UnknownId(99L), choice)
    }

    @Test
    fun `no writable calendar is its own case`() {
        assertEquals(
            CalendarSemantics.CalendarChoice.None,
            CalendarSemantics.chooseCalendar(emptyList(), requested = null),
        )
    }

    @Test
    fun `the instances uri carries the expansion window in its path`() {
        val start = Instant.parse("2026-11-02T00:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-11-03T00:00:00Z").toEpochMilli()

        val uri = CalendarSemantics.instancesUri(start, end)

        assertTrue("必须是 instances 而不是 events: $uri", uri.path.orEmpty().contains("instances"))
        assertTrue("窗口起点要进路径: $uri", uri.toString().contains(start.toString()))
        assertTrue("窗口终点要进路径: $uri", uri.toString().contains(end.toString()))
    }

    // ── 按 id 更新/删除的校验（P1-05 第二批） ────────────────────────────

    @Test
    fun `scope parsing accepts the documented words and rejects the rest`() {
        assertEquals(CalendarSemantics.RecurrenceScope.ALL, CalendarSemantics.RecurrenceScope.parse(null))
        assertEquals(CalendarSemantics.RecurrenceScope.ALL, CalendarSemantics.RecurrenceScope.parse(" ALL "))
        assertEquals(CalendarSemantics.RecurrenceScope.THIS, CalendarSemantics.RecurrenceScope.parse("This"))
        assertEquals(null, CalendarSemantics.RecurrenceScope.parse("sometimes"))
    }

    @Test
    fun `an invalid event id is rejected before touching the provider`() {
        listOf(null, 0L, -3L).forEach { id ->
            assertEquals(
                "id=$id 必须被拒绝",
                CalendarSemantics.MutationRejection.INVALID_ID,
                CalendarSemantics.checkUpdate(id, CalendarSemantics.RecurrenceScope.ALL, true, null),
            )
        }
    }

    @Test
    fun `an update without any field is rejected`() {
        assertEquals(
            CalendarSemantics.MutationRejection.NOTHING_TO_UPDATE,
            CalendarSemantics.checkUpdate(7L, CalendarSemantics.RecurrenceScope.ALL, hasAnyField = false, occurrenceTime = null),
        )
    }

    @Test
    fun `editing a single occurrence is refused with a reason instead of half working`() {
        assertEquals(
            "scope=this 缺 occurrence_time 要单独报",
            CalendarSemantics.MutationRejection.THIS_SCOPE_NEEDS_OCCURRENCE_TIME,
            CalendarSemantics.checkUpdate(7L, CalendarSemantics.RecurrenceScope.THIS, true, null),
        )
        assertEquals(
            "带了 occurrence_time 也要明确说不支持",
            CalendarSemantics.MutationRejection.THIS_SCOPE_UPDATE_UNSUPPORTED,
            CalendarSemantics.checkUpdate(7L, CalendarSemantics.RecurrenceScope.THIS, true, 1_700_000_000_000L),
        )
    }

    @Test
    fun `a normal whole-series update passes validation`() {
        assertEquals(
            null,
            CalendarSemantics.checkUpdate(7L, CalendarSemantics.RecurrenceScope.ALL, true, null),
        )
    }

    @Test
    fun `deleting a single occurrence requires its start time`() {
        assertEquals(
            CalendarSemantics.MutationRejection.THIS_SCOPE_NEEDS_OCCURRENCE_TIME,
            CalendarSemantics.checkDelete(7L, CalendarSemantics.RecurrenceScope.THIS, null),
        )
        assertEquals(
            "给了时间就放行（写例外行取消这一次）",
            null,
            CalendarSemantics.checkDelete(7L, CalendarSemantics.RecurrenceScope.THIS, 1_700_000_000_000L),
        )
        assertEquals(null, CalendarSemantics.checkDelete(7L, CalendarSemantics.RecurrenceScope.ALL, null))
    }

    @Test
    fun `deleting with an invalid id is rejected`() {
        assertEquals(
            CalendarSemantics.MutationRejection.INVALID_ID,
            CalendarSemantics.checkDelete(0L, CalendarSemantics.RecurrenceScope.ALL, null),
        )
    }
}
