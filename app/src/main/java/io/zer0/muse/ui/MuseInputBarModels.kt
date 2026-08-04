package io.zer0.muse.ui

import android.net.Uri
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.UIMessage
import io.zer0.ai.image.ImageGenParams
import io.zer0.muse.asr.ASRStatus
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.quickmsg.QuickMessageEntity
import io.zer0.muse.ui.chat.PendingDocument
import io.zer0.muse.ui.chat.VideoAttachment

/**
 * B7-07: InputBar 输入状态聚合。
 *
 * 把原来 50+ 个平铺参数中的「数据/开关」收敛为一个不可变状态对象,
 * 回调统一放进 [InputBarCallbacks],输入框只需两个参数。
 */
data class MuseInputState(
    val text: String = "",
    val isStreaming: Boolean = false,
    val isWaitingFirstToken: Boolean = false,
    val isDrawMode: Boolean = false,
    val isWebSearchEnabled: Boolean = false,
    val isDeepThinkingEnabled: Boolean = false,
    val deepThinkingLevel: ReasoningLevel = ReasoningLevel.HIGH,
    val showExpandButton: Boolean = false,
    val imageGenParams: ImageGenParams = ImageGenParams(),
    val showRestartContext: Boolean = false,
    val assistants: List<AssistantEntity> = emptyList(),
    val enterToSend: Boolean = false,
    val quickMessages: List<QuickMessageEntity> = emptyList(),
    val pendingImages: List<String> = emptyList(),
    val pendingDocuments: List<PendingDocument> = emptyList(),
    val pendingVideo: VideoAttachment? = null,
    val replyingTo: UIMessage? = null,
    val replyQuoteOverride: String? = null,
    val isRecording: Boolean = false,
    val asrStatus: ASRStatus = ASRStatus.Idle,
    val recordingAmplitudes: List<Float> = emptyList(),
    val showMic: Boolean = true,
    val toolCallCompleted: Int = 0,
    val toolCallTotal: Int = 0,
    val hasDraft: Boolean = false,
    val autoFocus: Boolean = true,
    val tokenEstimateEnabled: Boolean = false,
    val historyTokens: Int = 0,
    val contextWindow: Int = 0,
    val pasteAsFileEnabled: Boolean = true,
    val pasteAsFileThreshold: Int = 2000,
)

/** B7-07: InputBar 全部回调收敛。 */
data class InputBarCallbacks(
    val onCycleDeepThinkingLevel: () -> Unit = {},
    val onImageGenParamsChange: (ImageGenParams) -> Unit = {},
    val onTextChanged: (String) -> Unit = {},
    val onSend: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onNavigateInputHistory: (Int) -> Unit = {},
    val onPickDocument: () -> Unit = {},
    val onToggleDrawMode: () -> Unit = {},
    val onToggleWebSearch: () -> Unit = {},
    val onToggleDeepThinking: () -> Unit = {},
    val onRestartContext: () -> Unit = {},
    val onDelegateToAssistant: () -> Unit = {},
    val onPickKnowledge: () -> Unit = {},
    val onOpenPromptTemplates: () -> Unit = {},
    val onOpenSkills: () -> Unit = {},
    val onInsertQuickMessage: (QuickMessageEntity) -> Unit = {},
    val onPickImage: (Boolean) -> Unit = {},
    val onPickGalleryImage: (Uri) -> Unit = {},
    val onRemovePendingImage: (Int) -> Unit = {},
    val onRemovePendingDocument: (Int) -> Unit = {},
    val onPickVideo: () -> Unit = {},
    val onRemovePendingVideo: () -> Unit = {},
    val onClearReply: () -> Unit = {},
    val onEditReply: (String) -> Unit = {},
    val onStartRecording: () -> Boolean = { false },
    val onStopRecording: () -> Unit = {},
    val onCancelRecording: () -> Unit = {},
    val onShowToolCalls: () -> Unit = {},
    val onOpenVoiceConversation: () -> Unit = {},
    val onAddPastedTextAsDocument: (String) -> Unit = {},
)
