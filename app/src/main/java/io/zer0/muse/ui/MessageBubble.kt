package io.zer0.muse.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.GroupWork
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.MuseToast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import io.zer0.muse.R
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.RagCitation
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.ui.artifact.ArtifactCardList
import io.zer0.muse.ui.chat.parseQuotedContent
import io.zer0.muse.ui.common.AttachmentChip
import io.zer0.muse.ui.common.ContextMenuItem
import io.zer0.muse.ui.common.DesktopContextMenu
import io.zer0.muse.ui.common.IosTactileButton
import io.zer0.muse.ui.common.rememberDesktopShortcutsEnabled
import io.zer0.muse.ui.markdown.MarkdownText
import io.zer0.muse.ui.taskcard.AgentPlan
import io.zer0.muse.ui.taskcard.PlanCard
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MuseShadow
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.theme.tiny
import io.zer0.muse.ui.theme.assistantBubble  // Phase 1 1A: AI 气泡形状令牌
import io.zer0.muse.ui.theme.userBubble  // v1.48 (h18): 气泡形状令牌
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MusePaddings
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.ui.common.FullScreenMediaViewer
import io.zer0.muse.ui.common.IosSlider
import io.zer0.muse.ui.chat.VideoAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 消息单元 — iOS 风格。
 *
 * - USER: iMessage 灰泡,右对齐,非对称圆角(右下 6dp,其他 20dp),最大宽 280dp
 * - ASSISTANT: 无气泡,全宽文本,左对齐,直接铺在底色上
 * - 推理过程: 正文上方折叠卡片(Phase 8.3,默认折叠,点击展开/收起)
 * - 图片渲染: Phase 5-G,若 imageUrls 非空,用 Coil AsyncImage 展示
 * - 流式光标(阶段 4):末尾 AI 流式消息文本后追加闪烁竖线光标
 * - 长按菜单(阶段 4):整条消息长按弹出操作菜单(编辑/重新生成/翻译/朗读/收藏)
 * - 末尾 AI 快捷按钮(阶段 4):流式结束后显示"重新生成"图标按钮(iOS 风格)
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun MessageBubble(
    msg: UIMessage,
    isStreaming: Boolean,
    isLastAssistant: Boolean,
    isTranslating: Boolean,
    onEdit: () -> Unit,
    onQuote: () -> Unit,
    onRegenerate: () -> Unit,
    onTranslate: (String) -> Unit,
    onToggleFavorite: () -> Unit = {},
    // 阶段 J: 复制消息内容到剪贴板
    onCopyMessage: (String) -> Unit = {},
    // Phase 8.7: TTS 朗读(仅 AI 消息)
    isSpeaking: Boolean = false,
    onToggleTts: () -> Unit = {},
    // Phase 8.8: 任务卡(仅 AI 消息,有工具调用时显示)
    taskCard: io.zer0.muse.ui.taskcard.TaskCardData? = null,
    // Phase 10.1: 任务卡交互回调
    onToggleTaskCardExpand: () -> Unit = {},
    onRetryTaskCardStep: (String) -> Unit = {},
    // v1.25: 长按菜单触发「委托给助手」
    onDelegate: () -> Unit = {},
    // v0.29 P0-3: 分享整段对话(导出为 Markdown 通过系统 share sheet)
    onShareSession: () -> Unit = {},
    // v1.58: 从此消息分叉对话(复制历史到新会话)
    onFork: () -> Unit = {},
    // v1.48: 长按菜单删除消息(误发消息可从菜单删除)
    onDeleteMessage: () -> Unit = {},
    // v0.29 P0-4: AI 消息底部显示模型名 + token 估算(为 null 时不显示)
    modelName: String? = null,
    // v0.31: 聊天行为偏好(控制 MOOD/思考过程/token/模型名/时间戳显示)
    chatPrefs: io.zer0.muse.data.ChatPreferences = io.zer0.muse.data.ChatPreferences(),
    // v0.48: 消息分组 — 连续同角色且时间间隔 < 5 分钟的消息压缩头像 + 时间戳
    showAvatar: Boolean = true,
    showTimestamp: Boolean = true,
    // v0.48: AI 头像来源(从 currentAssistant 取,null 时回退到 muse logo 文字)
    assistant: io.zer0.muse.data.assistant.AssistantEntity? = null,
    // v1.43: 消息关联的产物卡片列表
    artifacts: List<ArtifactEntity> = emptyList(),
    // v1.43: 产物卡片点击回调
    onArtifactClick: (io.zer0.muse.data.artifact.ArtifactEntity) -> Unit = {},
    // 功能1: 会话内搜索高亮文本
    highlightText: String? = null,
    // v2.3: debug 模式性能摘要(仅最后一条 assistant 消息底部显示)
    debugInfo: String? = null,
    // v1.55: Agent 工作流计划卡(显示最新的活跃计划,随消息一起滚动)
    agentPlan: AgentPlan? = null,
    // v1.201: 委派链路根节点(仅 AI 消息,有委派时显示)
    delegationChain: List<io.zer0.muse.tools.DelegationChainTracker.ChainNode>? = null,
    // v1.45: mood/reasoning 折叠状态由外部控制,切页后不丢失
    isMoodExpanded: Boolean? = null,
    isReasoningExpanded: Boolean? = null,
    isReflectionExpanded: Boolean? = null,
    onToggleMoodExpanded: () -> Unit = {},
    onToggleReasoningExpanded: () -> Unit = {},
    onToggleReflectionExpanded: () -> Unit = {},
    // HTML/SVG 代码块全屏预览回调(参数为完整 HTML 源码,SVG 已包装)
    onHtmlPreview: (String) -> Unit = {},
    // 视频附件(仅 USER 消息):视频缩略图 + 时长 + 播放图标,点击用 ACTION_VIEW 调起系统播放器
    videoAttachment: VideoAttachment? = null,
    // v1.138: 视觉辅助 UI — 分析中进度(null=未在分析)
    visionAssistProgress: io.zer0.muse.vision.VisionProgress? = null,
    // v1.138: 视觉辅助 UI — 是否已对该消息做过视觉辅助(显示"辅助视觉"标签)
    visionAssisted: Boolean = false,
) {
    val isUser = msg.role == MessageRole.USER
    // 阶段 4: 长按菜单状态(主菜单 + 翻译语言子菜单)
    // v1.79 (M-B9): 菜单/子菜单/删除确认状态改用 rememberSaveable,旋转/后台后不丢失
    var showActionMenu by rememberSaveable { mutableStateOf(false) }
    var showLanguageSubmenu by rememberSaveable { mutableStateOf(false) }
    // P2-13: 桌面端右键上下文菜单(仅 Expanded 窗口 + 物理键盘场景显示)
    var showDesktopContextMenu by rememberSaveable { mutableStateOf(false) }
    // v1.60-B: 全屏媒体查看器状态 — 图片列表 + 初始索引
    // mediaPreview 为 Pair 类型,自定义 Saver 过于复杂,保持 remember
    var mediaPreview by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    // v1.48: 删除消息确认对话框
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    // 末尾 AI 流式时光标显示
    val showStreamingCursor = !isUser && isLastAssistant && isStreaming
    // v1.42: 流式中的最后一条 AI 消息禁用动画,避免每帧测量导致卡顿。
    val isAnimating = !(isLastAssistant && isStreaming)
    // 触觉反馈句柄(长按菜单 / 复制 / 收藏 / 重新生成 / TTS 切换时触发)
    val hapticFeedback = LocalHapticFeedback.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 引用回复:解析 content 开头的 `> ` 块,quotedContent 优先(用于编辑/草稿态)
    // v1.42: USER 消息才需要解析引用,Assistant 消息跳过 parseQuotedContent 减少计算。
    val quoteAndBody = remember(msg.content, isUser) {
        if (isUser) parseQuotedContent(msg.content) else null to msg.content
    }
    val parsedQuote = quoteAndBody.first
    val parsedBody = quoteAndBody.second
    // v1.79 (M-B8): 调用方保证 content 不含引用前缀,此处不再做去重处理
    val quote = msg.quotedContent ?: parsedQuote
    val body = if (msg.quotedContent != null) msg.content else parsedBody
    // P2-13: 桌面快捷键总开关(Expanded 窗口 + 物理键盘)— 控制右键菜单是否启用
    val desktopShortcutsEnabled = rememberDesktopShortcutsEnabled()
    // M-UI1: 长按菜单/桌面菜单手势统一收口,后续分别附加到用户/助手气泡上,
    // 避免整行 Column 都被点击高亮覆盖。
    val bubbleClickModifier = Modifier.combinedClickable(
        // v1.48: 改为仅长按弹菜单 — 单击弹菜单过于激进,且与 MarkdownText 链接点击冲突
        // (点正文文字时 LinkableText 消费 tap 事件导致不弹菜单,行为不可预测)
        onClick = {},
        onLongClick = {
            MuseHaptics.medium(hapticFeedback)
            if (desktopShortcutsEnabled) {
                showDesktopContextMenu = true
            } else {
                showActionMenu = true
            }
        },
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isAnimating) Modifier.animateContentSize() else Modifier),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        // v0.48: 消息分组头 — AI 消息显示头像 + 助手名 + 时间戳(连续同角色时压缩)
        // USER 消息不显示头像(右对齐气泡已足够),仅按 showTimestamp 在气泡下方显示时间戳
        if (!isUser && showAvatar) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = MusePaddings.tightGap),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (assistant != null) {
                    io.zer0.muse.ui.common.AssistantAvatar(
                        assistant = assistant,
                        avatarSize = 24.dp,
                    )
                } else {
                    // 无助手配置时用首字母占位圆
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "M",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = assistant?.name?.takeIf { it.isNotBlank() } ?: "muse",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (showTimestamp && chatPrefs.showTimestamp) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = formatMessageTime(msg.createdAt, use24Hour = chatPrefs.use24Hour),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        // v0.30-b: MOOD 块(6 步工作流第 2 步 — AI 内部腹稿,可折叠)
        // v0.31: 受 chatPrefs.showMoodBlock 开关控制,默认展开状态由 chatPrefs.moodExpandedByDefault 决定
        if (chatPrefs.showMoodBlock) {
            msg.mood?.takeIf { it.isNotBlank() }?.let { mood ->
                // v1.45: 优先使用外部受控状态;未控制时用默认值
                val moodExpanded = isMoodExpanded ?: chatPrefs.moodExpandedByDefault
                // v1.52: 仅"正在流式的那最后一条 AI 消息"强制展开,避免流式期间所有 AI 消息的 mood 块被锁死无法折叠
                val showMoodExpanded = (isLastAssistant && isStreaming) || moodExpanded
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                shape = MuseShapes.medium,
                tonalElevation = MuseElevation.none,
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .padding(bottom = 6.dp),
            ) {
                Column(modifier = Modifier.padding(MusePaddings.bubbleInner)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleMoodExpanded() }
                            .padding(vertical = MusePaddings.tinyGap),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "MOOD",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Icon(
                            imageVector = if (showMoodExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            // L-MB1: contentDescription 更明确
                            contentDescription = if (showMoodExpanded) stringResource(R.string.chat_mood_collapse_cd) else stringResource(R.string.chat_mood_expand_cd),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(MuseIconSizes.iconTiny),
                        )
                    }
                    if (showMoodExpanded) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = mood,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        }

        // Phase 8.3: 推理过程折叠卡片(对标 ChatGPT reasoning 折叠区)
        // v0.31: 受 chatPrefs.showReasoning 开关控制,默认展开状态由 chatPrefs.reasoningExpandedByDefault 决定
        // v1.45: 改为外部受控,切页/后台后保持折叠状态
        // v1.118: 折叠时标题显示思考内容摘要(而非静态"思考过程"四字),让用户快速了解思考了什么
        if (chatPrefs.showReasoning) {
            msg.reasoning?.takeIf { it.isNotBlank() }?.let { reasoning ->
                val reasoningExpanded = isReasoningExpanded ?: chatPrefs.reasoningExpandedByDefault
                // v1.52: 仅"正在流式的那最后一条 AI 消息"强制展开,避免流式期间所有 AI 消息的 reasoning 块被锁死无法折叠
                val showExpanded = (isLastAssistant && isStreaming) || reasoningExpanded
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = MuseShapes.medium,
                    tonalElevation = MuseElevation.low,
                    modifier = Modifier
                        .widthIn(max = 360.dp)
                        .padding(bottom = 6.dp),
                ) {
                    Column(modifier = Modifier.padding(MusePaddings.bubbleInner)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleReasoningExpanded() }
                                .padding(vertical = MusePaddings.tinyGap),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // v1.118: 折叠时标题显示思考内容摘要,展开时显示"思考过程"标题
                            // 摘要取 reasoning 前约 40 字符(合并换行),前缀"思考 · "标识来源
                            val titleText = if (showExpanded) {
                                stringResource(R.string.chat_reasoning_title)
                            } else {
                                val cleaned = reasoning.replace("\n", " ").trim()
                                if (cleaned.length > 40) {
                                    "思考 · ${cleaned.take(40)}…"
                                } else {
                                    "思考 · $cleaned"
                                }
                            }
                            Text(
                                text = titleText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = if (showExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                // L-MB1: contentDescription 更明确
                                contentDescription = if (showExpanded) stringResource(R.string.chat_reasoning_collapse_cd) else stringResource(R.string.chat_reasoning_expand_cd),
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(MuseIconSizes.iconTiny),
                            )
                        }
                        if (showExpanded) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = reasoning,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
        }

        // v1.64: 反思块(reflection — AI 对自身回答的准确性/完整性/语气自评,可折叠)
        // 与 mood/reasoning 块同构:受 chatPrefs.showReflectionBlock 开关 + 默认展开状态控制
        if (chatPrefs.showReflectionBlock) {
            msg.reflection?.takeIf { it.isNotBlank() }?.let { reflection ->
                val reflectionExpanded = isReflectionExpanded ?: chatPrefs.reflectionExpandedByDefault
                val showReflectionExpanded = (isLastAssistant && isStreaming) || reflectionExpanded
                Surface(
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f),
                    shape = MuseShapes.medium,
                    tonalElevation = MuseElevation.none,
                    modifier = Modifier
                        .widthIn(max = 360.dp)
                        .padding(bottom = 6.dp),
                ) {
                    Column(modifier = Modifier.padding(MusePaddings.bubbleInner)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleReflectionExpanded() }
                            .padding(vertical = MusePaddings.tinyGap),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.chat_reflection_title),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            Icon(
                                imageVector = if (showReflectionExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                // L-MB1: contentDescription 更明确
                                contentDescription = if (showReflectionExpanded) stringResource(R.string.chat_reflection_collapse_cd) else stringResource(R.string.chat_reflection_expand_cd),
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(MuseIconSizes.iconTiny),
                            )
                        }
                        if (showReflectionExpanded) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = reflection,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (isUser) {
            // 用户消息: iMessage 风格灰泡,右下角小圆角模拟尾巴
            // Phase 8.6: 若有图片,放在气泡内文字上方
            val hasImages = msg.imageBase64List.isNotEmpty()
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                // v1.48 (h18): 用 BubbleShape 令牌统一气泡圆角(原裸值 20/20/20/6)
                shape = MuseShapes.userBubble,
                // M-UI1: 手势收口到气泡本身,避免整行高亮
                // MANUS 风格:加 low 级别微阴影增强浮起质感
                modifier = bubbleClickModifier.shadow(MuseShadow.low.elevation, MuseShapes.userBubble),
            ) {
                Column(
                    modifier = Modifier
                        // Phase 1 1A: 用户气泡最大宽度从固定 280dp 改为屏幕宽度 78%
                        .fillMaxWidth(0.78f)
                        .padding(MusePaddings.bubbleInner),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 引用回复:用户消息顶部显示引用块
                    quote?.let {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = MuseShapes.small,
                        ) {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(MusePaddings.contentGap),
                            )
                        }
                    }
                    // 视频附件:缩略图 + 时长 + 播放图标,点击用 ACTION_VIEW 调起系统播放器
                    // 缩略图缺失时降级为深色占位 + 视频图标,保持高度一致避免布局抖动
                    videoAttachment?.let { va ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .clip(MuseShapes.medium)
                                .clickable {
                                    // 调用系统播放器播放视频;无应用可处理时 Toast 提示
                                    runCatching {
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(va.uri, "video/*")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                    }.onFailure { e ->
                                        MuseToast.show(
                                            context.getString(R.string.chat_video_open_failed, e.message ?: ""),
                                        )
                                    }
                                },
                        ) {
                            val thumb = va.thumbnail
                            if (!thumb.isNullOrBlank()) {
                                SmartImage(
                                    model = "data:image/jpeg;base64,$thumb",
                                    contentDescription = stringResource(R.string.chat_user_video_cd),
                                    modifier = Modifier.fillMaxWidth(),
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(160.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.VideoLibrary,
                                        contentDescription = stringResource(R.string.chat_user_video_cd),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(MuseIconSizes.iconEmpty),
                                    )
                                }
                            }
                            // 中央播放图标(scrim 半透明背景提升对比度)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(MuseIconSizes.iconLarge),
                                )
                            }
                            // 右下角时长标签(黑底白字,与系统相册风格一致)
                            Surface(
                                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f),
                                shape = MuseShapes.tiny,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(MusePaddings.labelVerticalGap),
                            ) {
                                Text(
                                    text = formatVideoDuration(va.durationMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(MusePaddings.chipInner),
                                )
                            }
                        }
                    }
                    if (hasImages) {
                        // v1.60-B: 用户图片也可点击放大进入全屏媒体查看器
                        val userImageUris = msg.imageBase64List.map { "data:image/jpeg;base64,$it" }
                        userImageUris.forEachIndexed { index, uri ->
                            SmartImage(
                                model = uri,
                                contentDescription = stringResource(R.string.chat_user_image_cd),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 220.dp)
                                    .clip(MuseShapes.medium)
                                    .clickable { mediaPreview = userImageUris to index },
                            )
                        }
                        // v1.138: 视觉辅助标签 — 在图片下方显示"辅助视觉"状态
                        // 分析中:显示进度"辅助视觉 · 分析中 x/y"
                        // 已完成:显示"辅助视觉 · 已分析"(成功)或"辅助视觉 · 失败"
                        val showVisionLabel = visionAssistProgress?.isActive == true || visionAssisted
                        if (showVisionLabel) {
                            val (labelText, labelColor, labelIcon) = when {
                                visionAssistProgress?.isActive == true -> Triple(
                                    stringResource(R.string.vision_assist_analyzing, visionAssistProgress.index, visionAssistProgress.total),
                                    MaterialTheme.colorScheme.tertiary,
                                    Icons.Outlined.Visibility,
                                )
                                visionAssisted -> Triple(
                                    stringResource(R.string.vision_assist_done),
                                    MaterialTheme.colorScheme.primary,
                                    Icons.Default.Check,
                                )
                                else -> Triple(
                                    stringResource(R.string.vision_assist_label),
                                    MaterialTheme.colorScheme.outline,
                                    Icons.Outlined.Visibility,
                                )
                            }
                            Surface(
                                color = labelColor.copy(alpha = 0.12f),
                                shape = MuseShapes.tiny,
                                modifier = Modifier.padding(top = 2.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(MusePaddings.chipInnerLoose),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    if (visionAssistProgress?.isActive == true) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(12.dp),
                                            strokeWidth = 1.5.dp,
                                            color = labelColor,
                                        )
                                    } else {
                                        Icon(
                                            imageVector = labelIcon,
                                            contentDescription = null,
                                            tint = labelColor,
                                            modifier = Modifier.size(12.dp),
                                        )
                                    }
                                    Text(
                                        text = labelText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = labelColor,
                                    )
                                }
                            }
                        }
                    }
                    val userText = body.ifEmpty { if (hasImages || videoAttachment != null) "" else " " }
                    if (userText.isNotBlank()) {
                        Text(
                            text = userText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // v0.48: USER 消息气泡下方右对齐显示时间戳(受 showTimestamp && chatPrefs.showTimestamp 控制)
            if (showTimestamp && chatPrefs.showTimestamp) {
                Text(
                    text = formatMessageTime(msg.createdAt, use24Hour = chatPrefs.use24Hour),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 2.dp, end = 4.dp),
                )
            }
        } else {
            // Phase 1 1A: AI 消息添加 surfaceVariant 气泡背景 + assistantBubble 形状
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MuseShapes.assistantBubble,
                // M-UI1: 手势收口到气泡本身,避免整行高亮
                modifier = Modifier.fillMaxWidth(0.85f).then(bubbleClickModifier),
            ) {
                Column(
                    modifier = Modifier.padding(MusePaddings.cardInner),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
            // Phase 5-G / Phase 8.6: 渲染生成的图片(URL 或 base64 data URI)
            // 统一显示源:优先用 imageUrls,避免 Gemini 同时有 imageUrls(data URI) 和 imageBase64List 时重复渲染
            // v1.95: 同时扫描 content 中的表情包绝对路径(filesDir/stickers/...),由 send_sticker 工具产生
            val displayImageUris = remember(msg.imageUrls, msg.imageBase64List, msg.content, isLastAssistant, isStreaming) {
                val fromUrls = if (msg.imageUrls.isNotEmpty()) msg.imageUrls
                else msg.imageBase64List.map { "data:image/png;base64,$it" }
                // v1.100: 流式期间跳过表情包路径扫描(正则开销随 content 增长),
                // 流式结束后(非 isStreaming)才扫描,与 MarkdownText 降级策略对齐
                if (isLastAssistant && isStreaming) {
                    fromUrls
                } else {
                    val stickerUris = extractStickerPaths(msg.content).map { "file://$it" }
                    if (stickerUris.isEmpty()) fromUrls else fromUrls + stickerUris
                }
            }
            displayImageUris.forEachIndexed { index, imageUri ->
                GeneratedImageCard(
                    imageUri = imageUri,
                    onPreview = { mediaPreview = displayImageUris to index },
                    onSave = {
                        scope.launch {
                            runCatching {
                                saveImageToGallery(context, imageUri)
                            }.onSuccess { path ->
                                // M-MB2: 改用 MuseToast 替代原生 Toast,保持主题一致
                                MuseToast.show(context.getString(R.string.chat_image_saved_toast, path))
                            }.onFailure { e ->
                                MuseToast.show(context.getString(R.string.chat_image_save_failed_toast, e.message))
                            }
                        }
                    },
                )
            }
            // v1.112 (C1): 任务清单与工具调用胶囊拆分布局
            // 展开态:TaskCard 占满宽度垂直堆叠(步骤列表需要空间),ToolCallCard 在下方
            val toolInfo = msg.toolCallInfo
            if (taskCard != null && toolInfo != null) {
                if (taskCard.isExpanded) {
                    // 展开态:TaskCard 占满宽度,ToolCallCard 在下方
                    io.zer0.muse.ui.taskcard.TaskCard(
                        data = taskCard,
                        onToggleExpand = onToggleTaskCardExpand,
                        onRetryStep = onRetryTaskCardStep,
                        delegationChain = delegationChain,
                    )
                    ToolCallCard(
                        toolName = toolInfo.toolName,
                        arguments = toolInfo.arguments,
                        result = toolInfo.result,
                        isSuccess = toolInfo.isSuccess,
                        modifier = Modifier.widthIn(max = 360.dp),
                    )
                } else {
                    // 折叠态:TaskCard(左) + ToolCallCard(右) 横向排列
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        io.zer0.muse.ui.taskcard.TaskCard(
                            data = taskCard,
                            onToggleExpand = onToggleTaskCardExpand,
                            onRetryStep = onRetryTaskCardStep,
                            modifier = Modifier.weight(1f),
                            delegationChain = delegationChain,
                        )
                        ToolCallCard(
                            toolName = toolInfo.toolName,
                            arguments = toolInfo.arguments,
                            result = toolInfo.result,
                            isSuccess = toolInfo.isSuccess,
                            modifier = Modifier.widthIn(max = 360.dp),
                        )
                    }
                }
            } else if (taskCard != null) {
                // 只有 TaskCard,没有 ToolCallInfo
                io.zer0.muse.ui.taskcard.TaskCard(
                    data = taskCard,
                    onToggleExpand = onToggleTaskCardExpand,
                    onRetryStep = onRetryTaskCardStep,
                    delegationChain = delegationChain,
                )
            } else if (toolInfo != null) {
                // 只有 ToolCallInfo,没有 TaskCard
                ToolCallCard(
                    toolName = toolInfo.toolName,
                    arguments = toolInfo.arguments,
                    result = toolInfo.result,
                    isSuccess = toolInfo.isSuccess,
                    modifier = Modifier.widthIn(max = 360.dp),
                )
            } else {
            // v5: 已压缩标记 — SYSTEM 消息且内容以 [COMPRESSED] 开头时显示
            val isCompressed = msg.role == io.zer0.ai.core.MessageRole.SYSTEM && msg.content.startsWith("[COMPRESSED]")
            if (isCompressed) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(bottom = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Compress,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "已压缩",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            // 文本内容(Markdown 渲染);图片消息可能 content 也含 markdown 图片语法,双渲染避免空泡
            val content = if (isCompressed) {
                body.removePrefix("[COMPRESSED]").trim()
            } else body.ifEmpty {
                if (msg.imageUrls.isEmpty() && msg.imageBase64List.isEmpty()) " " else ""
            }
            if (content.isNotBlank()) {
                // 引用回复:AI 消息顶部显示引用块(兼容含引用标记的内容)
                quote?.let {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = MuseShapes.small,
                    ) {
                        Box {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(MusePaddings.contentGap),
                            )
                            val bgColor = MaterialTheme.colorScheme.surface
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            0.5f to Color.Transparent,
                                            1.0f to bgColor,
                                        ),
                                    ),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                // v1.24: 正文不再整体折叠,仅 Markdown 代码块/作文块内部可折叠
                // v1.42: 流式中禁用 LinkableText 的链接点击检测,避免 pointerInput 随内容变化反复重建。
                // v1.52: 用 SelectionContainer 包裹,支持长按选取部分文本复制(非流式时)。
                //        流式中不启用选择,避免与光标/内容更新冲突;
                //        SelectionContainer 会消费文本上的长按手势(用于选择),
                //        父 Column 的 combinedClickable 仅在非文本区域触发操作菜单。
                val markdownContent = @Composable {
                    // v1.79 (H-B3): 防御性处理 citationUrls,MarkdownText 内部应保证 [N] 不越界
                    val safeCitationUrls = msg.citationUrls ?: emptyList()
                    if (highlightText != null && content.contains(highlightText, ignoreCase = true)) {
                        androidx.compose.material3.Text(
                            text = buildHighlightedText(content, highlightText),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        MarkdownText(
                            text = content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.fillMaxWidth(),
                            citationUrls = safeCitationUrls,
                            isStreaming = isLastAssistant && isStreaming,
                            onHtmlPreview = onHtmlPreview,
                        )
                    }
                }
                if (isLastAssistant && isStreaming) {
                    markdownContent()
                } else {
                    SelectionContainer { markdownContent() }
                }
            }
            // 功能3: 链接预览卡片(仅非流式时,避免流式增量导致重抓)
            if (!isStreaming) {
                val linkPreviews = rememberLinkPreviews(content)
                if (linkPreviews.isNotEmpty()) {
                    linkPreviews.forEach { preview ->
                        LinkPreviewCard(preview = preview)
                    }
                }
            }
            // 阶段 4: 流式光标(末尾 AI 流式时,在文本下方左对齐显示闪烁竖线)
            if (showStreamingCursor) {
                StreamingCursor(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // 功能2: TTS 语音消息播放器(仅非流式 AI 消息,且当 isSpeaking 时显示)
            if (!isUser && !isStreaming && isSpeaking) {
                TtsAudioPlayer(
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            // v0.29 P0-4: AI 消息底部显示模型名 + token 估算(流式结束后显示)
            // v0.31: 受 chatPrefs.showModelName / showTokenEstimate 开关控制
            val showModel = chatPrefs.showModelName && modelName != null
            val showTokens = chatPrefs.showTokenEstimate && msg.content.isNotBlank()
            // v1.79 (M-B3): estimatedTokens 用 remember 缓存,避免每次重组重复计算
            // M-MB1: 流式期间不计算 estimatedTokens(展示时已有 !isStreaming 守卫),
            //        避免流式每个 token 都触发 TokenEstimator.estimate
            val estimatedTokens = remember(msg.content, showTokens, isStreaming) {
                if (showTokens && !isStreaming) io.zer0.muse.util.TokenEstimator.estimate(msg.content) else 0
            }
            // v1.79 (L-B8): parts 用 remember 缓存,避免每次重组重建列表
            val parts = remember(showModel, showTokens, modelName, estimatedTokens) {
                buildList {
                    if (showModel) add(modelName)
                    if (showTokens) add("$estimatedTokens tokens")
                }
            }
            // v1.79 (L-B3): 此处已在 else 分支,!isUser 恒为 true,删除冗余判断
            if (!isStreaming && (showModel || showTokens || debugInfo != null)) {
                if (parts.isNotEmpty()) {
                    Text(
                        text = parts.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                // v2.3: debug 模式性能摘要
                if (debugInfo != null) {
                    Text(
                        text = debugInfo,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            // v1.43: 产物卡片列表(代码/文档/HTML/SVG/图片等)
            if (artifacts.isNotEmpty()) {
                ArtifactCardList(
                    artifacts = artifacts,
                    onArtifactClick = onArtifactClick,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            // v1.133: RAG 引用 chip 列表(点击展开 snippet)
            if (!isUser && !isStreaming && msg.ragCitations.isNotEmpty()) {
                RagCitationChips(
                    citations = msg.ragCitations,
                    modifier = Modifier.padding(top = MusePaddings.contentGap),
                )
            }
            }   // closes inner else (no taskCard/toolInfo)
            // v1.55: Agent 工作流计划卡随消息一起滚动,而不是固定在消息列表底部
            if (agentPlan != null) {
                PlanCard(plan = agentPlan)
            }
                }   // closes AI bubble Surface Column
            }       // closes AI bubble Surface
        }

        // v1.138: 助手消息底部快捷按钮 — 复制/翻译/分享(所有助手消息),重新生成(仅最后一条)
        // 翻译按钮复用长按菜单的语言子菜单(showActionMenu + showLanguageSubmenu)
        // 分享按钮用系统 share sheet 分享单条消息内容
        if (!isUser && msg.content.isNotEmpty() && !isStreaming && !isTranslating) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 复制
                IosTactileButton(
                    icon = Icons.Default.ContentCopy,
                    onClick = {
                        onCopyMessage(msg.content)
                        MuseHaptics.light(hapticFeedback)
                    },
                    contentDescription = stringResource(R.string.action_copy),
                    tint = MaterialTheme.colorScheme.outline,
                    size = MuseIconSizes.touchTarget,
                    iconSize = MuseIconSizes.iconSmall,
                )
                // 翻译(弹语言子菜单)
                IosTactileButton(
                    icon = Icons.Outlined.Language,
                    onClick = {
                        MuseHaptics.light(hapticFeedback)
                        showActionMenu = true
                        showLanguageSubmenu = true
                    },
                    contentDescription = stringResource(R.string.action_translate),
                    tint = MaterialTheme.colorScheme.outline,
                    size = MuseIconSizes.touchTarget,
                    iconSize = MuseIconSizes.iconSmall,
                )
                // 分享(系统 share sheet 分享单条消息)
                IosTactileButton(
                    icon = Icons.Outlined.Share,
                    onClick = {
                        MuseHaptics.light(hapticFeedback)
                        scope.launch {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, msg.content)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, null))
                        }
                    },
                    contentDescription = stringResource(R.string.action_share),
                    tint = MaterialTheme.colorScheme.outline,
                    size = MuseIconSizes.touchTarget,
                    iconSize = MuseIconSizes.iconSmall,
                )
                // 重新生成(仅最后一条助手消息)
                if (isLastAssistant) {
                    IosTactileButton(
                        icon = Icons.Default.Refresh,
                        onClick = {
                            MuseHaptics.light(hapticFeedback)
                            onRegenerate()
                        },
                        contentDescription = stringResource(R.string.chat_regenerate_cd),
                        tint = MaterialTheme.colorScheme.outline,
                        size = MuseIconSizes.touchTarget,
                        iconSize = MuseIconSizes.iconSmall,
                    )
                }
            }
        }

        // 阶段 4: 长按菜单(iOS 风格 ActionSheet,MuseDialog 替代原 ModalBottomSheet,避免真机 scrim 卡死)
        // 长按消息 → 弹出 编辑/重新生成/翻译/朗读/收藏/复制/分享 操作列表
        if (showActionMenu) {
            MuseDialog(
                onDismissRequest = {
                    showActionMenu = false
                    showLanguageSubmenu = false
                },
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (showLanguageSubmenu) {
                            // 翻译语言子菜单(从主菜单"翻译"项触发)
                            ActionMenuItem(
                                icon = Icons.Default.ArrowDownward,
                                text = stringResource(R.string.action_back),
                                contentDescription = stringResource(R.string.action_back),
                                onClick = { showLanguageSubmenu = false },
                            )
                            TranslationLanguages.forEach { lang ->
                                ActionMenuItem(
                                    icon = Icons.Outlined.Language,
                                    text = lang,
                                    contentDescription = stringResource(R.string.chat_translate_to_cd, lang),
                                    onClick = {
                                        showActionMenu = false
                                        showLanguageSubmenu = false
                                        onTranslate(lang)
                                    },
                                )
                            }
                        } else {
                            // M-UI2: 助手消息长按菜单严格精简为 引用/委托/分支,
                            // 用户消息保留原有完整菜单(编辑/翻译/收藏/复制/分享/删除等)。
                            ActionMenuItem(
                                icon = Icons.AutoMirrored.Outlined.Reply,
                                text = stringResource(R.string.message_action_quote),
                                contentDescription = stringResource(R.string.message_action_quote),
                                onClick = {
                                    showActionMenu = false
                                    onQuote()
                                },
                            )
                            ActionMenuItem(
                                icon = Icons.Outlined.GroupWork,
                                text = stringResource(R.string.chat_delegate_action),
                                contentDescription = stringResource(R.string.chat_delegate_action),
                                onClick = {
                                    showActionMenu = false
                                    onDelegate()
                                },
                            )
                            ActionMenuItem(
                                icon = Icons.AutoMirrored.Outlined.CallSplit,
                                text = stringResource(R.string.chat_fork_action),
                                contentDescription = stringResource(R.string.chat_fork_action),
                                onClick = {
                                    showActionMenu = false
                                    onFork()
                                },
                            )
                            if (isUser) {
                                // 用户消息保留完整菜单
                                ActionMenuItem(
                                    icon = Icons.Default.Edit,
                                    text = stringResource(R.string.action_edit),
                                    contentDescription = stringResource(R.string.action_edit),
                                    onClick = {
                                        showActionMenu = false
                                        onEdit()
                                    },
                                )
                                if (msg.content.isNotBlank()) {
                                    ActionMenuItem(
                                        icon = Icons.Outlined.Language,
                                        text = stringResource(R.string.chat_translate_action),
                                        contentDescription = stringResource(R.string.chat_translate_action),
                                        onClick = { showLanguageSubmenu = true },
                                    )
                                }
                                if (msg.content.isNotBlank() || msg.reasoning?.isNotBlank() == true) {
                                    ActionMenuItem(
                                        icon = if (msg.favorite) Icons.Outlined.StarBorder
                                               else Icons.Default.Star,
                                        text = if (msg.favorite) stringResource(R.string.chat_favorite_remove) else stringResource(R.string.chat_favorite_add),
                                        contentDescription = if (msg.favorite) stringResource(R.string.chat_favorite_remove) else stringResource(R.string.chat_favorite_add),
                                        onClick = {
                                            showActionMenu = false
                                            MuseHaptics.light(hapticFeedback)
                                            onToggleFavorite()
                                        },
                                    )
                                }
                                if (msg.content.isNotBlank()) {
                                    ActionMenuItem(
                                        icon = Icons.Default.ContentCopy,
                                        text = stringResource(R.string.action_copy),
                                        contentDescription = stringResource(R.string.action_copy),
                                        onClick = {
                                            showActionMenu = false
                                            MuseHaptics.light(hapticFeedback)
                                            onCopyMessage(msg.content)
                                        },
                                    )
                                }
                                ActionMenuItem(
                                    icon = Icons.Outlined.Share,
                                    text = stringResource(R.string.chat_share_action),
                                    contentDescription = stringResource(R.string.chat_share_action),
                                    onClick = {
                                        showActionMenu = false
                                        onShareSession()
                                    },
                                )
                                ActionMenuItem(
                                    icon = Icons.Default.Delete,
                                    text = stringResource(R.string.chat_delete_message),
                                    contentDescription = stringResource(R.string.chat_delete_message),
                                    tint = MaterialTheme.colorScheme.error,
                                    onClick = {
                                        showActionMenu = false
                                        showDeleteConfirm = true
                                    },
                                )
                            }
                        }
                    }
                },
                onConfirm = null,
                dismissText = stringResource(R.string.action_close),
                onDismiss = {
                    showActionMenu = false
                    showLanguageSubmenu = false
                },
            )
        }

        // P5-F: 翻译中指示
        if (isTranslating) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    stringResource(R.string.chat_translate_in_progress),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        // v1.79 (H-B2): 空列表检查移到 LaunchedEffect,避免 composition 期间写状态
        // L-MB2: mediaPreview 为 null 时跳过,避免多余触发
        LaunchedEffect(mediaPreview) {
            if (mediaPreview != null && mediaPreview?.first.isNullOrEmpty()) {
                mediaPreview = null
            }
        }
        // v1.0.15: 全屏媒体查看器抽取为共享组件(原 v1.60-B 内联实现),供群聊复用
        mediaPreview?.let { (images, initialIndex) ->
            FullScreenMediaViewer(
                images = images,
                initialIndex = initialIndex,
                onDismiss = { mediaPreview = null },
            )
        }
        if (showDeleteConfirm) {
            MuseDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = stringResource(R.string.chat_delete_message),
                content = { Text(stringResource(R.string.chat_delete_message_confirm)) },
                confirmText = stringResource(R.string.action_delete),
                onConfirm = { showDeleteConfirm = false; onDeleteMessage() },
                destructive = true,
            )
        }
        // P2-13: 桌面端右键上下文菜单(仅物理键盘 + Expanded 窗口下弹出)
        // 项:复制 / 重新生成(仅末尾 AI 消息)/ 删除 / 分享
        // 与移动端长按菜单(showActionMenu)功能对齐,但采用桌面右键菜单交互范式
        if (showDesktopContextMenu) {
            // 在 @Composable 上下文预提取本地化字符串,remember 块内不能调用 stringResource
            val copyLabel = stringResource(R.string.desktop_context_copy)
            val regenerateLabel = stringResource(R.string.desktop_context_regenerate)
            val shareLabel = stringResource(R.string.desktop_context_share)
            val deleteLabel = stringResource(R.string.desktop_context_delete)
            val contextMenuItems = remember(
                msg.id, isUser, isLastAssistant,
                copyLabel, regenerateLabel, shareLabel, deleteLabel,
            ) {
                buildList {
                    if (msg.content.isNotBlank()) {
                        add(
                            ContextMenuItem(
                                label = copyLabel,
                                icon = Icons.Default.ContentCopy,
                                onClick = { onCopyMessage(msg.content) },
                            )
                        )
                    }
                    // 仅末尾 AI 消息提供"重新生成"
                    if (!isUser && isLastAssistant && msg.content.isNotEmpty()) {
                        add(
                            ContextMenuItem(
                                label = regenerateLabel,
                                icon = Icons.Default.Refresh,
                                onClick = {
                                    MuseHaptics.light(hapticFeedback)
                                    onRegenerate()
                                },
                            )
                        )
                    }
                    add(
                        ContextMenuItem(
                            label = shareLabel,
                            icon = Icons.Outlined.Share,
                            onClick = onShareSession,
                        )
                    )
                    add(
                        ContextMenuItem(
                            label = deleteLabel,
                            icon = Icons.Default.Delete,
                            destructive = true,
                            onClick = { showDeleteConfirm = true },
                        )
                    )
                }
            }
            DesktopContextMenu(
                items = contextMenuItems,
                onDismiss = { showDesktopContextMenu = false },
            )
        }
    }
}

