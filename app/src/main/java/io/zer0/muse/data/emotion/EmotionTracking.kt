package io.zer0.muse.data.emotion

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 5 5C: 情绪追踪 — 基于消息 `<mood>` 标签和用户手动标注的情绪数据。
 *
 * 数据源:
 * - AI 消息中的 `<mood>` XML 标签(自动解析)
 * - 用户手动标记(长按消息 → 选择情绪)
 *
 * 展示:
 * - 折线图: X=时间, Y=情绪(-2 到 +2)
 * - 周/月/年视图
 * - AI 每周情绪总结
 */
@Serializable
data class EmotionEntry(
    val id: String,
    val sessionId: String,
    val messageId: String? = null,
    /** 情绪值: -2(很消极) 到 +2(很积极)。 */
    val value: Float,
    /** 情绪标签: positive / negative / neutral / mixed。 */
    val label: String = "neutral",
    /** 来源: auto(mood tag) / manual(user marking)。 */
    val source: String = "auto",
    val createdAt: Long = System.currentTimeMillis(),
) {
    val dateStr: String
        get() = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(createdAt))
}

/**
 * Phase 5 5C: 情绪解析器 — 从 AI 消息的 `<mood>` 标签中提取情绪值。
 *
 * 格式: `<mood value="0.5" label="positive">feeling happy today</mood>`
 */
object MoodParser {

    private val MOOD_REGEX = Regex("""<mood\s+value="([^"]+)"\s+label="([^"]+)"[^>]*>([^<]*)</mood>""")

    /**
     * 从消息文本中解析 mood 标签。
     *
     * @return 解析出的情绪信息,无标签返回 null
     */
    fun parse(messageContent: String): MoodParseResult? {
        val match = MOOD_REGEX.find(messageContent) ?: return null
        val value = match.groupValues[1].toFloatOrNull() ?: return null
        val label = match.groupValues[2]
        val description = match.groupValues[3].trim()
        return MoodParseResult(
            value = value.coerceIn(-2f, 2f),
            label = label,
            description = description,
        )
    }

    /**
     * 移除消息中的 mood 标签(显示用)。
     */
    fun stripMoodTags(content: String): String {
        return MOOD_REGEX.replace(content, "").trim()
    }
}

/** 情绪解析结果。 */
data class MoodParseResult(
    val value: Float,
    val label: String,
    val description: String,
)

/**
 * Phase 5 5C: 情绪统计(按周/月/年汇总)。
 */
@Serializable
data class EmotionStats(
    val period: String,
    val entries: List<EmotionEntry> = emptyList(),
    val average: Float = 0f,
    val highest: Float = 0f,
    val lowest: Float = 0f,
    val positiveCount: Int = 0,
    val neutralCount: Int = 0,
    val negativeCount: Int = 0,
) {
    companion object {
        fun fromEntries(entries: List<EmotionEntry>, period: String): EmotionStats {
            if (entries.isEmpty()) return EmotionStats(period = period)
            val values = entries.map { it.value }
            return EmotionStats(
                period = period,
                entries = entries,
                average = values.average().toFloat(),
                highest = values.max(),
                lowest = values.min(),
                positiveCount = values.count { it > 0.3f },
                neutralCount = values.count { it in -0.3f..0.3f },
                negativeCount = values.count { it < -0.3f },
            )
        }
    }
}
