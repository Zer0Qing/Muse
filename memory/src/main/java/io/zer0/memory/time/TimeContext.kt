package io.zer0.memory.time

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 时间上下文工具 (openhanako time-context.ts + time-utils.ts logicalDay 移植)。
 *
 * 职责:
 *  - 解析记忆时区(默认 Asia/Shanghai)
 *  - 计算逻辑日(跨夜用户按 04:00 切日,避免深夜对话被切到"明天")
 *  - 格式化带时区时间(供摘要时间标注)
 *  - 从消息列表构建 source_time_range
 *  - 从摘要文本提取时间信号(YYYY-MM-DD HH:MM)
 *  - 规范化 fact 时间(校验 LLM 抽出的时间是否在 source 范围内)
 */
object TimeContext {

    /** 默认时区。 */
    const val DEFAULT_TIMEZONE = "Asia/Shanghai"

    /** 逻辑日切点: 凌晨 04:00 之前算前一天(避免深夜对话被切到"明天")。 */
    const val LOGICAL_DAY_CUTOVER_HOUR = 4

    /** 6 小时步进(用于 collectLocalDatesBetween,跨夜用户一天可能跨 2 个本地日)。 */
    const val SIX_HOURS_MS = 6L * 60 * 60 * 1000

    /** collectLocalDatesBetween 迭代上限(防止无限循环)。 */
    const val COLLECT_MAX_ITERATIONS = 400

    private val isoFormatter: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    private val summaryTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    /** 解析时区字符串,失败回退默认。 */
    fun resolveTimeZone(raw: String?): ZoneId =
        runCatching { ZoneId.of(raw?.takeIf { it.isNotBlank() } ?: DEFAULT_TIMEZONE) }
            .getOrElse { ZoneId.of(DEFAULT_TIMEZONE) }

    /** 逻辑日: 04:00 之前算前一天。返回逻辑日的 00:00 起点。 */
    fun getLogicalDay(now: Instant = Instant.now(), zone: ZoneId = ZoneId.of(DEFAULT_TIMEZONE)): LogicalDay {
        val zdt = ZonedDateTime.ofInstant(now, zone)
        val cutoff = zdt.toLocalDate().atStartOfDay(zone).plusHours(LOGICAL_DAY_CUTOVER_HOUR.toLong())
        val logicalDate = if (zdt.isBefore(cutoff)) {
            zdt.toLocalDate().minusDays(1)
        } else {
            zdt.toLocalDate()
        }
        val rangeStart = logicalDate.atStartOfDay(zone).plusHours(LOGICAL_DAY_CUTOVER_HOUR.toLong()).toInstant()
        return LogicalDay(
            logicalDate = logicalDate,
            rangeStart = rangeStart,
        )
    }

    /** 格式化带时区时间(用于摘要内时间标注,如 "2026-07-04 15:30 +08:00")。 */
    fun formatZonedDateTime(instant: Instant, zone: ZoneId = ZoneId.of(DEFAULT_TIMEZONE)): String {
        val zdt = ZonedDateTime.ofInstant(instant, zone)
        return zdt.format(summaryTimeFormatter)
    }

    /** 格式化 ISO 时间戳。 */
    fun formatIso(instant: Instant = Instant.now()): String = instant.toString()

    /**
     * 从消息列表构建 source_time_range。
     * 返回 min/max timestamp 之间跨过的本地日期列表。
     */
    fun buildSourceTimeRange(
        timestamps: List<String>,
        zone: ZoneId = ZoneId.of(DEFAULT_TIMEZONE),
    ): SourceTimeRange? {
        val instants = timestamps.mapNotNull { parseInstant(it) }
        if (instants.isEmpty()) return null
        val start = instants.min()
        val end = instants.max()
        val localDates = collectLocalDatesBetween(start, end, zone)
        return SourceTimeRange(
            start = start.toString(),
            end = end.toString(),
            timezone = zone.id,
            localDates = localDates.map { it.toString() },
        )
    }

