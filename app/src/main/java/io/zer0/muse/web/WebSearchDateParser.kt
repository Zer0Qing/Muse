package io.zer0.muse.web

import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 从搜索结果的 snippet/title 里解析发布时间，供 date_range **软过滤**使用。
 *
 * 软过滤语义（避免误杀中文站点大量无日期页面）：
 * - 解析到绝对/相对日期且明显超出范围 → 由调用方剔除；
 * - 解析不到日期 → 返回 null，调用方**降权而不剔除**；
 * - 只认明确的日期字面量，不猜"最新""近日"这类模糊词。
 */
object WebSearchDateParser {

    private val ISO_DATE = Regex("(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})")
    private val DAYS_AGO = Regex("(\\d{1,3})\\s*(?:天|日)前")
    private val HOURS_AGO = Regex("(\\d{1,3})\\s*(?:小时|个小时)前")
    private val EN_DAYS_AGO = Regex("(\\d{1,3})\\s+days?\\s+ago", RegexOption.IGNORE_CASE)
    private val WEEKS_AGO = Regex("(\\d{1,2})\\s*(?:周|个?星期)前")

    /** 解析成功返回 epoch millis；无明确日期返回 null。nowMillis 注入便于测试。 */
    fun parse(text: String, nowMillis: Long = System.currentTimeMillis()): Long? {
        if (text.isBlank()) return null

        DAYS_AGO.find(text)?.let { return nowMillis - TimeUnit.DAYS.toMillis(it.groupValues[1].toLong()) }
        EN_DAYS_AGO.find(text)?.let { return nowMillis - TimeUnit.DAYS.toMillis(it.groupValues[1].toLong()) }
        WEEKS_AGO.find(text)?.let { return nowMillis - TimeUnit.DAYS.toMillis(it.groupValues[1].toLong() * 7) }
        HOURS_AGO.find(text)?.let { return nowMillis - TimeUnit.HOURS.toMillis(it.groupValues[1].toLong()) }

        ISO_DATE.find(text)?.let { m ->
            val year = m.groupValues[1].toIntOrNull() ?: return null
            val month = m.groupValues[2].toIntOrNull() ?: return null
            val day = m.groupValues[3].toIntOrNull() ?: return null
            if (month !in 1..12 || day !in 1..31) return null
            return runCatching {
                Calendar.getInstance(Locale.ROOT).apply {
                    clear()
                    set(year, month - 1, day)
                }.timeInMillis
            }.getOrNull()
        }
        return null
    }

    /** date_range 对应的最早可接受时间；未知值返回 null（不启用过滤）。 */
    fun earliestMillis(range: String?, nowMillis: Long = System.currentTimeMillis()): Long? = when (range) {
        "past_day" -> nowMillis - TimeUnit.DAYS.toMillis(1)
        "past_week" -> nowMillis - TimeUnit.DAYS.toMillis(7)
        "past_month" -> nowMillis - TimeUnit.DAYS.toMillis(31)
        "past_year" -> nowMillis - TimeUnit.DAYS.toMillis(366)
        else -> null
    }
}
