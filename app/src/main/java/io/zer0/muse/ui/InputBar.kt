package io.zer0.muse.ui

import android.Manifest
import android.os.Build
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import io.zer0.muse.ui.theme.MuseAnimation
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.outlined.GroupWork
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import io.zer0.ai.core.MessageRole
import io.zer0.muse.R
import io.zer0.muse.asr.ASRStatus
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShadow
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.huge
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.SmartImage
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** L-IB1: 输入框字符上限,防止超长文本拖慢渲染或超出模型上下文窗口。 */
private const val INPUT_TEXT_MAX_LENGTH = 5000

/**
 * v1.0.47 P5-2: 从新旧文本中提取被插入(粘贴)的片段。
 *
 * 通过剥离最长公共前缀与最长公共后缀,剩下的即为新增内容。
 * 适用于光标在任意位置的粘贴场景(不只是末尾追加)。
 */
private fun extractInsertedSegment(oldText: String, newText: String): String {
    if (oldText.isEmpty()) return newText
    val minLen = minOf(oldText.length, newText.length)
    var prefix = 0
    while (prefix < minLen && oldText[prefix] == newText[prefix]) prefix++
    var suffix = 0
    while (suffix < minLen - prefix &&
        oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
    ) suffix++
    return newText.substring(prefix, newText.length - suffix)
}

/**
 * v1.131: @mention 高亮正则 — 文件级常量,避免每次 [MentionHighlightTransformation.filter]
 * 重组都新建(输入框文本变化时 filter 会被高频调用)。
 * 匹配 @ 后跟中文/英文/数字/下划线序列(避免误高亮邮箱)。
 */
private val MENTION_HIGHLIGHT_REGEX = Regex("@[\\u4e00-\\u9fa5\\w]+")

