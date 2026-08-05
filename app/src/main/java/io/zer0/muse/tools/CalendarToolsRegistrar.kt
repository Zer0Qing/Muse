package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * P1-3b 拆域：日历工具注册器。
 *
 * 从 ToolRegistry.kt 抽出的 calendar_today / add_calendar_event，
 * 自带日期格式 ThreadLocal，不依赖 ToolRegistry 内部状态。
 */
class CalendarToolsRegistrar(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "calendar_today",
                description = "获取今日日历事件列表。需要 READ_CALENDAR 权限。",
                parameters = mapOf(
                    "date" to "可选,指定日期 YYYY-MM-DD,默认今天",
                    "days" to "可选,查询未来几天,默认 0=只今天",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args ->
            val cr = context.contentResolver
            val cal = java.util.Calendar.getInstance()
            val dateStr = args["date"]?.takeIf { it.isNotBlank() }
            if (dateStr != null) {
                val parts = dateStr.split("-")
                if (parts.size != 3) return@register context.getString(R.string.tool_date_format_error, dateStr)
                val y = parts[0].toIntOrNull() ?: return@register context.getString(R.string.tool_date_year_invalid, parts[0])
                val m = parts[1].toIntOrNull() ?: return@register context.getString(R.string.tool_date_month_invalid, parts[1])
                val d = parts[2].toIntOrNull() ?: return@register context.getString(R.string.tool_date_day_invalid, parts[2])
                try {
                    java.time.LocalDate.of(y, m, d)
                } catch (e: java.time.DateTimeException) {
                    return@register context.getString(R.string.tool_date_illegal, e.message ?: "", dateStr)
                }
                cal.set(y, m - 1, d)
            }
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            val startDay = cal.timeInMillis
            val days = args["days"]?.toIntOrNull() ?: 0
            if (days < 0) return@register context.getString(R.string.tool_days_negative)
            cal.add(java.util.Calendar.DAY_OF_MONTH, days + 1)
            val endDay = cal.timeInMillis
            val projection = arrayOf(
                android.provider.CalendarContract.Events.TITLE,
                android.provider.CalendarContract.Events.DTSTART,
                android.provider.CalendarContract.Events.DTEND,
                android.provider.CalendarContract.Events.EVENT_LOCATION,
            )
            val selection = "${android.provider.CalendarContract.Events.DTSTART} < ? AND " +
                "(${android.provider.CalendarContract.Events.DTEND} > ? OR " +
                "${android.provider.CalendarContract.Events.DTEND} IS NULL)"
            val selectionArgs = arrayOf(endDay.toString(), startDay.toString())
            val sortOrder = "${android.provider.CalendarContract.Events.DTSTART} ASC"
            val dateLabel = FMT_DATE.get()?.format(Date(startDay)) ?: startDay.toString()
            val sb = StringBuilder(
                context.getString(
                    R.string.tool_calendar_header,
                    dateLabel,
                    if (days > 0) context.getString(R.string.tool_calendar_days_suffix, days + 1) else "",
                ),
            )
            var count = 0
            try {
                cr.query(
                    android.provider.CalendarContract.Events.CONTENT_URI,
                    projection, selection, selectionArgs, sortOrder,
                )?.use { cursor ->
                    val titleIdx = cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Events.TITLE)
                    val startIdx = cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Events.DTSTART)
                    val endIdx = cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Events.DTEND)
                    val locIdx = cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Events.EVENT_LOCATION)
                    val fmt = FMT_DATETIME_MIN.get() ?: SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    while (cursor.moveToNext()) {
                        val title = cursor.getString(titleIdx) ?: context.getString(R.string.tool_no_title)
                        val startMs = cursor.getLong(startIdx)
                        val endMs = if (cursor.isNull(endIdx)) null else cursor.getLong(endIdx)
                        val loc = cursor.getString(locIdx)
                        val timeRange = if (endMs != null && endMs > startMs) {
                            "${fmt.format(Date(startMs))} ~ ${fmt.format(Date(endMs))}"
                        } else {
                            fmt.format(Date(startMs))
                        }
                        sb.appendLine("  - $timeRange $title${if (loc.isNullOrBlank()) "" else " @$loc"}")
                        count++
                    }
                }
            } catch (e: SecurityException) {
                return@register context.getString(R.string.tool_calendar_no_permission)
            }
            if (count == 0) context.getString(R.string.tool_no_calendar_events) else sb.toString().trimEnd()
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "add_calendar_event",
                description = "向系统日历添加一个新事件。需要 WRITE_CALENDAR 权限。",
                parameters = mapOf(
                    "title" to "必填,事件标题",
                    "start_time" to "必填,开始时间 ISO 8601 或 yyyy-MM-dd HH:mm,如 2026-07-10 09:00",
                    "end_time" to "可选,结束时间,默认开始时间+1小时",
                    "description" to "可选,事件描述",
                    "location" to "可选,地点",
                    "all_day" to "可选,true/false,默认 false",
                ),
                required = setOf("title", "start_time"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val cr = context.contentResolver
            val title = args["title"]?.takeIf { it.isNotBlank() }
                ?: return@register context.getString(R.string.tool_missing_param_title_event)
            val startTimeStr = args["start_time"] ?: return@register context.getString(R.string.tool_missing_param_start_time)
            val startMs = parseDateTime(startTimeStr)
                ?: return@register context.getString(R.string.tool_start_time_format_error)
            val allDay = args["all_day"].equals("true", ignoreCase = true)
            val endMs = if (allDay) {
                parseDateTime(args["end_time"] ?: "") ?: (startMs + 24 * 60 * 60 * 1000 - 1)
            } else {
                parseDateTime(args["end_time"] ?: "") ?: (startMs + 60 * 60 * 1000)
            }
            val calendarId = getDefaultCalendarId(cr)
                ?: return@register context.getString(R.string.tool_no_calendar_account)
            val values = android.content.ContentValues().apply {
                put(android.provider.CalendarContract.Events.TITLE, title)
                put(android.provider.CalendarContract.Events.DTSTART, startMs)
                put(android.provider.CalendarContract.Events.DTEND, endMs)
                put(android.provider.CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
                args["description"]?.takeIf { it.isNotBlank() }?.let {
                    put(android.provider.CalendarContract.Events.DESCRIPTION, it)
                }
                args["location"]?.takeIf { it.isNotBlank() }?.let {
                    put(android.provider.CalendarContract.Events.EVENT_LOCATION, it)
                }
                put(android.provider.CalendarContract.Events.CALENDAR_ID, calendarId)
                put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            resultOf {
                val uri = cr.insert(android.provider.CalendarContract.Events.CONTENT_URI, values)
                if (uri != null) {
                    context.getString(R.string.tool_calendar_added, title)
                } else {
                    context.getString(R.string.tool_calendar_add_failed_perm)
                }
            }.onError { msg, _ -> Logger.w("CalendarTools", "添加日历事件失败: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_calendar_add_failed)
        }
    }

    private fun parseDateTime(input: String): Long? {
        if (input.isBlank()) return null
        val formats = PARSE_FORMATS_TL.get() ?: return null
        for (fmt in formats) {
            val parsed = resultOf { fmt.parse(input) }.getOrNull()
            parsed?.time?.let { return it }
        }
        return null
    }

    private fun getDefaultCalendarId(cr: android.content.ContentResolver): Long? {
        return resultOf {
            cr.query(
                android.provider.CalendarContract.Calendars.CONTENT_URI,
                arrayOf(android.provider.CalendarContract.Calendars._ID),
                "${android.provider.CalendarContract.Calendars.VISIBLE} = ?",
                arrayOf("1"),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Calendars._ID))
                } else null
            }
        }.getOrNull()
    }

    private companion object {
        val FMT_DATE = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
        val FMT_DATETIME_MIN = ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
        val PARSE_FORMATS_PATTERNS = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy/MM/dd HH:mm",
            "yyyy-MM-dd'T'HH:mm:ss",
        )
        val PARSE_FORMATS_TL = ThreadLocal.withInitial {
            PARSE_FORMATS_PATTERNS.map { SimpleDateFormat(it, Locale.getDefault()) }
        }
    }
}
