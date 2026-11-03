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

    fun hasRenderableFooter(
        message: UIMessage,
        isStreaming: Boolean,
        isTranslating: Boolean,
        isLastAssistant: Boolean,
        chatPreferences: ChatPreferences,
        showsModelName: Boolean,
        showsTokenStats: Boolean,
        branchCount: Int,
        canContinue: Boolean,
    ): Boolean {
        if (isStreaming || isTranslating) return false
        if (message.role != io.zer0.ai.core.MessageRole.ASSISTANT) return false
        if (showsModelName || showsTokenStats || branchCount > 1 || canContinue) return true
        val content = message.content
        val hasContent = content.isNotEmpty()
        val hasReasoning = !message.reasoning.isNullOrBlank()
        if (chatPreferences.quickActionOrder.isNotEmpty()) {
            return chatPreferences.quickActionOrder
                .mapNotNull(QuickAction::fromId)
                .any { action ->
                    when (action) {
                        QuickAction.COPY, QuickAction.TRANSLATE, QuickAction.SHARE -> hasContent
                        QuickAction.REASONING ->
                            hasReasoning && (chatPreferences.quickActionReasoning || !hasContent)
                        QuickAction.REGENERATE -> isLastAssistant
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
                }
        }
        return (chatPreferences.quickActionCopy && hasContent) ||
            (chatPreferences.quickActionTranslate && hasContent) ||
            (chatPreferences.quickActionShare && hasContent) ||
            (
                (chatPreferences.quickActionReasoning || !hasContent) &&
                    hasReasoning
                ) ||
            (chatPreferences.quickActionRegenerate && isLastAssistant) ||
            (
                chatPreferences.quickActionDelete &&
                    (hasContent || hasReasoning)
                )
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
