package io.zer0.muse.conversation.render

import io.zer0.ai.core.AttachmentRef
import io.zer0.ai.core.ToolCallInfo
import io.zer0.ai.core.UIMessage
import io.zer0.muse.conversation.core.RenderBlock

enum class LegacyRenderSlotKind {
    Reasoning,
    Mood,
    Body,
    Reflection,
    Images,
    Attachments,
    Tool,
    Artifacts,
    Translations,
}

data class LegacyRenderSlot(
    val slotId: String,
    val kind: LegacyRenderSlotKind,
    val text: String = "",
) {
    init {
        require(slotId.isNotBlank()) { "render slot id must not be blank" }
    }
}

data class LegacyConversationProjection(
    val slotId: String,
    val messageId: String,
    val source: UIMessage,
    val markdown: List<RenderBlock>,
    val slots: List<LegacyRenderSlot>,
    val reasoning: String?,
    val mood: String?,
    val reflection: String?,
    val imageUrls: List<String>,
    val voiceUri: String?,
    val voiceDurationMs: Long,
    val attachments: List<AttachmentRef>,
    val toolCall: ToolCallInfo?,
    val artifactIds: List<String>,
    val quotedContent: String?,
    val translationSourceId: String?,
    val translations: Map<String, String>,
    val variantGroupId: String?,
    val variantIndex: Int,
    val variantCount: Int,
    val parentGroupId: String?,
)

object LegacyConversationProjector {
    fun project(
        rowId: String,
        message: UIMessage,
        complete: Boolean,
        parser: MarkdownRenderParser = MarkdownRenderParser(),
    ): LegacyConversationProjection {
        require(rowId.isNotBlank()) { "row id must not be blank" }
        val messageId = message.id.toString()
        val markdown =
            if (message.content.isEmpty()) {
                emptyList()
            } else {
                parser.parse(message.content, complete)
            }
        val slots = buildList {
            message.reasoning?.takeIf(String::isNotBlank)?.let { text ->
                add(LegacyRenderSlot("$rowId:reasoning", LegacyRenderSlotKind.Reasoning, text))
            }
            message.mood?.takeIf(String::isNotBlank)?.let { text ->
                add(LegacyRenderSlot("$rowId:mood", LegacyRenderSlotKind.Mood, text))
            }
            if (markdown.isNotEmpty()) {
                add(LegacyRenderSlot("$rowId:body", LegacyRenderSlotKind.Body, message.content))
            }
            message.reflection?.takeIf(String::isNotBlank)?.let { text ->
                add(LegacyRenderSlot("$rowId:reflection", LegacyRenderSlotKind.Reflection, text))
            }
            if (message.imageUrls.isNotEmpty()) {
                add(LegacyRenderSlot("$rowId:images", LegacyRenderSlotKind.Images))
            }
            if (message.attachments.isNotEmpty()) {
                add(LegacyRenderSlot("$rowId:attachments", LegacyRenderSlotKind.Attachments))
            }
            message.toolCallInfo?.let { tool ->
                add(LegacyRenderSlot("$rowId:tool", LegacyRenderSlotKind.Tool, tool.toolName))
            }
            if (message.artifactIds.isNotEmpty()) {
                add(LegacyRenderSlot("$rowId:artifacts", LegacyRenderSlotKind.Artifacts))
            }
            if (message.translations.isNotEmpty()) {
                add(LegacyRenderSlot("$rowId:translations", LegacyRenderSlotKind.Translations))
            }
        }
        return LegacyConversationProjection(
            slotId = rowId,
            messageId = messageId,
            source = message,
            markdown = markdown,
            slots = slots,
            reasoning = message.reasoning,
            mood = message.mood,
            reflection = message.reflection,
            imageUrls = message.imageUrls,
            voiceUri = message.voiceUri,
            voiceDurationMs = message.voiceDurationMs,
            attachments = message.attachments,
            toolCall = message.toolCallInfo,
            artifactIds = message.artifactIds,
            quotedContent = message.quotedContent,
            translationSourceId = message.translationSourceId,
            translations = message.translations,
            variantGroupId = message.variantGroupId,
            variantIndex = message.variantIndex,
            variantCount = message.variantCount,
            parentGroupId = message.parentGroupId,
        )
    }
}
