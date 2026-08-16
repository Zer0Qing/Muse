package io.zer0.muse.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import io.zer0.ai.core.UIMessage
import io.zer0.ai.image.ImageGenParams
import io.zer0.muse.asr.ASRStatus
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.quickmsg.QuickMessageEntity
import io.zer0.muse.ui.chat.VideoAttachment

/**
 * v1.0.75 fix (用户反馈): 输入栏上方 Markdown 格式工具条整条移除。
 *
 * P2-12 时代的富文本格式工具条(粗体/斜体/代码/引用/列表/链接)经多轮评估
 * 确认无实际使用价值:用户从未依赖它输入格式,且占用输入栏上方空间。
 * 用户明确"那一排没用,删了吧"。
 *
 * RichInputBar 退化为原 [InputBar] 的纯透传层(保持调用点兼容,后续可整体替换回 InputBar)。
 * 相关已删:FormatToolbarSurface / FormatButton / LinkInsertDialog / applyMarkdownFormat /
 * MarkdownFormat / formatEnabled 参数 / 设置开关(richInputEnabled)。
 */
@Composable
internal fun RichInputBar(
    text: String,
    isStreaming: Boolean,
    isWaitingFirstToken: Boolean = false,
    isDrawMode: Boolean,
    isWebSearchEnabled: Boolean,
    isDeepThinkingEnabled: Boolean = false,
    deepThinkingLevel: io.zer0.ai.core.ReasoningLevel = io.zer0.ai.core.ReasoningLevel.HIGH,
    onCycleDeepThinkingLevel: () -> Unit = {},
    imageGenParams: ImageGenParams = ImageGenParams(),
    onImageGenParamsChange: (ImageGenParams) -> Unit = {},
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onNavigateInputHistory: (Int) -> Unit = {},
    onPickDocument: () -> Unit,
    onToggleDrawMode: () -> Unit,
    onToggleWebSearch: () -> Unit,
    onToggleDeepThinking: () -> Unit = {},
    showRestartContext: Boolean = false,
    onRestartContext: () -> Unit = {},
    assistants: List<AssistantEntity> = emptyList(),
    onDelegateToAssistant: () -> Unit = {},
    onPickKnowledge: () -> Unit = {},
    onOpenPromptTemplates: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
    enterToSend: Boolean = false,
    quickMessages: List<QuickMessageEntity> = emptyList(),
    onInsertQuickMessage: (QuickMessageEntity) -> Unit = {},
    pendingImages: List<String> = emptyList(),
    onPickImage: (asOcr: Boolean) -> Unit = {},
    onPickGalleryImage: (Uri) -> Unit = {},
    onRemovePendingImage: (Int) -> Unit = {},
    pendingDocuments: List<io.zer0.muse.ui.chat.PendingDocument> = emptyList(),
    onRemovePendingDocument: (Int) -> Unit = {},
    pendingVideo: VideoAttachment? = null,
    onPickVideo: () -> Unit = {},
    onRemovePendingVideo: () -> Unit = {},
    replyingTo: UIMessage? = null,
    onClearReply: () -> Unit = {},
    replyQuoteOverride: String? = null,
    onEditReply: (String) -> Unit = {},
    isRecording: Boolean = false,
    asrStatus: ASRStatus = ASRStatus.Idle,
    recordingAmplitudes: List<Float> = emptyList(),
    onStartRecording: () -> Boolean = { false },
    onStopRecording: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
    showMic: Boolean = true,
    toolCallCompleted: Int = 0,
    toolCallTotal: Int = 0,
    onShowToolCalls: () -> Unit = {},
    hasDraft: Boolean = false,
    onOpenVoiceConversation: () -> Unit = {},
    showExpandButton: Boolean = false,
    autoFocus: Boolean = true,
    tokenEstimateEnabled: Boolean = false,
    historyTokens: Int = 0,
    contextWindow: Int = 0,
    pasteAsFileEnabled: Boolean = true,
    pasteAsFileThreshold: Int = 2000,
    onAddPastedTextAsDocument: (String) -> Unit = {},
) {
    // v1.0.75 fix: 格式工具条已移除,直接透传原 InputBar。
    // 注:InputBar 自身已含 imePadding/navigationBarsPadding,此处不再重复施加。
    InputBar(
        state = MuseInputState(
            text = text,
            isStreaming = isStreaming,
            isWaitingFirstToken = isWaitingFirstToken,
            isDrawMode = isDrawMode,
            isWebSearchEnabled = isWebSearchEnabled,
            isDeepThinkingEnabled = isDeepThinkingEnabled,
            deepThinkingLevel = deepThinkingLevel,
            showExpandButton = showExpandButton,
            imageGenParams = imageGenParams,
            showRestartContext = showRestartContext,
            assistants = assistants,
            enterToSend = enterToSend,
            quickMessages = quickMessages,
            pendingImages = pendingImages,
            pendingDocuments = pendingDocuments,
            pendingVideo = pendingVideo,
            replyingTo = replyingTo,
            replyQuoteOverride = replyQuoteOverride,
            isRecording = isRecording,
            asrStatus = asrStatus,
            recordingAmplitudes = recordingAmplitudes,
            showMic = showMic,
            toolCallCompleted = toolCallCompleted,
            toolCallTotal = toolCallTotal,
            hasDraft = hasDraft,
            autoFocus = autoFocus,
            tokenEstimateEnabled = tokenEstimateEnabled,
            historyTokens = historyTokens,
            contextWindow = contextWindow,
            pasteAsFileEnabled = pasteAsFileEnabled,
            pasteAsFileThreshold = pasteAsFileThreshold,
        ),
        callbacks = InputBarCallbacks(
            onCycleDeepThinkingLevel = onCycleDeepThinkingLevel,
            onImageGenParamsChange = onImageGenParamsChange,
            onTextChanged = onTextChanged,
            onSend = onSend,
            onStop = onStop,
            onNavigateInputHistory = onNavigateInputHistory,
            onPickDocument = onPickDocument,
            onToggleDrawMode = onToggleDrawMode,
            onToggleWebSearch = onToggleWebSearch,
            onToggleDeepThinking = onToggleDeepThinking,
            onRestartContext = onRestartContext,
            onDelegateToAssistant = onDelegateToAssistant,
            onPickKnowledge = onPickKnowledge,
            onOpenPromptTemplates = onOpenPromptTemplates,
            onOpenSkills = onOpenSkills,
            onInsertQuickMessage = onInsertQuickMessage,
            onPickImage = onPickImage,
            onPickGalleryImage = onPickGalleryImage,
            onRemovePendingImage = onRemovePendingImage,
            onRemovePendingDocument = onRemovePendingDocument,
            onPickVideo = onPickVideo,
            onRemovePendingVideo = onRemovePendingVideo,
            onClearReply = onClearReply,
            onEditReply = onEditReply,
            onStartRecording = onStartRecording,
            onStopRecording = onStopRecording,
            onCancelRecording = onCancelRecording,
            onShowToolCalls = onShowToolCalls,
            onOpenVoiceConversation = onOpenVoiceConversation,
            onAddPastedTextAsDocument = onAddPastedTextAsDocument,
        ),
    )
}
