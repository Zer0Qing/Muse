package io.zer0.muse.ui

import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.ui.chat.QuickAction

enum class ChatMessageRenderRowKind {
    Complete,
    Header,
    Body,
    Footer,
}

data class ChatMessageRenderRow(
    val key: String,
    val message: UIMessage?,
    val chunk: ChatRenderChunk?,
    val kind: ChatMessageRenderRowKind = ChatMessageRenderRowKind.Complete,
    val isFirstChunk: Boolean = false,
    val ownsInteraction: Boolean = true,
    val rendersMessageBody: Boolean = true,
    val continuationSourceStart: Int = 0,
    val messageIndex: Int = 0,
    val messageRowCount: Int = 1,
) {
    init {
        require(message != null || chunk != null) { "a render row must contain a message or chunk" }
    }
}

object ChatMessageRenderRows {
    fun needsFooterDecision(
        message: UIMessage,
        isStreaming: Boolean,
        hasRichContent: Boolean,
    ): Boolean =
        !isStreaming &&
            !hasRichContent &&
            message.role == io.zer0.ai.core.MessageRole.ASSISTANT &&
            message.content.length > ChatRenderChunks.maxChunkCharacters

    /**
     * 决定「这条消息要不要留页脚」所需的全部事实。
     *
     * 以前这 9 个参数是一个个传的：调用方看不出哪些属于这条消息、哪些属于全局设置，
     * 而判定逻辑也因为参数太多没法单独测。收成一处之后，「页脚由什么决定」第一次
     * 成为一个能单独说清、单独构造的对象。
     */
    data class FooterFacts(
        val message: UIMessage,
        val isStreaming: Boolean,
        val isTranslating: Boolean,
        val isLastAssistant: Boolean,
        val chatPreferences: ChatPreferences,
        val showsModelName: Boolean,
        val showsTokenStats: Boolean,
        val branchCount: Int,
        val canContinue: Boolean,
    )

    fun hasRenderableFooter(facts: FooterFacts): Boolean {
        if (facts.isStreaming || facts.isTranslating) return false
        if (facts.message.role != io.zer0.ai.core.MessageRole.ASSISTANT) return false
        // 只要有一块别的信息要显示（模型名 / token 统计 / 多个分支 / 可继续），页脚就得存在
        if (facts.showsModelName || facts.showsTokenStats || facts.branchCount > 1 || facts.canContinue) {
            return true
        }
        val hasContent = facts.message.content.isNotEmpty()
        val hasReasoning = !facts.message.reasoning.isNullOrBlank()
        val ordered = facts.chatPreferences.quickActionOrder.mapNotNull(QuickAction::fromId)
        return if (ordered.isNotEmpty()) {
            ordered.any { action -> rendersFor(action, facts, hasContent, hasReasoning) }
        } else {
            legacyFlagsNeedFooter(facts, hasContent, hasReasoning)
        }
    }

    /** 某个快捷动作在这条消息上**按得动吗** —— 按不动的动作不该把页脚撑出来。 */
    private fun rendersFor(
        action: QuickAction,
        facts: FooterFacts,
        hasContent: Boolean,
        hasReasoning: Boolean,
    ): Boolean = when (action) {
        QuickAction.COPY, QuickAction.TRANSLATE, QuickAction.SHARE -> hasContent
        QuickAction.REASONING ->
            hasReasoning && (facts.chatPreferences.quickActionReasoning || !hasContent)
        QuickAction.REGENERATE -> facts.isLastAssistant
        QuickAction.DELETE, QuickAction.FAVORITE -> hasContent || hasReasoning
        QuickAction.TTS -> hasContent
        QuickAction.EDIT -> false
        QuickAction.QUOTE,
        QuickAction.ANNOTATE,
        QuickAction.DELEGATE,
        QuickAction.FORK,
        QuickAction.MULTI_SELECT,
        QuickAction.INFO,
        QuickAction.FORWARD,
        -> true
    }

