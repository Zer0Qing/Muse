package io.zer0.muse.schedule

import java.util.Calendar

/**
 * 轻量级 5 字段 Cron 表达式解析器(不依赖外部库)。
 *
 * 5 字段顺序:minute(0-59) hour(0-23) day-of-month(1-31) month(1-12) day-of-week(0-6,0=周日)。
 *
 * 支持语法:
 *  - 基础:任意值(*)、精确值(N)、步进(*\/N)、范围(N-M)、列表(N,M,...)
 *  - v1.134 P3 高级语法:
 *    - `L`:最后一天(day-of-month:当月最后一天;day-of-week:本周最后一个周X,如 `5L`=最后一个周五)
 *    - `LW`:最后一个工作日(day-of-month)
 *    - `W`:最近工作日(day-of-month,如 `15W`=离 15 号最近的工作日)
 *    - `#`:第 N 个周X(day-of-week,如 `2#3`=第 3 个周一)
 *
 * @see <a href="https://www.quartz-scheduler.org/documentation/quartz-2.3.0/tutorials/crontrigger.html">Quartz Cron 规范</a>
 */
class CronExpression internal constructor(
    private val minutes: Set<Int>,
    private val hours: Set<Int>,
    private val daysOfMonth: Set<Int>,
    private val months: Set<Int>,
    private val daysOfWeek: Set<Int>,
    /**
     * v1.134 P3: day-of-month 字段的高级修饰符(与 [daysOfMonth] 互斥,优先级更高)。
     *
     * - [DayModifier.Last] → 当月最后一天(对应 `L`)
     * - [DayModifier.LastWeekday] → 当月最后一个工作日(对应 `LW`)
     * - [DayModifier.Weekday](N) → 离 N 号最近的工作日(对应 `NW`,N 已含在 [daysOfMonth] 中)
     */
    private val dayOfMonthModifier: DayModifier = DayModifier.None,
    /** v1.134 P3: day-of-week 字段的高级修饰符。 */
    private val dayOfWeekModifier: DayOfWeekModifier = DayOfWeekModifier.None,
) {
    /**
     * 计算给定时间戳 [timestamp] 之后(不含)下一次匹配的时间戳(分钟对齐)。
     *
     * 采用逐分钟遍历法,上限遍历 [MAX_SCAN_DAYS] 天避免死循环;无匹配返回 [Long.MAX_VALUE]。
     */
    fun nextRunAfter(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        // 对齐到当前分钟起点,再 +1 分钟,确保结果严格在 timestamp 之后(不含)
        cal.timeInMillis = timestamp
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.MINUTE, 1)

        // 上限:从原时间戳起 MAX_SCAN_DAYS 天
        val maxMs = timestamp + MAX_SCAN_DAYS * 24 * 60 * 60 * 1000

        while (cal.timeInMillis <= maxMs) {
            val minute = cal.get(Calendar.MINUTE)
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
            val month = cal.get(Calendar.MONTH) + 1 // Calendar.MONTH 为 0-11,转为 1-12
            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1 // Calendar 周日=1,转为 0=周日

            // B-13: Quartz 规范 — day-of-month 与 day-of-week 同时受限(非 *)时用 OR 匹配
            // (如 "0 0 1 * 1" = 每月 1 号 或 每周一);仅一边受限或都为 * 时才用 AND。
            val domRestricted = dayOfMonthModifier != DayModifier.None || daysOfMonth.size < 31
            val dowRestricted = dayOfWeekModifier != DayOfWeekModifier.None || daysOfWeek.size < 7
            val dayMatch = if (domRestricted && dowRestricted) {
                matchDayOfMonth(cal, dayOfMonth) || matchDayOfWeek(cal, dayOfWeek)
            } else {
                matchDayOfMonth(cal, dayOfMonth) && matchDayOfWeek(cal, dayOfWeek)
            }

            if (minute in minutes &&
                hour in hours &&
                month in months &&
                dayMatch
            ) {
                return cal.timeInMillis
            }
            cal.add(Calendar.MINUTE, 1)
        }
        return Long.MAX_VALUE
    }

    /**
     * v1.134 P3: 匹配 day-of-month 字段(考虑 L / LW / NW 修饰符)。
     *
     * 修饰符存在时优先用修饰符匹配;否则用普通集合匹配。
     */
    private fun matchDayOfMonth(cal: Calendar, dayOfMonth: Int): Boolean {
        return when (dayOfMonthModifier) {
            is DayModifier.Last -> {
                // L:当月最后一天
                val lastDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                dayOfMonth == lastDay
            }
            is DayModifier.LastWeekday -> {
                // LW:当月最后一个工作日(周一到周五)
                val lastDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                if (dayOfMonth == lastDay) {
                    val dow = cal.get(Calendar.DAY_OF_WEEK) - 1
                    dow in 1..5  // 周一到周五
                } else {
                    // 如果最后一天是周末,看是不是"最后一个周五"
                    val dow = cal.get(Calendar.DAY_OF_WEEK) - 1
                    if (dow == 6) {
                        // 周六:最后一个工作日是周五(dayOfMonth - 1)
                        dayOfMonth == lastDay - 1
                    } else if (dow == 0) {
                        // 周日:最后一个工作日是周五(dayOfMonth - 2)
                        dayOfMonth == lastDay - 2
                    } else {
                        false
                    }
                }
            }
            is DayModifier.Weekday -> {
                // NW:离 N 号最近的工作日(不跨月)
                val target = dayOfMonthModifier.day
                if (dayOfMonth == target) {
                    val dow = cal.get(Calendar.DAY_OF_WEEK) - 1
                    dow in 1..5  // 目标日本身是工作日
                } else {
                    val dow = cal.get(Calendar.DAY_OF_WEEK) - 1
                    // 目标日是周六(N):最近工作日是 N-1(周五)
                    if (dow == 6 && dayOfMonth == target - 1 && target - 1 >= 1) true
                    // 目标日是周日(N):最近工作日是 N+1(周一)
                    else if (dow == 1 && dayOfMonth == target + 1) true
                    else false
                }
            }
            DayModifier.None -> dayOfMonth in daysOfMonth
        }
    }

    /**
     * v1.134 P3: 匹配 day-of-week 字段(考虑 `5L` / `2#3` 修饰符)。
     *
     * 修饰符存在时优先用修饰符匹配;否则用普通集合匹配。
     */
    private fun matchDayOfWeek(cal: Calendar, dayOfWeek: Int): Boolean {
        return when (dayOfWeekModifier) {
            is DayOfWeekModifier.Last -> {
                // `5L`:最后一个周五 — 检查当前日期是否是本月最后一个 dayOfWeek
                val targetDow = dayOfWeekModifier.dayOfWeek
                if (dayOfWeek != targetDow) return false
                val currentDay = cal.get(Calendar.DAY_OF_MONTH)
                val lastDayOfMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                currentDay + 7 > lastDayOfMonth  // 当前日期 + 7 超出月末,说明这是本月最后一个该周几
            }
            is DayOfWeekModifier.Nth -> {
                // `2#3`:第 3 个周一 — 检查当前是第几个该周几
                if (dayOfWeek != dayOfWeekModifier.dayOfWeek) return false
                val currentDay = cal.get(Calendar.DAY_OF_MONTH)
                val occurrence = (currentDay - 1) / 7 + 1  // 第几个(从 1 开始)
                occurrence == dayOfWeekModifier.occurrence
            }
            DayOfWeekModifier.None -> dayOfWeek in daysOfWeek
        }
    }

    /** v1.134 P3: day-of-month 字段的修饰符。 */
    sealed class DayModifier {
        /** 无修饰符。 */
        object None : DayModifier()
        /** `L`:当月最后一天。 */
        object Last : DayModifier()
        /** `LW`:当月最后一个工作日。 */
        object LastWeekday : DayModifier()
        /** `NW`:离 N 号最近的工作日(N 已含在 [daysOfMonth] 中)。 */
        data class Weekday(val day: Int) : DayModifier()
    }

    /** v1.134 P3: day-of-week 字段的修饰符。 */
    sealed class DayOfWeekModifier {
        /** 无修饰符。 */
        object None : DayOfWeekModifier()
        /** `5L`:最后一个周五。 */
        data class Last(val dayOfWeek: Int) : DayOfWeekModifier()
        /** `2#3`:第 3 个周一。 */
        data class Nth(val dayOfWeek: Int, val occurrence: Int) : DayOfWeekModifier()
    }

    companion object {
        /** L-004: 逐分钟遍历的上限天数(覆盖闰年 + 冗余,避免死循环)。 */
        private const val MAX_SCAN_DAYS = 366L

        /**
         * 解析 Cron 表达式,非法时抛 [IllegalArgumentException]。
         * 异常消息使用英文(纯解析器无 Context 依赖),调用方捕获后用 context.getString 本地化。
         */
        fun parse(expr: String): CronExpression {
            val fields = expr.trim().split(Regex("\\s+"))
            if (fields.size != 5) {
                throw IllegalArgumentException("cron expression must have 5 fields: $expr")
            }
            // v1.0.17: Quartz `?` 表示"不指定"(用于 day-of-month/day-of-week 互斥),
            // 统一替换为 `*` 匹配任意值,避免下游解析报错。
            val domField = if (fields[2] == "?") "*" else fields[2]
            val dowField = if (fields[4] == "?") "*" else fields[4]
            // v1.134 P3: 先解析 day-of-month 和 day-of-week 的高级修饰符
            val (domSet, domMod) = parseDayOfMonthField(domField)
            val (dowSet, dowMod) = parseDayOfWeekField(dowField)
            return CronExpression(
                minutes = parseField(fields[0], 0, 59),
                hours = parseField(fields[1], 0, 23),
                daysOfMonth = domSet,
                months = parseField(fields[3], 1, 12),
                daysOfWeek = dowSet,
                dayOfMonthModifier = domMod,
                dayOfWeekModifier = dowMod,
            )
        }

        /**
         * v1.134 P3: 解析 day-of-month 字段,提取 L / LW / NW 修饰符。
         *
         * 返回 (基础集合, 修饰符)。修饰符优先级:LW > L > NW > None。
         * 修饰符存在时基础集合为空(matchDayOfMonth 只看修饰符)。
         */
        private fun parseDayOfMonthField(field: String): Pair<Set<Int>, DayModifier> {
            val trimmed = field.trim().uppercase()
            return when {
                trimmed == "LW" -> emptySet<Int>() to DayModifier.LastWeekday
                trimmed == "L" -> emptySet<Int>() to DayModifier.Last
                trimmed.endsWith("W") -> {
                    val numStr = trimmed.dropLast(1)
                    val n = numStr.toIntOrNull()
                        ?: throw IllegalArgumentException("invalid W modifier: $field")
                    if (n !in 1..31) {
                        throw IllegalArgumentException("W modifier day out of bounds: $n (allowed 1-31)")
                    }
                    setOf(n) to DayModifier.Weekday(n)
                }
                else -> parseField(field, 1, 31) to DayModifier.None
            }
        }

        /**
         * v1.134 P3: 解析 day-of-week 字段,提取 `5L` / `2#3` 修饰符。
         *
         * 返回 (基础集合, 修饰符)。修饰符优先级:# > L > None。
         */
        private fun parseDayOfWeekField(field: String): Pair<Set<Int>, DayOfWeekModifier> {
            val trimmed = field.trim().uppercase()
            // `5L`:最后一个周五
            if (trimmed.endsWith("L")) {
                val numStr = trimmed.dropLast(1)
                val n = numStr.toIntOrNull()
                    ?: throw IllegalArgumentException("invalid L modifier: $field")
                if (n !in 0..7) {
                    throw IllegalArgumentException("L modifier dayOfWeek out of bounds: $n (allowed 0-7, 7 also=0=Sunday)")
                }
                // 7 等价于 0(周日)
                val normalized = if (n == 7) 0 else n
                return emptySet<Int>() to DayOfWeekModifier.Last(normalized)
            }
            // `2#3`:第 3 个周一
            if (trimmed.contains("#")) {
                val parts = trimmed.split("#")
                if (parts.size != 2) {
                    throw IllegalArgumentException("invalid # modifier: $field")
                }
                val dow = parts[0].toIntOrNull()
                    ?: throw IllegalArgumentException("invalid # modifier dayOfWeek: $field")
                val occurrence = parts[1].toIntOrNull()
                    ?: throw IllegalArgumentException("invalid # modifier occurrence: $field")
                if (dow !in 0..7) {
                    throw IllegalArgumentException("# modifier dayOfWeek out of bounds: $dow (allowed 0-7)")
                }
                if (occurrence !in 1..5) {
                    throw IllegalArgumentException("# modifier occurrence out of bounds: $occurrence (allowed 1-5)")
                }
                val normalized = if (dow == 7) 0 else dow
                return emptySet<Int>() to DayOfWeekModifier.Nth(normalized, occurrence)
            }
            return parseField(field, 0, 6) to DayOfWeekModifier.None
        }

        /** 解析单个字段(支持逗号列表,每段可为 *、N、* /N、N-M)。 */
        private fun parseField(field: String, min: Int, max: Int): Set<Int> {
            if (field.isEmpty()) {
                throw IllegalArgumentException("cron field is empty")
            }
            val result = LinkedHashSet<Int>()
            field.split(",").forEach { segment ->
                result.addAll(parseSegment(segment.trim(), min, max))
            }
            if (result.isEmpty()) {
                throw IllegalArgumentException("cron field has no valid values: $field")
            }
            return result
        }

        /** 解析单段:任意值 *、步进 * /N、精确值 N、范围 N-M。 */
        private fun parseSegment(segment: String, min: Int, max: Int): Set<Int> {
            // v1.0.17: Quartz `?` 表示"不指定",视为 `*` 匹配任意值。
            if (segment == "*" || segment == "?") {
                return (min..max).toSet()
            }
            if (segment.startsWith("*/")) {
                val step = segment.substring(2).toIntOrNull()
                    ?: throw IllegalArgumentException("invalid step value: $segment")
                if (step <= 0) {
                    throw IllegalArgumentException("step must be positive: $segment")
                }
                return (min..max step step).toSet()
            }
            if (segment.contains("-")) {
                val parts = segment.split("-")
                if (parts.size != 2) {
                    throw IllegalArgumentException("invalid range: $segment")
                }
                val start = parts[0].toIntOrNull()
                    ?: throw IllegalArgumentException("invalid range start: $segment")
                val end = parts[1].toIntOrNull()
                    ?: throw IllegalArgumentException("invalid range end: $segment")
                if (start < min || end > max || start > end) {
                    throw IllegalArgumentException("range out of bounds: $segment (allowed $min-$max)")
                }
                return (start..end).toSet()
            }
            val n = segment.toIntOrNull()
                ?: throw IllegalArgumentException("invalid numeric value: $segment")
            if (n < min || n > max) {
                throw IllegalArgumentException("value out of bounds: $segment (allowed $min-$max)")
            }
            return setOf(n)
        }
    }
}
