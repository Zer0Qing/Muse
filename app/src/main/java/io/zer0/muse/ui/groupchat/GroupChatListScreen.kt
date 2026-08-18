package io.zer0.muse.ui.groupchat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import io.zer0.muse.ui.common.media.WindowWidthClass
import io.zer0.muse.ui.common.museAnimateItem
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.groupchat.GroupChatEntity
import io.zer0.muse.data.groupchat.GroupChatMessageEntity
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.surface.MuseCardPress
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 群聊列表页 — 展示全部群聊,卡片点击进入详情。
 *
 * 设计(warm-paper 风格):
 *  - 顶部标题栏(标题 + 新建群聊按钮,不用 FAB)
 *  - LazyColumn 展示群聊卡片(群聊名 + 成员头像行 + 最新消息预览 + 时间)
 *  - 空状态:居中图标 + "还没有群聊" + "新建群聊"按钮
 *
 * @param onOpenChat 点击群聊卡片回调(参数为 chatId)
 * @param viewModel 群聊 ViewModel
 */
@Composable
fun GroupChatListScreen(
    onOpenChat: (String) -> Unit,
    viewModel: GroupChatViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // P2-1: 大屏(Expanded)下内容区居中限宽 720dp
    val widthClass = rememberWindowWidthClass()
    // 前端修复 (性能-4): 页面级共享时间 ticker — 单协程每 60s 广播一次
    // 当前时间戳,所有群聊卡片共用;替代原先每张卡片一个 produceState 无限循环。
    val timeTicker by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }

    // P2-1: Box 包裹,Expanded 模式下居中限宽
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (widthClass == WindowWidthClass.Expanded) {
                        Modifier.widthIn(max = 720.dp)
                    } else {
                        Modifier
                    }
                )
                .navigationBarsPadding(),
        ) {
        // v2.1: 移除独立标题栏(Tab 已标注"群聊"),直接展示列表
        // v1.72: 首次加载时显示 loading,避免闪"还没有群聊"空状态
        if (state.isChatsLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (state.chats.isEmpty()) {
            // 空状态：简洁的"新建群聊"磁贴
            Surface(
                onClick = { showCreateDialog = true },
                shape = MuseShapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MusePaddings.screen),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(MuseIconSizes.icon),
                    )
                    Text(
                        text = stringResource(R.string.groupchat_create_cd),  // "新建群聊"
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = MusePaddings.screen,
                    end = MusePaddings.screen,
                    top = MusePaddings.itemGap,
                    bottom = 88.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // v2.2: 页面标题"我的群聊"
                item(key = "page_title") {
                    Text(
                        text = stringResource(R.string.groupchat_list_title),
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        textAlign = TextAlign.Center,
                    )
                }
                items(
                    items = state.chats,
                    key = { it.id },
                ) { chat ->
                    // E5 (H4): 群聊列表项入场/位移动画
                    Box(museAnimateItem()) {
                        val memberIds = remember(chat.memberIdsJson) { viewModel.parseMemberIds(chat) }
                        val members = remember(memberIds, state.assistants) {
                            memberIds.mapNotNull { id -> state.assistants.find { it.id == id } }
                        }
                        GroupChatCard(
                            chat = chat,
                            members = members,
                            memberCount = memberIds.size,
                            now = timeTicker,
                            viewModel = viewModel,
                            onClick = { onOpenChat(chat.id) },
                            onTogglePin = { viewModel.togglePin(chat.id) },
                            onDelete = { viewModel.deleteChat(chat.id) },
                            onClearMemory = { viewModel.clearChatMemory(chat.id) },
                        )
                    }
                }
                // v2.2: 底部"新建群聊"按钮(参考图:左侧绿色加号圆圈 + 居中绿色文字)
                item(key = "create_new") {
                    MuseCardPress(
                        onClick = { showCreateDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MuseShapes.extraLarge,
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Add,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.groupchat_create_cd),
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
    }

    // 新建群聊对话框
    if (showCreateDialog) {
        CreateGroupChatDialog(
            assistants = state.assistants,
            teams = state.teams,
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, memberIds, teamId ->
                showCreateDialog = false
                scope.launch {
                    viewModel.createChat(name, memberIds, teamId)
                }
            },
        )
    }
}

/**
 * v2.2: 群聊卡片 — 参考图样式(平铺成员头像 | 群名+发送者前缀消息 | 时间+箭头)。
 */
@Composable
private fun GroupChatCard(
    chat: GroupChatEntity,
    members: List<AssistantEntity>,
    memberCount: Int,
    /** 前端修复 (性能-4): 页面级共享 ticker 时间戳,用于相对时间刷新。 */
    now: Long,
    viewModel: GroupChatViewModel,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onClearMemory: () -> Unit,
) {
    // 异步加载最新消息预览。
    // 前端修复 (性能-4): produceState 以 chat.id 为 key,协程只在首次组合 /
    // chat.id 变化时启动;页面级 ticker 触发的重组不会重启该协程,不会重复查 DB。
    val latestMessage by produceState<GroupChatMessageEntity?>(
        initialValue = null,
        chat.id,
    ) {
        value = viewModel.getLatestMessage(chat.id)
    }

    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showClearMemoryConfirm by remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current

    MuseCardPress(
        onClick = onClick,
        onLongClick = {
            MuseHaptics.medium(hapticFeedback)
            showMenu = true
        },
        modifier = Modifier.fillMaxWidth(),
        shape = MuseShapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧:成员头像平铺(最多4个,40dp,间距4dp)
            MemberAvatarRow(
                members = members,
                memberCount = memberCount,
                avatarSize = 40.dp,
            )
            Spacer(Modifier.width(14.dp))
            // 中间:群名 + 最新消息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chat.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = LatestMessagePreview(latestMessage),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            // 右侧:时间 + chevron
            Text(
                text = formatRelativeTime(chat.updatedAt, now),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            ChevronRight()
        }
    }

    // 长按菜单:置顶/取消置顶、删除
    if (showMenu) {
        MuseDialog(
            onDismissRequest = { showMenu = false },
            title = chat.name,
            content = {
                Column {
                    TextButton(onClick = {
                        showMenu = false
                        onTogglePin()
                    }) {
                        Text(if (chat.pinned) stringResource(R.string.groupchat_unpin) else stringResource(R.string.groupchat_pin))
                    }
                    TextButton(onClick = {
                        showMenu = false
                        showDeleteConfirm = true
                    }) {
                        Text(stringResource(R.string.groupchat_delete_chat), color = MaterialTheme.colorScheme.error)
                    }
                    // v1.0.72: 清空群聊记忆(风格残留清理,独立于主记忆系统)
                    TextButton(onClick = {
                        showMenu = false
                        showClearMemoryConfirm = true
                    }) {
                        Text(stringResource(R.string.groupchat_clear_memory), color = MaterialTheme.colorScheme.error) // 前端修复 (i18n-2)
                    }
                }
            },
            onConfirm = null,
            dismissText = stringResource(R.string.groupchat_cancel),
            onDismiss = { showMenu = false },
        )
    }

    // v1.0.72: 清空群聊记忆确认
    if (showClearMemoryConfirm) {
        MuseDialog(
            onDismissRequest = { showClearMemoryConfirm = false },
            title = stringResource(R.string.groupchat_clear_memory), // 前端修复 (i18n-2)
            content = {
                Text(
                    text = stringResource(R.string.groupchat_clear_memory_confirm), // 前端修复 (i18n-2)
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.groupchat_clear_memory_btn), // 前端修复 (i18n-2)
            onConfirm = {
                showClearMemoryConfirm = false
                onClearMemory()
            },
            dismissText = stringResource(R.string.groupchat_cancel),
            onDismiss = { showClearMemoryConfirm = false },
        )
    }

    // 删除确认
    if (showDeleteConfirm) {
        MuseDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = stringResource(R.string.groupchat_delete_chat),
            content = {
                Text(
                    text = stringResource(R.string.groupchat_delete_chat_confirm, chat.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.groupchat_delete),
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
            },
            dismissText = stringResource(R.string.groupchat_cancel),
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

/**
 * v2.2: 成员头像平铺行 — 最多显示4个,紧凑排列。
 */
@Composable
private fun MemberAvatarRow(
    members: List<AssistantEntity>,
    memberCount: Int,
    avatarSize: androidx.compose.ui.unit.Dp,
) {
    val visibleCount = minOf(4, members.size)
    Row(
        horizontalArrangement = Arrangement.spacedBy((-6).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until visibleCount) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(avatarSize),
            ) {
                AssistantAvatar(
                    assistant = members[i],
                    avatarSize = avatarSize,
                )
            }
        }
        if (memberCount > visibleCount) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(avatarSize),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "+${memberCount - visibleCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * v2.2: 最新消息预览 — 发送者简称 + 冒号 + 内容。
 */
@Composable
private fun LatestMessagePreview(message: GroupChatMessageEntity?): String {
    if (message == null) return stringResource(R.string.groupchat_no_messages)
    val prefix = when (message.senderType) {
        "user" -> stringResource(R.string.groupchat_sender_user)
        else -> message.senderName.take(1).ifBlank { "A" }
    }
    return "$prefix: ${message.body}"
}

/**
 * 格式化相对时间(刚刚 / N分钟前 / HH:mm / MM-dd)。
 *
 * 前端修复 (性能-4): now 由页面级共享 ticker 传入,卡片不再各自起
 * 60s 无限循环 produceState 协程;ticker 刷新时统一重算时间文本。
 */
@Composable
private fun formatRelativeTime(timestamp: Long, now: Long): String {
    // v1.71: 用 remember 缓存 SimpleDateFormat(必须在条件分支之前调用)
    val timeFmt = remember { SimpleDateFormat(MuseDateFormats.TIME_SHORT, Locale.getDefault()) }
    val dateFmt = remember { SimpleDateFormat(MuseDateFormats.DATE_SHORT, Locale.getDefault()) }
    if (timestamp <= 0) return ""
    val diff = now - timestamp
    val justNow = stringResource(R.string.groupchat_just_now)
    val minutesAgoFmt = stringResource(R.string.groupchat_minutes_ago)
    return when {
        diff < 60_000 -> justNow
        diff < 3_600_000 -> minutesAgoFmt.format(diff / 60_000)
        diff < 86_400_000 -> timeFmt.format(Date(timestamp))
        else -> dateFmt.format(Date(timestamp))
    }
}