/**
 * v0.22 极简胶囊输入栏 — 极简胶囊设计。
 *
 * 设计(对标图片):
 *  - 整体:浅色圆角长条(圆角 24dp),背景 surfaceVariant
 *  - 左侧:圆形 + 号按钮 → 展开附件/图片/绘图/联网搜索菜单
 *  - 中间:无边框 TextField,占位文字"发送消息…"
 *  - 右侧:麦克风(空文本时) / 发送按钮(有文本时)
 *  - 流式中:右侧变为停止按钮
 *
 * 功能保留:
 *  - 附件、图片(OCR/视觉)、语音输入、绘图模式、联网搜索
 *  - 快捷消息 chips、模式选择器、待发送图片预览
 *  - 边缘到边缘: navigationBarsPadding + imePadding
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun InputBar(
    state: MuseInputState = MuseInputState(),
    callbacks: InputBarCallbacks = InputBarCallbacks(),
) {
    val hapticFeedback = LocalHapticFeedback.current
    // B7-07: 从聚合状态/回调中解包,保持函数体原有逻辑不变
    val text = state.text
    val isStreaming = state.isStreaming
    val isWaitingFirstToken = state.isWaitingFirstToken
    val isDrawMode = state.isDrawMode
    val isWebSearchEnabled = state.isWebSearchEnabled
    val isDeepThinkingEnabled = state.isDeepThinkingEnabled
    val deepThinkingLevel = state.deepThinkingLevel
    val showExpandButton = state.showExpandButton
    val imageGenParams = state.imageGenParams
    val showRestartContext = state.showRestartContext
    val assistants = state.assistants
    val enterToSend = state.enterToSend
    val quickMessages = state.quickMessages
    val pendingImages = state.pendingImages
    val pendingDocuments = state.pendingDocuments
    val pendingVideo = state.pendingVideo
    val replyingTo = state.replyingTo
    val replyQuoteOverride = state.replyQuoteOverride
    val isRecording = state.isRecording
    val asrStatus = state.asrStatus
    val recordingAmplitudes = state.recordingAmplitudes
    val showMic = state.showMic
    val toolCallCompleted = state.toolCallCompleted
    val toolCallTotal = state.toolCallTotal
    val hasDraft = state.hasDraft
    val autoFocus = state.autoFocus
    val pasteAsFileEnabled = state.pasteAsFileEnabled
    val pasteAsFileThreshold = state.pasteAsFileThreshold
    val onCycleDeepThinkingLevel = callbacks.onCycleDeepThinkingLevel
    val onImageGenParamsChange = callbacks.onImageGenParamsChange
    val onTextChanged = callbacks.onTextChanged
    val onSend = callbacks.onSend
    val onStop = callbacks.onStop
    val onNavigateInputHistory = callbacks.onNavigateInputHistory
    val onPickDocument = callbacks.onPickDocument
    val onToggleDrawMode = callbacks.onToggleDrawMode
    val onToggleWebSearch = callbacks.onToggleWebSearch
    val onToggleDeepThinking = callbacks.onToggleDeepThinking
    val onRestartContext = callbacks.onRestartContext
    val onDelegateToAssistant = callbacks.onDelegateToAssistant
    val onPickKnowledge = callbacks.onPickKnowledge
    val onOpenPromptTemplates = callbacks.onOpenPromptTemplates
    val onOpenSkills = callbacks.onOpenSkills
    val onInsertQuickMessage = callbacks.onInsertQuickMessage
    val onPickImage = callbacks.onPickImage
    val onPickGalleryImage = callbacks.onPickGalleryImage
    val onRemovePendingImage = callbacks.onRemovePendingImage
    val onRemovePendingDocument = callbacks.onRemovePendingDocument
    val onPickVideo = callbacks.onPickVideo
    val onRemovePendingVideo = callbacks.onRemovePendingVideo
    val onClearReply = callbacks.onClearReply
    val onEditReply = callbacks.onEditReply
    val onStartRecording = callbacks.onStartRecording
    val onStopRecording = callbacks.onStopRecording
    val onCancelRecording = callbacks.onCancelRecording
    val onShowToolCalls = callbacks.onShowToolCalls
    val onOpenVoiceConversation = callbacks.onOpenVoiceConversation
    val onAddPastedTextAsDocument = callbacks.onAddPastedTextAsDocument
    // v1.26: 上滑取消后的"已取消"瞬态提示(1.5s 后自动消失)
    var showCancelledHint by remember { mutableStateOf(false) }
    LaunchedEffect(showCancelledHint) {
        if (showCancelledHint) {
            delay(1500)
            showCancelledHint = false
        }
    }
    var expanded by remember { mutableStateOf(false) }
    // 长按输入栏弹出的动作菜单(全屏输入模式入口)
    var showActionMenu by remember { mutableStateOf(false) }
    if (expanded) {
        MuseExpandedInputEditor(
            text = text,
            onTextChanged = onTextChanged,
            onSend = onSend,
            onClose = { expanded = false },
        )
    }
    // 进入聊天页时自动聚焦输入框(仅在文本为空且允许自动聚焦时,避免打断已有草稿)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (autoFocus && text.isEmpty()) {
            focusRequester.requestFocus()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // v1.99: 大R角/曲面屏设备横向安全区避让(displayCutout 在非 cutout 设备上返回 0,安全)
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .navigationBarsPadding()
            .imePadding()
            // v1.0.72: 输入栏岛两侧留白(缩小: 24dp → 8dp,保留悬浮感但不遮内容)
            .padding(horizontal = 8.dp)
            // v1.0.72: 顶部收窄、底部悬浮间距(缩小到 6dp,高度别太高)
            .padding(top = MusePaddings.inputVertical, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        // v1.0.29: 联网搜索 / 深度思考 已移入加号菜单,
        // 输入栏上方仅保留语音对话入口和工具进度 pill(有内容时才显示)。
        if (showMic || toolCallTotal > 0) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 语音对话模式入口(仅 ASR API 已配置时显示):点击进入全屏连续对话
            // 与长按麦克风区分:长按是单次识别填入输入框,语音对话是连续 ASR + AI + TTS 循环
            if (showMic) {
                val voiceInteractionSource = remember { MutableInteractionSource() }
                val isVoicePressed by voiceInteractionSource.collectIsPressedAsState()
                val voiceBgColor by animateColorAsState(
                    targetValue = if (isVoicePressed) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    label = "voiceConvBg",
                )
                Box(
                    modifier = Modifier
                        .size(MuseIconSizes.touchTarget)
                        .clip(CircleShape)
                        .background(voiceBgColor)
                        .clickable(
                            interactionSource = voiceInteractionSource,
                            indication = null,
                        ) {
                            MuseHaptics.light(hapticFeedback)
                            onOpenVoiceConversation()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.RecordVoiceOver,
                        contentDescription = stringResource(R.string.voice_conversation_open_cd),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                }
            }
            // v1.97: 工具/任务进度 pill — 靠右显示 x/y,不用红色 Badge,保持 UI 一致性
            if (toolCallTotal > 0) {
                Spacer(Modifier.weight(1f))
                val toolPillInteractionSource = remember { MutableInteractionSource() }
                val isToolPillPressed by toolPillInteractionSource.collectIsPressedAsState()
                val toolPillBgColor by animateColorAsState(
                    targetValue = if (isToolPillPressed) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    label = "toolPillBg",
                )
                Row(
                    modifier = Modifier
                        .height(MuseIconSizes.controlTouch)
                        .clip(MuseShapes.pill)
                        .background(toolPillBgColor)
                        .clickable(
                            interactionSource = toolPillInteractionSource,
                            indication = null,
                        ) { onShowToolCalls() }
                        .padding(horizontal = MusePaddings.itemGap)
                        .semantics {
                            contentDescription = "工具调用进度 $toolCallCompleted/$toolCallTotal"
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                    Text(
                        text = "$toolCallCompleted/$toolCallTotal",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        }
        // QuickMessages 气泡
        if (quickMessages.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.inputStackGap),
            ) {
                quickMessages.forEach { qm ->
                    MuseChip(
                        selected = false,
                        onClick = { onInsertQuickMessage(qm) },
                        label = qm.name.ifBlank { stringResource(R.string.chat_unnamed) },
                    )
                }
            }
        }
        // 待发送图片预览
        if (pendingImages.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                pendingImages.forEachIndexed { index, b64 ->
                    Box(
                        modifier = Modifier
                            .size(MusePaddings.previewThumb)
                            .padding(top = MusePaddings.tightGap),
                    ) {
                        SmartImage(
                            model = "data:image/jpeg;base64,$b64",
                            contentDescription = stringResource(R.string.chat_pending_image_cd, index + 1),
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(MuseShapes.medium),
                        )
                        // v1.135: 移除按钮改为 iOS 风格小圆点,避免 48dp 大圆覆盖整张照片。
                        // 视觉尺寸 20dp,实际触摸目标 32dp(可点击区域略大于视觉,保证易点)。
                        val removeInteractionSource = remember { MutableInteractionSource() }
                        val isRemovePressed by removeInteractionSource.collectIsPressedAsState()
                        val removeBgColor by animateColorAsState(
                            targetValue = if (isRemovePressed) MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            label = "removeImgBg",
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = MusePaddings.labelVerticalGap, y = (-MusePaddings.labelVerticalGap))
                                .size(MuseIconSizes.controlTouch)
                                .clickable(
                                    interactionSource = removeInteractionSource,
                                    indication = null,
                                ) { onRemovePendingImage(index) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(MuseIconSizes.iconMedium)
                                    .clip(CircleShape)
                                    .background(removeBgColor)
                                    .padding(MusePaddings.removeDotPadding),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = TablerIcons.X,
                                    contentDescription = stringResource(R.string.chat_remove_image_cd),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }

        // v1.136 T10: 待发送文档预览(可移除的文件芯片)
        if (pendingDocuments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = MusePaddings.tightGap),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pendingDocuments.forEachIndexed { index, doc ->
                    val docInteractionSource = remember { MutableInteractionSource() }
                    Surface(
                        shape = MuseShapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier
                            .padding(top = MusePaddings.tightGap)
                            .clickable(
                                interactionSource = docInteractionSource,
                                indication = null,
                            ) { onRemovePendingDocument(index) },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = MusePaddings.contentGap, vertical = MusePaddings.labelVerticalGap),
                        ) {
                            Icon(
                                imageVector = TablerIcons.FileText,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(MuseIconSizes.iconSmallTiny),
                            )
                            Spacer(Modifier.width(MusePaddings.tightGap))
                            Column {
                                Text(
                                    text = doc.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = MusePaddings.maxInlineWidth),
                                )
                                Text(
                                    text = "${doc.charCount} 字",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(MusePaddings.tightGap))
                            Icon(
                                imageVector = TablerIcons.X,
                                contentDescription = stringResource(R.string.chat_remove_document_cd),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(MuseIconSizes.iconTiny),
                            )
                        }
                    }
                }
            }
        }

        // 待发送视频预览(与图片预览样式一致,叠加时长 + 播放图标提示视频类型)
        pendingVideo?.let { va ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MusePaddings.tightGap),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(MusePaddings.previewThumb)
                        .clip(MuseShapes.medium),
                ) {
                    // 缩略图缺失时降级为深色占位 + 视频图标,避免空白
                    val thumb = va.thumbnail
                    if (!thumb.isNullOrBlank()) {
                        SmartImage(
                            model = "data:image/jpeg;base64,$thumb",
                            contentDescription = stringResource(R.string.chat_pending_video_cd),
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.VideoLibrary,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(MuseIconSizes.iconLarge),
                            )
                        }
                    }
                    // 中央播放图标,提示这是视频而非图片
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(MuseIconSizes.iconVideo)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = TablerIcons.PlayerPlay,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    }
                    // 移除按钮(同图片预览,触摸目标 48dp,Icon 居中)
                    val removeVideoInteractionSource = remember { MutableInteractionSource() }
                    val isRemoveVideoPressed by removeVideoInteractionSource.collectIsPressedAsState()
                    val removeVideoBgColor by animateColorAsState(
                        targetValue = if (isRemoveVideoPressed) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        label = "removeVideoBg",
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(MuseIconSizes.touchTarget)
                            .clip(CircleShape)
                            .background(removeVideoBgColor)
                            .clickable(
                                interactionSource = removeVideoInteractionSource,
                                indication = null,
                            ) { onRemovePendingVideo() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = TablerIcons.X,
                            contentDescription = stringResource(R.string.chat_remove_video_cd),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                    }
                }
                // 右侧显示视频元信息:时长 + 分辨率,帮助用户确认附件
                Column {
                    Text(
                        text = formatVideoDuration(va.durationMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (va.width > 0 && va.height > 0) {
                        Text(
                            text = "${va.width}x${va.height}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }

        // 引用回复:输入框上方显示被引用消息摘要
        // v1.0.72: 移除引用编辑功能(编辑后引用块会固定在输入栏的 bug,编辑本身没什么用)
        // v1.0.72 fix: 整个引用块可点击清除(用户反馈"叉点不下去" — X 太小且可能被点击拦截,
        //   点引用块任意位置都清除引用,降低操作难度)
        replyingTo?.let { msg ->
            // v1.0.72: 引用块做回岛样式(实色背景 + 圆角),输入框透明已解决白块
            io.zer0.muse.ui.common.surface.MuseIsland(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClearReply,
                    ),
                backgroundAlpha = 1f,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.bubbleInner),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            if (msg.role == MessageRole.USER) R.string.quote_label_user
                            else R.string.quote_label_assistant
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = msg.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // v1.0.72: 清除引用按钮 — 加大点击区(48dp) + 图标更大更清晰
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClearReply,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = TablerIcons.X,
                        contentDescription = stringResource(R.string.quote_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
                }
            }
        }

        // v0.34: 绘图模式参数面板(临时覆盖设置默认值)
        if (isDrawMode) {
            ImageGenParamsPanel(
                params = imageGenParams,
                onParamsChange = onImageGenParamsChange,
            )
        }

        // v1.0.53: 思考强度胶囊已合并到加号菜单的"深度思考"开关 —
        // 点击 toggle 开关,长按循环切换级别 (LOW → MED → HIGH → XHIGH)。

        // 主输入栏: 圆角容器
        // v1.0.72: 做回岛样式 — 实色背景 + 圆角 + 阴影(用户反馈完全透明太裸);
        //   输入框本身保持透明,避免实色容器叠成"白块"
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MuseShapes.huge,
            tonalElevation = MuseElevation.low,
            shadowElevation = MuseShadow.low.elevation,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // v0.52: @mention 高亮转换(把 @文档名 染为 primary 色,提示引用了知识库)
            val mentionColor = MaterialTheme.colorScheme.primary
            val mentionTransform = remember(mentionColor) { MentionHighlightTransformation(mentionColor) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // v1.131: 内部 Row vertical padding 6dp → 3dp,缩小输入栏高度
                    // v1.137 B5: vertical padding 3dp → 1dp,进一步降低高度
                    .padding(horizontal = MusePaddings.contentGap, vertical = MusePaddings.compactChipVertical)
                    // 长按输入栏弹出动作菜单(全屏输入模式入口)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = {
                            MuseHaptics.medium(hapticFeedback)
                            showActionMenu = true
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                // v0.44: Sheet 状态声明(右侧 Add 按钮触发,Sheet 块留在 Row 内不影响布局)
                var showToolSheet by remember { mutableStateOf(false) }
                // v0.53: 工具菜单中最近相册权限与图片列表
                val context = LocalContext.current
                val galleryPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_IMAGES
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
                var hasGalleryPermission by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(context, galleryPermission) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    )
                }
                // M-IB1: hasGalleryPermission 仅在首次组合时检查;
                // 用户从系统设置中修改权限后不会自动更新,需重新进入页面才会刷新。
                // 工具 Sheet 打开时通过 LaunchedEffect 触发查询会间接刷新(见下文 recentImages 加载)。
                val galleryPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    hasGalleryPermission = granted
                }
                // 左侧: + 号按钮 → 底部 Sheet
                IconButton(
                    onClick = {
                        MuseHaptics.light(hapticFeedback)
                        showToolSheet = true
                    },
                    enabled = !isStreaming,
                    modifier = Modifier.size(MuseIconSizes.touchTarget),
                ) {
                    Icon(
                        imageVector = TablerIcons.Plus,
                        contentDescription = stringResource(R.string.chat_tools_cd),
                        // v1.79 (L-I7): 禁用态降低 alpha,提供视觉反馈
                        tint = if (isStreaming) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                }
                 if (showToolSheet) {
                     val deepThinkingLabel = stringResource(R.string.chat_deep_thinking_cd)
                     val deepThinkingTitle = if (isDeepThinkingEnabled) {
                         val levelLabel = when (deepThinkingLevel) {
                             // v1.0.72: 思考等级改中文(低/中/高/极高)
                             io.zer0.ai.core.ReasoningLevel.LOW -> "低"
                             io.zer0.ai.core.ReasoningLevel.MEDIUM -> "中"
                             io.zer0.ai.core.ReasoningLevel.HIGH -> "高"
                             io.zer0.ai.core.ReasoningLevel.XHIGH -> "极高"
                             else -> "自动"
                         }
                         "$deepThinkingLabel · $levelLabel"
                     } else {
                         deepThinkingLabel
                     }
                     val toolEntries = buildList {
                         add(
                             ToolEntry(
                                 icon = Icons.Default.Language,
                                 title = stringResource(R.string.chat_web_search_cd),
                                 isActive = isWebSearchEnabled,
                                 showArrow = false,
                                 onClick = {
                                     MuseHaptics.light(hapticFeedback)
                                     onToggleWebSearch()
                                 },
                             ),
                         )
                         add(
                             ToolEntry(
                                 icon = Icons.Default.Psychology,
                                 title = deepThinkingTitle,
                                 isActive = isDeepThinkingEnabled,
                                 showArrow = false,
                                 onClick = {
                                     MuseHaptics.light(hapticFeedback)
                                     onToggleDeepThinking()
                                 },
                                 onLongClick = {
                                     MuseHaptics.light(hapticFeedback)
                                     onCycleDeepThinkingLevel()
                                 },
                             ),
                         )
                         add(
                             ToolEntry(
                                 icon = TablerIcons.Paperclip,
                                 title = stringResource(R.string.chat_tool_attachment),
                                 onClick = {
                                     MuseHaptics.light(hapticFeedback)
                                     showToolSheet = false
                                     onPickDocument()
                                 },
                             ),
                         )
                         add(
                             ToolEntry(
                                 icon = TablerIcons.Book,
                                 title = stringResource(R.string.chat_tool_knowledge),
                                 subtitle = stringResource(R.string.chat_tool_knowledge_subtitle),
                                 onClick = {
                                     MuseHaptics.light(hapticFeedback)
                                     showToolSheet = false
                                     onPickKnowledge()
                                 },
                             ),
                         )
                         add(
                             ToolEntry(
                                 icon = TablerIcons.Book,
                                 title = stringResource(R.string.chat_prompt_templates_title),
                                 subtitle = stringResource(R.string.chat_tool_prompt_template_subtitle),
                                 onClick = {
                                     MuseHaptics.light(hapticFeedback)
                                     showToolSheet = false
                                     onOpenPromptTemplates()
                                 },
                             ),
                         )
                         add(
                             ToolEntry(
                                 icon = Icons.Default.Build,
                                 title = stringResource(R.string.chat_tool_skills),
                                 subtitle = stringResource(R.string.chat_tool_skills_subtitle),
                                 onClick = {
                                     MuseHaptics.light(hapticFeedback)
                                     showToolSheet = false
                                     onOpenSkills()
                                 },
                             ),
                         )
                         add(
                             ToolEntry(
                                 icon = Icons.Default.Brush,
                                 title = stringResource(R.string.chat_tool_draw_mode),
                                 subtitle = if (isDrawMode) stringResource(R.string.chat_tool_draw_mode_subtitle_on) else stringResource(R.string.chat_tool_draw_mode_subtitle),
                                 isActive = isDrawMode,
                                 showArrow = !isDrawMode,
                                 onClick = {
                                     MuseHaptics.light(hapticFeedback)
                                     showToolSheet = false
                                     onToggleDrawMode()
                                 },
                             ),
                         )
                         if (assistants.isNotEmpty()) {
                             add(
                                 ToolEntry(
                                     icon = Icons.Outlined.GroupWork,
                                     title = stringResource(R.string.chat_delegate_action),
                                     subtitle = stringResource(R.string.chat_tool_delegate_subtitle),
                                     onClick = {
                                         MuseHaptics.light(hapticFeedback)
                                         showToolSheet = false
                                         onDelegateToAssistant()
                                     },
                                 ),
                             )
                         }
                         if (showRestartContext) {
                             add(
                                 ToolEntry(
                                     icon = TablerIcons.Refresh,
                                     title = stringResource(R.string.chat_tool_restart_context),
                                     subtitle = stringResource(R.string.chat_tool_restart_context_subtitle),
                                     onClick = {
                                         MuseHaptics.light(hapticFeedback)
                                         showToolSheet = false
                                         onRestartContext()
                                     },
                                 ),
                             )
                         }
                     }
                     MuseToolSheet(
                         context = context,
                         hapticFeedback = hapticFeedback,
                         hasGalleryPermission = hasGalleryPermission,
                         galleryPermission = galleryPermission,
                         onRequestGalleryPermission = { galleryPermissionLauncher.launch(galleryPermission) },
                         onPickImage = { asOcr ->
                             showToolSheet = false
                             onPickImage(asOcr)
                         },
                         onPickGalleryImage = { uri ->
                             showToolSheet = false
                             onPickGalleryImage(uri)
                         },
                         entries = toolEntries,
                         onDismiss = { showToolSheet = false },
                     )
                 }

                 // v1.0.47 P5-4: 抽取 MessageInputField 子组件,隔离输入框高频重组,
                // 避免 onValueChange 触发整个 InputBar(含工具 Sheet/图片预览等)重组。
                MessageInputField(
                    text = text,
                    isStreaming = isStreaming,
                    isDrawMode = isDrawMode,
                    enterToSend = enterToSend,
                    hasDraft = hasDraft,
                    showExpandButton = showExpandButton,
                    focusRequester = focusRequester,
                    mentionTransform = mentionTransform,
                    pasteAsFileEnabled = pasteAsFileEnabled,
                    pasteAsFileThreshold = pasteAsFileThreshold,
                    onTextChanged = onTextChanged,
                    onSend = onSend,
                    onNavigateInputHistory = onNavigateInputHistory,
                    onAddPastedTextAsDocument = onAddPastedTextAsDocument,
                    onExpand = { expanded = true },
                    onClearDraft = { onTextChanged("") },
                )
                // 长按输入栏弹出的动作菜单(全屏输入模式入口)
                DropdownMenu(
                    expanded = showActionMenu,
                    onDismissRequest = { showActionMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_input_action_fullscreen)) },
                        onClick = {
                            showActionMenu = false
                            expanded = true
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = compose.icons.TablerIcons.ArrowsMaximize,
                                contentDescription = null,
                                modifier = Modifier.size(MuseIconSizes.iconMedium),
                            )
                        },
                    )
                }
                // 右侧: 麦克风(空文本且无待发图片时) / 发送(有文本时) / 停止(流式中)
                if (isStreaming) {
                    // 停止生成:红色实心圆形按钮,白色停止图标
                    val stopInteractionSource = remember { MutableInteractionSource() }
                    val isStopPressed by stopInteractionSource.collectIsPressedAsState()
                    val stopScale by animateFloatAsState(
                        targetValue = if (isStopPressed) 0.9f else 1f,
                        label = "stopScale",
                    )
                    Box(
                        modifier = Modifier
                            .size(MuseIconSizes.touchTarget)
                            .clickable(
                                interactionSource = stopInteractionSource,
                                indication = null,
                                onClick = {
                                    MuseHaptics.medium(hapticFeedback)
                                    onStop()
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(MuseIconSizes.stopButton)
                                .graphicsLayer { scaleX = stopScale; scaleY = stopScale }
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isWaitingFirstToken) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(MuseIconSizes.iconSmallTiny),
                                    strokeWidth = MuseIconSizes.progressStroke,
                                    color = MaterialTheme.colorScheme.onError,
                                )
                            } else {
                                Icon(
                                    imageVector = TablerIcons.Square,
                                    contentDescription = stringResource(R.string.chat_stop_generation_cd),
                                    tint = MaterialTheme.colorScheme.onError,
                                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                                )
                            }
                        }
                    }
                } else if (text.isBlank() && pendingImages.isEmpty() && pendingVideo == null && showMic) {
                    // v1.26: 麦克风统一为长按说话 + 上滑取消(不再区分 API/Vosk 路径,
                    //   由 ChatScreen 在 onStartRecording/onStopRecording/onCancelRecording 回调里
                    //   决定使用哪个识别器;InputBar 只负责手势交互)
                    val pulseScale by animateFloatAsState(
                        targetValue = if (isRecording) 1.25f else 1f,
                        animationSpec = if (isRecording) infiniteRepeatable(
                            animation = tween(MuseAnimation.LOOP_NORMAL_MS, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse,
                        ) else tween(MuseAnimation.TACTILE_MS),
                        label = "micPulse",
                    )
                    // v1.79 (H-I2): 用 rememberUpdatedState 包装回调,
                    // 避免 pointerInput(Unit) 捕获过期 lambda(key 不变时不重组)
                    val currentOnStart by rememberUpdatedState(onStartRecording)
                    val currentOnStop by rememberUpdatedState(onStopRecording)
                    val currentOnCancel by rememberUpdatedState(onCancelRecording)
                    Box(
                        modifier = Modifier
                            .size(MuseIconSizes.touchTarget)
                            .pointerInput(Unit) {
                                // v1.79 (M-I10): 上滑取消阈值由 100dp 降至 48dp
                                // (PointerInputScope 是 Density,可直接 toPx)
                                val slideThresholdPx = MuseIconSizes.touchTarget.toPx()
                                awaitPointerEventScope {
                                    val down = awaitFirstDown()
                                    // 长按开始录音;若模型未就绪/权限未授予,
                                    // onStartRecording 返回 false,直接退出不进入手势循环
                                    val started = currentOnStart()
                                    if (!started) {
                                        // v1.98: 移除 Toast 提示,静默处理
                                        return@awaitPointerEventScope
                                    }
                                    MuseHaptics.medium(hapticFeedback)
                                    var cancelled = false
                                    try {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            // v1.79 (H-I3): 改用 firstOrNull 防止越界
                                            val change = event.changes.firstOrNull() ?: continue
                                            // 上滑超过阈值 → 取消(放弃本次识别结果)
                                            if (down.position.y - change.position.y > slideThresholdPx) {
                                                cancelled = true
                                                break
                                            }
                                            // 松手 → 停止录音并处理结果
                                            // (含快速点击:短按短放也走这里,正常处理录音)
                                            if (change.changedToUp()) break
                                        }
                                    } finally {
                                        // v1.79 (H-I1): try/finally 保证 Composable 离开组合时
                                        // 协程被取消也能释放录音资源
                                        if (cancelled) {
                                            currentOnCancel()
                                            showCancelledHint = true
                                        } else {
                                            currentOnStop()
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        val recognizingCd = stringResource(R.string.chat_recognizing_cd)
                        val recordingCd = stringResource(R.string.chat_recording_cd)
                        val holdToRecordCd = stringResource(R.string.chat_hold_to_record_cd)
                        when {
                            // v1.91: Stopping(收尾中)显示 loading,流式模式下 Listening 期间已有结果回填
                            asrStatus == ASRStatus.Stopping -> CircularProgressIndicator(
                                // v1.79 (L-I3): 无障碍 contentDescription
                                modifier = Modifier
                                    .size(MuseIconSizes.iconMedium)
                                    .semantics { contentDescription = recognizingCd },
                                strokeWidth = MuseIconSizes.progressStroke,
                            )
                            // 任务 1: Reconnecting(断网重连中)显示 loading,提示用户网络恢复中
                            asrStatus == ASRStatus.Reconnecting -> CircularProgressIndicator(
                                modifier = Modifier
                                    .size(MuseIconSizes.iconMedium)
                                    .semantics { contentDescription = recognizingCd },
                                strokeWidth = MuseIconSizes.progressStroke,
                            )
                            isRecording -> Icon(
                                imageVector = TablerIcons.Microphone,
                                contentDescription = recordingCd,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .size(MuseIconSizes.iconMedium)
                                    .scale(pulseScale),
                            )
                            else -> Icon(
                                imageVector = TablerIcons.Microphone,
                                contentDescription = holdToRecordCd,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(MuseIconSizes.iconMedium),
                            )
                        }
                    }
                } else {
                    // 发送按钮:月桂绿实心圆形按钮,白色纸飞机图标
                    val sendInteractionSource = remember { MutableInteractionSource() }
                    val sendPressed by sendInteractionSource.collectIsPressedAsState()
                    val sendScale by animateFloatAsState(
                        targetValue = if (sendPressed) 0.9f else 1f,
                        label = "sendScale",
                    )
                    val canSend = text.isNotBlank() || pendingImages.isNotEmpty() || pendingVideo != null
                    Box(
                        modifier = Modifier
                            .size(MuseIconSizes.touchTarget)
                            .clickable(
                                interactionSource = sendInteractionSource,
                                indication = null,
                                enabled = canSend,
                                onClick = {
                                    MuseHaptics.medium(hapticFeedback)
                                    onSend()
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(MuseIconSizes.stopButton)
                                .graphicsLayer { scaleX = sendScale; scaleY = sendScale }
                                .clip(CircleShape)
                                .background(
                                    if (canSend) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = TablerIcons.Send,
                                contentDescription = stringResource(R.string.action_send),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(MuseIconSizes.iconSmall),
                            )
                        }
                    }
                }
            }
        }

        // 录音/识别/取消状态提示条
        // v1.91: Stopping(收尾中)显示 loading;isRecording 含 Connecting/Listening/Stopping/Reconnecting,
        // 但 Stopping/Reconnecting 优先匹配到 LoadingDots,Listening/Connecting 才走波形分支
        if (isRecording || asrStatus == ASRStatus.Stopping || showCancelledHint) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MusePaddings.tightGap),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    asrStatus == ASRStatus.Stopping -> LoadingDots(text = stringResource(R.string.voice_recognizing))
                    // 任务 1: Reconnecting(断网重连中)显示"网络异常,正在重连…",提示用户网络问题
                    asrStatus == ASRStatus.Reconnecting -> LoadingDots(text = stringResource(R.string.voice_reconnecting))
                    // v1.0.4 (P2): Connecting 阶段尚未开始录音,显示"正在连接识别服务…"而不是空白波形
                    // (isRecording 包含 Connecting,必须先短路匹配 Connecting 才能落到正确的分支)
                    asrStatus == ASRStatus.Connecting -> LoadingDots(text = stringResource(R.string.voice_connecting))
                    isRecording -> {
                        RecordingWaveform(amplitudes = recordingAmplitudes)
                        Spacer(modifier = Modifier.width(MusePaddings.contentGap))
                        Text(
                            text = stringResource(R.string.voice_release_to_recognize),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    showCancelledHint -> Text(
                        text = stringResource(R.string.chat_cancelled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 录音波形条:把最近振幅历史渲染成竖条。
 * v1.91: 振幅改为归一化 Float(0-1f),无需再除以 32768。
 */
