package io.zer0.muse.data.moment

import io.zer0.ai.ChatService
import io.zer0.ai.image.ImageService
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * AI 朋友圈动态 / 评论 / 私信生成器。
 *
 * v2.6.7 重构（P0-1）：
 * 此前所有生成都只做 `"你是 $name" + 素材` 的三段字符串拼接，绕过 [SystemPromptAssembler]，
 * 导致世界书 / 记忆 / 用户画像 / 提示词注入 / MOOD / 表情包指南全部不生效，
 * 同一角色在主对话与朋友圈里表现为两个人格。现在统一经 [MiniContextBuilder]
 * 走完整生成链路。
 *
 * 另一处修正：旧实现把人设截断到 500 字（`systemPrompt.take(500)`），
 * 长人设直接残缺。现在由 Assembler 完整注入，不再截断。
 */
class MomentGenerator(
    private val chatService: ChatService?,
    private val imageService: ImageService?,
    private val contextBuilder: MiniContextBuilder,
    private val settingsRepository: SettingsRepository,
) {

    /** 生成一条动态(按 [assistant] 身份)。返回 null 表示失败(调用方跳过,不阻塞调度)。 */
    suspend fun generate(assistant: io.zer0.muse.data.assistant.AssistantEntity? = null): GeneratedMoment? {
        val llm = withContext(Dispatchers.IO) {
            resultOf { generateWithLlm(assistant) }
                .onError { msg, t -> Logger.w(TAG, "LLM 生成动态失败: ${t?.message ?: msg}") }
                .getOrNull()
        }
        if (llm != null) return llm

        val fallback = generateFallback()
        return GeneratedMoment(content = fallback, type = "seasonal", mood = null)
    }

    private suspend fun generateWithLlm(assistant: io.zer0.muse.data.assistant.AssistantEntity?): GeneratedMoment? {
        val service = chatService ?: return null
        val mode = readContentMode()
        val material = buildMomentMaterial(assistant, mode)
        val messages = contextBuilder.buildMessages(
            assistant = assistant,
            userContent = material,
        )

        val text = resultOf {
            withTimeoutOrNull(30_000L) {
                service.completeText(
                    messages = messages,
                    temperature = 0.8f,
                    maxTokens = 200,
                ).text.let { io.zer0.muse.transformer.stripThinkTags(it) }
            }
        }.onError { msg, t ->
            Logger.w(TAG, "LLM 动态生成调用失败: ${t?.message ?: msg}")
        }.getOrNull()

        val cleaned = text?.trim()?.removePrefix("\"")?.removeSuffix("\"")?.trim()
        if (cleaned.isNullOrBlank() || cleaned.length < 5) return null

        // 配图：有生图配置 + 随机一半概率（失败不影响动态）
        val imageUrl = if (imageService != null && Random.nextFloat() < 0.5f) {
            try {
                generateImageFor(service, cleaned, emptyList())
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

    /**
     * 组装朋友圈素材。
     *
     * 内容模式（用户可在设置里切换）：
     * - [MomentContentMode.REAL] 真实系：只发和用户有关的事，克制，像真的在记录生活
     * - [MomentContentMode.CHARACTER] 角色系：角色有自己的生活与遭遇，用户是旁观者
     *
     * 素材仍然基于真实记忆，但两种模式对"能否展开角色自身经历"的授权不同——
     * 旧实现的铁律"不编造用户没说过的经历"在角色扮演语境下把角色锁死在只能复述用户，
     * 因此这里按模式分流，而不是一刀切。
     */
    private suspend fun buildMomentMaterial(assistant: io.zer0.muse.data.assistant.AssistantEntity?, mode: MomentContentMode): String =
        buildString {
            appendLine("<moment_task>")
            appendLine("请发一条朋友圈动态。")
            appendLine("</moment_task>")
            appendLine()
            appendLine("<content_mode>")
            when (mode) {
                MomentContentMode.REAL -> {
                    appendLine("- 只写与用户、与你们共同生活相关的内容，克制、具体。")
                    appendLine("- 不要编造用户没说过、没发生过的经历、感受或行动。")
                    appendLine("- 像真实的人在记录生活，简短自然。")
                }
                MomentContentMode.CHARACTER -> {
                    appendLine("- 你可以有自己的日常、工作、心情和小遭遇，用户是你的旁观者。")
                    appendLine("- 涉及用户的部分仍不得编造用户没说过的事；但你可以主动分享自己的经历。")
                    appendLine("- 保持人设一致，像真人在分享自己的生活。")
                }
            }
            appendLine("</content_mode>")
            appendLine()
            appendLine("<style>")
            appendLine("- 口语自然，30-80 字。")
            appendLine("- 不要客服腔、不要 AI 自我介绍、不要 #话题。")
            appendLine("- 只输出动态正文，不要前缀、引号、解释、MOOD 或反思。")
            appendLine("</style>")
        }

    /** 读取当前内容模式；读取失败退化为真实系（更保守，不编造用户经历）。 */
    private suspend fun readContentMode(): MomentContentMode = resultOf { settingsRepository.momentContentMode() }
        .onError { msg, t -> Logger.w(TAG, "读取朋友圈内容模式失败，退化为真实系: ${t?.message ?: msg}") }
        .getOrNull() ?: MomentContentMode.REAL

    /**
     * 按 [assistant] 身份生成一条评论回复。带 [images] 时走视觉模型看图评论。失败返回 null。
     * [allowSkip] = true 时模型可选择性跳过(输出"不回复"标记);false 时必须输出评论(用户刚发动态的主动互动)。
     */
    suspend fun generateReply(
        momentContent: String,
        userComment: String,
        assistant: io.zer0.muse.data.assistant.AssistantEntity? = null,
        images: List<String> = emptyList(),
        allowSkip: Boolean = true,
    ): String? {
        val service = chatService ?: return null
        val material = buildString {
            appendLine("<moment_context>")
            appendLine("动态内容: $momentContent")
            if (images.isNotEmpty()) {
                appendLine("动态带 ${images.size} 张图片,请先看图再评论。")
            }
            appendLine("评论内容: $userComment")
            appendLine("</moment_context>")
            appendLine()
            if (allowSkip) {
                appendLine("- 有内容、在问你或值得接话:输出一句 15-40 字的自然回复。")
                appendLine("- 纯寒暄、无内容或重复信息:只输出“不回复”。")
            } else {
                appendLine("必须输出一句 15-40 字的自然回复。")
            }
            appendLine("只输出回复或“不回复”,不要解释、MOOD 或反思。")
        }
        val messages = contextBuilder.buildMessages(assistant = assistant, userContent = material)
            .let { list ->
                // 带图时把图片挂到最后一条 USER 消息上（base64 data URI 自动转视觉输入）
                if (images.isEmpty()) {
                    list
                } else {
                    val parts = images.mapNotNull { it.toBase64Part() }
                    if (parts.isEmpty()) {
                        list
                    } else {
                        list.mapIndexed { index, msg ->
                            if (index == list.lastIndex) msg.copy(imageBase64List = parts) else msg
                        }
                    }
                }
            }

        val raw = resultOf {
            withTimeoutOrNull(20_000L) {
                service.completeText(
                    messages = messages,
                    temperature = 0.8f,
                    maxTokens = 160,
                ).text.trim()
            }
        }.onError { msg, t ->
            Logger.w(TAG, "评论回复生成失败: ${t?.message ?: msg}")
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }

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
    private suspend fun generateImageFor(service: ChatService, momentContent: String, recentFacts: List<String>): String? {
        val prompt = buildString {
            appendLine("<moment_material>")
            appendLine("朋友圈动态内容: $momentContent")
            if (recentFacts.isNotEmpty()) {
                appendLine("相关记忆素材: ${recentFacts.take(3).joinToString("; ")}")
            }
            appendLine("</moment_material>")
            appendLine("请为这条动态生成 15-40 字画面描述;只输出描述,不要文字元素、引号或解释。")
        }
        val description = resultOf {
            withTimeoutOrNull(20_000L) {
                service.completeText(
                    messages = listOf(
                        io.zer0.ai.core.UIMessage(
                            role = io.zer0.ai.core.MessageRole.USER,
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

    companion object {
        private const val TAG = "MomentGenerator"
    }
}

/** 生成结果。 */
data class GeneratedMoment(
    val content: String,
    val type: String,
    val mood: String?,
    /** AI 配图 URL(可为空)。 */
    val imageUrl: String? = null,
)
