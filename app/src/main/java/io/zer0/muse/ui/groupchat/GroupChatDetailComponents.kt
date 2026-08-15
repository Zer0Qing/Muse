@file:Suppress(
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
    "CyclomaticComplexMethod",
    "TooManyFunctions",
    "UseCheckOrError",
)


@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package io.zer0.muse.ui.groupchat

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import android.content.ContentUris
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import androidx.compose.foundation.background
import android.content.Context
import androidx.compose.material.icons.filled.PhotoCamera
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.HowToVote
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import io.zer0.muse.ui.common.form.MuseChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import io.zer0.muse.ui.common.form.MuseTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.zer0.common.AppJson
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.groupchat.GroupChatMessageEntity
import io.zer0.muse.ui.SmartImage
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.markdown.MarkdownText
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.theme.userBubble  // v1.48 (h18): 气泡形状令牌
import io.zer0.muse.ui.theme.assistantBubble  // v1.48 (h18): 气泡形状令牌
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.ByteArrayOutputStream

/**
 * 群聊消息气泡 — 区分 user(右侧)/ assistant(左侧 + 头像)。
 *
 * @param message 消息实体
 * @param assistants 全部助手列表(查找头像用)
 * @param chatPrefs 聊天偏好(控制 mood/reasoning 显示与默认展开)
 * @param isMoodExpanded mood 块外部受控展开状态(null 表示使用默认)
 * @param isReasoningExpanded reasoning 块外部受控展开状态(null 表示使用默认)
 * @param onToggleMoodExpanded mood 块展开切换回调
 * @param onToggleReasoningExpanded reasoning 块展开切换回调
 */
