package io.zer0.muse.schedule

import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.fact.FactEntity
import io.zer0.muse.ui.GreetingHelper
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 问候语个性化提醒生成器 — 让 LLM 根据记忆里的近期事项自行组织提醒文案。
 *
 * 相比写死的规则模板,LLM 生成的文案更自然、更多样、不会重复时间词
 * (如"明天有考试:你明天要参加..."这类啰嗦表达)。
 *
 * 流程:
 *  1. [GreetingHelper.recentEvents] 筛选近期事项候选(未来 1-3 天,关键词命中)
 *  2. LLM 生成一句简短提醒(≤ 18 字,第二人称,自然口语)
 *  3. 失败/超时回退规则版 [GreetingHelper.getMemoryHint],不阻塞 UI
 *
 * 调用方负责缓存(当天最多生成一次,见 ChatListScreen)。
 */
class GreetingHintGenerator(
    private val chatService: ChatService,
) {

    private val tag = "GreetingHintGenerator"

    /** LLM 生成超时(UI 场景不能久等,失败快速回退)。 */
    private val LLM_TIMEOUT_MS = 8000L

    /** 生成结果最大长度(UI 单行预算)。 */
    private val maxHintLength = GreetingHelper.PERSONALIZED_HINT_MAX_LENGTH

    /**
     * 生成问候语后缀。无近期事项返回 null;LLM 失败回退规则版。
     */
    suspend fun generate(facts: List<FactEntity>): String? {
        val events = GreetingHelper.recentEvents(facts)
        if (events.isEmpty()) return null
        val fallback = GreetingHelper.getMemoryHint(facts)
        if (fallback == null) return null

        val llmHint = resultOf {
            withTimeoutOrNull(LLM_TIMEOUT_MS) {
                chatService.completeText(
                    messages = buildPrompt(events),
                    temperature = 0.8f,
                    maxTokens = 32,
                )
            }
        }.onError { msg, t ->
            Logger.w(tag, "问候语提醒 LLM 生成失败: ${t?.message ?: msg},回退规则版")
        }.getOrNull()?.text?.let { io.zer0.muse.transformer.stripThinkTags(it) }

        if (llmHint.isNullOrBlank()) {
            Logger.d(tag, "LLM 结果为空,使用规则版")
            return fallback
        }
        val compactHint = GreetingHelper.compactGreetingText(llmHint, maxHintLength)
        if (llmHint.length > maxHintLength) {
            Logger.d(tag, "LLM 结果过长(${llmHint.length}字),已压缩到${maxHintLength}字")
        }
        return compactHint ?: fallback
    }

    private fun buildPrompt(events: List<String>): List<UIMessage> {
        val system = UIMessage(
            role = MessageRole.SYSTEM,
            content = buildString {
                appendLine("你正在给用户的问候语补一句近期事项提醒。")
                appendLine("只依据给出的事项,不要推测;保留事项中的相对时间,不要重复时间词。")
                appendLine("只保留一个最重要的事项,输出 8-18 字的一句话,用\"你\"称呼用户,自然口语,不要引号、前缀、解释或分段。")
            },
        )
        val user = UIMessage(
            role = MessageRole.USER,
            content = events.joinToString("\n"),
        )
        return listOf(system, user)
    }
}