/**
 * v0.52: @mention 高亮转换。
 *
 * 知识库选中后输入框插入 "@文档名" 纯文本(见 ChatScreen 知识库 sheet),
 * 此 VisualTransformation 在显示层把 "@文档名" 染为 primary 色 + 中粗体,
 * 作为引用知识库的视觉提示。不改动底层文本(发送内容不变)。
 *
 * 匹配规则:`@` 后跟中文/英文/数字/下划线序列。
 */
private class MentionHighlightTransformation(
    private val highlightColor: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        // v1.79 (L-I5): 收紧正则,仅匹配 @后跟中文/英文/数字/下划线,避免误高亮邮箱等
        // v1.131: Regex 提为文件级常量 MENTION_HIGHLIGHT_REGEX,避免每次 filter 重组都新建。
        if (!text.text.contains('@')) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val builder = AnnotatedString.Builder(text.text)
        MENTION_HIGHLIGHT_REGEX.findAll(text.text).forEach { match ->
            builder.addStyle(
                SpanStyle(color = highlightColor, fontWeight = FontWeight.Medium),
                match.range.first,
                match.range.last + 1,
            )
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}

/**
 * 视频时长格式化:毫秒 → "M:SS"(超过 1 小时则 "H:MM:SS")。
 * 仅用于 UI 预览,无业务逻辑依赖。
 */
private fun formatVideoDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "0:00"
    val totalSec = durationMs / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * v1.0.47 P5-4: 消息输入框子组件 — 渲染隔离。
 *
 * 从 [InputBar] 抽取的独立 Composable,封装:
 *  - 草稿标记
 *  - TextField(含 @mention 高亮、回车发送、箭头历史导航)
 *  - 长文本粘贴检测 + 转文件对话框
 *  - 展开按钮(全屏输入)
 *  - Token 计数按钮(仅 tokenEstimateEnabled 时)
 *
 * 渲染隔离原理:
 *  - Compose 编译器对参数稳定的 Composable 生成 skippable 代码
 *  - 当 InputBar 其他参数变化(如 toolCallCompleted/isStreaming)时,
 *    只要 MessageInputField 的参数未变,它不会被重新组合
 *  - 反之,当 text 变化时,只有本组件重组,InputBar 中的工具 Sheet/
 *    图片预览/文档芯片等(已抽取为独立 Composable 或 if 块)不受影响
 *
 * 内部状态(粘贴检测)随组件生命周期管理,切会话时随 InputBar 重组自动重置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RowScope.MessageInputField(
    text: String,
    isStreaming: Boolean,
    isDrawMode: Boolean,
    enterToSend: Boolean,
    hasDraft: Boolean,
    showExpandButton: Boolean,
    focusRequester: FocusRequester,
    mentionTransform: VisualTransformation,
    pasteAsFileEnabled: Boolean,
    pasteAsFileThreshold: Int,
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onNavigateInputHistory: (Int) -> Unit,
    onAddPastedTextAsDocument: (String) -> Unit,
    onExpand: () -> Unit,
    onClearDraft: () -> Unit,
) {
    // ── 长文本粘贴检测状态 ──────────────────────────────────────────
    // v1.0.47 P5-2: 记录上一次输入文本,用于判断是否为大段粘贴
    var prevInputText by remember { mutableStateOf(text) }
    // 待确认的粘贴内容(newText=完整新文本, inserted=提取出的粘贴片段)
    var pendingPaste by remember { mutableStateOf<Pair<String, String>?>(null) }

    /**
     * 输入变化处理,拦截大段粘贴。
     * 检测到粘贴(新增字符数 >= [pasteAsFileThreshold])时暂存待确认,弹窗让用户选择
     * 「作为文件附加」或「直接粘贴」,而非直接塞入输入框。
     */
    fun handleInputChange(newText: String) {
        if (newText.length > INPUT_TEXT_MAX_LENGTH) return
        val delta = newText.length - prevInputText.length
        if (pasteAsFileEnabled && delta >= pasteAsFileThreshold && pendingPaste == null) {
            val inserted = extractInsertedSegment(prevInputText, newText)
            if (inserted.length >= pasteAsFileThreshold) {
                pendingPaste = newText to inserted
                return // 不立即更新 text,等用户选择
            }
        }
        prevInputText = newText
        onTextChanged(newText)
    }

    // 外部清空输入(发送/切会话/历史导航)时同步 prevInputText,避免误判
    LaunchedEffect(text) {
        if (pendingPaste == null) prevInputText = text
    }

    // ── 草稿标记 ─────────────────────────────────────────────────────
    if (hasDraft && text.isNotBlank()) {
        Text(
            text = stringResource(R.string.chat_draft_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    shape = MuseShapes.pill,
                )
                .clickable { onClearDraft() }
                .padding(MusePaddings.chipInner),
        )
    }

    // ── TextField ────────────────────────────────────────────────────
    // 回车键发送(enterToSend 开启时,Enter 发送,Shift+Enter 换行)
    MuseTextField(
        value = text,
        onValueChange = { handleInputChange(it) },
        // v1.0.72: 输入框背景透明 — 岛背景就是容器,避免输入框实色块叠成"白块"
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier
            .weight(1f)
            .heightIn(min = MuseIconSizes.inputMinHeight, max = MusePaddings.maxMessageFieldHeight)
            .focusRequester(focusRequester)
            .onKeyEvent { event ->
                if (enterToSend && event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    (event.nativeKeyEvent.metaState and KeyEvent.META_SHIFT_ON) == 0
                ) {
                    if (text.isNotBlank() && !isStreaming) {
                        onSend()
                    }
                    true
                } else if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP
                ) {
                    // 上箭头 → 输入历史向更旧移动(硬件方向键映射为 DPAD)
                    onNavigateInputHistory(-1)
                    true
                } else if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                ) {
                    // 下箭头 → 输入历史向更新移动
                    onNavigateInputHistory(1)
                    true
                } else {
                    false
                }
            },
        placeholder = {
            Text(
                if (isDrawMode) stringResource(R.string.chat_placeholder_draw) else stringResource(R.string.chat_placeholder_send),
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        keyboardOptions = KeyboardOptions(
            imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default,
        ),
        keyboardActions = KeyboardActions(
            onSend = {
                if (text.isNotBlank() && !isStreaming) {
                    onSend()
                }
            },
        ),
        visualTransformation = mentionTransform,
    )

    // ── 展开按钮(全屏输入) ──────────────────────────────────────────
    if (showExpandButton && !isStreaming) {
        IconButton(
            onClick = onExpand,
            modifier = Modifier.size(MuseIconSizes.touchTarget),
        ) {
            Icon(
                imageVector = compose.icons.TablerIcons.ArrowsMaximize,
                contentDescription = stringResource(R.string.chat_expand_input_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
        }
    }

    // ── 大段粘贴确认对话框 ──────────────────────────────────────────
    val pending = pendingPaste
    if (pending != null) {
        val (fullText, inserted) = pending
        val charCount = inserted.length
        MuseDialog(
            onDismissRequest = {
                // 取消:不粘贴也不转文件,保持原输入
                pendingPaste = null
            },
            title = stringResource(R.string.chat_paste_as_file_title),
            content = {
                Text(
                    stringResource(R.string.chat_paste_as_file_msg, charCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmText = stringResource(R.string.chat_paste_as_file_attach),
            onConfirm = {
                onAddPastedTextAsDocument(inserted)
                prevInputText = fullText.removeRange(
                    fullText.indexOf(inserted),
                    fullText.indexOf(inserted) + inserted.length,
                )
                pendingPaste = null
            },
            dismissText = stringResource(R.string.chat_paste_as_file_paste),
            onDismiss = {
                prevInputText = fullText
                onTextChanged(fullText)
                pendingPaste = null
            },
        )
    }
}

/** v1.0.72: Telegram 风格输入岛底部悬浮间距(dp)。 */
private val InputIslandBottomGap = 10.dp
