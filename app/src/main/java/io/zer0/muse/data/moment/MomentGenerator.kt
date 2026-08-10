package io.zer0.muse.data.moment

import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.fact.FactStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v1.0.72: AI 朋友圈动态生成器。
 *
 * 动态来源:
 *  - LLM 生成:输入 = 近期记忆(FactStore) + 当前时间/节气 + 情绪,生成 30-80 字口语化动态。
 *  - 规则兜底:LLM 失败时用节气/时间模板,保证每天有内容。
 *
 * 生成铁律(写进 prompt):
 *  - 内容必须基于真实记忆,禁止编造用户没说过的事
 *  - 口语化,像人的朋友圈,不官方
 *  - 不暴露"我是 AI"的机械感,但可以有一点 AI 视角的幽默
 */
class MomentGenerator(
    private val chatService: ChatService?,
    private val factStore: FactStore?,
) {

    private val TAG = "MomentGenerator"

    /** 生成一条动态。返回 null 表示失败(调用方跳过,不阻塞调度)。 */
    suspend fun generate(): GeneratedMoment? {
        // 1. LLM 生成
        val llm = withContext(Dispatchers.IO) {
            resultOf { generateWithLlm() }
                .onError { msg, t -> Logger.w(TAG, "LLM 生成动态失败: ${t?.message ?: msg}") }
                .getOrNull()
        }
        if (llm != null) return llm

        // 2. 规则兜底(节气/时间)
        val fallback = generateFallback()
        return GeneratedMoment(content = fallback, type = "seasonal", mood = null)
    }

    /** LLM 生成。 */
    private suspend fun generateWithLlm(): GeneratedMoment? {
        val service = chatService ?: return null
        val facts = resultOf { factStore?.getAll("main") }.getOrNull() ?: emptyList()
        // 取最近 8 条记忆作为素材
        val recentFacts = facts
            .sortedByDescending { it.createdAt }
            .take(8)
            .map { it.fact }

        val sb = StringBuilder()
        sb.appendLine("你是 Muse,一个陪伴用户的 AI 助手。现在要发一条朋友圈动态。")
        sb.appendLine("要求:")
        sb.appendLine("- 口语化,像真实的人在朋友圈分享,不要官方腔")
        sb.appendLine("- 长度 30-80 字")
        sb.appendLine("- 内容必须基于下面的真实记忆素材,禁止编造用户没说过的事")
        sb.appendLine("- 可以有一点 AI 视角的幽默(比如提到自己在记笔记),但不要机械感")
        sb.appendLine("- 不要用 emoji,不要用 #话题标签")
        sb.appendLine("- 直接输出动态内容,不要任何前缀、引号或说明")
        sb.appendLine()
        if (recentFacts.isNotEmpty()) {
            sb.appendLine("近期记忆素材(从中挑 1-2 个点发动态):")
            recentFacts.forEach { sb.appendLine("- $it") }
        } else {
            sb.appendLine("(暂无记忆素材,可以发一条轻松的生活分享,比如今天想记录点什么)")
        }
        sb.appendLine()
        sb.appendLine("请输出朋友圈动态:")

        val text = resultOf {
            withTimeoutOrNull(30_000L) {
                service.completeText(
                    messages = listOf(
                        UIMessage(
                            role = MessageRole.USER,
                            content = sb.toString(),
                            createdAt = System.currentTimeMillis(),
                        ),
                    ),
                    temperature = 0.8f,
                    maxTokens = 150,
                ).text.trim()
            }
        }.onError { msg, t ->
            Logger.w(TAG, "LLM 动态生成调用失败: ${t?.message ?: msg}")
        }.getOrNull()

        val cleaned = text?.trim()?.removePrefix("\"")?.removeSuffix("\"")?.trim()
        if (cleaned.isNullOrBlank() || cleaned.length < 5) return null
        return GeneratedMoment(content = cleaned, type = "life_share", mood = null)
    }

    /** 规则兜底:节气/时间模板。 */
    private fun generateFallback(): String {
        val today = java.time.LocalDate.now()
        // 用节气表(GreetingHelper 的算法,简单复用同款 C 值)
        val term = solarTermOf(today)
        return when {
            term != null -> "翻了下日历,$term 了。日子过得真快,记一笔。"
            today.dayOfMonth == 1 -> "新的一个月,新的开始。今天想记录点什么。"
            else -> "今天天气不错,适合记录一点小事。"
        }
    }

    /** 简单节气(与 GreetingHelper 同款 C 值,避免跨类依赖)。 */
    private fun solarTermOf(date: java.time.LocalDate): String? {
        val cValues = doubleArrayOf(
            5.4055, 20.12, 3.87, 18.73, 5.63, 20.646, 4.81, 20.1,
            5.52, 21.04, 5.678, 21.37, 7.108, 22.83, 7.5, 23.13,
            7.646, 23.042, 8.318, 23.438, 7.438, 22.36, 7.18, 21.94,
        )
        val termNames = listOf(
            "小寒", "大寒", "立春", "雨水", "惊蛰", "春分", "清明", "谷雨",
            "立夏", "小满", "芒种", "夏至", "小暑", "大暑", "立秋", "处暑",
            "白露", "秋分", "寒露", "霜降", "立冬", "小雪", "大雪", "冬至",
        )
        val y = (date.year % 100).toDouble()
        for (idx in termNames.indices) {
            if (idx / 2 + 1 != date.monthValue) continue
            val day = (Math.floor(y * 0.2422 + cValues[idx]) - Math.floor(y / 4.0)).toInt()
            if (day == date.dayOfMonth) return termNames[idx]
        }
        return null
    }
}

/** v1.0.72: 生成结果。 */
data class GeneratedMoment(
    val content: String,
    val type: String,
    val mood: String?,
)
