package io.zer0.muse.ui

import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import android.content.Intent
import kotlin.math.roundToInt
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.GroupWork
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import io.zer0.common.resultOf
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.zer0.muse.R
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.ui.artifact.ArtifactCardList
import io.zer0.muse.ui.chat.BranchSelector
import io.zer0.muse.ui.chat.parseQuotedContent
import io.zer0.muse.ui.chat.buildHighlightedText
import io.zer0.muse.ui.chat.buildMoodSkinAnnotated
import io.zer0.muse.ui.chat.MessageInfoSheet
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.ui.common.media.ContextMenuItem
import io.zer0.muse.ui.common.media.DesktopContextMenu
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.media.rememberDesktopShortcutsEnabled
import io.zer0.muse.ui.markdown.MarkdownText
import io.zer0.muse.transformer.MoodSkinParser
import io.zer0.muse.ui.taskcard.AgentPlan
import io.zer0.muse.ui.taskcard.PlanCard
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.tiny
import androidx.compose.material.icons.outlined.VideoLibrary
import io.zer0.muse.ui.common.media.FullScreenMediaViewer
import io.zer0.muse.ui.chat.VideoAttachment
import kotlinx.coroutines.launch

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

// v1.x: 产物占位符标记(模型可能直接输出 [artifact:uuid] 引用,但无成对标签内容);
// 渲染层统一剥离,避免把 UUID 明文展示给用户(真实产物由 artifactIds 卡片列表展示)。
private val ARTIFACT_MARKER_RE = Regex("""\[artifact:[0-9a-fA-F-]{36}\]""")

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun MessageBubble(
    msg: UIMessage,
    isStreaming: Boolean,
    isLastAssistant: Boolean,
    isTranslating: Boolean,
    // v1.0.53: 是否为会话最后一条消息(快捷菜单/分支切换用)
    isLast: Boolean = false,
    // v1.0.53: 当前消息的分支索引与总数(助手消息多版本切换)
    branchIndex: Int = 0,
    branchCount: Int = 1,
    onBranchPrevious: () -> Unit = {},
    onBranchNext: () -> Unit = {},
    onEdit: () -> Unit,
    onQuote: () -> Unit,
    onRegenerate: () -> Unit,
    /** B7-04: 流式打断后继续生成入口。 */
    onContinue: (() -> Unit)? = null,
    /** B7-01: 多选模式。 */
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelection: (() -> Unit)? = null,
    onEnterMultiSelect: (() -> Unit)? = null,
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
    onCancelTask: () -> Unit = {},
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
        /** P1 UI: Token 统计条(由 ChatScreen 传入,显示在助手消息快捷按钮组下方)。 */
    tokenStats: (@Composable () -> Unit)? = null,
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
    // v1.0.74 fix: 记录气泡在窗口中的位置,长按菜单以此锚定(此前无锚点,永远弹在窗口右上角)
    var actionMenuBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    // v1.0.72: Manus 风格长按菜单 — false=精简面板(引用/分享/复制/选择文本/更多),
    // true=展开完整菜单(委托/分支/翻译/收藏/编辑/删除等)
    var showExtendedMenu by rememberSaveable { mutableStateOf(false) }
    // v1.0.72: 文本选择模式 — 长按菜单点"选择文本"后进入,支持划选复制;
    // 点击气泡或再次长按退出
    var textSelectMode by rememberSaveable { mutableStateOf(false) }
    // P2-13: 桌面端右键上下文菜单(仅 Expanded 窗口 + 物理键盘场景显示)
    var showDesktopContextMenu by rememberSaveable { mutableStateOf(false) }
    // v1.60-B: 全屏媒体查看器状态 — 图片列表 + 初始索引
    // mediaPreview 为 Pair 类型,自定义 Saver 过于复杂,保持 remember
    var mediaPreview by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    // v1.48: 删除消息确认对话框
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    // A5: 消息信息弹层(长按扩展菜单/桌面右键菜单「消息信息」触发)
    var showInfoSheet by rememberSaveable { mutableStateOf(false) }
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
    val bubbleInteractionSource = remember { MutableInteractionSource() }
    val bubbleClickModifier = Modifier.combinedClickable(
        interactionSource = bubbleInteractionSource,
        indication = null,
        // v1.0.72: 长按消息任意位置(含文字区域,已去掉 SelectionContainer 拦截)都弹操作菜单;
        // 点击:多选模式切换选中 / 文本选择模式退出
        onClick = {
            if (selectionMode) onToggleSelection?.invoke()
            else if (textSelectMode) textSelectMode = false
        },
        onLongClick = {
            MuseHaptics.medium(hapticFeedback)
            if (textSelectMode) {
                // v1.0.72: 文本选择模式下长按交给 SelectionContainer 激活系统选择手柄,
                // 不弹操作菜单(否则长按被抢走,永远选不了文字)
                return@combinedClickable
            }
            if (selectionMode) {
                onToggleSelection?.invoke()
            } else if (desktopShortcutsEnabled) {
                showDesktopContextMenu = true
            } else {
                showActionMenu = true
            }
        },
    )

    // 按当前布局方向计算绝对对齐,避免 RTL 下用户/助手气泡左右颠倒
    val layoutDirection = LocalLayoutDirection.current
    val isLtr = layoutDirection == LayoutDirection.Ltr
    val horizontalAlignment = if (isUser == isLtr) Alignment.End else Alignment.Start

    // v1.0.74 fix (前端审计 2.5): 仅长按菜单显示时记录位置。
    // 原实现滚动期间每帧 onGloballyPositioned 写 MutableState,气泡(含 MarkdownText)滚动中反复重组。
    // C-16: actionMenuBounds 初始为 Rect.Zero(非 null),原条件 `actionMenuBounds != null`
    // 恒为 true → 防护从未生效,每个布局帧都写 state;改为按菜单可见性判断。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // v1.0.74 fix: 记录气泡窗口位置供长按菜单锚定(仅菜单显示时更新)
            .onGloballyPositioned {
                if (showActionMenu) actionMenuBounds = it.boundsInWindow()
            }
            .then(if (isAnimating) Modifier.animateContentSize() else Modifier),
    ) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = horizontalAlignment,
    ) {


        // B7-01: 多选模式指示
        if (selectionMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                horizontalArrangement = if (isUser == isLtr) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(22.dp),
                ) {
                    Icon(
                        imageVector = if (selected) TablerIcons.Check else TablerIcons.Circle,
                        // C-21: 多选指示器专用语义(此前复用 skill_enabled/skill_disabled,TalkBack 语义错误)
                        contentDescription = stringResource(if (selected) R.string.chat_msg_selected else R.string.chat_msg_not_selected),
                        tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(2.dp),
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
                        Spacer(Modifier.height(MusePaddings.tinyGap))
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
        // v1.0.54: 工具轮消息(带 toolCalls/toolCallInfo)不显示思考块 — 工具调用的推理过程
        //   对用户无价值且出戏(send_sticker 选贴纸的思考会被完整展示),兜底过滤。
        val isToolRoundMessage = !msg.toolCalls.isNullOrEmpty() || msg.toolCallInfo != null
        if (chatPrefs.showReasoning && !isToolRoundMessage) {
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
                                    stringResource(R.string.chat_thinking_preview, cleaned.take(40) + "…")
                                } else {
                                    stringResource(R.string.chat_thinking_preview, cleaned)
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
                            Spacer(Modifier.height(MusePaddings.tinyGap))
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
                            Spacer(Modifier.height(MusePaddings.tinyGap))
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
            // 用户消息: iOS 风格浅色暖灰/米白圆角气泡,无尾巴,18dp 统一圆角
            // Phase 8.6: 若有图片,放在气泡内文字上方
            val hasImages = msg.imageBase64List.isNotEmpty()
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MuseShapes.large,
                // v1.0.29: 移除阴影,避免浅色气泡在深色/浅色背景下出现奇怪阴影边缘。
                modifier = bubbleClickModifier
                    .padding(horizontal = MusePaddings.tinyGap, vertical = 3.dp),
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
                                    imageVector = TablerIcons.PlayerPlay,
                                    contentDescription = stringResource(R.string.chat_video_play_cd),
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
                                    text = MessageBubbleFormatters.formatVideoDuration(va.durationMs),
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
                                    TablerIcons.Check,
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
                                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
                                ) {
                                    if (visionAssistProgress?.isActive == true) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(MusePaddings.itemGap),
                                            strokeWidth = 1.5.dp,
                                            color = labelColor,
                                        )
                                    } else {
                                        Icon(
                                            imageVector = labelIcon,
                                            contentDescription = null,
                                            tint = labelColor,
                                            modifier = Modifier.size(MusePaddings.itemGap),
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
                    modifier = Modifier.padding(top = 2.dp, end = MusePaddings.tinyGap),
                )
            }
        } else {
            // v1.0.54: 空 assistant 消息(content 空 + 无图片/卡片/思考/反思/情绪)不渲染 —
            //   工具轮占位消息 updateAssistant 不更新 toolCalls(恒为 null),无法按工具轮判断;
            //   流式期间保留(ThinkingIndicator 是正常生成反馈),结束后/加载时空消息隐藏。
            // C-22: 含产物卡(artifactIds)/任务卡(经 taskCardInfo)的消息即使正文为空也不隐藏
            val isToolRoundPlaceholder = !isStreaming &&
                body.isBlank() &&
                msg.imageUrls.isEmpty() && msg.imageBase64List.isEmpty() &&
                msg.artifactIds.isEmpty() &&
                msg.toolCallInfo == null &&
                msg.reasoning.isNullOrBlank() &&
                msg.mood.isNullOrBlank() &&
                msg.reflection.isNullOrBlank()
            // v0.48: AI 头像 — 消息分组时连续同角色消息压缩头像(showAvatar=false 时跳过)
            if (showAvatar && !isToolRoundPlaceholder) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .padding(bottom = MusePaddings.tinyGap),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AssistantAvatar(
                        assistant = assistant ?: io.zer0.muse.data.assistant.AssistantEntity(
                            id = "default",
                            name = "Muse",
                        ),
                        avatarSize = 28.dp,
                    )
                    if (showTimestamp && chatPrefs.showTimestamp) {
                        Spacer(Modifier.width(MusePaddings.contentGap))
                        Text(
                            text = (assistant?.name ?: "Muse") +
                                " · " + formatMessageTime(msg.createdAt, use24Hour = chatPrefs.use24Hour),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
            // AI 消息:白色卡片,左对齐,18dp 统一圆角,0.5dp 浅边框,无阴影
            if (!isToolRoundPlaceholder) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MuseShapes.large,
                tonalElevation = MuseElevation.card,
                shadowElevation = 0.dp,
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .border(
                        width = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        shape = MuseShapes.large,
                    )
                    .then(bubbleClickModifier),
            ) {
                Column(
                    modifier = Modifier.padding(MusePaddings.cardInner),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 流式/思考状态:AI 消息顶部显示"正在思考…"带绿色脉动圆点
                    if (isLastAssistant && isStreaming) {
                        ThinkingIndicator()
                    }
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
                            resultOf {
                                saveImageToGallery(context, imageUri)
                            }.onSuccess { path ->
                                // M-MB2: 改用 MuseToast 替代原生 Toast,保持主题一致
                                MuseToast.show(context.getString(R.string.chat_image_saved_toast, path))
                            }.onError { msg, t ->
                                MuseToast.show(context.getString(R.string.chat_image_save_failed_toast, msg))
                            }
                        }
                    },
                )
            }
            // 审计修复 (S-02): 视频生成结果卡片(generate_video 写入的 videoFileUri)
            // 此前只存在于内存 UIMessage 且无渲染,重启/切页后视频永久丢失;
            // 现在随消息落库(v88 迁移)并在此渲染,点击调起系统播放器。
            val generatedVideoUri = msg.videoFileUri
            if (!generatedVideoUri.isNullOrBlank()) {
                AssistantVideoCard(videoUri = generatedVideoUri)
            }
            // v1.112 (C1): 任务清单与工具调用胶囊拆分布局
            // 展开态:TaskCard 占满宽度垂直堆叠(步骤列表需要空间),ToolCallCard 在下方
            val toolInfo = msg.toolCallInfo
            // v1.0.53: 静默工具(send_sticker)— 表情包是趣味交互,不展示工具调用卡片,
            //   避免"调用工具"的提示破坏贴纸体验。贴纸图片本身照常渲染。
            // v1.0.54: list_stickers 同样静默(列表情包是内部工作)。
            val isSilentTool = toolInfo?.toolName == "send_sticker" ||
                toolInfo?.toolName == "list_stickers"
            if (taskCard != null && toolInfo != null) {
                if (taskCard.isExpanded) {
                    // 展开态:TaskCard 占满宽度,ToolCallCard 在下方
                    io.zer0.muse.ui.taskcard.TaskCard(
                        data = taskCard,
                        onToggleExpand = onToggleTaskCardExpand,
                        onRetryStep = onRetryTaskCardStep,
                        onCancel = onCancelTask,
                        delegationChain = delegationChain,
                    )
                    if (!isSilentTool) {
                        ToolCallCard(
                            toolName = toolInfo.toolName,
                            arguments = toolInfo.arguments,
                            result = toolInfo.result,
                            isSuccess = toolInfo.isSuccess,
                            modifier = Modifier.widthIn(max = 360.dp),
                        )
                    }
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
                            onCancel = onCancelTask,
                            modifier = Modifier.weight(1f),
                            delegationChain = delegationChain,
                        )
                        if (!isSilentTool) {
                            ToolCallCard(
                                toolName = toolInfo.toolName,
                                arguments = toolInfo.arguments,
                                result = toolInfo.result,
                                isSuccess = toolInfo.isSuccess,
                                modifier = Modifier.widthIn(max = 360.dp),
                            )
                        }
                    }
                }
            } else if (taskCard != null) {
                // 只有 TaskCard,没有 ToolCallInfo
                io.zer0.muse.ui.taskcard.TaskCard(
                    data = taskCard,
                    onToggleExpand = onToggleTaskCardExpand,
                    onRetryStep = onRetryTaskCardStep,
                    onCancel = onCancelTask,
                    delegationChain = delegationChain,
                )
            } else if (toolInfo != null && !isSilentTool) {
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
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
                    modifier = Modifier.padding(bottom = MusePaddings.tinyGap),
                ) {
                    Icon(
                        imageVector = TablerIcons.GitMerge,
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
            // v1.0.54: send_sticker 的 content 只有贴纸绝对路径(用于渲染图片),渲染文本时剔除,
            //   避免"莫名其妙的路径"显示成文本。
            val stickerPathTexts = if (msg.imageUrls.isEmpty() && msg.imageBase64List.isEmpty()) {
                extractStickerPaths(msg.content)
            } else emptyList()
            val bodyWithoutStickerPaths = if (stickerPathTexts.isNotEmpty()) {
                STICKER_PATH_PATTERN.replace(body, "").trim()
            } else body
            val content = if (isCompressed) {
                body.removePrefix("[COMPRESSED]").trim()
            } else bodyWithoutStickerPaths.ifEmpty {
                if (msg.imageUrls.isEmpty() && msg.imageBase64List.isEmpty()) " " else ""
            }
            // Markdown 标题提取:若内容以 # 标题开头,顶部显示粗体标题行,正文不再重复渲染标题
            val firstLineEnd = content.indexOf('\n').takeIf { it >= 0 } ?: content.length
            val firstLine = content.substring(0, firstLineEnd)
            val hasHeading = !isStreaming && firstLine.isNotBlank() && firstLine.startsWith("#")
            val titleText = if (hasHeading) firstLine else null
            val bodyContent = (if (hasHeading) content.substring(firstLineEnd + 1).trimStart() else content)
                .replace(ARTIFACT_MARKER_RE, "")
            if (content.isNotBlank()) {
                // Markdown 标题行
                titleText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (bodyContent.isNotBlank()) {
                        Spacer(Modifier.height(MusePaddings.tinyGap))
                    }
                }
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
                // v1.0.53: 数据卡片(```card JSON)优先渲染,其余走 markdown
                val dataCard = remember(bodyContent) {
                    if (io.zer0.muse.ui.markdown.DataCardParser.containsCardBlock(bodyContent)) {
                        io.zer0.muse.ui.markdown.DataCardParser.parse(bodyContent)
                    } else null
                }
                val markdownContent = @Composable {
                    // v1.79 (H-B3): 防御性处理 citationUrls,MarkdownText 内部应保证 [N] 不越界
                    val safeCitationUrls = msg.citationUrls ?: emptyList()
                    if (highlightText != null && bodyContent.contains(highlightText, ignoreCase = true)) {
                        androidx.compose.material3.Text(
                            text = buildHighlightedText(bodyContent, highlightText),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else if (MoodSkinParser.containsInlineEffect(bodyContent)) {
                        Text(
                            text = buildMoodSkinAnnotated(bodyContent),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        MarkdownText(
                            text = MoodSkinParser.stripInlineEffects(bodyContent),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.fillMaxWidth(),
                            citationUrls = safeCitationUrls,
                            isStreaming = isLastAssistant && isStreaming,
                            // v1.0.72: 选择模式或文本选择模式都禁用链接检测:
                            // 文本选择模式必须禁用,否则 LinkableText 的 pointerInput 拦截长按,
                            // 系统文本选择手柄永远无法激活(用户反馈"选择文本完全失效")
                            disableLinks = selectionMode || textSelectMode,
                            onHtmlPreview = onHtmlPreview,
                            // v1.0.72: 长按非链接区域 → 弹气泡长按菜单(修复长按消息无反应)
                            onLongPressOutside = {
                                textSelectMode = false
                                showActionMenu = true
                            },
                        )
                    }
                }
                if (dataCard != null) {
                    io.zer0.muse.ui.markdown.DataCardRenderer(card = dataCard)
                } else if (textSelectMode) {
                    // v1.0.72: 仅"选择文本"模式下用 SelectionContainer 支持划选复制;
                    // 其余情况不用(否则 SelectionContainer 会拦截长按,弹不出操作菜单)
                    SelectionContainer { markdownContent() }
                } else {
                    markdownContent()
                }
            }
            // 功能3: 链接预览卡片(仅非流式时,避免流式增量导致重抓)
            if (!isStreaming) {
                val linkPreviews = rememberLinkPreviews(bodyContent)
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
                    modifier = Modifier.padding(top = MusePaddings.contentGap),
                )
            }
            // v2.3: debug 模式性能摘要(可选)
            if (!isStreaming && debugInfo != null) {
                Text(
                    text = debugInfo,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // v1.43: 产物卡片列表(代码/文档/HTML/SVG/图片等)
            if (artifacts.isNotEmpty()) {
                ArtifactCardList(
                    artifacts = artifacts,
                    onArtifactClick = onArtifactClick,
                    modifier = Modifier.padding(top = MusePaddings.contentGap),
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
            }
            }       // closes AI bubble Surface
        }

        // v1.0.53: 用户消息底部快捷按钮 — 复制 + 重试(仅最后一条)
        if (isUser && msg.content.isNotEmpty() && !isStreaming && !isTranslating) {
            Row(
                modifier = Modifier.padding(top = 2.dp, end = MusePaddings.tinyGap),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MuseTactileButton(
                    icon = TablerIcons.Copy,
                    onClick = {
                        MuseHaptics.light(hapticFeedback)
                        onCopyMessage(MoodSkinParser.cleanForExport(msg.content))
                    },
                    contentDescription = stringResource(R.string.action_copy),
                    tint = MaterialTheme.colorScheme.outline,
                    size = MuseIconSizes.touchTarget,
                    iconSize = MuseIconSizes.iconSmall,
                )
                if (isLast) {
                    MuseTactileButton(
                        icon = TablerIcons.Refresh,
                        onClick = {
                            MuseHaptics.light(hapticFeedback)
                            onRegenerate()
                        },
                        contentDescription = stringResource(R.string.action_retry),
                        tint = MaterialTheme.colorScheme.outline,
                        size = MuseIconSizes.touchTarget,
                        iconSize = MuseIconSizes.iconSmall,
                    )
                }
            }
        }

        // v1.138 / v1.0.53: 助手消息底部快捷按钮 — 复制/翻译/分享/重新生成 + 分支切换器
        // 翻译按钮复用长按菜单的语言子菜单(showActionMenu + showLanguageSubmenu)
        // 分享按钮用系统 share sheet 分享单条消息内容
        if (!isUser && msg.content.isNotEmpty() && !isStreaming && !isTranslating) {
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 复制
                MuseTactileButton(
                    icon = TablerIcons.Copy,
                    onClick = {
                        onCopyMessage(MoodSkinParser.cleanForExport(msg.content))
                        MuseHaptics.light(hapticFeedback)
                    },
                    contentDescription = stringResource(R.string.action_copy),
                    tint = MaterialTheme.colorScheme.outline,
                    size = MuseIconSizes.touchTarget,
                    iconSize = MuseIconSizes.iconSmall,
                )
                // 翻译(弹语言子菜单)
                MuseTactileButton(
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
                MuseTactileButton(
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
                    MuseTactileButton(
                        icon = TablerIcons.Refresh,
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
                // B7-04: 继续生成(仅中断的最后一条助手消息)
                if (isLastAssistant && msg.content.contains("[已中断]") && onContinue != null) {
                    MuseTactileButton(
                        icon = TablerIcons.PlayerPlay,
                        onClick = {
                            MuseHaptics.light(hapticFeedback)
                            onContinue()
                        },
                        contentDescription = stringResource(R.string.chat_asr_tip_confirm),
                        tint = MaterialTheme.colorScheme.primary,
                        size = MuseIconSizes.touchTarget,
                        iconSize = MuseIconSizes.iconSmall,
                    )
                }
            }
            // P1 UI: Token 统计独立一行(快捷按钮下方,不再挤占按钮行)
            if (!isUser && tokenStats != null) {
                tokenStats()
            }
            // 分支切换器独立一行,避免窄屏被顶出屏幕
            if (branchCount > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BranchSelector(
                        currentIndex = branchIndex,
                        totalCount = branchCount,
                        onPrevious = onBranchPrevious,
                        onNext = onBranchNext,
                    )
                }
            }
        }

        // 用户消息版本切换器：编辑/重试产生的用户提问版本
        if (isUser && branchCount > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = if (isUser == isLtr) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BranchSelector(
                    currentIndex = branchIndex,
                    totalCount = branchCount,
                    onPrevious = onBranchPrevious,
                    onNext = onBranchNext,
                )
            }
        }

        // 阶段 4: 长按菜单
        // v1.0.72: Telegram 风格 — Popup 定位在消息附近(哪里按哪里弹出,非底部滑入),
        // 卡片含 引用/复制/选择文本/分享/编辑(仅用户消息)/更多;
        // "更多"展开完整菜单(委托/分支/翻译/收藏/删除等,原逻辑保留)。
        if (showActionMenu) {
            if (!showExtendedMenu) {
                // ── Telegram 风格 Popup 卡片(锚定消息气泡) ──
                // v1.0.74 fix: 此前无 parent 锚点,菜单永远弹在窗口右上角(离手指很远)。
                // 改为按气泡窗口位置定位:菜单右缘贴气泡右缘,上缘在气泡上方 8dp。
                // Popup 的 offset 是像素单位,直接用 boundsInWindow 的像素坐标。
                val density = LocalDensity.current
                val menuWidthPx = (220 * density.density).roundToInt()
                val padPx = (8 * density.density).roundToInt()
                Popup(
                    onDismissRequest = {
                        showActionMenu = false
                        showLanguageSubmenu = false
                    },
                    alignment = Alignment.TopStart,
                    offset = if (actionMenuBounds != androidx.compose.ui.geometry.Rect.Zero) {
                        IntOffset(
                            x = (actionMenuBounds.right.toInt() - menuWidthPx).coerceAtLeast(padPx),
                            y = (actionMenuBounds.top.toInt() - padPx).coerceAtLeast(padPx),
                        )
                    } else {
                        IntOffset(0, 8)
                    },
                ) {
                    TelegramActionCard(
                        isUser = isUser,
                        onQuote = {
                            showActionMenu = false
                            showLanguageSubmenu = false
                            onQuote()
                        },
                        onCopy = {
                            showActionMenu = false
                            showLanguageSubmenu = false
                            MuseHaptics.light(hapticFeedback)
                            onCopyMessage(MoodSkinParser.cleanForExport(msg.content))
                        },
                        onSelectText = {
                            // v1.0.72: "选择文本"= 进入文本选择模式(长按文字激活系统选择手柄)
                            showActionMenu = false
                            showLanguageSubmenu = false
                            textSelectMode = true
                            MuseToast.show(context.getString(R.string.chat_select_text_hint))
                        },
                        onShare = {
                            showActionMenu = false
                            showLanguageSubmenu = false
                            onShareSession()
                        },
                        onEdit = {
                            showActionMenu = false
                            showLanguageSubmenu = false
                            onEdit()
                        },
                        onMore = {
                            showExtendedMenu = true
                        },
                    )
                }
            } else {
            MuseDialog(
                onDismissRequest = {
                    showActionMenu = false
                    showLanguageSubmenu = false
                    showExtendedMenu = false
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
                            ActionMenuItem(
                                icon = TablerIcons.Square,
                                text = stringResource(R.string.chat_select_messages),
                                contentDescription = stringResource(R.string.chat_select_messages),
                                onClick = {
                                    showActionMenu = false
                                    onEnterMultiSelect?.invoke()
                                },
                            )
                            // A5: 消息信息弹层(模型/耗时/Token 用量)
                            ActionMenuItem(
                                icon = TablerIcons.InfoCircle,
                                text = stringResource(R.string.msg_info_title),
                                contentDescription = stringResource(R.string.msg_info_title),
                                onClick = {
                                    showActionMenu = false
                                    showLanguageSubmenu = false
                                    showInfoSheet = true
                                },
                            )
                            if (msg.content.isNotBlank()) {
                                ActionMenuItem(
                                    icon = TablerIcons.Copy,
                                    text = stringResource(R.string.action_copy),
                                    contentDescription = stringResource(R.string.action_copy),
                                    onClick = {
                                        showActionMenu = false
                                        MuseHaptics.light(hapticFeedback)
                                        onCopyMessage(MoodSkinParser.cleanForExport(msg.content))
                                    },
                                )
                            }
                            if (msg.content.isNotBlank() || msg.reasoning?.isNotBlank() == true) {
                                ActionMenuItem(
                                    icon = if (msg.favorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                                    text = if (msg.favorite) stringResource(R.string.chat_favorite_remove) else stringResource(R.string.chat_favorite_add),
                                    contentDescription = if (msg.favorite) stringResource(R.string.chat_favorite_remove) else stringResource(R.string.chat_favorite_add),
                                    onClick = {
                                        showActionMenu = false
                                        MuseHaptics.light(hapticFeedback)
                                        onToggleFavorite()
                                    },
                                )
                            }
                            if (isUser) {
                                // C-14: 用户消息只补用户专属项(编辑/翻译/分享/删除);
                                // 选择消息/收藏/复制已在公共菜单(上方)渲染,不再重复。
                                ActionMenuItem(
                                    icon = TablerIcons.Edit,
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
                                    icon = TablerIcons.Trash,
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
                    showExtendedMenu = false
                },
            )
            }
        }

        // P5-F: 翻译中指示
        if (isTranslating) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = MusePaddings.tinyGap),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(MusePaddings.itemGap),
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
        // A5: 消息信息弹层(模型/时间/耗时/Token 用量)
        if (showInfoSheet) {
            MessageInfoSheet(
                msg = msg,
                onDismiss = { showInfoSheet = false },
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
            // A5: 消息信息弹层入口(桌面右键菜单)
            val infoLabel = stringResource(R.string.msg_info_title)
            val contextMenuItems = remember(
                msg.id, isUser, isLastAssistant,
                copyLabel, regenerateLabel, shareLabel, deleteLabel, infoLabel,
            ) {
                buildList {
                    if (msg.content.isNotBlank()) {
                        add(
                            ContextMenuItem(
                                label = copyLabel,
                                icon = TablerIcons.Copy,
                                onClick = { onCopyMessage(MoodSkinParser.cleanForExport(msg.content)) },
                            )
                        )
                    }
                    // 仅末尾 AI 消息提供"重新生成"
                    if (!isUser && isLastAssistant && msg.content.isNotEmpty()) {
                        add(
                            ContextMenuItem(
                                label = regenerateLabel,
                                icon = TablerIcons.Refresh,
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
                    // A5: 消息信息弹层(模型/耗时/Token 用量)
                    add(
                        ContextMenuItem(
                            label = infoLabel,
                            icon = TablerIcons.InfoCircle,
                            onClick = {
                                showDesktopContextMenu = false
                                showInfoSheet = true
                            },
                        )
                    )
                    add(
                        ContextMenuItem(
                            label = deleteLabel,
                            icon = TablerIcons.Trash,
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
        } // 闭合 Column

        // v1.0.72: 多选模式全尺寸遮罩 — 选择模式下点击消息任意位置(含文字/图片/代码块/引用块,
        // 这些子组件会消费点击事件)都切换选中。修复"进入选择模式后根本选不了消息"。
        if (selectionMode) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onToggleSelection?.invoke() },
            )
        }
    } // 闭合 Box
}

/**
 * v1.0.72: 长按操作卡片(Popup 定位,哪里按哪里弹出)。
 *
 * v1.0.72 迭代:
 *  - 固定配色(不随应用主题色):深色模式用深灰底白字,浅色用白底黑字,
 *    避免主题色导致分割线/背景不可见。
 *  - 整体紧凑:缩小图标底块 / 行高 / 圆角 / 间距。
 *  - scale+fade 进场动画。
 * 内容:引用 / 复制 / 选择文本 / 分享 / 编辑(仅用户消息) / 更多。
 */
@Composable
private fun TelegramActionCard(
    isUser: Boolean,
    onQuote: () -> Unit,
    onCopy: () -> Unit,
    onSelectText: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onMore: () -> Unit,
) {
    // C-15: 应用主题为三态(system/light/dark),isSystemInDarkTheme() 只认系统设置,
    // 与设置页三态不一致(用户在 light 主题下系统为暗色时会得到错误的固定配色)。
    // 改为按实际生效配色的亮度推断暗色。
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 固定配色(不随主题色)
    val bg = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val textColor = if (dark) Color(0xFFFFFFFF) else Color(0xFF000000)
    val iconBlock = if (dark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7)
    val divider = if (dark) Color(0xFF3A3A3C) else Color(0xFFE5E5EA)

    // 进场动画:scale 0.92 → 1 + fade
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(160, easing = FastOutSlowInEasing),
        label = "menuScale",
    )
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(140),
        label = "menuAlpha",
    )
    Surface(
        color = bg,
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 10.dp,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 170.dp, max = 210.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .padding(vertical = 5.dp),
        ) {
            FixedColorActionRow(Icons.AutoMirrored.Outlined.Reply, stringResource(R.string.message_action_quote), textColor, iconBlock, onQuote)
            FixedColorActionRow(TablerIcons.Copy, stringResource(R.string.action_copy), textColor, iconBlock, onCopy)
            FixedColorActionRow(TablerIcons.Square, stringResource(R.string.action_select_text), textColor, iconBlock, onSelectText)
            FixedColorActionRow(Icons.Outlined.Share, stringResource(R.string.chat_share_action), textColor, iconBlock, onShare)
            if (isUser) {
                FixedColorActionRow(TablerIcons.Edit, stringResource(R.string.action_edit), textColor, iconBlock, onEdit)
            }
            // 固定色细分割线
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .height(0.5.dp)
                    .background(divider),
            )
            FixedColorActionRow(Icons.Outlined.MoreHoriz, stringResource(R.string.action_more), textColor, iconBlock, onMore)
        }
    }
}

/** v1.0.72: 固定配色菜单行(紧凑:小图标底块 + 小行高 + 按压 scale)。 */
@Composable
private fun FixedColorActionRow(
    icon: ImageVector,
    text: String,
    textColor: Color,
    iconBlockColor: Color,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(100),
        label = "rowScale",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 小图标底块
        Surface(
            shape = RoundedCornerShape(9.dp),
            color = iconBlockColor,
            modifier = Modifier.size(30.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = text,
                    modifier = Modifier.size(16.dp),
                    tint = textColor,
                )
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
        )
    }
}
