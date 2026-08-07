package io.zer0.muse.ui

import io.zer0.common.Logger
import io.zer0.memory.fact.FactEntity
import java.time.LocalDate
import java.time.LocalTime

object GreetingHelper {
    // 根据时间返回问候语
    fun getTimeGreeting(hour: Int = LocalTime.now().hour): String {
        return when (hour) {
            in 5..10 -> "早上好"
            in 11..13 -> "中午好"
            in 14..17 -> "下午好"
            in 18..22 -> "晚上好"
            else -> "深夜了"
        }
    }

    // 获取节气（简化版：用预计算的日期表）
    fun getSolarTerm(date: LocalDate = LocalDate.now()): String? {
        // 24节气每月日期（近似值，精度足够）
        val solarTerms = listOf(
            // 月, 日, 节气名
            Triple(1, 6, "小寒"), Triple(1, 20, "大寒"),
            Triple(2, 4, "立春"), Triple(2, 19, "雨水"),
            Triple(3, 6, "惊蛰"), Triple(3, 21, "春分"),
            Triple(4, 5, "清明"), Triple(4, 20, "谷雨"),
            Triple(5, 6, "立夏"), Triple(5, 21, "小满"),
            Triple(6, 6, "芒种"), Triple(6, 21, "夏至"),
            Triple(7, 7, "小暑"), Triple(7, 23, "大暑"),
            Triple(8, 8, "立秋"), Triple(8, 23, "处暑"),
            Triple(9, 8, "白露"), Triple(9, 23, "秋分"),
            Triple(10, 8, "寒露"), Triple(10, 24, "霜降"),
            Triple(11, 7, "立冬"), Triple(11, 22, "小雪"),
            Triple(12, 7, "大雪"), Triple(12, 22, "冬至"),
        )
        // 检查今天或明天是否是节气（提前告知）
        val today = solarTerms.firstOrNull { it.first == date.monthValue && it.second == date.dayOfMonth }
        if (today != null) return "今天是${today.third}"
        val tomorrow = date.plusDays(1)
        val tmr = solarTerms.firstOrNull { it.first == tomorrow.monthValue && it.second == tomorrow.dayOfMonth }
        if (tmr != null) return "明天是${tmr.third}"
        return null
    }

    // 获取节日
    fun getFestival(date: LocalDate = LocalDate.now()): String? {
        val festivals = listOf(
            Triple(1, 1, "元旦"), Triple(2, 14, "情人节"),
            Triple(3, 8, "妇女节"), Triple(5, 1, "劳动节"),
            Triple(5, 4, "青年节"), Triple(6, 1, "儿童节"),
            Triple(7, 1, "建党节"), Triple(8, 1, "建军节"),
            Triple(9, 10, "教师节"), Triple(10, 1, "国庆节"),
            Triple(12, 25, "圣诞节"),
        )
        val today = festivals.firstOrNull { it.first == date.monthValue && it.second == date.dayOfMonth }
        if (today != null) return "今天是${today.third}"
        val tomorrow = date.plusDays(1)
        val tmr = festivals.firstOrNull { it.first == tomorrow.monthValue && it.second == tomorrow.dayOfMonth }
        if (tmr != null) return "明天是${tmr.third}"
        return null
    }

    /**
     * 近期事项关键词 — 命中这些词的记忆会被视为"近期事件"（考试/出行/会议等）。
     * 命中后配合记忆的 time 字段（明天/后天/3 天内）生成个性化提醒。
     */
    private val EVENT_KEYWORDS = listOf(
        "考试", "考研", "高考", "期末", "答辩", "面试",
        "航班", "火车", "高铁", "飞机", "机票", "出发", "回程", "出差", "旅行", "旅游",
        "会议", "开会", "报告", "截止", "报名", "提交",
        "体检", "复诊", "复查", "手术", "取药",
        "还款", "交租", "缴费", "deadline", "DDL",
        "生日", "纪念日", "约会", "聚会", "婚礼",
    )

    /**
     * 筛选近期事项候选(未来 1-3 天内、关键词命中),返回原始事项文本列表。
     * 供 LLM 生成个性化提醒时作为输入;无候选返回空列表。
     */
    fun recentEvents(facts: List<FactEntity>, today: LocalDate = LocalDate.now()): List<String> {
        if (facts.isEmpty()) return emptyList()
        val events = mutableListOf<String>()
        for (fact in facts) {
            val text = fact.fact ?: continue
            val time = fact.time ?: continue
            if (!EVENT_KEYWORDS.any { text.contains(it) }) continue
            val eventDate = runCatching { LocalDate.parse(time.substringBefore("T")) }
                .onFailure { Logger.w("GreetingHelper", "忽略无法解析的记忆时间字段", it) }
                .getOrNull() ?: continue
            val diff = java.time.temporal.ChronoUnit.DAYS.between(today, eventDate)
            if (diff !in 1..3) continue
            val whenText = when (diff) {
                1L -> "明天"
                2L -> "后天"
                else -> "${diff}天后"
            }
            events += "$whenText$text"
        }
        return events
    }