    /** 收集 [start, end] 之间跨过的本地日期(按 6 小时步进,防无限循环)。 */
    private fun collectLocalDatesBetween(start: Instant, end: Instant, zone: ZoneId): List<LocalDate> {
        if (start.isAfter(end)) return emptyList()
        val dates = linkedSetOf<LocalDate>()
        var cursor = start
        var iterations = 0
        while (!cursor.isAfter(end) && iterations < COLLECT_MAX_ITERATIONS) {
            dates += ZonedDateTime.ofInstant(cursor, zone).toLocalDate()
            cursor = cursor.plusMillis(SIX_HOURS_MS)
            iterations++
        }
        // 兜底加上 end 当天的日期(防止步进跳过)
        dates += ZonedDateTime.ofInstant(end, zone).toLocalDate()
        return dates.toList()
    }

    /** 解析 ISO 时间戳,失败返回 null。 */
    fun parseInstant(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value) }
            .recoverCatching { LocalDate.parse(value).atStartOfDay(ZoneId.of(DEFAULT_TIMEZONE)).toInstant() }
            .getOrNull()
    }

    /** 从摘要文本提取时间信号(YYYY-MM-DD HH:MM 或纯日期或纯时间)。 */
    fun extractSummaryTimeSignals(summary: String): SummaryTimeSignals {
        if (summary.isEmpty()) return SummaryTimeSignals(emptyList(), emptyList(), emptyList())
        val dateTimes = Regex("""\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}""")
            .findAll(summary).map { it.value }.toList()
        val dates = Regex("""\b\d{4}-\d{2}-\d{2}\b""")
            .findAll(summary).map { it.value }.toList()
        val times = Regex("""\b\d{2}:\d{2}\b""")
            .findAll(summary).map { it.value }.toList()
        return SummaryTimeSignals(dateTimes, dates, times)
    }

    /**
     * 规范化 fact 时间。LLM 抽出的 time 必须满足:
     *  1. 格式 YYYY-MM-DDTHH:MM
     *  2. 必须在 sourceTimeRange.localDates 内(防 LLM 编造)
     *  3. 跨多日且只有 HH:MM → null(无法定位是哪天)
     */
    fun normalizeFactTime(
        value: String?,
        ctx: FactTimeContext,
    ): String? {
        if (value.isNullOrBlank()) return null
        val v = value.trim()

        // 完整 YYYY-MM-DDTHH:MM
        val fullMatch = Regex("""^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2})$""").find(v)
        if (fullMatch != null) {
            val (date, time) = fullMatch.destructured
            return if (date in ctx.localDates) "${date}T${time}" else null
        }

        // 仅 HH:MM
        val timeMatch = Regex("""^(\d{2}:\d{2})$""").find(v)
        if (timeMatch != null) {
            val time = timeMatch.groupValues[1]
            // 只有一个 source date 时,补上日期
            if (ctx.localDates.size == 1) {
                return "${ctx.localDates[0]}T$time"
            }
            return null // 多日时无法定位
        }

        // 仅 YYYY-MM-DD
        val dateMatch = Regex("""^(\d{4}-\d{2}-\d{2})$""").find(v)
        if (dateMatch != null) {
            val date = dateMatch.groupValues[1]
            return if (date in ctx.localDates) "${date}T00:00" else null
        }

        return null
    }

    /** 逻辑日。 */
    data class LogicalDay(
        val logicalDate: LocalDate,
        val rangeStart: Instant,
    )

    /** source_time_range。 */
    @Serializable
    data class SourceTimeRange(
        val start: String,
        val end: String,
        val timezone: String,
        val localDates: List<String>,
    )

    /** 摘要文本中的时间信号。 */
    data class SummaryTimeSignals(
        val dateTimes: List<String>,
        val dates: List<String>,
        val times: List<String>,
    )

    /** fact 时间规范化的上下文。 */
    data class FactTimeContext(
        val localDates: List<String>,
        val summaryTimes: List<String>,
    )
}