    /** 老口径：没有 `quickActionOrder` 时，按各个独立开关判断。 */
    private fun legacyFlagsNeedFooter(
        facts: FooterFacts,
        hasContent: Boolean,
        hasReasoning: Boolean,
    ): Boolean {
        val prefs = facts.chatPreferences
        return (prefs.quickActionCopy && hasContent) ||
            (prefs.quickActionTranslate && hasContent) ||
            (prefs.quickActionShare && hasContent) ||
            ((prefs.quickActionReasoning || !hasContent) && hasReasoning) ||
            (prefs.quickActionRegenerate && facts.isLastAssistant) ||
            (prefs.quickActionDelete && (hasContent || hasReasoning))
    }

    fun project(
        message: UIMessage,
        isStreaming: Boolean,
        hasRichContent: Boolean,
        messageIndex: Int = 0,
        isLastMessage: Boolean = false,
        allowSplitting: Boolean = true,
        hasRenderableFooter: Boolean = true,
    ): List<ChatMessageRenderRow> {
        val messageId = message.id.toString()
        val ownsInteractionShell =
            !hasRichContent &&
                message.quotedContent.isNullOrBlank() &&
                message.imageUrls.isEmpty() &&
                message.imageBase64List.isEmpty() &&
                message.videoFileUri.isNullOrBlank() &&
                message.voiceUri.isNullOrBlank() &&
                message.attachments.isEmpty() &&
                message.artifactIds.isEmpty() &&
                message.toolCallInfo == null &&
                message.toolCalls.isNullOrEmpty() &&
                message.translations.isEmpty() &&
                message.reasoning.isNullOrBlank() &&
                message.mood.isNullOrBlank() &&
                message.reflection.isNullOrBlank() &&
                message.citationUrls.isEmpty() &&
                message.moodSkin.isNullOrBlank() &&
                message.ragCitations.isEmpty() &&
                message.variantGroupId == null &&
                message.parentGroupId == null &&
                message.reaction == null
        val chunks =
            ChatRenderChunks.project(
                messageId = messageId,
                content = message.content,
                role = if (message.role == io.zer0.ai.core.MessageRole.USER) {
                    ChatRenderRole.User
                } else {
                    ChatRenderRole.Assistant
                },
                isStreaming = isStreaming,
                hasRichContent = !ownsInteractionShell,
            )
        if (
            !allowSplitting ||
            !hasRenderableFooter ||
            chunks.size == 1 ||
            message.role != io.zer0.ai.core.MessageRole.ASSISTANT
        ) {
            return listOf(
                ChatMessageRenderRow(
                    key = "$messageId:message",
                    message = message,
                    chunk = null,
                    kind = ChatMessageRenderRowKind.Complete,
                    rendersMessageBody = true,
                    messageIndex = messageIndex,
                    messageRowCount = 1,
                ),
            )
        }
        if (isStreaming && isLastMessage) {
            return listOf(ChatMessageRenderRow("$messageId:message", message, null))
        }
        return buildList {
            val messageRowCount = chunks.size + 2
            add(
                ChatMessageRenderRow(
                    key = "$messageId:header",
                    message = message,
                    chunk = null,
                    kind = ChatMessageRenderRowKind.Header,
                    isFirstChunk = true,
                    ownsInteraction = false,
                    rendersMessageBody = false,
                    messageIndex = messageIndex,
                    messageRowCount = messageRowCount,
                ),
            )
            chunks.forEachIndexed { index, chunk ->
                add(
                    ChatMessageRenderRow(
                        key = chunk.key,
                        message = message,
                        chunk = chunk,
                        kind = ChatMessageRenderRowKind.Body,
                        isFirstChunk = index == 0,
                        ownsInteraction = false,
                        rendersMessageBody = true,
                        continuationSourceStart = chunk.sourceStart,
                        messageIndex = messageIndex,
                        messageRowCount = messageRowCount,
                    ),
                )
            }
            add(
                ChatMessageRenderRow(
                    key = "$messageId:footer",
                    message = message,
                    chunk = null,
                    kind = ChatMessageRenderRowKind.Footer,
                    isFirstChunk = false,
                    ownsInteraction = true,
                    rendersMessageBody = false,
                    messageIndex = messageIndex,
                    messageRowCount = messageRowCount,
                ),
            )
        }
    }
}
