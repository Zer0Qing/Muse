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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import io.zer0.muse.ui.common.WindowWidthClass
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.groupchat.GroupChatEntity
import io.zer0.muse.data.groupchat.GroupChatMessageEntity
import io.zer0.muse.ui.common.AssistantAvatar
import io.zer0.muse.ui.common.EmptyState
import io.zer0.muse.ui.common.IosCardPress
import io.zer0.muse.ui.common.rememberWindowWidthClass
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseHaptics
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
        // 顶部标题栏(标题 + 新建按钮)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = MusePaddings.screen, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.groupchat_list_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            IconButton(onClick = { showCreateDialog = true }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.groupchat_create_cd),
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        // v1.72: 首次加载时显示 loading,避免闪"还没有群聊"空状态
        if (state.isChatsLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (state.chats.isEmpty()) {
            // 空状态
            EmptyState(
                icon = Icons.Outlined.Forum,
                title = stringResource(R.string.groupchat_empty_title),
                subtitle = stringResource(R.string.groupchat_empty_subtitle),
                actionText = stringResource(R.string.groupchat_create_cd),
                onAction = { showCreateDialog = true },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = MusePaddings.screen,
                    end = MusePaddings.screen,
                    top = MusePaddings.itemGap / 2,
                    // v1.0.17: 底部留出悬浮胶囊空间,避免最后一条群聊被遮挡
                    bottom = 88.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            ) {
                // M10 已知限制: 每卡片独立 DB 查询(N+1),群聊数量少时影响小;
                // 后续群聊增多可改为批量查询最新消息 + 成员后一次性注入。
                items(
                    items = state.chats,
                    key = { it.id },
                ) { chat ->
                    val memberIds = remember(chat.memberIdsJson) { viewModel.parseMemberIds(chat) }
                    val members = remember(memberIds, state.assistants) {
                        memberIds.mapNotNull { id -> state.assistants.find { it.id == id } }
                    }
                    GroupChatCard(
                        chat = chat,
                        members = members,
                        memberCount = memberIds.size,
                        viewModel = viewModel,
                        onClick = { onOpenChat(chat.id) },
                        onTogglePin = { viewModel.togglePin(chat.id) },
                        onDelete = { viewModel.deleteChat(chat.id) },
                    )
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
 * 群聊卡片 — 展示群聊名 + 成员头像行 + 最新消息预览 + 时间。
 */
@Composable
private fun GroupChatCard(
    chat: GroupChatEntity,
    members: List<AssistantEntity>,
    memberCount: Int,
    viewModel: GroupChatViewModel,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    // 异步加载最新消息预览
    val latestMessage by produceState<GroupChatMessageEntity?>(
        initialValue = null,
        chat.id,
    ) {
        value = viewModel.getLatestMessage(chat.id)
    }

    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current

    // v1.0.17: 用 IosCardPress 替换 Card.combinedClickable,消除 Material ripple 黑色遮罩
    IosCardPress(
        onClick = onClick,
        onLongClick = {
            MuseHaptics.medium(hapticFeedback)
            showMenu = true
        },
        modifier = Modifier.fillMaxWidth(),
        shape = MuseShapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
        ) {
            // 第一行:群聊名 + 时间
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = chat.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatRelativeTime(chat.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.height(4.dp))

            // 第二行:最新消息预览
            Text(
                text = latestMessage?.body ?: stringResource(R.string.groupchat_no_messages),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))

            // 第三行:成员头像行
            MemberAvatarRow(members = members, totalCount = memberCount)
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
                }
            },
            onConfirm = null,
            dismissText = stringResource(R.string.groupchat_cancel),
            onDismiss = { showMenu = false },
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
 * 成员头像行 — 最多显示 3 个头像 + "+N"。
 */
@Composable
private fun MemberAvatarRow(
    members: List<AssistantEntity>,
    totalCount: Int,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val visibleCount = minOf(3, members.size)
        for (i in 0 until visibleCount) {
            AssistantAvatar(
                assistant = members[i],
                avatarSize = 24.dp,
            )
        }
        if (totalCount > visibleCount) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+${totalCount - visibleCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.groupchat_member_count, totalCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * 格式化相对时间(刚刚 / N分钟前 / HH:mm / MM-dd)。
 */
@Composable
private fun formatRelativeTime(timestamp: Long): String {
    // v1.71: 用 remember 缓存 SimpleDateFormat(必须在条件分支之前调用)
    val timeFmt = remember { SimpleDateFormat(MuseDateFormats.TIME_SHORT, Locale.getDefault()) }
    val dateFmt = remember { SimpleDateFormat(MuseDateFormats.DATE_SHORT, Locale.getDefault()) }
    // M-GC4 修复: 原先仅读一次 System.currentTimeMillis(),列表停留在屏幕上时"刚刚/N分钟前"不会
    // 随时间推移刷新。现用 produceState 每 60 秒触发一次重组,使相对时间自动更新。
    // produceState 协程在组合离开时自动取消,不会泄漏。
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }
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
