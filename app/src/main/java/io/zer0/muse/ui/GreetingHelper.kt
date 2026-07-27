package io.zer0.muse.ui

import io.zer0.memory.fact.FactEntity
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

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

    // 从记忆中提取提示（生日、纪念日、近期事项）
    fun getMemoryHint(facts: List<FactEntity>, today: LocalDate = LocalDate.now()): String? {
        val hints = mutableListOf<String>()
        for (fact in facts) {
            val text = fact.fact ?: continue
            // 检查生日
            if (text.contains("生日") || text.contains("出生")) {
                // 尝试提取日期
                val dateMatch = Regex("""(\d{1,2})[月/-](\d{1,2})""").find(text)
                if (dateMatch != null) {
                    val month = dateMatch.groupValues[1].toIntOrNull() ?: continue
                    val day = dateMatch.groupValues[2].toIntOrNull() ?: continue
                    val birthdayThisYear = LocalDate.of(today.year, month, day)
                    val diff = java.time.temporal.ChronoUnit.DAYS.between(today, birthdayThisYear)
                    when {
                        diff == 0L -> hints.add("今天是${if (text.contains("用户")||text.contains("我")) "你的" else "ta的"}生日")
                        diff == 1L -> hints.add("明天是生日")
                        diff in 2..7L -> hints.add("${diff}天后是生日")
                    }
                }
            }
            // 检查近期事项（time字段在明天/后天）
            val time = fact.time
            if (time != null && (text.contains("要") || text.contains("需要") || text.contains("计划") || text.contains("会议") || text.contains("报告") || text.contains("截止"))) {
                try {
                    val eventDate = LocalDate.parse(time.substringBefore("T"))
                    val diff = java.time.temporal.ChronoUnit.DAYS.between(today, eventDate)
                    when {
                        diff == 1L -> hints.add("明天有事：${text.take(20)}")
                        diff == 2L -> hints.add("后天有事：${text.take(20)}")
                    }
                } catch (_: Exception) {}
            }
        }
        return hints.firstOrNull()
    }

    // 记忆提示语（人性化）
    fun getMemoryCountText(count: Int): String {
        return when (count) {
            0 -> "Muse 还跟你不够熟悉"
            in 1..9 -> "Muse 正在慢慢认识你"
            in 10..49 -> "Muse 已经记住了 $count 条记忆，开始熟悉了"
            in 50..99 -> "Muse 和你已经很熟了"
            in 100..199 -> "Muse 和你无话不谈了"
            else -> "Muse 比谁都懂你，已记住 $count 条记忆"
        }
    }

    // 组装完整问候语
    fun buildGreeting(facts: List<FactEntity>, hour: Int = LocalTime.now().hour, date: LocalDate = LocalDate.now()): String {
        val sb = StringBuilder()
        sb.append(getTimeGreeting(hour))
        val extras = mutableListOf<String>()
        getSolarTerm(date)?.let { extras.add(it) }
        getFestival(date)?.let { extras.add(it) }
        getMemoryHint(facts, date)?.let { extras.add(it) }
        if (extras.isNotEmpty()) {
            sb.append("，").append(extras.joinToString("，"))
        }
        return sb.toString()
    }
}
