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
 *  2. LLM 生成一句简短提醒(≤ 30 字,第二人称,自然口语)
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

    /** 生成结果最大长度(UI 一行放得下)。 */
    private val MAX_HINT_LENGTH = 30

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
                    maxTokens = 64,
                )
            }
        }.onError { msg, t ->
            Logger.w(tag, "问候语提醒 LLM 生成失败: ${t?.message ?: msg},回退规则版")
        }.getOrNull()?.text?.trim()

        if (llmHint.isNullOrBlank() || llmHint.length > MAX_HINT_LENGTH) {
            Logger.d(tag, "LLM 结果无效(${llmHint?.length}字),使用规则版")
            return fallback
        }
        return llmHint
    }

    private fun buildPrompt(events: List<String>): List<UIMessage> {
        val system = UIMessage(
            role = MessageRole.SYSTEM,
            content = buildString {
                appendLine("你正在给用户发一条日常问候,需要附带一句贴心的提醒。")
                appendLine("根据下面的近期事项,生成一句简短的提醒,要求:")
                appendLine("- 15-30 字,一句话说完,不要分段")
                appendLine("- 用\"你\"称呼用户,自然口语化,像朋友提醒,不要官方腔")
                appendLine("- 直接使用事项里已有的时间(如\"明天\"),不要重复啰嗦")
                appendLine("- 直接输出提醒内容,不要加引号、冒号、前缀或解释")
            },
        )
        val user = UIMessage(
            role = MessageRole.USER,
            content = events.joinToString("\n"),
        )
        return listOf(system, user)
    }
}
