package io.zer0.muse.ui.chat

import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.ChatStreamEvent

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

/** 将一次性响应转换成现有流式消费器可以处理的事件序列。 */
internal fun completionToStreamEvents(completion: ChatCompletion): List<ChatStreamEvent> = buildList {
    completion.reasoningContent?.takeIf { it.isNotEmpty() }?.let {
        add(
            ChatStreamEvent.ReasoningDelta(
                delta = it,
                signature = completion.thinkingSignature,
                encryptedContent = completion.thinkingEncryptedContent,
            ),
        )
    }
    completion.text.takeIf { it.isNotEmpty() }?.let { add(ChatStreamEvent.ContentDelta(it)) }
    completion.toolCalls.orEmpty().forEachIndexed { index, toolCall ->
        add(
            ChatStreamEvent.ToolCallDelta(
                index = index,
                id = toolCall.id,
                name = toolCall.name,
                argumentsDelta = toolCall.arguments,
                isSnapshot = true,
            ),
        )
    }
    completion.usageTokens?.let { add(ChatStreamEvent.UsageDelta(it)) }
    completion.citationUrls.takeIf { it.isNotEmpty() }?.let { add(ChatStreamEvent.CitationDelta(it)) }
    add(ChatStreamEvent.Done(completion.finishReason))
}