/**
 * 生成的图片卡片 — 圆角 + 点击预览 + 保存按钮。
 */
@Composable
private fun GeneratedImageCard(
    imageUri: String,
    onPreview: () -> Unit,
    onSave: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.tightGap)
            .clip(MuseShapes.medium)
            .clickable(onClick = onPreview),
    ) {
        SmartImage(
            model = imageUri,
            contentDescription = stringResource(R.string.chat_generated_image_cd),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .clip(MuseShapes.medium),
        )
        IconButton(
            onClick = {
                onSave()
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(MusePaddings.contentGap)
                .size(MuseIconSizes.touchTarget)
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    shape = CircleShape,
                ),
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = stringResource(R.string.chat_save_image_cd),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
        }
    }
}

/**
 * iOS 风格 ActionSheet 行项 — 全宽 Row(图标 + 文字),点击触发回调。
 */
@Composable
private fun ActionMenuItem(
    icon: ImageVector,
    text: String,
    contentDescription: String,
    onClick: () -> Unit,
    // v1.48: 可选 tint,用于"删除消息"等危险操作标红
    tint: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
) {
    val iconTint = if (tint == androidx.compose.ui.graphics.Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint
    val textTint = if (tint == androidx.compose.ui.graphics.Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint
    Surface(
        onClick = onClick,
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.iconPadding, vertical = MusePaddings.inputPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.iconPadding),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = iconTint,
                // M-MB3: 图标尺寸用 MuseIconSizes.iconMedium 令牌替代硬编码 22dp
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = textTint,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 任务 2A: iOS 风格 shimmer 骨架屏 + 脉冲点加载动画。
 * 三个圆点依次缩放/淡入淡出,下方显示状态文字。
 */
@Composable
internal fun LoadingDots(text: String = stringResource(R.string.chat_loading_thinking)) {
    Column(
        modifier = Modifier.padding(MusePaddings.cardInner),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // v1.79 (L-B15): 三个圆点共享一个 InfiniteTransition,减少动画开销
            val infiniteTransition = rememberInfiniteTransition(label = "dots")
            repeat(3) { index ->
                val scale by infiniteTransition.animateFloat(
                    initialValue = 0.6f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600, delayMillis = index * 120, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "dot$index",
                )
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.4f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600, delayMillis = index * 120, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "dotAlpha$index",
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .scale(scale)
                        .alpha(alpha)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

/**
 * v0.48: shimmer 骨架屏占位 — 空流式 assistant 消息气泡渲染占位条,
 * 替代旧 LoadingDots 的"思考中"文字,营造"AI 正在写"的呼吸感。
 *
 * 实现:一个 fillMaxWidth(0.6f) / height 14dp 圆角矩形,
 * 用 [Brush.linearGradient] 配合 [animateFloat] + [infiniteRepeatable]
 * 做从左到右的扫光动画(1200ms 一个周期,LinearEasing)。
 * 颜色:surfaceVariant(0.4) → primary(0.2) → surfaceVariant(0.4)。
 */
@Composable
internal fun ShimmerPlaceholder(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerTranslate",
    )
    val brush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        start = Offset(translateAnim * -300f, 0f),
        end = Offset(translateAnim * 300f, 0f),
    )
    Box(
        modifier = modifier
            .fillMaxWidth(0.6f)
            .height(14.dp)
            .clip(MuseShapes.tiny)
            .background(brush),
    )
}

/**
 * v1.33: 智能图片渲染器
 * 绕过 Coil 的 data URI 解析(部分设备上 DataUriFetcher 静默失败)
 * data URI → 直接用 BitmapFactory 解码;普通 URL/Uri → 仍走 Coil AsyncImage
 */
@Composable
fun SmartImage(
    model: Any?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val dataUriPrefix = "data:image/"
    if (model is String && model.startsWith(dataUriPrefix)) {
        // data URI:提取 base64 部分,IO 线程解码
        val base64Part = remember(model) {
            val commaIndex = model.indexOf(',')
            if (commaIndex > 0) model.substring(commaIndex + 1) else null
        }
        if (base64Part != null) {
            val bitmapState by produceState<android.graphics.Bitmap?>(initialValue = null, base64Part) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = android.util.Base64.decode(base64Part, android.util.Base64.NO_WRAP)
                        // v1.79 (H-B1): 先探测尺寸再降采样,避免大图解码 OOM
                        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        val targetSize = 1024
                        var sampleSize = 1
                        while (options.outWidth / sampleSize > targetSize || options.outHeight / sampleSize > targetSize) {
                            sampleSize *= 2
                        }
                        val decodeOptions = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                    }.getOrNull()
                }
                // v1.79 (M-B11): produceState 退出时显式回收 Bitmap,避免内存泄漏
                awaitDispose {
                    value?.recycle()
                }
            }
            val current = bitmapState
            if (current != null) {
                Image(
                    bitmap = current.asImageBitmap(),
                    contentDescription = contentDescription,
                    modifier = modifier,
                    contentScale = contentScale,
                )
            } else {
                // 解码中/失败:灰色占位
                Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant))
            }
        } else {
            Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant))
        }
    } else {
        // 普通 URL/Uri:走 Coil AsyncImage
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
    }
}

