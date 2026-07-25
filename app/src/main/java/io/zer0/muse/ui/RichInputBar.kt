package io.zer0.muse.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.TextFormat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.UIMessage
import io.zer0.ai.image.ImageGenParams
import io.zer0.muse.R
import io.zer0.muse.asr.ASRStatus
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.quickmsg.QuickMessageEntity
import io.zer0.muse.ui.chat.VideoAttachment
import io.zer0.muse.ui.common.IosTactileButton
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge

/**
 * P2-12: 富文本输入栏 — 在原 [InputBar] 基础上叠加「Markdown 格式工具条」。
 *
 * 设计目标:
 *  - 保留原 [InputBar] 全部能力(附件 / ASR / 绘图 / 联网搜索 / 引用回复 / 快捷消息等),
 *    不修改 InputBar.kt,通过组合方式在外层包装格式工具条。
 *  - 工具条默认隐藏,点击「格式」按钮展开/收起。
 *  - 工具条提供 8 种格式:粗体 / 斜体 / 行内代码 / 代码块 / 引用 / 无序列表 / 有序列表 / 链接。
 *  - 操作逻辑:有文本时包裹当前文本(近似「选中包裹」,因 InputBar 内部 TextField 不暴露选区);
 *    无文本时插入占位符,用户继续输入替换。
 *  - 链接点击弹 [MuseDialog] 收集 URL,生成 `[text](url)` 占位符。
 *
 * 不引入新依赖,全部使用 MuseShapes / MusePaddings / MuseIconSizes 设计令牌,
 * 不使用 Material3 默认 Button / AlertDialog,统一 [IosTactileButton] / [MuseDialog]。
 *
 * 注:VisualTransformation 实时预览因 InputBar 内部已绑定 MentionHighlightTransformation,
 * 此处不再叠加(任务描述标记为可选),格式预览交由 MessageBubble 渲染时统一处理。
 */
@Composable
internal fun RichInputBar(
    text: String,
    isStreaming: Boolean,
    isDrawMode: Boolean,
    isWebSearchEnabled: Boolean,
    isDeepThinkingEnabled: Boolean = false,
    imageGenParams: ImageGenParams = ImageGenParams(),
    onImageGenParamsChange: (ImageGenParams) -> Unit = {},
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
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
    enterToSend: Boolean = false,
    quickMessages: List<QuickMessageEntity> = emptyList(),
    onInsertQuickMessage: (QuickMessageEntity) -> Unit = {},
    pendingImages: List<String> = emptyList(),
    onPickImage: (asOcr: Boolean) -> Unit = {},
    onPickGalleryImage: (Uri) -> Unit = {},
    onRemovePendingImage: (Int) -> Unit = {},
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
    // P2-12: 是否启用 Markdown 格式工具条(开关关闭时等价于直接调用 InputBar)
    formatEnabled: Boolean = true,
    // v1.0.29: 是否进入页面时自动聚焦输入框并呼出输入法。
    autoFocus: Boolean = true,
) {
    // 工具条展开状态(配置变更后保留,避免旋转屏 / 切后台后丢失)
    var showFormatToolbar by rememberSaveable { mutableStateOf(false) }
    // 链接对话框状态
    var showLinkDialog by rememberSaveable { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current

    // ── 工具条 + 原 InputBar 垂直堆叠 ──
    // 注:InputBar 自身已含 imePadding/navigationBarsPadding,
    // 此处不再重复施加,避免双重 padding 导致输入栏被键盘顶得过高。
    androidx.compose.foundation.layout.Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 格式工具条(展开时可见,默认隐藏;formatEnabled=false 时完全跳过)
        if (formatEnabled) {
            AnimatedVisibility(
                visible = showFormatToolbar,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                FormatToolbarSurface(
                    onFormatClick = { format ->
                        MuseHaptics.light(hapticFeedback)
                        val newText = applyMarkdownFormat(text, format)
                        onTextChanged(newText)
                    },
                onLinkClick = {
                    MuseHaptics.light(hapticFeedback)
                    showLinkDialog = true
                },
            )
        }
        }
        // 格式切换按钮 + 原 InputBar
        // 把「格式」按钮放在 InputBar 上方的独立 Row,右对齐保持视觉轻量。
        // formatEnabled=false 时跳过格式按钮(等价于直接调用 InputBar)
        if (formatEnabled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MusePaddings.inputPadding),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 格式切换按钮(展开时高亮,提示当前状态)
                val toggleTint = if (showFormatToolbar) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
                IosTactileButton(
                    icon = Icons.Default.TextFormat,
                    onClick = {
                        MuseHaptics.light(hapticFeedback)
                        showFormatToolbar = !showFormatToolbar
                    },
                    contentDescription = stringResource(R.string.rich_input_format),
                    tint = toggleTint,
                    size = MuseIconSizes.touchTarget,
                    iconSize = MuseIconSizes.iconMedium,
                )
            }
        }
        // 原 InputBar(完全透传所有参数,不破坏既有逻辑)
        InputBar(
            text = text,
            isStreaming = isStreaming,
            isDrawMode = isDrawMode,
            isWebSearchEnabled = isWebSearchEnabled,
            isDeepThinkingEnabled = isDeepThinkingEnabled,
            imageGenParams = imageGenParams,
            onImageGenParamsChange = onImageGenParamsChange,
            onTextChanged = onTextChanged,
            onSend = onSend,
            onStop = onStop,
            onPickDocument = onPickDocument,
            onToggleDrawMode = onToggleDrawMode,
            onToggleWebSearch = onToggleWebSearch,
            onToggleDeepThinking = onToggleDeepThinking,
            showRestartContext = showRestartContext,
            onRestartContext = onRestartContext,
            assistants = assistants,
            onDelegateToAssistant = onDelegateToAssistant,
            onPickKnowledge = onPickKnowledge,
            onOpenPromptTemplates = onOpenPromptTemplates,
            enterToSend = enterToSend,
            quickMessages = quickMessages,
            onInsertQuickMessage = onInsertQuickMessage,
            pendingImages = pendingImages,
            onPickImage = onPickImage,
            onPickGalleryImage = onPickGalleryImage,
            onRemovePendingImage = onRemovePendingImage,
            pendingVideo = pendingVideo,
            onPickVideo = onPickVideo,
            onRemovePendingVideo = onRemovePendingVideo,
            replyingTo = replyingTo,
            onClearReply = onClearReply,
            replyQuoteOverride = replyQuoteOverride,
            onEditReply = onEditReply,
            isRecording = isRecording,
            asrStatus = asrStatus,
            recordingAmplitudes = recordingAmplitudes,
            onStartRecording = onStartRecording,
            onStopRecording = onStopRecording,
            onCancelRecording = onCancelRecording,
            showMic = showMic,
            toolCallCompleted = toolCallCompleted,
            toolCallTotal = toolCallTotal,
            onShowToolCalls = onShowToolCalls,
            hasDraft = hasDraft,
            onOpenVoiceConversation = onOpenVoiceConversation,
            autoFocus = autoFocus,
        )
    }

    // 链接输入对话框
    if (showLinkDialog) {
        LinkInsertDialog(
            onConfirm = { linkText, url ->
                val newText = applyMarkdownFormat(text, MarkdownFormat.LINK, linkText, url)
                onTextChanged(newText)
                showLinkDialog = false
            },
            onDismiss = { showLinkDialog = false },
        )
    }
}

