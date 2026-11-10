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
            val dateLabel = FMT_DATE.get()?.format(Date(startDay)) ?: startDay.toString()
            val sb = StringBuilder(
                context.getString(
                    R.string.tool_calendar_header,
                    dateLabel,
                    if (days > 0) context.getString(R.string.tool_calendar_days_suffix, days + 1) else "",
                ),
            )
            var count = 0
            // 查 Instances 而不是 Events：重复日程在 Events 里只有一行，
            // 查它会漏掉今天之后的每一次（"每天的站会今天不见了"）。
            val uri = CalendarSemantics.instancesUri(startDay, endDay)
            try {
                cr.query(
                    uri,
                    CalendarSemantics.INSTANCE_PROJECTION,
                    null,
                    null,
                    "${android.provider.CalendarContract.Instances.BEGIN} ASC",
                )?.use { cursor ->
                    val titleIdx = cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Instances.TITLE)
                    val beginIdx = cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Instances.BEGIN)
                    val endIdx = cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Instances.END)
                    val locIdx = cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Instances.EVENT_LOCATION)
                    val idIdx = cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Instances.EVENT_ID)
                    val fmt = FMT_DATETIME_MIN.get() ?: SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    while (cursor.moveToNext()) {
                        val title = cursor.getString(titleIdx) ?: context.getString(R.string.tool_no_title)
                        val startMs = cursor.getLong(beginIdx)
                        val endMs = if (cursor.isNull(endIdx)) null else cursor.getLong(endIdx)
                        val loc = cursor.getString(locIdx)
                        val eventId = cursor.getLong(idIdx)
                        val timeRange = if (endMs != null && endMs > startMs) {
                            "${fmt.format(Date(startMs))} ~ ${fmt.format(Date(endMs))}"
                        } else {
                            fmt.format(Date(startMs))
                        }
                        // 带上 event_id：后续更新/删除要靠它，模型看不到就没法做对象级操作
                        sb.appendLine("  - $timeRange $title${if (loc.isNullOrBlank()) "" else " @$loc"} (id=$eventId)")
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
                    "all_day" to "可选,true/false,默认 false;全天事件按 UTC 午夜写入",
                    "calendar_id" to "可选,写入哪个日历;有多个可写日历时必须指定(不指定会列出候选)",
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
            val requestedEnd = parseDateTime(args["end_time"] ?: "")
            // 全天事件必须落 UTC 午夜（DTEND 不含当日）；用本地毫秒会让西半球整体前移一天。
            val requestedStart = startMs
            val (eventStartMs, eventEndMs) = if (allDay) {
                CalendarSemantics.normalizeAllDay(requestedStart, requestedEnd ?: (requestedStart + DAY_MS))
            } else {
                requestedStart to (requestedEnd ?: (requestedStart + HOUR_MS))
            }
            val requestedCalendarId = args["calendar_id"]?.trim()?.toLongOrNull()
            val calendarId = when (
                val choice = CalendarSemantics.chooseCalendar(listWritableCalendars(cr), requestedCalendarId)
            ) {
                is CalendarSemantics.CalendarChoice.Chosen -> choice.id
                is CalendarSemantics.CalendarChoice.Ambiguous -> return@register context.getString(
                    R.string.tool_calendar_choose_required,
                    choice.candidates.joinToString("、") { "${it.second}(id=${it.first})" },
                )
                is CalendarSemantics.CalendarChoice.UnknownId ->
                    return@register context.getString(R.string.tool_calendar_unknown_id, choice.id.toString())
                CalendarSemantics.CalendarChoice.None ->
                    return@register context.getString(R.string.tool_no_calendar_account)
            }
            val values = android.content.ContentValues().apply {
                put(android.provider.CalendarContract.Events.TITLE, title)
                put(android.provider.CalendarContract.Events.DTSTART, eventStartMs)
                put(android.provider.CalendarContract.Events.DTEND, eventEndMs)
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
                    // 把 event_id 交回去：没有它，下一步"改时间/删掉"就只能靠标题猜
                    context.getString(
                        R.string.tool_calendar_added_with_id,
                        title,
                        uri.lastPathSegment ?: "?",
                    )
                } else {
                    context.getString(R.string.tool_calendar_add_failed_perm)
                }
            }.onError { msg, _ -> Logger.w("CalendarTools", "添加日历事件失败: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_calendar_add_failed)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "update_calendar_event",
                description = "按 event_id 修改一个日历事件。需要 WRITE_CALENDAR 权限。",
                parameters = mapOf(
                    "event_id" to "必填,事件 id(calendar_today 输出里的 id=...)",
                    "title" to "可选,新标题",
                    "start_time" to "可选,新开始时间 ISO 8601 或 yyyy-MM-dd HH:mm",
                    "end_time" to "可选,新结束时间",
                    "description" to "可选,新描述",
                    "location" to "可选,新地点",
                    "all_day" to "可选,true/false;为 true 时按 UTC 午夜写入",
                    "scope" to "可选,all(默认,整个系列) / this(仅单次;当前不支持修改)",
                    "occurrence_time" to "可选,scope=this 时该次的开始时间",
                ),
                required = setOf("event_id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val cr = context.contentResolver
            val eventIdRaw = args["event_id"]
            val eventId = eventIdRaw?.trim()?.toLongOrNull()
            val scope = CalendarSemantics.RecurrenceScope.parse(args["scope"])
                ?: return@register context.getString(R.string.tool_calendar_invalid_scope, args["scope"].orEmpty())
            val occurrenceTime = parseDateTime(args["occurrence_time"] ?: "")
            val allDay = args["all_day"]?.takeIf { it.isNotBlank() }?.equals("true", ignoreCase = true)
            val startMs = parseDateTime(args["start_time"] ?: "")
            val endMs = parseDateTime(args["end_time"] ?: "")
            val values = android.content.ContentValues()
            var hasField = false
            args["title"]?.takeIf { it.isNotBlank() }?.let {
                values.put(android.provider.CalendarContract.Events.TITLE, it)
                hasField = true
            }
            if (startMs != null) {
                values.put(android.provider.CalendarContract.Events.DTSTART, startMs)
                hasField = true
            }
            if (endMs != null) {
                values.put(android.provider.CalendarContract.Events.DTEND, endMs)
                hasField = true
            }
            args["description"]?.let {
                values.put(android.provider.CalendarContract.Events.DESCRIPTION, it)
                hasField = true
            }
            args["location"]?.let {
                values.put(android.provider.CalendarContract.Events.EVENT_LOCATION, it)
                hasField = true
            }
            allDay?.let {
                values.put(android.provider.CalendarContract.Events.ALL_DAY, if (it) 1 else 0)
                hasField = true
            }
            CalendarSemantics.checkUpdate(eventId, scope, hasField, occurrenceTime)?.let { rejection ->
                return@register when (rejection) {
                    CalendarSemantics.MutationRejection.INVALID_ID ->
                        context.getString(R.string.tool_calendar_invalid_event_id, eventIdRaw.orEmpty())
                    CalendarSemantics.MutationRejection.NOTHING_TO_UPDATE ->
                        context.getString(R.string.tool_calendar_nothing_to_update)
                    CalendarSemantics.MutationRejection.THIS_SCOPE_NEEDS_OCCURRENCE_TIME ->
                        context.getString(R.string.tool_calendar_this_needs_occurrence)
                    CalendarSemantics.MutationRejection.THIS_SCOPE_UPDATE_UNSUPPORTED ->
                        context.getString(R.string.tool_calendar_this_update_unsupported)
                }
            }
            // 改成全天时同样要落 UTC 午夜，否则和新增路径不一致（西半球会前移一天）
            if (allDay == true && startMs != null) {
                val (utcStart, utcEnd) = CalendarSemantics.normalizeAllDay(
                    startMs,
                    endMs ?: (startMs + DAY_MS),
                )
                values.put(android.provider.CalendarContract.Events.DTSTART, utcStart)
                values.put(android.provider.CalendarContract.Events.DTEND, utcEnd)
            }
            val id = eventId ?: 0L
            resultOf {
                val rows = cr.update(
                    android.provider.CalendarContract.Events.CONTENT_URI,
                    values,
                    "${android.provider.CalendarContract.Events._ID} = ?",
                    arrayOf(id.toString()),
                )
                if (rows > 0) {
                    context.getString(R.string.tool_calendar_updated, id.toString())
                } else {
                    context.getString(R.string.tool_calendar_event_not_found, id.toString())
                }
            }.onError { msg, _ -> Logger.w("CalendarTools", "更新日历事件失败: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_calendar_update_failed, "")
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "delete_calendar_event",
                description = "按 event_id 删除日历事件；重复日程可只取消某一次。需要 WRITE_CALENDAR 权限。",
                parameters = mapOf(
                    "event_id" to "必填,事件 id",
                    "scope" to "可选,all(默认,整个系列) / this(只取消这一次)",
                    "occurrence_time" to "可选,scope=this 必填:该次的开始时间",
                ),
                required = setOf("event_id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val cr = context.contentResolver
            val eventIdRaw = args["event_id"]
            val eventId = eventIdRaw?.trim()?.toLongOrNull()
            val scope = CalendarSemantics.RecurrenceScope.parse(args["scope"])
                ?: return@register context.getString(R.string.tool_calendar_invalid_scope, args["scope"].orEmpty())
            val occurrenceTime = parseDateTime(args["occurrence_time"] ?: "")
            CalendarSemantics.checkDelete(eventId, scope, occurrenceTime)?.let { rejection ->
                return@register when (rejection) {
                    CalendarSemantics.MutationRejection.INVALID_ID ->
                        context.getString(R.string.tool_calendar_invalid_event_id, eventIdRaw.orEmpty())
                    CalendarSemantics.MutationRejection.THIS_SCOPE_NEEDS_OCCURRENCE_TIME ->
                        context.getString(R.string.tool_calendar_this_needs_occurrence)
                    else -> context.getString(R.string.tool_calendar_delete_failed, "")
                }
            }
            val id = eventId ?: 0L
            if (scope == CalendarSemantics.RecurrenceScope.THIS) {
                // 取消单次 = 写一条"例外行"（STATUS_CANCELED），而不是删整条重复规则
                return@register cancelSingleOccurrence(cr, id, occurrenceTime ?: 0L)
            }
            resultOf {
                val rows = cr.delete(
                    android.provider.CalendarContract.Events.CONTENT_URI,
                    "${android.provider.CalendarContract.Events._ID} = ?",
                    arrayOf(id.toString()),
                )
                if (rows > 0) {
                    context.getString(R.string.tool_calendar_deleted, id.toString())
                } else {
                    context.getString(R.string.tool_calendar_event_not_found, id.toString())
                }
            }.onError { msg, _ -> Logger.w("CalendarTools", "删除日历事件失败: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_calendar_delete_failed, "")
        }
    }

    /**
     * 取消重复日程中的某一次。
     *
     * CalendarContract 的做法是写一条**例外行**：`ORIGINAL_ID` 指向原事件、
     * `ORIGINAL_INSTANCE_TIME` 指向该次开始、`STATUS = STATUS_CANCELED`。
     * 直接删原事件会把整个系列删掉 —— 那是完全不同的语义。
     */
    private fun cancelSingleOccurrence(cr: android.content.ContentResolver, eventId: Long, occurrenceTime: Long): String = resultOf {
        val original = readEventForException(cr, eventId)
            ?: return@resultOf context.getString(R.string.tool_calendar_event_not_found, eventId.toString())
        val values = android.content.ContentValues().apply {
            put(android.provider.CalendarContract.Events.ORIGINAL_ID, eventId)
            put(android.provider.CalendarContract.Events.ORIGINAL_INSTANCE_TIME, occurrenceTime)
            put(android.provider.CalendarContract.Events.STATUS, android.provider.CalendarContract.Events.STATUS_CANCELED)
            put(android.provider.CalendarContract.Events.CALENDAR_ID, original.first)
            put(android.provider.CalendarContract.Events.DTSTART, occurrenceTime)
            put(android.provider.CalendarContract.Events.DTEND, occurrenceTime)
            put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        val uri = cr.insert(android.provider.CalendarContract.Events.CONTENT_EXCEPTION_URI, values)
        if (uri != null) {
            context.getString(R.string.tool_calendar_canceled_this, eventId.toString())
        } else {
            context.getString(R.string.tool_calendar_delete_failed, "")
        }
    }.onError { msg, _ -> Logger.w("CalendarTools", "取消单次日程失败: $msg") }
        .getOrNull() ?: context.getString(R.string.tool_calendar_delete_failed, "")

    /** 读原事件的日历 id 与时间（写例外行需要）。 */
    private fun readEventForException(cr: android.content.ContentResolver, eventId: Long): Pair<Long, Long>? = resultOf {
        cr.query(
            android.provider.CalendarContract.Events.CONTENT_URI,
            arrayOf(
                android.provider.CalendarContract.Events.CALENDAR_ID,
                android.provider.CalendarContract.Events.DTSTART,
            ),
            "${android.provider.CalendarContract.Events._ID} = ?",
            arrayOf(eventId.toString()),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getLong(0) to cursor.getLong(1)
            } else {
                null
            }
        }
    }.getOrNull()

    private fun parseDateTime(input: String): Long? {
        if (input.isBlank()) return null
        val formats = PARSE_FORMATS_TL.get() ?: return null
        for (fmt in formats) {
            val parsed = resultOf { fmt.parse(input) }.getOrNull()
            parsed?.time?.let { return it }
        }
        return null
    }

    /** 可写日历（VISIBLE=1 且非只读）；有多个时由调用方要求用户指定。 */
    private fun listWritableCalendars(cr: android.content.ContentResolver): List<Pair<Long, String>> = resultOf {
        cr.query(
            android.provider.CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                android.provider.CalendarContract.Calendars._ID,
                android.provider.CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            ),
            "${android.provider.CalendarContract.Calendars.VISIBLE} = 1 AND " +
                "${android.provider.CalendarContract.Calendars.IS_PRIMARY} IS NOT NULL",
            null,
            "${android.provider.CalendarContract.Calendars._ID} ASC",
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Calendars._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(
                android.provider.CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            )
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getLong(idIdx) to (cursor.getString(nameIdx) ?: ""))
                }
            }
        }
    }.getOrNull().orEmpty()

    private companion object {
        const val HOUR_MS = 60 * 60 * 1000L
        const val DAY_MS = 24 * HOUR_MS

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