/**
 * 阶段 4: 流式光标 — 末尾 AI 流式消息文本后追加的闪烁竖线。
 *
 * 设计: 2.5dp 宽 / 18dp 高竖条,通过 [rememberInfiniteTransition] + [animateFloat]
 *       在 1.0 ↔ 0.2 间用 FastOutSlowInEasing 往返(530ms 周期),呈现"打字机呼吸感"。
 * 颜色: 取自 MaterialTheme.colorScheme.primary(月桂绿 #2D8C5F),
 *       与品牌色保持一致,符合"深夜台灯"配色铁律(<5% 品牌色点缀)。
 *
 * v1.0.3 改进:
 *  - 周期从 1s 缩短到 530ms,看起来更"活跃",与更快的内容流入节奏匹配
 *  - alpha 范围从 0~1 改为 0.2~1,避免完全消失,视觉更连贯
 *  - 缓动从 LinearEasing 改为 FastOutSlowInEasing,呼吸感更自然
 *  - 宽度从 2dp 加到 2.5dp,高度从 16dp 加到 18dp,略微更醒目
 */
@Composable
private fun StreamingCursor(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "streaming_cursor")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 530, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursor_alpha",
    )
    Box(
        modifier = modifier
            .size(width = 2.5.dp, height = 18.dp)
            .background(color = color.copy(alpha = alpha)),
    )
}

