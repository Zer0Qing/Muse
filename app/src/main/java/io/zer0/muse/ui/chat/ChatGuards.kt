package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.UIMessage
import io.zer0.muse.chat.InternalPromptMarkers
import io.zer0.muse.context.ContextBudget
import io.zer0.muse.context.ContextSection
import io.zer0.muse.data.session.MessageMarkers

/**
 * 对话发送/续传/重生成的纯判定函数。
 *
 * 这些函数没有任何状态依赖，只吃参数吐结果，所以从 [ChatViewModel] 里整块切出来：
 * 它们各自有独立单测，放在 ViewModel 文件里会被 5000+ 行的上下文淹没。
 */
/** R-TEST-06: 发送守卫纯逻辑（防空消息/流式中/Agent 会话创建重入）。 */
internal fun canStartGeneration(text: String, images: List<String>, isStreaming: Boolean, isCreatingAgentSession: Boolean): Boolean =
    (text.isNotBlank() || images.isNotEmpty()) && !isStreaming && !isCreatingAgentSession

/** R-TEST-06: 发送前合并待发送文档内容与用户输入(文档文本 + 用户输入)。 */
internal fun buildSendText(
    rawText: String,
    documentContents: List<String>,
    budget: io.zer0.muse.context.ContextBudget = io.zer0.muse.context.ContextBudget(),
): String {
    val docText = documentContents.joinToString("\n\n---\n\n")
    // M4.3: 附件正文受统一 ContextBudget 上限约束(保留头部,截断可诊断);
    // 用户输入在拼接顺序的最后,不会被附件截断吞掉。
    val clampedDoc = budget.clampText(io.zer0.muse.context.ContextSection.ATTACHMENT_TEXT, docText)
    return when {
        documentContents.isEmpty() -> rawText
        rawText.isBlank() -> clampedDoc
        else -> "$clampedDoc\n\n---\n\n$rawText"
    }
}

/** R-TEST-06: 仅当非流式、最后一条为带 [已中断] 标记的助手消息时才允许续写。 */
internal fun canContinueGeneration(isStreaming: Boolean, lastMessage: UIMessage?): Boolean = !isStreaming && lastMessage != null &&
    lastMessage.role == MessageRole.ASSISTANT &&
    MessageMarkers.isInterrupted(lastMessage.content)

/** R-TEST-06: 去掉 [已中断] 尾部标记,保留断点前内容。 */
internal fun resumeFromInterrupted(content: String): String = MessageMarkers.stripInterrupted(content)

/**
 * C-11: 计算 [a] 与 [b] 的最长公共前缀长度(按 Unicode 代码单位)。
 *
 * 纯函数,供断点续传去重(duplicateRemaining)使用:当 provider 改写/补全返回内容时,
 * 返回内容与已显示内容前缀并非逐字一致(原精确 startsWith 匹配会整体漏去重,
 * 导致重复/跳变)。以 LCP 判定重叠量,仅跳过重叠部分,保留改写后的新内容。
 *
 * 例:longestCommonPrefix("今天天气很好", "今天天下雨") == 3(跳过"今天天")。
 */
internal fun longestCommonPrefix(a: String, b: String): Int {
    val max = minOf(a.length, b.length)
    var i = 0
    while (i < max && a[i] == b[i]) i++
    return i
}

/**
 * 续传去重的保守裁剪策略。
 *
 * 只有两种情况可以证明 delta 是重放内容：
 * 1. 整个 delta 都是旧前缀；
 * 2. delta 覆盖了剩余旧前缀，后面才是新内容。
 *
 * 若 delta 在旧前缀中途分叉，公共前缀可能只是新内容的巧合开头；
 * 此时返回 0，保留整段 delta，避免把用户真正的新首字吞掉。
 */
internal fun resumeOverlapToDrop(duplicateRemaining: String, delta: String): Int {
    if (duplicateRemaining.isEmpty() || delta.isEmpty()) return 0
    val overlap = longestCommonPrefix(duplicateRemaining, delta)
    // A partial matching chunk may be legitimate new continuation text. Only drop
    // a delta that proves the entire remaining old answer was replayed; otherwise
    // prefer visible duplication over silently swallowing user-visible characters.
    return overlap.takeIf { it == duplicateRemaining.length } ?: 0
}

/**
 * v2.0: 续传时判定 provider 是否“从头重写” — 原文已被大面积消费(重叠量 >= 原文一半且至少 6 字符)
 * 后出现分叉,说明新一轮是重新生成的完整回复,应用新文替换旧内容而不是追加,避免重复拼接。
 */
internal fun shouldReplaceOnResumeRewrite(duplicateTotal: Int, consumedChars: Int, minOverlap: Int = 6): Boolean =
    duplicateTotal > 0 && consumedChars >= maxOf(minOverlap, duplicateTotal / 2)

/**
 * v2.0: Done 阶段兑底 — 新一轮尝试的文本把原文开头片段写回来了时,
 * 同样视为“从头重写”,应替换旧内容(旧文+新文重复拼接的最后一层保护)。
 */
internal fun shouldReplaceOnResumeSupersede(original: String?, attemptText: String?, currentContent: String): Boolean {
    if (original.isNullOrEmpty() || attemptText.isNullOrEmpty()) return false
    val head = original.take(SUPERSEDE_HEAD_CHARS)
    if (head.length < 4) return false
    if (!attemptText.contains(head)) return false
    return !currentContent.contentEquals(attemptText)
}

/** [shouldReplaceOnResumeSupersede] 比对的原文头部片段长度。 */
private const val SUPERSEDE_HEAD_CHARS = 6

/**
 * C-12: 判定某轮是否可用 [toolModel] 作为请求模型。
 *
 * 工具轮(上一轮结果含 toolCalls)默认走 toolModel;但当本轮历史仍含图片、
 * 而 [toolModel] 不支持视觉输入时,继续把图发给不支持视觉的模型会导致 HTTP 400,
 * 此时应回退主模型(其视觉能力已在 prepareVisionContext 判定/降级)。
 * 未配置 toolModel 或历史无图时返回 true,保持默认行为。
 *
 * @param history 本轮将要发送的对话历史
 * @param toolModel 用户配置的工具模型(已增强识别能力)
 */
internal fun canUseToolModelForRound(history: List<UIMessage>, toolModel: Model): Boolean {
    if (toolModel.supportsVisionInput()) return true
    val hasImages = history.any { it.imageBase64List.isNotEmpty() }
    return !hasImages
}

/** R-TEST-06: 重生成仅当非流式、有会话且当前用户变体可选时可用。 */
internal fun canRegenerate(isStreaming: Boolean, hasSession: Boolean, hasSelectedUserVariant: Boolean): Boolean =
    !isStreaming && hasSession && hasSelectedUserVariant

/** Build the source text used by message translation, including reasoning-only replies. */
internal fun buildTranslationSourceText(message: UIMessage): String {
    val content = message.content.trim()
    val reasoning = message.reasoning.orEmpty().trim()
    return when {
        content.isNotBlank() && reasoning.isNotBlank() ->
            "${InternalPromptMarkers.BODY_HEADER}:\n$content\n\n${InternalPromptMarkers.THINKING_HEADER}:\n$reasoning"
        content.isNotBlank() -> content
        else -> reasoning
    }
}

internal fun effectiveChatSessionId(state: ChatUiState): String? = if (state.isAgentMode) state.agentSessionId else state.currentSessionId
