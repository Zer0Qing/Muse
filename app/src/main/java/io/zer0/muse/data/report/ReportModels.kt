package io.zer0.muse.data.report

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Phase 5 5B: 报告系统 — 周报/月报/年报数据模型。
 *
 * 周报: 每周日 20:00 自动生成(聊天数/记忆数/情绪趋势/最佳语录)
 * 月报: 全屏沉浸式(封面/统计/情绪图表/词云/AI寄语)
 * 年报: Spotify Wrapped 风格 HorizontalPager
 */
@Serializable
data class WeeklyReport(
    val id: String,
    val weekStart: String,
    val weekEnd: String,
    val totalChats: Int = 0,
    val totalMessages: Int = 0,
    val newMemories: Int = 0,
    val moodTrend: List<MoodPoint> = emptyList(),
    val topQuote: String = "",
    val aiMessage: String = "",
    val generatedAt: Long = System.currentTimeMillis(),
)

/**
 * 月报数据。
 */
@Serializable
data class MonthlyReport(
    val id: String,
    val yearMonth: String,
    val totalChats: Int = 0,
    val totalMessages: Int = 0,
    val totalWords: Int = 0,
    val newMemories: Int = 0,
    val milestones: List<String> = emptyList(),
    val moodChart: List<MoodPoint> = emptyList(),
    val topWords: List<WordCloudEntry> = emptyList(),
    val topQuote: String = "",
    val aiMessage: String = "",
    val generatedAt: Long = System.currentTimeMillis(),
)

/**
 * 年报数据(Spotify Wrapped 风格)。
 */
@Serializable
data class YearlyReport(
    val id: String,
    val year: Int,
    val totalChats: Int = 0,
    val totalMessages: Int = 0,
    val totalWords: Int = 0,
    val longestStreak: Int = 0,
    val topAssistant: String = "",
    val topMemories: List<String> = emptyList(),
    val moodSummary: MoodSummary = MoodSummary(),
    val aiMessage: String = "",
    val generatedAt: Long = System.currentTimeMillis(),
)

/** 情绪数据点。 */
@Serializable
data class MoodPoint(
    val date: String,
    val value: Float = 0f, // -2 to +2
    val label: String = "",
)

/** 词云条目。 */
@Serializable
data class WordCloudEntry(
    val word: String,
    val count: Int,
)

/** 情绪总结。 */
@Serializable
data class MoodSummary(
    val positiveDays: Int = 0,
    val neutralDays: Int = 0,
    val negativeDays: Int = 0,
    val averageMood: Float = 0f,
)

/**
 * Phase 5 5B: 报告生成器 — 从本地数据汇总生成报告。
 */
object ReportGenerator {

    /**
     * 生成周报(统计本周数据)。
     */
    fun generateWeeklyReport(
        chatCount: Int,
        messageCount: Int,
        newMemoryCount: Int,
        moodPoints: List<MoodPoint> = emptyList(),
        bestQuote: String = "",
    ): WeeklyReport {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        val weekStart = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
        cal.add(Calendar.DAY_OF_WEEK, 6)
        val weekEnd = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)

        return WeeklyReport(
            id = "weekly-${System.currentTimeMillis()}",
            weekStart = weekStart,
            weekEnd = weekEnd,
            totalChats = chatCount,
            totalMessages = messageCount,
            newMemories = newMemoryCount,
            moodTrend = moodPoints,
            topQuote = bestQuote,
            aiMessage = generateAiMessage(chatCount, messageCount),
        )
    }

    /**
     * 生成月报(统计本月数据)。
     */
    fun generateMonthlyReport(
        chatCount: Int,
        messageCount: Int,
        wordCount: Int,
        newMemoryCount: Int,
        milestones: List<String> = emptyList(),
        moodPoints: List<MoodPoint> = emptyList(),
        topWords: List<WordCloudEntry> = emptyList(),
        bestQuote: String = "",
    ): MonthlyReport {
        val yearMonth = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
        return MonthlyReport(
            id = "monthly-$yearMonth",
            yearMonth = yearMonth,
            totalChats = chatCount,
            totalMessages = messageCount,
            totalWords = wordCount,
            newMemories = newMemoryCount,
            milestones = milestones,
            moodChart = moodPoints,
            topWords = topWords,
            topQuote = bestQuote,
            aiMessage = generateAiMessage(chatCount, messageCount),
        )
    }

    private fun generateAiMessage(chats: Int, messages: Int): String {
        return when {
            messages > 100 -> "Wow, what a talkative week! $messages messages across $chats conversations."
            messages > 50 -> "A solid week of chatting. You sent $messages messages!"
            messages > 0 -> "Thanks for stopping by this week. Every message counts!"
            else -> "Looking forward to our conversations next week!"
        }
    }
}