/**
 * 功能2: TTS 语音消息播放器。显示在 AI 消息气泡下方,当前消息正在 TTS 朗读时出现。
 *
 * 包含波形条动画 + 播放/暂停按钮 + 进度条 + 倍速选择。
 */
@Composable
private fun TtsAudioPlayer(
    modifier: Modifier = Modifier,
) {
    val ttsManager: io.zer0.muse.ui.speech.TtsManager = org.koin.compose.koinInject()
    val state by ttsManager.playbackState.collectAsStateWithLifecycle()
    val isPlaying = state.status == io.zer0.muse.ui.speech.PlaybackStatus.Playing
    val isPaused = state.status == io.zer0.muse.ui.speech.PlaybackStatus.Paused
    val progress = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f

    var speedIndex by rememberSaveable { mutableIntStateOf(1) }
    val speeds = remember { listOf(0.8f, 1.0f, 1.2f, 1.5f) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = MuseShapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.bubbleInner),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 播放/暂停按钮
                IconButton(
                    onClick = {
                        if (isPlaying) ttsManager.pause()
                        else ttsManager.resume()
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) stringResource(R.string.speech_pause_cd) else stringResource(R.string.speech_resume_cd),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                // 波形条动画(4 条竖条,随播放状态弹跳)
                WaveformBars(isActive = isPlaying)
                Spacer(Modifier.weight(1f))
                // 倍速选择
                Text(
                    text = "${speeds[speedIndex]}x",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            speedIndex = (speedIndex + 1) % speeds.size
                            ttsManager.setSpeed(speeds[speedIndex])
                        }
                        .clip(MuseShapes.tiny)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                        .padding(MusePaddings.chipInnerLoose),
                )
            }
            Spacer(Modifier.height(4.dp))
            // 进度条
            IosSlider(
                value = progress,
                onValueChange = { v ->
                    val targetMs = (v * state.durationMs).toLong()
                    val delta = (targetMs - state.positionMs).toInt()
                    ttsManager.seekBy(delta)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 波形条动画 — 4 条竖条,播放时逐条错开弹跳,暂停时静止。
 */
@Composable
private fun WaveformBars(
    isActive: Boolean,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val heights = listOf(12.dp, 18.dp, 14.dp, 20.dp)
        heights.forEachIndexed { index, maxHeight ->
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 600
                        0.4f at 0
                        1f at (150 + index * 50)
                        0.4f at 600
                    },
                    repeatMode = RepeatMode.Restart,
                ),
                label = "bar$index",
            )
            val currentHeight = if (isActive) maxHeight * scale else maxHeight * 0.4f
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(currentHeight)
                    .clip(MuseShapes.tiny)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isActive) 0.8f else 0.3f)),
            )
        }
    }
}