/**
 * P2-12: Markdown 格式枚举 — 对应工具条 8 个按钮。
 */
enum class MarkdownFormat {
    BOLD,
    ITALIC,
    INLINE_CODE,
    CODE_BLOCK,
    QUOTE,
    UNORDERED_LIST,
    ORDERED_LIST,
    LINK,
}

/**
 * P2-12: 工具条 Surface 容器 — 横向滚动的格式按钮 Row。
 *
 * 使用 [Surface] + [Row] + 多个 [IosTactileButton] 组合,遵循设计令牌:
 *  - 容器:[MuseShapes.semiLarge] 圆角 + surfaceVariant 半透明背景
 *  - 按钮:[IosTactileButton](无涟漪、按下渐变反馈)
 *  - 间距:[MusePaddings.contentGap]
 *  - 横向滚动:防止小屏溢出
 */
@Composable
private fun FormatToolbarSurface(
    onFormatClick: (MarkdownFormat) -> Unit,
    onLinkClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = MuseShapes.semiLarge,
        tonalElevation = MuseElevation.low,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.inputPadding),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = MusePaddings.contentGap, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FormatButton(
                icon = Icons.Default.FormatBold,
                contentDescription = stringResource(R.string.rich_input_bold),
                onClick = { onFormatClick(MarkdownFormat.BOLD) },
            )
            FormatButton(
                icon = Icons.Default.FormatItalic,
                contentDescription = stringResource(R.string.rich_input_italic),
                onClick = { onFormatClick(MarkdownFormat.ITALIC) },
            )
            FormatButton(
                icon = Icons.Default.Code,
                contentDescription = stringResource(R.string.rich_input_inline_code),
                onClick = { onFormatClick(MarkdownFormat.INLINE_CODE) },
            )
            FormatButton(
                icon = Icons.Default.DataObject,
                contentDescription = stringResource(R.string.rich_input_code_block),
                onClick = { onFormatClick(MarkdownFormat.CODE_BLOCK) },
            )
            FormatButton(
                icon = Icons.Default.FormatQuote,
                contentDescription = stringResource(R.string.rich_input_quote),
                onClick = { onFormatClick(MarkdownFormat.QUOTE) },
            )
            FormatButton(
                icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                contentDescription = stringResource(R.string.rich_input_ulist),
                onClick = { onFormatClick(MarkdownFormat.UNORDERED_LIST) },
            )
            FormatButton(
                icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                contentDescription = stringResource(R.string.rich_input_olist),
                onClick = { onFormatClick(MarkdownFormat.ORDERED_LIST) },
            )
            FormatButton(
                icon = Icons.Default.Link,
                contentDescription = stringResource(R.string.rich_input_link),
                onClick = onLinkClick,
            )
        }
    }
}

