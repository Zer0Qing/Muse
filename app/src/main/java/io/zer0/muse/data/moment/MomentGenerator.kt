package io.zer0.muse.data.moment

import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.ai.image.ImageService
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.fact.FactStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * v1.0.72: AI 朋友圈动态生成器。
 *
 * 动态来源:
 *  - LLM 生成:输入 = 近期记忆(FactStore) + 当前时间/节气 + 情绪,生成 30-80 字口语化动态。
 *  - 规则兜底:LLM 失败时用节气/时间模板,保证每天有内容。
 *
 * v1.0.73: 多助手 — 按发布者身份(名字/人设)生成;配图 — 配置了生图模型时,
 * 约半数动态会基于记忆/内容生成一张配图(LLM 先写画面描述,再调 ImageService)。
 *
 * 生成铁律(写进 prompt):
 *  - 内容必须基于真实记忆,禁止编造用户没说过的事
 *  - 口语化,像人的朋友圈,不官方
 *  - 不暴露"我是 AI"的机械感,但可以有一点 AI 视角的幽默
 */
class MomentGenerator(
    private val chatService: ChatService?,
    private val factStore: FactStore?,
    private val imageService: ImageService?,
) {

    private val TAG = "MomentGenerator"

    /** 生成一条动态(按 [assistant] 身份)。返回 null 表示失败(调用方跳过,不阻塞调度)。 */
    suspend fun generate(assistant: io.zer0.muse.data.assistant.AssistantEntity? = null): GeneratedMoment? {
        // 1. LLM 生成
        val llm = withContext(Dispatchers.IO) {
            resultOf { generateWithLlm(assistant) }
                .onError { msg, t -> Logger.w(TAG, "LLM 生成动态失败: ${t?.message ?: msg}") }
                .getOrNull()
        }
        if (llm != null) return llm

        // 2. 规则兜底(节气/时间)
        val fallback = generateFallback()
        return GeneratedMoment(content = fallback, type = "seasonal", mood = null)
    }

    /** LLM 生成。 */
    private suspend fun generateWithLlm(assistant: io.zer0.muse.data.assistant.AssistantEntity?): GeneratedMoment? {
        val service = chatService ?: return null
        val facts = resultOf { factStore?.getAll("main") }.getOrNull() ?: emptyList()
        // 取最近 8 条记忆作为素材
        val recentFacts = facts
            .sortedByDescending { it.createdAt }
            .take(8)
            .map { it.fact }

        val assistantName = assistant?.name?.takeIf { it.isNotBlank() } ?: "Muse"
        val sb = StringBuilder()
        sb.appendLine("你是 $assistantName,一个陪伴用户的 AI 助手。现在要发一条朋友圈动态。")
        if (!assistant?.systemPrompt.isNullOrBlank()) {
            // 注入助手人设(截取前 500 字,避免过长)
            sb.appendLine("你的人设: ${assistant!!.systemPrompt.take(500)}")
        }
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
                // v1.0.74 fix: 剥离 <think> 推理标签,防止思考内容混入朋友圈动态
                ).text.let { io.zer0.muse.transformer.stripThinkTags(it) }
            }
        }.onError { msg, t ->
            Logger.w(TAG, "LLM 动态生成调用失败: ${t?.message ?: msg}")
        }.getOrNull()

        val cleaned = text?.trim()?.removePrefix("\"")?.removeSuffix("\"")?.trim()
        if (cleaned.isNullOrBlank() || cleaned.length < 5) return null

        // v1.0.73: 配图 — 有生图配置 + 随机一半概率,生成一张配图(失败不影响动态)
        val imageUrl = if (imageService != null && Random.nextFloat() < 0.5f) {
            try {
                generateImageFor(service, cleaned, recentFacts)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Logger.w(TAG, "动态配图失败(不影响动态): ${t.message}")
                null
            }
        } else {
            null
        }

        return GeneratedMoment(
            content = cleaned,
            type = "life_share",
            mood = null,
            imageUrl = imageUrl,
        )
    }

    /** 按 [assistant] 身份生成一条评论回复。带 [images] 时走视觉模型看图评论。失败返回 null。
     *  [allowSkip] = true 时模型可选择性跳过(输出"不回复"标记);false 时必须输出评论(用户刚发动态的主动互动)。 */
    suspend fun generateReply(
        momentContent: String,
        userComment: String,
        assistant: io.zer0.muse.data.assistant.AssistantEntity? = null,
        images: List<String> = emptyList(),
        allowSkip: Boolean = true,
    ): String? {
        val service = chatService ?: return null
        val assistantName = assistant?.name?.takeIf { it.isNotBlank() } ?: "Muse"
        val facts = resultOf { factStore?.getAll("main") }.getOrNull() ?: emptyList()
        val recentFacts = facts.take(5).joinToString("; ") { it.fact }

        val prompt = buildString {
            appendLine("你是 $assistantName,一个陪伴用户的 AI 助手。你在朋友圈看到一条动态:")
            appendLine("\"$momentContent\"")
            if (images.isNotEmpty()) {
                appendLine("动态带 ${images.size} 张图片(图片已附在消息中,请先看图再评论):")
            }
            appendLine("有人评论:\"$userComment\"")
            appendLine("请以 $assistantName 的身份决定要不要回复这条评论:")
            if (allowSkip) {
                appendLine("- 如果这条评论值得回(有内容、在问你、值得接话),用一句话自然回复(15-40 字,口语化,不要官方腔,可结合图片内容)")
                appendLine("- 如果只是寒暄/没内容/没什么好回的,直接输出\"不回复\"三个字")
                appendLine("- 像真实朋友圈那样选择性回复,不要每条都回")
            } else {
                appendLine("请以 $assistantName 的身份用一句话自然回复(15-40 字,口语化,不要官方腔,可结合图片内容):")
            }
            if (recentFacts.isNotBlank()) {
                appendLine("记忆素材: $recentFacts")
            }
        }

        val raw = resultOf {
            withTimeoutOrNull(20_000L) {
                service.completeText(
                    messages = listOf(
                        UIMessage(
                            role = MessageRole.USER,
                            content = prompt,
                            createdAt = System.currentTimeMillis(),
                            // v1.0.74: 带图时附图片(base64 data URI 自动转视觉输入)
                            imageBase64List = images.mapNotNull { it.toBase64Part() },
                        ),
                    ),
                    temperature = 0.8f,
                    maxTokens = 80,
                ).text.trim()
            }
        }.onError { msg, t ->
            Logger.w(TAG, "评论回复生成失败: ${t?.message ?: msg}")
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }

        // v1.0.74: 选择性回复 — 模型判断不值得回时输出标记,过滤掉不插入
        // v1.0.74 fix: 旧条件有漏网("我就不回复了"等变体)与误杀(英文评论 skip);
        // 改整句正则:允许结尾标点,整句为"不回复/跳过/skip/NONE/无"才视为跳过
        val skipRegex = Regex("""^(不回复|跳过|skip|NONE|无)[。.!！~～]*$""", RegexOption.IGNORE_CASE)
        return raw?.takeIf { reply ->
            val t = reply.trim()
            t.isNotBlank() && t != "null" && !skipRegex.matches(t)
        }
    }

    /** data:image/...;base64,xxx → xxx(视觉消息格式);非 data URI 返回 null(URL 图不内联)。 */
    private fun String.toBase64Part(): String? {
        if (!startsWith("data:image/", ignoreCase = true)) return null
        val comma = indexOf(',')
        return if (comma > 0) substring(comma + 1) else null
    }

    /** 基于动态内容生成配图:LLM 写画面描述 → ImageService 生成 → 返回第一张 URL。 */
    private suspend fun generateImageFor(
        service: ChatService,
        momentContent: String,
        recentFacts: List<String>,
    ): String? {
        // 1. LLM 写画面描述(15-40 字,适合文生图)
        val prompt = buildString {
            appendLine("朋友圈动态内容: \"$momentContent\"")
            if (recentFacts.isNotEmpty()) {
                appendLine("相关记忆素材: ${recentFacts.take(3).joinToString("; ")}")
            }
            appendLine("请为这条朋友圈配一张图,给出画面描述(15-40 字,只输出描述本身,不要引号):")
        }
        val description = resultOf {
            withTimeoutOrNull(20_000L) {
                service.completeText(
                    messages = listOf(
                        UIMessage(
                            role = MessageRole.USER,
                            content = prompt,
                            createdAt = System.currentTimeMillis(),
                        ),
                    ),
                    temperature = 0.9f,
                    maxTokens = 60,
                ).text.trim()
            }
        }.onError { msg, t ->
            Logger.w(TAG, "配图描述生成失败: ${t?.message ?: msg}")
        }.getOrNull()?.takeIf { it.isNotBlank() && it.length <= 80 }

        if (description.isNullOrBlank()) return null

        // 2. 生图
        val urls = resultOf {
            withTimeoutOrNull(60_000L) {
                imageService?.generate(
                    prompt = description,
                    params = io.zer0.ai.image.ImageGenParams(
                        size = "1024x1024",
                        responseFormat = "url",
                        n = 1,
                    ),
                )
            }
        }.onError { msg, t ->
            Logger.w(TAG, "配图生成失败: ${t?.message ?: msg}")
        }.getOrNull()

        return urls?.firstOrNull()
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
    /** v1.0.73: AI 配图 URL(可为空)。 */
    val imageUrl: String? = null,
)