/**
 * v0.48: 消息时间戳格式化 — 受 chatPrefs.use24Hour 控制时制,
 * 默认 24 小时制显示 "HH:mm",12 小时制显示 "h:mm a"。
 */
// v1.79 (L-B12): SimpleDateFormat 提为文件级缓存,避免每条消息独立创建
private val sdf24Hour by lazy {
    java.text.SimpleDateFormat(MuseDateFormats.TIME_SHORT, java.util.Locale.getDefault())
}
private val sdf12Hour by lazy {
    java.text.SimpleDateFormat(MuseDateFormats.TIME_12H, java.util.Locale.getDefault())
}

// L-MB3: 移除多余的 @Composable 注解(函数不使用任何 Composable API)
private fun formatMessageTime(timestamp: Long, use24Hour: Boolean = true): String {
    val sdf = if (use24Hour) sdf24Hour else sdf12Hour
    return sdf.format(java.util.Date(timestamp))
}

/**
 * 视频时长格式化:毫秒 → "M:SS"(超过 1 小时则 "H:MM:SS")。
 * 仅用于消息气泡右下角时长标签,与 InputBar 中同名函数语义一致。
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
 * v0.47: 工具调用卡片 — 折叠式展示 tool_call 的入参/出参。
 *
 * 显示工具名 + 状态图标(成功/失败),点击展开看入参 JSON 和出参文本。
 * 用于替代原来"调用工具 xxx: 参数: ... 结果: ..."的纯文本 ASSISTANT 消息。
 *
 * 结果文本中若包含沙盒内文件路径,会渲染为可点击的附件芯片(见 [AttachmentChip])。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolCallCard(
    toolName: String,
    arguments: String,
    result: String,
    isSuccess: Boolean,
    modifier: Modifier = Modifier,
) {
    // v1.79 (L-B25): expanded 改用 rememberSaveable,旋屏/后台后保持展开状态
    var expanded by rememberSaveable { mutableStateOf(false) }
    // v1.79 (M-B4): extractFilePaths 含 file.exists() 磁盘 IO,移到 LaunchedEffect + IO 线程
    var attachments by remember(result) { mutableStateOf(emptyList<Pair<String, Long?>>()) }
    LaunchedEffect(result) {
        attachments = withContext(Dispatchers.IO) { extractFilePaths(result) }
    }
    // v1.28: 有附件时默认展开,让用户直接看到可下载的文件芯片
    LaunchedEffect(attachments.isNotEmpty()) {
        if (attachments.isNotEmpty()) expanded = true
    }
    Surface(
        color = if (isSuccess) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
        shape = MuseShapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(MusePaddings.itemGap)) {
            // 头部:工具名 + 状态图标 + 展开箭头
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isSuccess) Icons.Default.Check else Icons.Default.Close,
                        contentDescription = if (isSuccess) stringResource(R.string.chat_tool_success_cd) else stringResource(R.string.chat_tool_failed_cd),
                        tint = if (isSuccess) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = toolName,
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = MuseMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                // v1.48 (h19): 触摸目标 24→48dp(MuseIconSizes.touchTarget),图标视觉保持 18dp
                // L-MB4: 图标尺寸用 MuseIconSizes.iconSmall 令牌替代硬编码 18dp
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(MuseIconSizes.touchTarget)) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) stringResource(R.string.action_collapse) else stringResource(R.string.action_expand),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                }
            }
            // 展开内容:入参 + 出参
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.chat_tool_params), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(2.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = MuseShapes.extraSmall,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = arguments.ifBlank { "{}" },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = MuseMonoFontFamily,
                            modifier = Modifier.padding(MusePaddings.contentGap),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.chat_tool_result), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(2.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = MuseShapes.extraSmall,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val isTruncated = result.length > 500
                        Box {
                            Text(
                                text = result,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = MuseMonoFontFamily,
                                modifier = Modifier.padding(MusePaddings.contentGap),
                                maxLines = if (isTruncated) 15 else Int.MAX_VALUE,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (isTruncated) {
                                val bgColor = MaterialTheme.colorScheme.surfaceVariant
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.verticalGradient(
                                                0.5f to Color.Transparent,
                                                1.0f to bgColor,
                                            ),
                                        ),
                                )
                            }
                        }
                    }
                    // 结果文本下方:若检测到沙盒内文件路径,渲染可点击附件芯片
                    if (attachments.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            attachments.forEach { (path, size) ->
                                AttachmentChip(
                                    filePath = path,
                                    fileSize = size,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 从工具结果文本中提取沙盒内文件路径。
 *
 * 匹配 /data/.../files/ 或 /data/.../cache/ 等绝对路径下、带扩展名的文件,
 * 只返回磁盘中真实存在的文件,按路径去重。
 *
 * @return 文件路径与对应字节数(读取失败则为 null)的列表
 */