/**
 * P2-12: 单个格式按钮 — 用 [IosTactileButton] 保持 iOS 触觉反馈一致性。
 */
@Composable
private fun FormatButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IosTactileButton(
        icon = icon,
        onClick = onClick,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        size = MuseIconSizes.touchTarget,
        iconSize = MuseIconSizes.iconMedium,
    )
}

/**
 * P2-12: 链接插入对话框 — 收集链接文本 + URL,确认后生成 `[text](url)`。
 *
 * 使用 [MuseDialog] 自定义 content,内含两个 [OutlinedTextField]。
 * 不使用 AlertDialog(遵循项目规范)。
 */
@Composable
private fun LinkInsertDialog(
    onConfirm: (linkText: String, url: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var linkText by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    val linkTextLabel = stringResource(R.string.rich_input_link_text)
    val linkUrlLabel = stringResource(R.string.rich_input_link_url)
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.rich_input_link_insert),
        content = {
            // 用 Column 排列两个输入框;OutlinedTextField 仅在 Dialog 内部使用,不违反"不用 AlertDialog"约束
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = linkText,
                    onValueChange = { linkText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(linkTextLabel) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(linkUrlLabel) },
                    singleLine = true,
                    // URL 输入用等宽字体提示技术性
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        confirmText = stringResource(R.string.rich_input_link_insert),
        onConfirm = {
            // URL 为空时不允许确认,避免生成无效链接
            if (url.isNotBlank()) {
                val finalText = linkText.ifBlank { url }
                onConfirm(finalText, url)
            }
        },
        dismissText = stringResource(R.string.common_cancel),
        onDismiss = onDismiss,
    )
}

/**
 * P2-12: 应用 Markdown 格式到文本。
 *
 * 行为约定:
 *  - 行内格式(BOLD / ITALIC / INLINE_CODE):文本非空时包裹整个文本;空文本时插入占位符。
 *  - 块格式(CODE_BLOCK / QUOTE / UNORDERED_LIST / ORDERED_LIST):在每行行首加前缀,
 *    空文本时插入单行前缀占位符。
 *  - LINK:用 [linkText] / [url] 生成 `[text](url)`,追加到末尾。
 *
 * 注:由于 [InputBar] 内部 TextField 用 `value: String` 不暴露选区,
 * 此处近似处理「选中文本包裹」为「包裹整个文本」,符合任务描述「未选中时插入占位符」的兜底语义。
 *
 * @param currentText 当前输入框文本
 * @param format 目标格式
 * @param linkText 链接显示文本(仅 [MarkdownFormat.LINK] 使用)
 * @param url 链接 URL(仅 [MarkdownFormat.LINK] 使用)
 * @return 格式化后的新文本
 */
private fun applyMarkdownFormat(
    currentText: String,
    format: MarkdownFormat,
    linkText: String = "",
    url: String = "",
): String {
    val placeholder = "text"
    return when (format) {
        MarkdownFormat.BOLD -> {
            if (currentText.isNotBlank()) "**$currentText**" else "**$placeholder**"
        }
        MarkdownFormat.ITALIC -> {
            if (currentText.isNotBlank()) "*$currentText*" else "*$placeholder*"
        }
        MarkdownFormat.INLINE_CODE -> {
            if (currentText.isNotBlank()) "`$currentText`" else "`$placeholder`"
        }
        MarkdownFormat.CODE_BLOCK -> {
            // 代码块用三反引号包裹,空文本时插入空代码块占位符
            if (currentText.isNotBlank()) {
                "```\n$currentText\n```"
            } else {
                "```\n$placeholder\n```"
            }
        }
        MarkdownFormat.QUOTE -> {
            // 引用:每行行首加 "> "
            if (currentText.isNotBlank()) {
                currentText.lineSequence().joinToString("\n") { "> $it" }
            } else {
                "> "
            }
        }
        MarkdownFormat.UNORDERED_LIST -> {
            // 无序列表:每行行首加 "- "
            if (currentText.isNotBlank()) {
                currentText.lineSequence().joinToString("\n") { "- $it" }
            } else {
                "- "
            }
        }
        MarkdownFormat.ORDERED_LIST -> {
            // 有序列表:每行行首加 "1. " "2. " ...
            if (currentText.isNotBlank()) {
                currentText.lineSequence().mapIndexed { i, line ->
                    "${i + 1}. $line"
                }.joinToString("\n")
            } else {
                "1. "
            }
        }
        MarkdownFormat.LINK -> {
            // 链接:生成 [text](url),追加到原文本末尾(若原文本非空则换行)
            val link = "[$linkText]($url)"
            if (currentText.isNotBlank()) {
                if (currentText.endsWith("\n")) "$currentText$link" else "$currentText\n$link"
            } else {
                link
            }
        }
    }
}
