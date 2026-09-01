package io.zer0.muse.ui.chat

import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.toEventSequence

/** 判断上游是否拒绝在 thinking 模式下使用 required tool_choice。 */
internal fun isThinkingToolChoiceUnsupported(message: String): Boolean {
    val normalized = message.lowercase()
    return normalized.contains("thinking mode") &&
        (normalized.contains("tool_choice") || normalized.contains("tool choice"))
}

/** 仅允许在没有任何输出时为 required tool_choice 做一次兼容重试。 */
internal fun shouldRetryToolChoiceCompatibility(
    message: String,
    toolChoice: String?,
    retryUsed: Boolean,
    hasMeaningfulOutput: Boolean,
): Boolean = toolChoice == "required" &&
    !retryUsed &&
    !hasMeaningfulOutput &&
    isThinkingToolChoiceUnsupported(message)

/**
 * 将一次性响应转换成现有流式消费器可以处理的事件序列。
 *
 * M2.4: 转换逻辑下沉到 ai 模块([ChatCompletion.toEventSequence]),
 * 本函数保留为兼容入口,保证既有调用方(chat UI/测试)无需改动。
 */
internal fun completionToStreamEvents(completion: ChatCompletion): List<ChatStreamEvent> =
    completion.toEventSequence()