// v1.79 (L-B10): Regex 提为文件级常量,避免每次调用重建
private val PATH_PATTERN = Regex("""(/data/[^\s,)]+\.[a-zA-Z0-9]+)""")

private fun extractFilePaths(text: String): List<Pair<String, Long?>> {
    val results = mutableListOf<Pair<String, Long?>>()
    PATH_PATTERN.findAll(text).forEach { match ->
        val path = match.groupValues[1]
        val file = java.io.File(path)
        if (file.exists()) {
            results.add(path to file.length())
        }
    }
    return results.distinctBy { it.first }
}

/**
 * v1.95: 从文本中提取表情包绝对路径。
 *
 * send_sticker 工具返回格式为 `已发送表情包。路径:/data/.../files/stickers/猫猫/001.png`,
 * 本函数用正则匹配包含 `/stickers/` 的绝对路径(从路径起始的 `/` 到图片扩展名为止),
 * 供 MessageBubble 把这些路径转为 file:// URI 用 Coil AsyncImage 渲染。
 *
 * @return 去重后的绝对路径列表
 */
// v1.95: 表情包路径正则 — 匹配包含 /stickers/ 且以图片扩展名结尾的绝对路径,不区分大小写
// 从路径起始的 / 开始匹配(如 /data/.../files/stickers/猫猫/001.png),保证 file:// URI 可解析
private val STICKER_PATH_PATTERN =
    Regex("""(/[^\s\]]*?/stickers/[^\s\]]+\.(?:png|jpg|jpeg|gif|webp|bmp))""", RegexOption.IGNORE_CASE)