    /**
     * 从记忆中提取个性化提示（生日、近期事项）。
     *
     * 优先级：明天 > 后天 > 3 天内。同一优先级取第一条。
     * 返回的提示已按 UI 长度截断（≤ 24 字）。
     */
    fun getMemoryHint(facts: List<FactEntity>, today: LocalDate = LocalDate.now()): String? {
        if (facts.isEmpty()) return null
        var best: Pair<Int, String>? = null // (diffDays, hint)
        for (fact in facts) {
            val text = fact.fact ?: continue
            // 检查生日（支持"生日是 X月X日"）
            if (text.contains("生日") || text.contains("出生")) {
                val dateMatch = Regex("""(\d{1,2})[月/-](\d{1,2})""").find(text)
                if (dateMatch != null) {
                    val month = dateMatch.groupValues[1].toIntOrNull() ?: continue
                    val day = dateMatch.groupValues[2].toIntOrNull() ?: continue
                    val birthdayThisYear = LocalDate.of(today.year, month, day)
                    val diff = java.time.temporal.ChronoUnit.DAYS.between(today, birthdayThisYear)
                    val hint = when {
                        diff == 0L -> "今天是生日"
                        diff == 1L -> "明天是生日"
                        diff in 2..7L -> "${diff}天后是生日"
                        else -> null
                    }
                    if (hint != null) best = betterHint(best, diff, hint)
                }
                continue
            }
            // 近期事项：关键词命中 + time 字段在 3 天内
            if (EVENT_KEYWORDS.any { text.contains(it) }) {
                val time = fact.time
                if (time == null) continue
                val eventDate = runCatching { LocalDate.parse(time.substringBefore("T")) }
                    .onFailure { Logger.w("GreetingHelper", "忽略无法解析的记忆时间字段", it) }
                    .getOrNull() ?: continue
                val diff = java.time.temporal.ChronoUnit.DAYS.between(today, eventDate)
                if (diff !in 1..3) continue
                val keyword = EVENT_KEYWORDS.firstOrNull { text.contains(it) } ?: "事"
                val hint = when (diff) {
                    1L -> "明天有$keyword：${text.take(16)}"
                    else -> "${diff}天内有$keyword：${text.take(14)}"
                }
                best = betterHint(best, diff, hint)
            }
        }
        return best?.second?.take(24)?.let {
            // 纯事件句保留；含记忆原文的截断到 24 字
            it
        }?.replace("用户", "你")
    }

    /** 保留更紧急（diff 更小）的提示。 */
    private fun betterHint(best: Pair<Int, String>?, diff: Long, hint: String): Pair<Int, String> {
        return if (best == null || diff < best.first) diff.toInt() to hint else best
    }
    // 记忆提示语（人性化）
    fun getMemoryCountText(count: Int, assistantName: String = "Muse"): String {
        return when (count) {
            0 -> "$assistantName 还跟你不够熟悉"
            in 1..9 -> "$assistantName 正在慢慢认识你"
            in 10..49 -> "$assistantName 已经记住了 $count 条记忆，开始熟悉了"
            in 50..99 -> "$assistantName 和你已经很熟了"
            in 100..199 -> "$assistantName 和你无话不谈了"
            else -> "$assistantName 比谁都懂你，已记住 $count 条记忆"
        }
    }

    /**
     * 组装完整问候语。
     *
     * 个性化优先：记忆里有近期事项（考试/航班/会议等）时，只用记忆提示；
     * 没有时再从节气/节日里随机选一个作为通用信息（避免每次都一样）。
     * 长度控制：后缀只保留一条，整体 ≤ 约 28 字。
     */
    fun buildGreeting(facts: List<FactEntity>, hour: Int = LocalTime.now().hour, date: LocalDate = LocalDate.now()): String {
        val prefix = getTimeGreeting(hour)
        // 1. 记忆提示优先（个性化）
        getMemoryHint(facts, date)?.let { hint ->
            return "$prefix，$hint"
        }
        // 2. 无记忆提示：节气/节日随机选一（通用信息随机替换）
        val extras = listOfNotNull(getSolarTerm(date), getFestival(date))
        if (extras.isNotEmpty()) {
            return "$prefix，${extras.random()}"
        }
        return prefix
    }
}
