package io.zer0.muse.ui

import android.Manifest
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.KeyEvent
import java.io.ByteArrayOutputStream
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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoCamera
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import coil.compose.AsyncImage
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.ai.image.ImageGenParams
import io.zer0.ai.image.ImageModelCatalog
import io.zer0.muse.R
import io.zer0.muse.asr.ASRStatus
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.quickmsg.QuickMessageEntity
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShadow
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.huge
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.SmartImage
import io.zer0.muse.ui.chat.TokenStatsBar
import io.zer0.muse.ui.chat.VideoAttachment
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
    text: String,
    isStreaming: Boolean,
    isDrawMode: Boolean,
    isWebSearchEnabled: Boolean,
    isDeepThinkingEnabled: Boolean = false,
    // v1.0.47 P5-6: 深度思考级别(仅 isDeepThinkingEnabled=true 时有意义),输入栏点击胶囊循环切换
    deepThinkingLevel: io.zer0.ai.core.ReasoningLevel = io.zer0.ai.core.ReasoningLevel.HIGH,
    onCycleDeepThinkingLevel: () -> Unit = {},
    showExpandButton: Boolean = false,
    // v0.34: 绘图参数(临时覆盖设置默认值)
    imageGenParams: ImageGenParams = ImageGenParams(),
    onImageGenParamsChange: (ImageGenParams) -> Unit = {},
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    // v1.0.47 P5: 输入框上/下箭头回调,遍历本会话输入历史(direction<0=上/旧,direction>0=下/新)。
    // 仅硬件键盘生效;软键盘无箭头键,不触发。
    onNavigateInputHistory: (Int) -> Unit = {},
    onPickDocument: () -> Unit,
    onToggleDrawMode: () -> Unit,
    onToggleWebSearch: () -> Unit,
    onToggleDeepThinking: () -> Unit = {},
    // v1.24: Agent 加号工具栏专用 — 重启上下文(普通会话不显示)
    showRestartContext: Boolean = false,
    onRestartContext: () -> Unit = {},
    // v1.25: 委托给助手入口(点击弹出助手/团队选择 sheet)
    assistants: List<AssistantEntity> = emptyList(),
    onDelegateToAssistant: () -> Unit = {},
    // v0.29 P1-6: 知识库 @mention 入口(点击弹出文档选择 sheet)
    onPickKnowledge: () -> Unit = {},
    // v1.58: Prompt 模板库入口(点击弹出模板选择 sheet)
    onOpenPromptTemplates: () -> Unit = {},
    // v2.2: 技能入口(点击跳转技能列表)
    onOpenSkills: () -> Unit = {},
    // v0.31: 回车键发送消息开关(关闭则回车换行)
    enterToSend: Boolean = false,
    quickMessages: List<QuickMessageEntity> = emptyList(),
    onInsertQuickMessage: (QuickMessageEntity) -> Unit = {},
    pendingImages: List<String> = emptyList(),
    onPickImage: (asOcr: Boolean) -> Unit = {},
    // v0.53: 工具菜单中最近相册图片点击回调
    onPickGalleryImage: (Uri) -> Unit = {},
    onRemovePendingImage: (Int) -> Unit = {},
    // v1.136 T10: 待发送文档(已解析为纯文本,发送时合并到消息内容)
    pendingDocuments: List<io.zer0.muse.ui.chat.PendingDocument> = emptyList(),
    onRemovePendingDocument: (Int) -> Unit = {},
    // 视频输入支持:待发送视频附件(null 表示无);与 pendingImages 互斥,避免一次发送超大 payload
    pendingVideo: VideoAttachment? = null,
    onPickVideo: () -> Unit = {},
    onRemovePendingVideo: () -> Unit = {},
    // 引用回复:当前正在回复的消息与取消回调
    replyingTo: UIMessage? = null,
    onClearReply: () -> Unit = {},
    // v1.57: 引用卡片自定义文本(用户编辑裁剪后)+ 编辑回调
    replyQuoteOverride: String? = null,
    onEditReply: (String) -> Unit = {},
    isRecording: Boolean = false,
    asrStatus: ASRStatus = ASRStatus.Idle,
    recordingAmplitudes: List<Float> = emptyList(),
    onStartRecording: () -> Boolean = { false },
    onStopRecording: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
    // v1.97: 仅在用户配置了 ASR API 后才显示麦克风 UI
    showMic: Boolean = true,
    // v1.97: 工具/任务进度指示器 — pill 形状显示 x/y,有进度时显示在 Row 右侧
    toolCallCompleted: Int = 0,
    toolCallTotal: Int = 0,
    onShowToolCalls: () -> Unit = {},
    // 功能2: 是否显示"草稿"标记(从 DataStore 恢复的输入)
    hasDraft: Boolean = false,
    // 语音对话模式入口:点击进入全屏语音对话(连续 ASR + AI + TTS)
    // 仅在已配置 ASR API(showMic=true)时显示,与普通长按录音区分
    onOpenVoiceConversation: () -> Unit = {},
    // v1.0.29: 是否进入页面时自动聚焦输入框并呼出输入法。
    // Agent Tab 首次切换时不应主动弹键盘,避免抢占屏幕。
    autoFocus: Boolean = true,
    // v1.0.47 P5-3: Token 估算开关(默认关闭)。开启时输入栏底部显示 Token 统计条。
    tokenEstimateEnabled: Boolean = false,
    // v1.0.53: Token 统计条数据(历史消息 token 数 + 模型上下文窗口)
    historyTokens: Int = 0,
    contextWindow: Int = 0,
    // v1.0.47 P5-2: 长文本粘贴转文件(默认开启),粘贴超阈值文本时弹窗提示转为 txt 附件
    pasteAsFileEnabled: Boolean = true,
    pasteAsFileThreshold: Int = 2000,
    onAddPastedTextAsDocument: (String) -> Unit = {},
) {
    val hapticFeedback = LocalHapticFeedback.current
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
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)).systemBarsPadding()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = { expanded = false }) {
                        Icon(compose.icons.TablerIcons.ArrowLeft, contentDescription = stringResource(R.string.action_cancel), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    Text(text = stringResource(R.string.chat_expand_input_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(48.dp))
                }
                Spacer(Modifier.height(12.dp))
                MuseTextField(
                    value = text,
                    onValueChange = { if (it.length <= 50000) onTextChanged(it) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    minLines = 10, maxLines = 100,
                    placeholder = { Text(stringResource(R.string.chat_placeholder_send)) },
                )
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Surface(
                        onClick = { if (text.isNotBlank()) { onSend(); expanded = false } },
                        enabled = text.isNotBlank(),
                        shape = io.zer0.muse.ui.theme.MuseShapes.pill,
                        color = if (text.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(compose.icons.TablerIcons.Send, contentDescription = null, modifier = Modifier.size(MuseIconSizes.iconSmall), tint = if (text.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            Spacer(Modifier.width(6.dp))
                            Text(text = stringResource(R.string.action_send), style = MaterialTheme.typography.labelLarge, color = if (text.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        }
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
            // v1.132: 输入栏横向宽度缩小(两侧 padding 12dp → 24dp,总宽度减少 24dp);
            // v1.137 B5: 纵向 padding 4dp → 2dp,进一步降低输入栏高度
            .padding(horizontal = 24.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // v1.0.29: 联网搜索 / 深度思考 已移入加号菜单,
        // 输入栏上方仅保留语音对话入口和工具进度 pill(有内容时才显示)。
        if (showMic || toolCallTotal > 0) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                        .height(32.dp)
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
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
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
                horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pendingImages.forEachIndexed { index, b64 ->
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .padding(top = 4.dp),
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
                                .offset(x = 6.dp, y = (-6).dp)
                                .size(32.dp)
                                .clickable(
                                    interactionSource = removeInteractionSource,
                                    indication = null,
                                ) { onRemovePendingImage(index) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(removeBgColor)
                                    .padding(3.dp),
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
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pendingDocuments.forEachIndexed { index, doc ->
                    val docInteractionSource = remember { MutableInteractionSource() }
                    Surface(
                        shape = MuseShapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clickable(
                                interactionSource = docInteractionSource,
                                indication = null,
                            ) { onRemovePendingDocument(index) },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                imageVector = TablerIcons.FileText,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Column {
                                Text(
                                    text = doc.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 120.dp),
                                )
                                Text(
                                    text = "${doc.charCount} 字",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = TablerIcons.X,
                                contentDescription = stringResource(R.string.chat_remove_document_cd),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(14.dp),
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
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
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
                            .size(28.dp)
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
        replyingTo?.let { msg ->
            // v1.57: 引用卡片编辑对话框状态
            var showEditReplyDialog by remember { mutableStateOf(false) }
            var editReplyText by remember(replyQuoteOverride, msg.id) {
                mutableStateOf(replyQuoteOverride ?: msg.content)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(MuseShapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
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
                        // v1.57: 优先显示用户编辑裁剪后的引用文本
                        text = replyQuoteOverride ?: msg.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // v1.57: 编辑引用文本按钮(精准引用部分内容)
                IconButton(onClick = { showEditReplyDialog = true }) {
                    Icon(
                        imageVector = TablerIcons.Edit,
                        contentDescription = stringResource(R.string.chat_edit_reply_cd),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
                IconButton(onClick = onClearReply) {
                    Icon(
                        imageVector = TablerIcons.X,
                        contentDescription = stringResource(R.string.quote_clear),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            // v1.57: 引用编辑对话框(iOS 风格 MuseDialog)
            if (showEditReplyDialog) {
                MuseDialog(
                    onDismissRequest = { showEditReplyDialog = false },
                    title = stringResource(R.string.chat_edit_reply_title),
                    content = {
                        MuseTextField(
                            value = editReplyText,
                            onValueChange = { editReplyText = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 8,
                        )
                    },
                    confirmText = stringResource(R.string.action_save),
                    onConfirm = {
                        if (editReplyText.isNotBlank()) {
                            onEditReply(editReplyText)
                            showEditReplyDialog = false
                        }
                    },
                    dismissText = stringResource(R.string.action_cancel),
                    onDismiss = { showEditReplyDialog = false },
                )
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
        // H-IB1: 预设主题未定义 surfaceContainerLow,改用 surfaceVariant 保持主题一致
        // v1.0.22: 加 MuseShadow.low 微阴影,增强 MANUS 风格浮起感
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
                    .padding(horizontal = 8.dp, vertical = 1.dp)
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
                horizontalArrangement = Arrangement.spacedBy(4.dp),
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
                    // v1.46: MANUS 风格底部展开面板(替代 MuseDialog 弹窗)
                    // v1.0.29: maxHeightFraction=0.55f,让加号菜单高度对齐
                    // 摄像头/照片磁贴底部,位置更自然。
                    MuseBottomSheet(
                        onDismissRequest = { showToolSheet = false },
                        maxHeightFraction = 0.55f,
                    ) {
                        Text(
                            text = stringResource(R.string.chat_tools_pick_content),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )

                        Spacer(Modifier.height(16.dp))

                        // v0.53: 最近相册图片(工具 Sheet 展开时刷新)
                        var recentImages by remember { mutableStateOf<List<Uri>>(emptyList()) }
                        // v1.79 (H-I5): MediaStore 查询移到 IO 线程,避免阻塞主线程
                        LaunchedEffect(showToolSheet, hasGalleryPermission) {
                            if (showToolSheet && hasGalleryPermission) {
                                recentImages = withContext(Dispatchers.IO) {
                                    queryRecentGalleryImages(context, 10)
                                }
                            }
                        }

                        // 媒体快捷入口:iOS 风格横向圆角卡片 + 右侧最近相册
                        // v1.46: 整体可横向滑动,避免小屏手机占用最近相册空间
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = MusePaddings.tightGap),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // 摄像头 / 照片(用户要求互换位置:摄像头在前,照片在后)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ToolMediaCard(
                                    icon = Icons.Default.PhotoCamera,
                                    label = stringResource(R.string.chat_tool_camera),
                                    onClick = {
                                        MuseHaptics.light(hapticFeedback)
                                        showToolSheet = false
                                        onPickImage(true)
                                    },
                                )
                                ToolMediaCard(
                                    icon = Icons.Default.Photo,
                                    label = stringResource(R.string.chat_tool_photo),
                                    onClick = {
                                        MuseHaptics.light(hapticFeedback)
                                        showToolSheet = false
                                        onPickImage(false)
                                    },
                                )
                            }

                            // 分隔线
                            if (recentImages.isNotEmpty() || !hasGalleryPermission) {
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = MusePaddings.tightGap)
                                        .width(1.dp)
                                        .height(64.dp)
                                        .background(
                                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                            // 0.5dp 圆角视觉不可见,改用 RectangleShape 更明确
                                            RectangleShape,
                                        ),
                                )
                            }

                            // 右侧最近相册:点击快速加入待发送(父 Row 已支持横向滑动)
                            if (hasGalleryPermission) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    recentImages.forEach { uri ->
                                        AsyncImage(
                                            model = uri,
                                            contentDescription = stringResource(R.string.chat_gallery_image_cd),
                                            modifier = Modifier
                                                .size(64.dp)
                                                .clip(MuseShapes.medium)
                                                .clickable {
                                                    MuseHaptics.light(hapticFeedback)
                                                    showToolSheet = false
                                                    onPickGalleryImage(uri)
                                                },
                                            contentScale = ContentScale.Crop,
                                        )
                                    }
                                }
                            } else {
                                Surface(
                                    modifier = Modifier
                                        .height(64.dp)
                                        .clip(MuseShapes.medium)
                                        .clickable {
                                            MuseHaptics.light(hapticFeedback)
                                            galleryPermissionLauncher.launch(galleryPermission)
                                        },
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = MusePaddings.screen),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Photo,
                                            contentDescription = stringResource(R.string.chat_gallery_cd),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = stringResource(R.string.chat_authorize_gallery),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(24.dp))

                        // 功能列表:iOS 风格左图标 + 标题/副标题 + 右箭头
                        // v1.63: 加 verticalScroll + heightIn(max),避免功能多了被截断
                        // v1.125: 内层 Column(bottom padding) 避免滚动到底时最后一项被裁剪
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                        // v1.0.29: 联网搜索 / 深度思考 移入加号菜单,
                        // 放在媒体入口下方、附件上方,与下方功能列表保持同一样式。
                        ToolListRow(
                            icon = Icons.Default.Language,
                            title = stringResource(R.string.chat_web_search_cd),
                            isActive = isWebSearchEnabled,
                            showArrow = false,
                            onClick = {
                                MuseHaptics.light(hapticFeedback)
                                onToggleWebSearch()
                            },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            thickness = 0.5.dp,
                        )
                        // v1.0.53: 深度思考合并了思考强度胶囊 —
                        // 点击:toggle 开关;长按:循环切换级别 (LOW→MED→HIGH→XHIGH)
                        val deepThinkingLabel = stringResource(R.string.chat_deep_thinking_cd)
                        val deepThinkingTitle = if (isDeepThinkingEnabled) {
                            val levelLabel = when (deepThinkingLevel) {
                                io.zer0.ai.core.ReasoningLevel.LOW -> "LOW"
                                io.zer0.ai.core.ReasoningLevel.MEDIUM -> "MED"
                                io.zer0.ai.core.ReasoningLevel.HIGH -> "HIGH"
                                io.zer0.ai.core.ReasoningLevel.XHIGH -> "XHIGH"
                                else -> "AUTO"
                            }
                            "$deepThinkingLabel · $levelLabel"
                        } else {
                            deepThinkingLabel
                        }
                        ToolListRow(
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
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            thickness = 0.5.dp,
                        )
                        ToolListRow(
                            icon = TablerIcons.Paperclip,
                            title = stringResource(R.string.chat_tool_attachment),
                            onClick = {
                                MuseHaptics.light(hapticFeedback)
                                showToolSheet = false
                                onPickDocument()
                            },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            thickness = 0.5.dp,
                        )
                        ToolListRow(
                            icon = TablerIcons.Book,
                            title = stringResource(R.string.chat_tool_knowledge),
                            subtitle = stringResource(R.string.chat_tool_knowledge_subtitle),
                            onClick = {
                                MuseHaptics.light(hapticFeedback)
                                showToolSheet = false
                                onPickKnowledge()
                            },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            thickness = 0.5.dp,
                        )
                        // v1.58: Prompt 模板库
                        ToolListRow(
                            icon = TablerIcons.Book,
                            title = stringResource(R.string.chat_prompt_templates_title),
                            subtitle = stringResource(R.string.chat_tool_prompt_template_subtitle),
                            onClick = {
                                MuseHaptics.light(hapticFeedback)
                                showToolSheet = false
                                onOpenPromptTemplates()
                            },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            thickness = 0.5.dp,
                        )
                        // v2.2: 技能入口
                        ToolListRow(
                            icon = Icons.Default.Build,
                            title = stringResource(R.string.chat_tool_skills),
                            subtitle = stringResource(R.string.chat_tool_skills_subtitle),
                            onClick = {
                                MuseHaptics.light(hapticFeedback)
                                showToolSheet = false
                                onOpenSkills()
                            },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            thickness = 0.5.dp,
                        )
                        ToolListRow(
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
                        )
                        if (assistants.isNotEmpty()) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                thickness = 0.5.dp,
                            )
                            ToolListRow(
                                icon = Icons.Outlined.GroupWork,
                                title = stringResource(R.string.chat_delegate_action),
                                subtitle = stringResource(R.string.chat_tool_delegate_subtitle),
                                onClick = {
                                    MuseHaptics.light(hapticFeedback)
                                showToolSheet = false
                                onDelegateToAssistant()
                                },
                            )
                        }
                        if (showRestartContext) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                thickness = 0.5.dp,
                            )
                            ToolListRow(
                                icon = TablerIcons.Refresh,
                                title = stringResource(R.string.chat_tool_restart_context),
                                subtitle = stringResource(R.string.chat_tool_restart_context_subtitle),
                                onClick = {
                                    MuseHaptics.light(hapticFeedback)
                                showToolSheet = false
                                onRestartContext()
                                },
                            )
                        }
                            } // 内部 padding Column 结束
                        } // verticalScroll Column 结束
                    }
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
                            .size(36.dp)
                            .graphicsLayer { scaleX = stopScale; scaleY = stopScale }
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error)
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
                        Icon(
                            imageVector = TablerIcons.Square,
                            contentDescription = stringResource(R.string.chat_stop_generation_cd),
                            tint = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
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
                                val slideThresholdPx = 48.dp.toPx()
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
                                    .size(20.dp)
                                    .semantics { contentDescription = recognizingCd },
                                strokeWidth = 2.dp,
                            )
                            // 任务 1: Reconnecting(断网重连中)显示 loading,提示用户网络恢复中
                            asrStatus == ASRStatus.Reconnecting -> CircularProgressIndicator(
                                modifier = Modifier
                                    .size(20.dp)
                                    .semantics { contentDescription = recognizingCd },
                                strokeWidth = 2.dp,
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
                            .size(36.dp)
                            .graphicsLayer { scaleX = sendScale; scaleY = sendScale }
                            .clip(CircleShape)
                            .background(
                                if (canSend) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                            )
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

        // 录音/识别/取消状态提示条
        // v1.91: Stopping(收尾中)显示 loading;isRecording 含 Connecting/Listening/Stopping/Reconnecting,
        // 但 Stopping/Reconnecting 优先匹配到 LoadingDots,Listening/Connecting 才走波形分支
        if (isRecording || asrStatus == ASRStatus.Stopping || showCancelledHint) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
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
                        Spacer(modifier = Modifier.width(8.dp))
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

        // v1.0.53: 输入栏底部 Token 统计条(替代原先的输入栏旁 Token 按钮)
        if (tokenEstimateEnabled) {
            TokenStatsBar(
                inputText = text,
                historyTokens = historyTokens,
                contextWindow = contextWindow,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 录音波形条:把最近振幅历史渲染成竖条。
 * v1.91: 振幅改为归一化 Float(0-1f),无需再除以 32768。
 */
@Composable
private fun RecordingWaveform(amplitudes: List<Float>) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.height(24.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        amplitudes.forEach { amp ->
            // v1.91: amp 已是 0-1f 归一化值,直接 coerceIn 即可
            val fraction = amp.coerceIn(0.05f, 1f)
            val animatedHeight by animateFloatAsState(
                targetValue = fraction,
                animationSpec = tween(MuseAnimation.FAST_MS),
                label = "wave",
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(animatedHeight)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(primary.copy(alpha = 0.7f)),
            )
        }
    }
}

/**
 * v0.35: 绘图模式参数面板 — 尺寸/质量/风格 + 参考图临时覆盖。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImageGenParamsPanel(
    params: ImageGenParams,
    onParamsChange: (ImageGenParams) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.let {
            scope.launch {
                // v1.79 (H-I4): 大图片读取 + Base64 编码移到 IO 线程,避免阻塞主线程
                // v1.140: 选图后自动压缩到 1024px 内 + JPEG 85,限制 base64 后 <= 4MB,
                //         避免原图 10MB+ 直传导致 OOM 和请求超时
                withContext(Dispatchers.IO) {
                    runCatching {
                        compressReferenceImageToDataUri(uri = it, context = context)
                    }
                }.onSuccess { result ->
                    onParamsChange(params.copy(referenceImageUri = result.dataUri))
                    // UI 提示压缩后的尺寸(透明体验)
                    MuseToast.show(
                        context.getString(R.string.chat_ref_image_compressed, result.describe()),
                        2500,
                    )
                }.onFailure { e ->
                    // v1.79 (H-I4+M-I2): 加 onFailure 提示
                    MuseToast.show(context.getString(R.string.chat_ref_image_load_failed, e.message ?: ""))
                }
            }
        }
    }

    val model = remember(params.model) { ImageModelCatalog.resolveById(params.model) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(MuseShapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(MusePaddings.cardInnerAux),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.chat_draw_params),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            model?.let {
                Text(
                    text = it.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // 尺寸
        val sizes = model?.supportedSizes
        if (!sizes.isNullOrEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                sizes.map { it to it }.forEach { (value, label) ->
                    MuseChip(
                        selected = params.size == value,
                        onClick = { onParamsChange(params.copy(size = value)) },
                        label = label,
                    )
                }
            }
        }

        // 质量
        val qualities = model?.supportedQualities
        if (!qualities.isNullOrEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                qualities.forEach { value ->
                    val label = when (value) {
                        "standard" -> stringResource(R.string.chat_quality_standard)
                        "hd" -> stringResource(R.string.chat_quality_hd)
                        "high" -> stringResource(R.string.chat_quality_high)
                        "medium" -> stringResource(R.string.chat_quality_medium)
                        "low" -> stringResource(R.string.chat_quality_low)
                        "auto" -> stringResource(R.string.chat_quality_auto)
                        else -> value
                    }
                    MuseChip(
                        selected = params.quality == value,
                        onClick = { onParamsChange(params.copy(quality = value)) },
                        label = label,
                    )
                }
            }
        }

        // 风格
        val styles = model?.supportedStyles
        if (!styles.isNullOrEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                styles.forEach { value ->
                    val label = when (value) {
                        "vivid" -> stringResource(R.string.chat_style_vivid)
                        "natural" -> stringResource(R.string.chat_style_natural)
                        else -> value
                    }
                    MuseChip(
                        selected = params.style == value,
                        onClick = { onParamsChange(params.copy(style = value)) },
                        label = label,
                    )
                }
            }
        }

        // 参考图
        val supportsRef = model?.supportsReferenceImage == true
        if (params.referenceImageUri.isNullOrBlank()) {
            MuseChip(
                selected = false,
                onClick = {
                    if (supportsRef) imagePicker.launch("image/*")
                },
                enabled = supportsRef,
                label = if (supportsRef) stringResource(R.string.chat_ref_image_add) else stringResource(R.string.chat_ref_image_not_supported),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Photo,
                        // v1.79 (L-I3): 无障碍 contentDescription
                        contentDescription = stringResource(R.string.chat_ref_image_cd),
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                },
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 120.dp)
                    .clip(MuseShapes.small),
            ) {
                SmartImage(
                    model = params.referenceImageUri,
                    contentDescription = stringResource(R.string.chat_ref_image_cd),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth(),
                )
                IconButton(
                    onClick = { onParamsChange(params.copy(referenceImageUri = null)) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(MuseIconSizes.touchTarget)
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            shape = CircleShape,
                        ),
                ) {
                    Icon(
                        imageVector = TablerIcons.X,
                        contentDescription = stringResource(R.string.chat_ref_image_clear_cd),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * v1.140: 参考图压缩结果。
 *
 * @property dataUri 形如 `data:image/jpeg;base64,...` 的 Data URI,可直接交给 ImageProvider
 * @property width 压缩后宽度
 * @property height 压缩后高度
 * @property byteCount 压缩后 JPEG 字节数(未 base64)
 */
private data class CompressedReferenceImage(
    val dataUri: String,
    val width: Int,
    val height: Int,
    val byteCount: Int,
) {
    /** 人类可读的尺寸/体积描述,用于 Toast 提示。 */
    fun describe(): String {
        val kb = byteCount / 1024
        return "${width}x${height}, ${kb}KB"
    }
}

/**
 * v1.140: 把用户选中的参考图 URI 压缩为符合体积/尺寸约束的 Data URI。
 *
 * 处理流程:
 *  1. 先解码边界获取原始尺寸(API 28+ 用 ImageDecoder,低版本用 BitmapFactory)
 *  2. 计算 inSampleSize,使长边缩到 [maxSide] 附近(2 的幂次降采样)
 *  3. 解码得到 Bitmap 后,如长边仍 > [maxSide],用 Matrix 精确缩放
 *  4. JPEG 压缩质量 [quality],base64 后如仍超过 [maxBase64Bytes],则
 *     逐级降质量 / 缩尺寸,直到满足体积约束或降到下限
 *
 * 抛出 [IllegalStateException] 表示压缩失败(原图无法解码或压缩后仍过大)。
 */
private fun compressReferenceImageToDataUri(
    uri: Uri,
    context: android.content.Context,
    maxSide: Int = 1024,
    quality: Int = 85,
    maxBase64Bytes: Int = 4 * 1024 * 1024,
): CompressedReferenceImage {
    val resolver = context.contentResolver

    // 1. 解码原始尺寸
    val (origW, origH) = decodeImageBounds(resolver, uri)
    if (origW <= 0 || origH <= 0) {
        error("decode bounds failed for $uri")
    }

    // 2. 计算 inSampleSize(2 的幂次,使降采样后长边尽量接近 maxSide 但不超过 2 倍)
    var sample = 1
    while (origW / sample / 2 >= maxSide || origH / sample / 2 >= maxSide) sample *= 2

    // 3. 解码为 Bitmap(降采样后)
    var bitmap = decodeSampledBitmap(resolver, uri, sample)
        ?: error("decode bitmap failed for $uri")

    // 4. 精确缩放到 maxSide 内(保持宽高比)
    val scaled = scaleBitmapToMaxSide(bitmap, maxSide)
    if (scaled !== bitmap) {
        bitmap.recycle()
        bitmap = scaled
    }

    // 5. 逐级压缩,直到 base64 体积满足约束或降到下限
    var currentQuality = quality
    var currentBmp = bitmap
    var bytes = compressJpeg(currentBmp, currentQuality)
    var base64Len = base64Length(bytes.size)

    // 5.1 先尝试只降质量(75 → 65 → 55)
    val qualitySteps = listOf(75, 65, 55)
    var stepIndex = 0
    while (base64Len > maxBase64Bytes && stepIndex < qualitySteps.size) {
        currentQuality = qualitySteps[stepIndex++]
        bytes = compressJpeg(currentBmp, currentQuality)
        base64Len = base64Length(bytes.size)
    }

    // 5.2 仍超限则缩小尺寸(768 → 512 → 384)
    val sideSteps = listOf(768, 512, 384)
    var sideIndex = 0
    while (base64Len > maxBase64Bytes && sideIndex < sideSteps.size) {
        val newSide = sideSteps[sideIndex++]
        val shrunk = scaleBitmapToMaxSide(currentBmp, newSide)
        if (shrunk !== currentBmp) {
            currentBmp.recycle()
            currentBmp = shrunk
        }
        bytes = compressJpeg(currentBmp, currentQuality)
        base64Len = base64Length(bytes.size)
    }

    val width = currentBmp.width
    val height = currentBmp.height
    currentBmp.recycle()

    if (base64Len > maxBase64Bytes) {
        // 仍超限:拒绝上传,避免 OOM/超时
        error("image still too large after compression (${width}x${height}, ${bytes.size / 1024}KB)")
    }

    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    val dataUri = "data:image/jpeg;base64,$base64"
    return CompressedReferenceImage(
        dataUri = dataUri,
        width = width,
        height = height,
        byteCount = bytes.size,
    )
}

/** 解码原图边界(宽高),不将像素加载到内存。 */
private fun decodeImageBounds(
    resolver: android.content.ContentResolver,
    uri: Uri,
): Pair<Int, Int> {
    // 用 BitmapFactory.inJustDecodeBounds 探尺寸,零像素分配;
    // ImageDecoder 在 API 28+ 也支持,但探边界时二者效果一致,这里统一走 BitmapFactory。
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, opts)
    }
    return opts.outWidth to opts.outHeight
}

/** 按 inSampleSize 解码 Bitmap。 */
private fun decodeSampledBitmap(
    resolver: android.content.ContentResolver,
    uri: Uri,
    sampleSize: Int,
): Bitmap? {
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return resolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, opts)
    }
}

/** 把 Bitmap 等比缩放到长边 <= maxSide;若已满足则原样返回。 */
private fun scaleBitmapToMaxSide(src: Bitmap, maxSide: Int): Bitmap {
    val w = src.width
    val h = src.height
    val longSide = maxOf(w, h)
    if (longSide <= maxSide) return src
    val scale = maxSide.toFloat() / longSide
    val newW = (w * scale).toInt().coerceAtLeast(1)
    val newH = (h * scale).toInt().coerceAtLeast(1)
    val matrix = Matrix().apply { setScale(scale, scale) }
    return Bitmap.createBitmap(src, 0, 0, w, h, matrix, true)
}

/** JPEG 压缩为字节数组。 */
private fun compressJpeg(bmp: Bitmap, quality: Int): ByteArray {
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return out.toByteArray()
}

/** base64 编码后体积约为原字节 * 4/3,向上取整。 */
private fun base64Length(byteCount: Int): Int {
    // 每 3 字节 → 4 字符;不足 3 按 3 算。NO_WRAP 不加换行符。
    return ((byteCount + 2) / 3) * 4
}

/**
 * v2.2: iOS/Manus 风格工具菜单中的媒体快捷卡片 — 加厚遮罩,更醒目。
 */
@Composable
private fun ToolMediaCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
        modifier = Modifier
            .size(72.dp)
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * iOS/Manus 风格工具菜单中的列表行。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolListRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    isActive: Boolean = false,
    showArrow: Boolean = true,
    onClick: () -> Unit,
    // v1.0.53: 可选长按回调(深度思考用:长按切换级别)
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                // v1.0.53: 有长按回调时用 combinedClickable,否则用普通 clickable 保持原行为
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            )
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        // v1.0.29: 激活态显示黑白小勾(参考 iOS 设置页选中样式),
        // 非激活态按 showArrow 决定是否显示右箭头。
        if (isActive) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        } else if (showArrow) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

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
 * v0.53: 查询系统相册最近图片。
 *
 * 通过 MediaStore 读取 EXTERNAL_CONTENT_URI,按添加时间倒序返回最近 [maxCount] 张图片 URI。
 * 调用方需已持有 READ_MEDIA_IMAGES(Android 13+) 或 READ_EXTERNAL_STORAGE 权限。
 */
private fun queryRecentGalleryImages(context: android.content.Context, maxCount: Int): List<Uri> {
    return runCatching {
        val uris = mutableListOf<Uri>()
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            var count = 0
            while (cursor.moveToNext() && count < maxCount) {
                val id = cursor.getLong(idColumn)
                uris.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id))
                count++
            }
        }
        uris
    }.onFailure { e ->
        // v1.79 (M-3): 不再静默吞异常,记录日志便于排查
        Logger.w("InputBar", "queryRecentGalleryImages 查询失败", e)
    }.getOrDefault(emptyList())
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
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 36.dp, max = 160.dp)
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
            modifier = Modifier.size(36.dp),
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