private fun extractStickerPaths(text: String): List<String> {
    return STICKER_PATH_PATTERN.findAll(text)
        .map { it.groupValues[1] }
        .distinct()
        .toList()
}

/**
 * v1.133: RAG 引用 chip 列表 — 渲染知识库检索引用,点击展开 snippet 预览。
 *
 * 设计:
 *  - 用 FlowRow 横向排列 chip(自动换行,适配窄屏)
 *  - 当前展开项独占一行显示完整 snippet + 元数据(分数/匹配类型)
 *  - chip 形状用 [MuseShapes.pill](iOS 胶囊形),颜色用 surfaceVariant/primaryContainer
 *
 * @param citations 引用列表(与 system prompt 中的 [N] 一一对应)
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RagCitationChips(
    citations: List<RagCitation>,
    modifier: Modifier = Modifier,
) {
    // 当前展开的 citation index,-1 表示全部收起
    var expandedIndex by rememberSaveable { mutableStateOf(-1) }
    Column(modifier = modifier.fillMaxWidth()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            citations.forEach { citation ->
                RagCitationChip(
                    citation = citation,
                    isExpanded = expandedIndex == citation.index,
                    onClick = {
                        expandedIndex = if (expandedIndex == citation.index) -1 else citation.index
                    },
                )
            }
        }
        // 展开的 snippet 预览(独占一行)
        val expanded = citations.firstOrNull { it.index == expandedIndex }
        if (expanded != null) {
            Surface(
                shape = MuseShapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MusePaddings.tightGap),
            ) {
                Column(modifier = Modifier.padding(MusePaddings.contentGap)) {
                    Text(
                        text = "《${expanded.docTitle}》 · 片段 #${expanded.chunkIndex}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(MusePaddings.tightGap))
                    Text(
                        text = expanded.snippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "分数 ${"%.2f".format(expanded.score)} · ${expanded.matchType}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = MusePaddings.tightGap),
                    )
                }
            }
        }
    }
}

@Composable
private fun RagCitationChip(
    citation: RagCitation,
    isExpanded: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = MuseShapes.pill,
        color = if (isExpanded) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (isExpanded) MaterialTheme.colorScheme.onPrimaryContainer
                       else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = MusePaddings.contentGap, vertical = MusePaddings.tightGap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            Text(
                text = "[${citation.index}]",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = citation.docTitle.take(20),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(MuseIconSizes.iconTiny),
            )
        }
    }
}

/**
 * 功能1: 构建带高亮的 AnnotatedString。
 * 在文本中查找 query 出现的位置,用 primaryContainer 色高亮匹配段。
 */