@Composable
internal fun GroupChatMessageBubble(
    message: GroupChatMessageEntity,
    assistants: List<AssistantEntity>,
    chatPrefs: io.zer0.muse.data.ChatPreferences = io.zer0.muse.data.ChatPreferences(),
    isMoodExpanded: Boolean? = null,
    isReasoningExpanded: Boolean? = null,
    onToggleMoodExpanded: () -> Unit = {},
    onToggleReasoningExpanded: () -> Unit = {},
    // v1.77: 长按弹出操作菜单(复制 / 删除)
    onLongClick: () -> Unit = {},
    // v1.0.74 fix (前端审计 1.4): 搜索跳转高亮 — 目标消息短暂高亮提示用户定位
    highlighted: Boolean = false,
    // v1.x: 多选模式
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onSelectToggle: () -> Unit = {},
    /** HTML/SVG 代码块全屏预览回调。 */
    onHtmlPreview: (String) -> Unit = {},
) {
    val isUser = message.senderType == "user"
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    // v1.0.74 fix (前端审计 6.1): 气泡最大宽按屏宽 70%,大屏/横屏/大字体下不再过窄
    val maxBubbleWidth = with(androidx.compose.ui.platform.LocalConfiguration.current) {
        (screenWidthDp * 0.7f).dp
    }
    // v1.x: 多选模式下点击消息切换选中,长按退出菜单
    val bubbleClick: () -> Unit = { if (selectionMode) onSelectToggle() }
    if (isUser) {
        // 用户消息:右侧气泡
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // v1.0.74 fix: 搜索跳转高亮
                .then(
                    if (highlighted) Modifier
                        .clip(MuseShapes.semiLarge)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                    else Modifier
                ),
            horizontalArrangement = Arrangement.End,
        ) {
            Column(
                // v1.0.74 fix (前端审计 6.1): 屏宽 70% 替代固定 280dp
                modifier = Modifier.widthIn(max = maxBubbleWidth),
                horizontalAlignment = Alignment.End,
            ) {
                // v1.0.29: combinedClickable 移到 Surface 上,避免 MarkdownText/Text 消费触摸事件
                // 导致 Row 层 combinedClickable 长按不触发(只能长按空白区域)
                Surface(
                    shape = MuseShapes.userBubble,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = if (selected) androidx.compose.foundation.BorderStroke(
                        1.5.dp, MaterialTheme.colorScheme.primary,
                    ) else null,
                    modifier = Modifier.combinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = bubbleClick,
                        onLongClick = {
                            MuseHaptics.medium(haptic)
                            onLongClick()
                        },
                    ),
                ) {
                    Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                        // v2.x: 悄悄话标记
                        if (message.whisperTargetId != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(12.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.groupchat_message_whisper),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                        Text(
                            text = message.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        // 图片附件
                        MessageImageGrid(
                            imageBase64Json = message.imageBase64Json,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    } else {
        // Agent 消息:左侧 + 头像 + senderName
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // v1.0.74 fix: 搜索跳转高亮
                .then(
                    if (highlighted) Modifier
                        .clip(MuseShapes.semiLarge)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                    else Modifier
                ),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Top,
        ) {
            // 头像(也可长按触发菜单)
            val assistant = remember(message.senderId, assistants) {
                assistants.find { it.id == message.senderId }
            }
            val avatarInteraction = remember { MutableInteractionSource() }
            if (assistant != null) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .combinedClickable(
                            interactionSource = avatarInteraction,
                            indication = null,
                            onClick = {},
                            onLongClick = {
                                MuseHaptics.medium(haptic)
                                onLongClick()
                            },
                        ),
                ) {
                    AssistantAvatar(
                        assistant = assistant,
                        avatarSize = 32.dp,
                    )
                }
            } else {
                // 兜底:首字母圆形头像
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .combinedClickable(
                            interactionSource = avatarInteraction,
                            indication = null,
                            onClick = {},
                            onLongClick = {
                                MuseHaptics.medium(haptic)
                                onLongClick()
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = message.senderName.firstOrNull()?.toString() ?: "A",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // 气泡
            // v1.0.74 fix (前端审计 6.1): 屏宽 70% 替代固定 280dp
            Column(modifier = Modifier.widthIn(max = maxBubbleWidth)) {
                Text(
                    text = message.senderName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
                // v1.46: MOOD 标签胶囊(Agent 内部腹稿,可折叠)
                if (chatPrefs.showMoodBlock) {
                    message.mood?.takeIf { it.isNotBlank() }?.let { mood ->
                        val moodExpanded = isMoodExpanded ?: chatPrefs.moodExpandedByDefault
                        MoodCapsule(
                            mood = mood,
                            expanded = moodExpanded,
                            onToggle = onToggleMoodExpanded,
                        )
                    }
                }
                // v1.46: 思考过程块(可折叠)
                if (chatPrefs.showReasoning) {
                    message.reasoning?.takeIf { it.isNotBlank() }?.let { reasoning ->
                        val reasoningExpanded = isReasoningExpanded ?: chatPrefs.reasoningExpandedByDefault
                        GroupChatExpandableBlock(
                            title = stringResource(R.string.groupchat_reasoning),
                            content = reasoning,
                            expanded = reasoningExpanded,
                            onToggle = onToggleReasoningExpanded,
                            titleColor = MaterialTheme.colorScheme.outline,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                }
                // v1.0.29: combinedClickable 移到 Surface 上,避免 MarkdownText 消费触摸事件
                Surface(
                    shape = MuseShapes.assistantBubble,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    border = if (selected) androidx.compose.foundation.BorderStroke(
                        1.5.dp, MaterialTheme.colorScheme.primary,
                    ) else null,
                    modifier = Modifier.combinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = bubbleClick,
                        onLongClick = {
                            MuseHaptics.medium(haptic)
                            onLongClick()
                        },
                    ),
                ) {
                    Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                        MarkdownText(
                            text = message.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            onHtmlPreview = onHtmlPreview,
                        )
                        // 图片附件
                        MessageImageGrid(
                            imageBase64Json = message.imageBase64Json,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * MOOD 标签胶囊 — 浅绿色背景,品牌色文字,可展开查看完整腹稿。
 */
@Composable
internal fun MoodCapsule(
    mood: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MuseShapes.medium,
        modifier = Modifier
            .padding(bottom = 6.dp)
            .clickable { onToggle() },
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "MOOD",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) {
                        stringResource(R.string.groupchat_collapse)
                    } else {
                        stringResource(R.string.groupchat_expand)
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconTiny),
                )
            }
            if (expanded) {
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

/**
 * 群聊可折叠块 — 用于 MOOD / 思考过程。
 */
@Composable
internal fun GroupChatExpandableBlock(
    title: String,
    content: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    titleColor: Color,
    containerColor: Color,
) {
    Surface(
        color = containerColor,
        shape = MuseShapes.medium,
        tonalElevation = 0.dp,
        modifier = Modifier
            .widthIn(max = 360.dp)
            .padding(bottom = 6.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(vertical = MusePaddings.tinyGap),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = titleColor,
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) {
                        stringResource(R.string.groupchat_collapse)
                    } else {
                        stringResource(R.string.groupchat_expand)
                    },
                    tint = titleColor,
                    modifier = Modifier.size(MuseIconSizes.iconTiny),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 消息图片网格 — 在气泡内展示用户/Agent 发送的图片附件。
 */
@Composable
internal fun MessageImageGrid(
    imageBase64Json: String,
    modifier: Modifier = Modifier,
) {
    val images = remember(imageBase64Json) {
        // L4: 用 resultOf 替代 runCatching,getOrNull 替代 getOrDefault
        resultOf {
            AppJson.decodeFromString(ListSerializer(String.serializer()), imageBase64Json)
        }.getOrNull() ?: emptyList()
    }
    if (images.isEmpty()) return
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        images.forEach { image ->
            SmartImage(
                model = image,
                contentDescription = stringResource(R.string.groupchat_image),
                modifier = Modifier
                    .size(120.dp)
                    .clip(MuseShapes.semiLarge),
            )
        }
    }
}

/**
 * "正在思考..."等待状态 — Agent 回复期间显示。
 * v1.104: 若 currentSpeaker 非空,显示"XXX 正在思考..."并高亮其头像;
 *         为空时回退到通用"正在思考..."(向后兼容)。
 */
@Composable
internal fun ThinkingIndicator(currentSpeaker: AssistantEntity? = null) {
    val displayName = currentSpeaker?.name?.takeIf { it.isNotBlank() }
    val thinkingText = if (displayName != null) {
        stringResource(R.string.groupchat_agent_thinking, displayName)
    } else {
        stringResource(R.string.groupchat_thinking)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(MuseIconSizes.iconLarge)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
        }
        Spacer(Modifier.width(8.dp))
        Surface(
            // v1.48 (h18): 用 BubbleShape 令牌统一气泡圆角(原 4/18/18/18 → 6/20/20/20)
            shape = MuseShapes.assistantBubble,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ) {
            Row(
                modifier = Modifier.padding(MusePaddings.cardInnerMedium),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,  // v1.115: 显式指定,深色模式可见性
                )
                Text(
                    text = thinkingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 群聊输入栏 — 加号菜单 + 文本输入 + 发送按钮。
 *
 * @param text 当前输入文本
 * @param onTextChange 文本变化回调
 * @param onSend 发送回调
 * @param onOpenToolSheet 打开加号菜单回调
 * @param enabled 是否可用(Agent 回复期间禁用)
 * @param canSend 是否可以发送
 * @param members 群聊成员列表(@mention 自动补全用)
 */
@Composable
internal fun GroupChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onOpenToolSheet: () -> Unit,
    enabled: Boolean,
    canSend: Boolean,
    members: List<AssistantEntity> = emptyList(),
) {
    // @mention 自动补全状态:用户点击外部关闭后置 false,再次输入 @ 触发时置 true
    var showMentionDropdown by remember { mutableStateOf(false) }
    // 从文本末尾找最后一个 @,若 @ 后无空白则为有效 mention 查询(按 既有实现 channel-mentions)
    val mentionQuery: String? = remember(text) {
        val atIndex = text.lastIndexOf('@')
        if (atIndex < 0) return@remember null
        val afterAt = text.substring(atIndex + 1)
        // @ 后无空白字符 — 有效 mention 查询(空字符串表示刚输入 @)
        if (afterAt.isEmpty() || !afterAt.any { it.isWhitespace() }) afterAt else null
    }
    // 用户输入新 @ 时重新打开下拉
    LaunchedEffect(mentionQuery) {
        if (mentionQuery != null) showMentionDropdown = true
    }
    // 过滤匹配成员(按名称包含查询文本,大小写不敏感;长 alias 优先排序)
    val filteredMembers = remember(mentionQuery, members) {
        if (mentionQuery == null) {
            emptyList()
        } else if (mentionQuery.isEmpty()) {
            members.sortedByDescending { it.name.length }
        } else {
            members
                .filter { it.name.contains(mentionQuery, ignoreCase = true) }
                .sortedByDescending { it.name.length }
        }
    }
    // v1.0.28: 输入框重写,对齐任务页面样式(MuseTextField + 圆形按钮在同一行,去掉外层 Surface 色块)
    // v1.0.72: Telegram 风格大岛 — 加号菜单 + 输入框 + 发送全部包裹在圆角大栏里(与单聊输入栏一致)
    // v1.0.72: 做回岛样式(实色背景 + 圆角 + 阴影)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = io.zer0.muse.ui.theme.MuseElevation.low,
        shadowElevation = io.zer0.muse.ui.theme.MuseShadow.low.elevation,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .navigationBarsPadding()
            // v1.0.72: 两侧留白(缩小到 8dp) + 悬浮间距
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Box {
            // @mention 自动补全下拉(锚定在输入框上方)
            DropdownMenu(
                expanded = showMentionDropdown && filteredMembers.isNotEmpty(),
                onDismissRequest = { showMentionDropdown = false },
            ) {
                filteredMembers.take(8).forEach { member ->
                    DropdownMenuItem(
                        text = { Text(member.name) },
                        onClick = {
                            // 替换 @query 为 @memberName(末尾加空格,便于继续输入)
                            val atIndex = text.lastIndexOf('@')
                            if (atIndex >= 0) {
                                val newText = text.substring(0, atIndex) + "@${member.name} "
                                onTextChange(newText)
                            }
                            showMentionDropdown = false
                        },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MusePaddings.contentGap, vertical = MusePaddings.compactChipVertical),
                // v1.0.72 fix: Bottom 对齐导致加号/发送按钮视觉"歪"(多行输入时),
                // 改为 CenterVertically 与单聊输入栏一致
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                    // 加号菜单入口(保留,但改为小型图标按钮,不再用大圆形 Surface)
                    IconButton(
                        onClick = onOpenToolSheet,
                        enabled = enabled,
                        modifier = Modifier.size(MuseIconSizes.touchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.groupchat_tools),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.icon),
                        )
                    }
                    MuseTextField(
                        value = text,
                        onValueChange = onTextChange,
                        placeholder = { Text(stringResource(R.string.groupchat_input_placeholder)) },
                        enabled = enabled,
                        // v1.0.72: 输入框背景透明(岛背景即容器)
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        modifier = Modifier.weight(1f),
                        maxLines = 4,
                        singleLine = false,
                    )
                    // 发送按钮(保留,改为小型图标按钮)
                    IconButton(
                        onClick = onSend,
                        enabled = enabled && canSend,
                        modifier = Modifier.size(MuseIconSizes.touchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.groupchat_send),
                            tint = if (enabled && canSend) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                            },
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    }
                }
        }
    }
}

/**
 * 群聊活动状态栏 — 展示当前轮转中各 agent 的状态 chip。
 *
 * IDLE 状态被过滤不显示;无活动时整个栏不占空间。
 * 既有实现 ActivityHub UI:紧凑横向 chip 行,每个 chip 含图标 + 名字 + 状态文案;
 * REPLYING 态加呼吸动画,ERROR 态用错误配色,NO_REPLY 态弱化展示。
 *
 * @param activities 当前群聊中各 agent 的活动列表
 */
@Composable
internal fun AgentActivityBar(activities: List<AgentActivity>) {
    // 过滤掉 IDLE 状态(默认空闲,不展示)
    val visible = activities.filter { it.status != AgentActivityStatus.IDLE }
    if (visible.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = MusePaddings.screen, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            visible.forEach { activity ->
                AgentActivityChip(activity)
            }
        }
    }
}

/**
 * 单个 agent 活动状态 chip — 图标 + 名字 + 状态文案,配色随状态变化。
 *
 * REPLYING 态使用 [rememberInfiniteTransition] 做呼吸 alpha 动画,让用户感知"正在输出"。
 */
@Composable
internal fun AgentActivityChip(activity: AgentActivity) {
    val bgColor = when (activity.status) {
        AgentActivityStatus.VIEWING -> MaterialTheme.colorScheme.secondaryContainer
        AgentActivityStatus.REPLYING -> MaterialTheme.colorScheme.primaryContainer
        AgentActivityStatus.NO_REPLY -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        AgentActivityStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
        AgentActivityStatus.IDLE -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (activity.status) {
        AgentActivityStatus.VIEWING -> MaterialTheme.colorScheme.onSecondaryContainer
        AgentActivityStatus.REPLYING -> MaterialTheme.colorScheme.onPrimaryContainer
        AgentActivityStatus.NO_REPLY -> MaterialTheme.colorScheme.onSurfaceVariant
        AgentActivityStatus.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        AgentActivityStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = when (activity.status) {
        AgentActivityStatus.VIEWING -> Icons.Filled.Visibility
        AgentActivityStatus.REPLYING -> Icons.Filled.Edit
        AgentActivityStatus.NO_REPLY -> Icons.Filled.Block
        AgentActivityStatus.ERROR -> Icons.Filled.ErrorOutline
        AgentActivityStatus.IDLE -> Icons.Filled.Block
    }
    val statusLabel = when (activity.status) {
        AgentActivityStatus.VIEWING -> stringResource(R.string.groupchat_activity_viewing)
        AgentActivityStatus.REPLYING -> stringResource(R.string.groupchat_activity_replying)
        AgentActivityStatus.NO_REPLY -> stringResource(R.string.groupchat_activity_no_reply)
        AgentActivityStatus.ERROR -> stringResource(R.string.groupchat_activity_error)
        AgentActivityStatus.IDLE -> ""
    }
    // REPLYING 态呼吸动画:alpha 在 1f↔0.5f 间循环,让 chip 有"正在输出"的视觉反馈
    // v1.0.74 fix (前端审计 6.4): 仅 REPLYING 时创建无限动画,
    // 非 REPLYING 状态不跑动画帧(原实现无条件创建,静止时也每帧驱动)。
    val pulseAlpha = if (activity.status == AgentActivityStatus.REPLYING) {
        val infiniteTransition = rememberInfiniteTransition(label = "activity_pulse")
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.5f,
            animationSpec = infiniteRepeatable(
                animation = tween(800),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse_alpha",
        ).value
    } else {
        1f
    }
    val chipAlpha = if (activity.status == AgentActivityStatus.REPLYING) pulseAlpha else 1f
    Surface(
        shape = MuseShapes.semiLarge,
        color = bgColor,
        contentColor = contentColor,
        modifier = Modifier.alpha(chipAlpha),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            val agentInitial = remember(activity.assistantName) {
                activity.assistantName.firstOrNull()?.toString() ?: ""
            }
            Text(
                text = "$agentInitial $statusLabel",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

/**
 * 群聊加号菜单 — v1.0.72: 媒体区(相机实时预览 + 相册缩略图) + 横滚 tab。
 *
 * 功能:相机拍照 / 相册最近图片 / 图片 / 附件 / 知识库 / Prompt模板 / 成员 / 表决 / 总结 / 上下文 / @提及 / 编辑群聊
 */
@Composable
internal fun GroupChatToolSheet(
    onPickImage: () -> Unit,
    onPickDocument: () -> Unit,
    onInsertKnowledge: () -> Unit,
    onPickPromptTemplate: () -> Unit,
    onOpenMembers: () -> Unit,
    onLaunchVote: () -> Unit,
    onLaunchSummary: () -> Unit,
    onOpenContext: () -> Unit,
    onMentionMember: () -> Unit,
    onEditGroup: () -> Unit,
    // v1.0.72: 媒体区(相机 + 相册)
    hasGalleryPermission: Boolean = false,
    galleryPermission: String = android.Manifest.permission.READ_MEDIA_IMAGES,
    onRequestGalleryPermission: () -> Unit = {},
    onPickGalleryImage: (Uri) -> Unit = {},
    onCaptureImage: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // 相机预览权限
    val cameraPermission = android.Manifest.permission.CAMERA
    val hasCameraPermission = remember {
        ContextCompat.checkSelfPermission(context, cameraPermission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    var cameraGranted by remember { mutableStateOf(hasCameraPermission) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> cameraGranted = granted }

    // 相册最近图片
    var recentImages by remember { mutableStateOf<List<Uri>>(emptyList()) }
    LaunchedEffect(hasGalleryPermission) {
        if (hasGalleryPermission) {
            recentImages = withContext(Dispatchers.IO) {
                queryRecentGalleryImages(context, 6)
            }
        }
    }

    MuseBottomSheet(
        onDismissRequest = onDismiss,
        // v1.0.72: 群聊加号菜单左右不留白
        horizontalPadding = 0.dp,
    ) {
        Text(
            text = stringResource(R.string.groupchat_pick_content),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(12.dp))
        // v1.0.72: 媒体区 — 相机实时预览 + 相册缩略图(与单聊加号菜单统一)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(128.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = MusePaddings.tightGap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
        ) {
            // 相机实时预览(第一格)
            if (cameraGranted) {
                CameraLivePreviewBox(
                    modifier = Modifier.size(128.dp).clip(MuseShapes.extraLarge),
                    onTap = onCaptureImage,
                )
            } else {
                GroupMediaCard(
                    icon = Icons.Default.PhotoCamera,
                    label = stringResource(R.string.chat_tool_camera),
                    modifier = Modifier.size(128.dp),
                ) { cameraPermissionLauncher.launch(cameraPermission) }
            }
            // 相册最近图片缩略图
            if (hasGalleryPermission) {
                recentImages.forEach { uri ->
                    AsyncImage(
                        model = uri,
                        contentDescription = stringResource(R.string.chat_gallery_image_cd),
                        modifier = Modifier
                            .size(128.dp)
                            .clip(MuseShapes.extraLarge)
                            .clickable { onPickGalleryImage(uri) },
                        contentScale = ContentScale.Crop,
                    )
                }
            } else {
                GroupMediaCard(
                    icon = Icons.Default.Photo,
                    label = stringResource(R.string.chat_authorize_gallery),
                    modifier = Modifier.size(128.dp),
                ) { onRequestGalleryPermission() }
            }
        }
        Spacer(Modifier.height(16.dp))
        // 横滚 tab(圆形图标 + 下方文字,与单聊加号菜单统一)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = MusePaddings.tightGap)
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.screen),
        ) {
            GroupToolTab(Icons.Default.Photo, stringResource(R.string.groupchat_image)) {
                onPickImage(); onDismiss()
            }
            GroupToolTab(TablerIcons.Paperclip, stringResource(R.string.chat_tool_attachment)) {
                onPickDocument(); onDismiss()
            }
            GroupToolTab(TablerIcons.Book, stringResource(R.string.chat_tool_knowledge)) {
                onInsertKnowledge(); onDismiss()
            }
            GroupToolTab(TablerIcons.Template, stringResource(R.string.chat_prompt_templates_title)) {
                onPickPromptTemplate(); onDismiss()
            }
            GroupToolTab(TablerIcons.Users, stringResource(R.string.groupchat_tool_members)) {
                onOpenMembers(); onDismiss()
            }
            GroupToolTab(Icons.Filled.HowToVote, stringResource(R.string.groupchat_tool_vote)) {
                onLaunchVote(); onDismiss()
            }
            GroupToolTab(Icons.Filled.Summarize, stringResource(R.string.groupchat_tool_summary)) {
                onLaunchSummary(); onDismiss()
            }
            GroupToolTab(TablerIcons.Folder, stringResource(R.string.groupchat_tool_context)) {
                onOpenContext(); onDismiss()
            }
            GroupToolTab(TablerIcons.At, stringResource(R.string.groupchat_tool_mention)) {
                onMentionMember(); onDismiss()
            }
            GroupToolTab(TablerIcons.Edit, stringResource(R.string.groupchat_edit_cd)) {
                onEditGroup(); onDismiss()
            }
        }
    }
}

/** v1.0.72: 群聊加号菜单横滚 tab(圆形图标 + 下方独立文字)。 */
@Composable
private fun GroupToolTab(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(min = 60.dp)
            .clip(MuseShapes.extraLarge)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(44.dp),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
    }
}

/** v1.112: 群聊工具菜单行(iOS 风格左图标 + 标题/副标题 + 右箭头)。 */
@Composable
internal fun GroupChatToolRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = MuseShapes.semiLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(MuseIconSizes.icon),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** v1.112: 群聊工具菜单分隔线。 */
@Composable
internal fun GroupChatToolDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        thickness = 0.5.dp,
    )
}

/**
 * 待发送图片预览行 — 可点击右上角删除。
 */
@Composable
internal fun PendingImagesRow(
    images: List<String>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        images.forEach { image ->
            Box(modifier = Modifier.size(64.dp)) {
                io.zer0.muse.ui.SmartImage(
                    model = image,
                    contentDescription = stringResource(R.string.groupchat_pending_image),
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(MuseShapes.semiLarge),
                )
                // v1.0.52: 按 InputBar 的 iOS 风格小圆点设计,避免 48dp 大圆覆盖整张照片。
                // 视觉尺寸 20dp,实际触摸目标 32dp(可点击区域略大于视觉,保证易点)。
                // offset 偏移到图片右上角外侧,不遮挡图片内容。
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-6).dp)
                        .size(32.dp)
                        .clickable { onRemove(image) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.groupchat_delete),
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 成员列表对话框。
 */
@Composable
internal fun MembersDialog(
    memberNames: List<String>,
    memberCount: Int,
    onDismiss: () -> Unit,
) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.groupchat_members_title, memberCount),
        content = {
            if (memberNames.isEmpty()) {
                Text(
                    text = stringResource(R.string.groupchat_no_members),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    memberNames.forEach { name ->
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmText = stringResource(R.string.groupchat_close),
        onConfirm = onDismiss,
        dismissText = null,
    )
}

/**
 * v1.97: 编辑群聊对话框 — 改名 + 改成员。
 *
 * 按 [CreateGroupChatDialog] 的样式,预填当前群聊名与已选成员,
 * 保存时调用 [GroupChatViewModel.updateChat]。
 *
 * @param initialName 当前群聊名(预填)
 * @param assistants 全部助手列表(成员候选)
 * @param initialMemberIds 当前成员 id 列表(预选)
 * @param onDismiss 关闭回调
 * @param onConfirm 确认回调(newName, newMemberIds)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditGroupChatDialog(
    // v1.0.74 fix (前端审计 6.3): 稳定 key — 用 chatId 而非 initialMemberIds,
    // 否则群聊元数据 Flow 刷新时 key 变化重置用户编辑中的成员/模式选择。
    dialogKey: String,
    initialName: String,
    assistants: List<AssistantEntity>,
    initialMemberIds: List<String>,
    initialDiscussionMode: String = "round_robin",
    initialAutoMaxRounds: Int = 5,
    initialHostId: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (
        newName: String,
        newMemberIds: List<String>,
        newDiscussionMode: String,
        newAutoMaxRounds: Int,
        newHostId: String?,
    ) -> Unit,
) {
    // v1.97: 用 rememberSaveable 持久化编辑中的状态,旋转屏不丢
    // v1.0.74 fix (前端审计 6.3): key 统一用 dialogKey(chatId),避免元数据刷新重置编辑
    var name by rememberSaveable(dialogKey) { mutableStateOf(initialName) }
    var selectedMemberIds by rememberSaveable(dialogKey) {
        mutableStateOf(initialMemberIds.toSet())
    }
    var showErrors by rememberSaveable(dialogKey) { mutableStateOf(false) }
    // v2.x: 讨论模式状态
    var discussionMode by rememberSaveable(dialogKey) { mutableStateOf(initialDiscussionMode) }
    var autoMaxRounds by rememberSaveable(dialogKey) { mutableStateOf(initialAutoMaxRounds) }
    var hostId by rememberSaveable(dialogKey) { mutableStateOf(initialHostId ?: "") }

    val maxNameLength = 30
    val nameError = showErrors && name.isBlank()
    val memberError = showErrors && selectedMemberIds.isEmpty()

    // 讨论模式列表
    val modeOptions = listOf(
        "round_robin" to R.string.groupchat_mode_round_robin,
        "auto" to R.string.groupchat_mode_auto,
        "debate" to R.string.groupchat_mode_debate,
        "host" to R.string.groupchat_mode_host,
    )
    val modeDescMap = mapOf(
        "round_robin" to R.string.groupchat_mode_round_robin_desc,
        "auto" to R.string.groupchat_mode_auto_desc,
        "debate" to R.string.groupchat_mode_debate_desc,
        "host" to R.string.groupchat_mode_host_desc,
    )

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.groupchat_edit_title),
        content = {
            // 群聊名输入框
            MuseTextField(
                value = name,
                onValueChange = { newName ->
                    showErrors = false
                    name = newName.take(maxNameLength)
                },
                label = { Text(stringResource(R.string.groupchat_edit_name_hint)) },
                singleLine = true,
                isError = nameError,
                supportingText = if (nameError) {
                    { Text(stringResource(R.string.groupchat_name_required), color = MaterialTheme.colorScheme.error) }
                } else {
                    { Text("${name.length}/$maxNameLength") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            // 成员选择
            Text(
                text = stringResource(R.string.groupchat_select_members),
                style = MaterialTheme.typography.labelMedium,
                color = if (memberError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            if (assistants.isEmpty()) {
                Text(
                    text = stringResource(R.string.groupchat_no_assistants),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    assistants.forEach { assistant ->
                        val selected = assistant.id in selectedMemberIds
                        MuseChip(
                            selected = selected,
                            onClick = {
                                showErrors = false
                                selectedMemberIds = if (selected) {
                                    selectedMemberIds - assistant.id
                                } else {
                                    selectedMemberIds + assistant.id
                                }
                            },
                            label = assistant.name,
                        )
                    }
                }
            }
            if (memberError) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.groupchat_edit_member_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.groupchat_selected_members, selectedMemberIds.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth(),
            )

            // ── v2.x: 讨论模式选择 ──
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.groupchat_mode_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                modeOptions.forEach { (mode, labelRes) ->
                    MuseChip(
                        selected = discussionMode == mode,
                        onClick = { discussionMode = mode },
                        label = stringResource(labelRes),
                    )
                }
            }
            // 当前模式描述
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(modeDescMap[discussionMode] ?: R.string.groupchat_mode_round_robin_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )

            // Auto 模式:最大轮数滑块
            if (discussionMode == "auto") {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.groupchat_mode_auto_rounds),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "$autoMaxRounds",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Slider(
                    value = autoMaxRounds.toFloat(),
                    onValueChange = { autoMaxRounds = it.toInt().coerceIn(1, 20) },
                    valueRange = 1f..20f,
                    steps = 18,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 主持人模式:选择主持人
            if (discussionMode == "host") {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.groupchat_mode_select_host),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // "不选"选项
                    MuseChip(
                        selected = hostId.isBlank(),
                        onClick = { hostId = "" },
                        label = stringResource(R.string.groupchat_mode_no_host),
                    )
                    // 只能选已选成员做主持人
                    assistants.filter { it.id in selectedMemberIds }.forEach { assistant ->
                        MuseChip(
                            selected = hostId == assistant.id,
                            onClick = { hostId = assistant.id },
                            label = assistant.name,
                        )
                    }
                }
            }
        },
        confirmText = stringResource(R.string.groupchat_edit_btn),
        onConfirm = {
            val trimmedName = name.trim()
            if (trimmedName.isNotBlank() && selectedMemberIds.isNotEmpty()) {
                onConfirm(
                    trimmedName,
                    selectedMemberIds.toList(),
                    discussionMode,
                    autoMaxRounds,
                    hostId.ifBlank { null },
                )
            } else {
                showErrors = true
            }
        },
        dismissText = stringResource(R.string.groupchat_cancel),
        onDismiss = onDismiss,
    )
}

/**
 * H5: 把 Bitmap 编码为 base64 字符串。
 *
 * 用 JPEG 递减 quality 压缩,直到 base64 字符串不超过 2MB。
 * 最终仍超过 2MB 则抛异常,提示用户选择较小图片。
 */
internal fun readImageBytes(context: android.content.Context, uri: Uri): ByteArray {
    val stream = context.contentResolver.openInputStream(uri)
    if (stream != null) return stream.use { it.readBytes() }
    val fd = context.contentResolver.openFileDescriptor(uri, "r")
        ?: throw IllegalStateException("无法读取图片")
    return fd.use { java.io.FileInputStream(it.fileDescriptor).use { fis -> fis.readBytes() } }
}

internal fun encodeBitmapToBase64(bitmap: Bitmap): String {
    val maxBase64Length = 2 * 1024 * 1024 // 2MB(base64 字符串长度上限)
    val baos = ByteArrayOutputStream()
    var quality = 90
    var base64: String
    do {
        baos.reset()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        base64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.DEFAULT)
        if (base64.length > maxBase64Length) {
            quality -= 10
        } else {
            break
        }
    } while (quality >= 20)

    if (base64.length > maxBase64Length) {
        throw IllegalStateException("图片过大,请选择较小图片")
    }
    return base64
}

/** v1.0.72: 群聊加号菜单媒体卡(相机/授权占位)。 */
@Composable
private fun GroupMediaCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

/** v1.0.72: 相机实时取景预览(CameraX,复用单聊加号菜单实现)。 */
@Composable
private fun CameraLivePreviewBox(
    modifier: Modifier,
    onTap: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnTap by rememberUpdatedState(onTap)
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                setOnClickListener { currentOnTap() }
            }
            runCatching {
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    runCatching {
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                    }.onFailure { e -> Logger.w("GroupChatToolSheet", "相机预览绑定失败", e) }
                }, ContextCompat.getMainExecutor(ctx))
            }.onFailure { e -> Logger.w("GroupChatToolSheet", "相机预览初始化失败", e) }
            previewView
        },
        modifier = modifier,
    )
}

/** v1.0.72: 查询系统相册最近图片。 */
private fun queryRecentGalleryImages(context: Context, maxCount: Int): List<Uri> {
    return runCatching {
        val uris = mutableListOf<Uri>()
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, sortOrder,
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
    }.onFailure { e -> Logger.w("GroupChatToolSheet", "查询相册失败", e) }.getOrDefault(emptyList())
}