@Composable
private fun buildHighlightedText(text: String, query: String): AnnotatedString {
    val highlightColor = MaterialTheme.colorScheme.primaryContainer
    val onHighlight = MaterialTheme.colorScheme.onPrimaryContainer
    return buildAnnotatedString {
        var start = 0
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()
        while (true) {
            val index = lowerText.indexOf(lowerQuery, start)
            if (index < 0) {
                append(text.substring(start))
                break
            }
            append(text.substring(start, index))
            withStyle(SpanStyle(background = highlightColor, color = onHighlight)) {
                append(text.substring(index, index + query.length))
            }
            start = index + query.length
        }
    }
}

/**
 * v1.98 (T5): 任务待办胶囊 — 长任务(≥2 步工具调用)时,显示在工具调用卡片左边。
 *
 * 显示内容:
 * - 执行中:旋转加载图标 + "当前/总数"(primary 色)
 * - 已完成:Build 图标 + "成功/总数"(primary 色)
 * - 有失败:Build 图标 + "成功/总数"(error 色)
 *
 * 点击展开/折叠完整 TaskCard。竖向胶囊,贴合工具调用卡片左侧。
 */
@Composable
private fun TaskProgressBadge(
    phase: io.zer0.muse.ui.taskcard.TaskCardPhase,
    successCount: Int,
    total: Int,
    isExecuting: Boolean,
    modifier: Modifier = Modifier,
) {
    val hasFailed = phase == io.zer0.muse.ui.taskcard.TaskCardPhase.DONE && successCount < total
    val badgeColor = if (hasFailed) MaterialTheme.colorScheme.error
                     else MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier
            .width(36.dp),
        shape = MuseShapes.pill,
        color = badgeColor.copy(alpha = 0.12f),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (isExecuting) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                    color = badgeColor,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = "$successCount/$total",
                style = MaterialTheme.typography.labelSmall,
                color = badgeColor,
                fontWeight = FontWeight.Medium,
                fontSize = 10.sp,
            )
        }
    }
}

