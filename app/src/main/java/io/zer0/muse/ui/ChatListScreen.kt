package io.zer0.muse.ui

import io.zer0.muse.ui.common.EmptyState
import io.zer0.muse.ui.common.IosCardPress
import io.zer0.muse.ui.common.LoadingState
import io.zer0.muse.ui.common.MuseToast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.session.FolderEntity
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.huge
import io.zer0.muse.ui.theme.mega
import io.zer0.muse.ui.theme.pill
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatListScreen(
    sessions: List<SessionEntity>,
    folders: List<FolderEntity>,
    currentSessionId: String?,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onDelete: (String) -> Unit,
    onRename: (SessionEntity) -> Unit,
    /** v1.48: 重命名(带新名字),修复旧实现传 session.title 导致重命名失效的 bug。 */
    onRenameTo: (SessionEntity, String) -> Unit = { s, _ -> onRename(s) },
    onTogglePinned: (String) -> Unit,
    onMoveSessionToFolder: (String, String?) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onToggleFolderExpanded: (String, Boolean) -> Unit,
    assistants: List<AssistantEntity> = emptyList(),
    currentAssistant: AssistantEntity? = null,
    /** v0.45: 已归档会话列表(历史数据保留,当前 UI 不再展示归档入口)。 */
    archivedSessions: List<SessionEntity> = emptyList(),
    /** v0.45: 归档会话(主列表项长按菜单用)。 */
    onArchive: (String) -> Unit = {},
    /** v0.45: 取消归档(归档列表项长按菜单用)。 */
    onUnarchive: (String) -> Unit = {},
    onOpenScheduledTasks: () -> Unit = {},
    onOpenQuickNotes: () -> Unit = {},
    /** v1.0.27: 打开快速翻译页。 */
    onOpenQuickTranslate: () -> Unit = {},
    /** v1.0.27: 打开知识库页。 */
    onOpenKnowledgeBase: () -> Unit = {},
    /** v2.0: 打开最近删除页。 */
    onOpenRecentlyDeleted: () -> Unit = {},
    /** Phase 1 WS5: 打开助手/角色管理页面(情感空状态 CTA 用)。 */
    onOpenAssistants: () -> Unit = {},
    /** v1.72: 会话列表首次加载标志(避免闪空状态) */
    isSessionsLoading: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // v1.69: 文件夹分组 UI — 新建文件夹对话框状态
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .padding(horizontal = MusePaddings.screen),
        ) {
            // v1.0.16: 顶部快捷工具栏与新建任务卡片已移到 HomeScreen 的 tab 栏下方与右下角悬浮胶囊,
            // 本页仅保留会话列表,让首页布局更清爽。

            // 会话列表或空状态
            // v0.36 性能优化:缓存排序结果,避免每次重组都重新计算。
            // v1.0.28: 移除置顶/归档筛选,统一展示全部会话(置顶优先,按更新时间倒序)。
            val displayedSessions by remember(sessions) {
                mutableStateOf(sessions.sortedWith(
                    compareByDescending<SessionEntity> { it.pinned }.thenByDescending { it.updatedAt }
                ))
            }

            // v1.72: 首次加载时显示 loading,避免 DB emit 前闪"还没有任务"空状态
            if (isSessionsLoading) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingState()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    // v1.0.17: 底部留出悬浮胶囊空间(胶囊高度~48dp + 底部间距 16dp + 余量),
                    // 避免最后一条会话被右下角悬浮胶囊遮挡。
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    // v1.0.28: 移除筛选器后统一展示全部会话。
                    // 置顶会话始终排在最前, followed by others sorted by updatedAt.
                    // v1.0.29: 空列表时不显示任何占位,保持页面简洁。
                    val pinned = displayedSessions.filter { it.pinned }
                    val others = displayedSessions.filterNot { it.pinned }
                    items(pinned + others, key = { "session_${it.id}" }) { session ->
                        val onSelectSession = remember(session.id) { { onSelect(session.id) } }
                        val onDeleteSession = remember(session.id) { { onDelete(session.id) } }
                        val onRenameSession = remember(session.id) { { onRename(session) } }
                        val onRenameToSession = remember(session.id) { { newName: String -> onRenameTo(session, newName) } }
                        val onTogglePinnedSession = remember(session.id) { { onTogglePinned(session.id) } }
                        val onArchiveSession = remember(session.id) { { onArchive(session.id) } }
                        val onMoveToFolderSession = remember(session.id) { { folderId: String? -> onMoveSessionToFolder(session.id, folderId) } }
                        ChatListItem(
                            modifier = Modifier.animateItem(),
                            session = session,
                            isActive = session.id == currentSessionId,
                            folders = folders,
                            onSelect = onSelectSession,
                            onDelete = onDeleteSession,
                            onRename = onRenameSession,
                            onRenameTo = onRenameToSession,
                            onTogglePinned = onTogglePinnedSession,
                            onMoveToFolder = onMoveToFolderSession,
                            onArchive = onArchiveSession,
                        )
                    }
                }
            }
        }
    }

    // v1.69: 新建文件夹对话框
    if (showCreateFolderDialog) {
        MuseDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = stringResource(R.string.chat_list_new_folder),
            content = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    placeholder = { Text(stringResource(R.string.chat_list_folder_name_placeholder)) },
                    singleLine = true,
                    shape = MuseShapes.medium,
                )
            },
            confirmText = stringResource(R.string.chat_list_create),
            onConfirm = {
                if (newFolderName.isNotBlank()) {
                    onCreateFolder(newFolderName.trim())
                }
                showCreateFolderDialog = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showCreateFolderDialog = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatListItem(
    session: SessionEntity,
    isActive: Boolean,
    folders: List<FolderEntity>,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    /** v1.48: 重命名回调改为带新名字,修复重命名失效 bug(旧实现传原 session.title)。 */
    onRenameTo: (String) -> Unit = { onRename() },
    onTogglePinned: () -> Unit,
    onMoveToFolder: (String?) -> Unit,
    /** v0.45: 归档当前会话(主列表项菜单,非空才显示)。 */
    onArchive: (() -> Unit)? = null,
    /** v0.45: 取消归档(归档列表项菜单,非空才显示)。 */
    onUnarchive: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(session.title) }
    // v1.48: 删除前二次确认(滑动删除与菜单删除共用),避免误滑/误点导致会话不可恢复丢失
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // 功能3: 归档前二次确认
    var showArchiveConfirm by remember { mutableStateOf(false) }

    // 滑动删除:v1.48 改为滑出阈值后弹确认框,不再立即删除
    // 功能3: 左滑删除/右滑归档(或取消归档)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                MuseHaptics.medium(hapticFeedback)
                showDeleteConfirm = true
                false  // 不立即消失,等用户确认
            } else if (value == SwipeToDismissBoxValue.StartToEnd) {
                if (onArchive != null) {
                    MuseHaptics.medium(hapticFeedback)
                    showArchiveConfirm = true
                } else if (onUnarchive != null) {
                    MuseHaptics.medium(hapticFeedback)
                    onUnarchive()
                }
                false
            } else {
                false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MuseShapes.medium),
            ) {
                // 右侧:删除(滑出 EndToStart 时显示)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = MusePaddings.messageGap),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
                // 左侧:归档/取消归档(滑出 StartToEnd 时显示,覆盖在删除背景上方)
                if (onArchive != null || onUnarchive != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = MusePaddings.messageGap),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Icon(
                            imageVector = if (onArchive != null) Icons.Outlined.Archive else Icons.Default.Unarchive,
                            contentDescription = if (onArchive != null) stringResource(R.string.chat_list_filter_archived) else stringResource(R.string.chat_list_unarchive),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        },
        enableDismissFromStartToEnd = onArchive != null || onUnarchive != null,
    ) {
    // v1.0.16: 用 IosCardPress 替换 Card.combinedClickable,消除 Material3 Card 默认 ripple 黑色遮罩
    IosCardPress(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        shape = MuseShapes.medium,
        containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        onLongClick = {
            MuseHaptics.medium(hapticFeedback)
            showMenu = true
        },
    ) {
        Row(
            modifier = Modifier.padding(MusePaddings.cardInnerLoose),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (session.pinned) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                    }
                    Text(
                        text = session.title.ifBlank { stringResource(R.string.chat_new_session) },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                        ),
                        color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // v5: 分叉子会话计数标签
                    if (session.childCount > 0) {
                        Spacer(Modifier.size(4.dp))
                        Surface(
                            shape = MuseShapes.pill,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        ) {
                            Text(
                                text = stringResource(R.string.chat_list_child_count, session.childCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                // v5: 分叉来源提示
                if (session.parentSessionId != null) {
                    Text(
                        text = stringResource(R.string.chat_list_forked_from, session.parentSessionId.take(8)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (session.lastMessagePreview.isNotBlank()) {
                    Text(
                        text = session.lastMessagePreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    text = formatTime(session.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
    }

    if (showMenu) {
        MuseDialog(
            onDismissRequest = { showMenu = false },
            title = session.title.ifBlank { stringResource(R.string.chat_new_session) },
            content = {
                Column {
                    ActionSheetItem(
                        icon = Icons.Default.PushPin,
                        text = if (session.pinned) stringResource(R.string.chat_list_unpin) else stringResource(R.string.chat_list_filter_pinned),
                        contentDescription = if (session.pinned) stringResource(R.string.chat_list_unpin) else stringResource(R.string.chat_list_filter_pinned),
                        onClick = {
                            showMenu = false
                            MuseHaptics.light(hapticFeedback)
                            onTogglePinned()
                        },
                    )
                    if (folders.isNotEmpty()) {
                        ActionSheetItem(
                            icon = Icons.AutoMirrored.Filled.DriveFileMove,
                            text = stringResource(R.string.chat_list_move_ungrouped),
                            contentDescription = stringResource(R.string.chat_list_move_ungrouped),
                            onClick = {
                                showMenu = false
                                onMoveToFolder(null)
                            },
                        )
                        folders.forEach { folder ->
                            ActionSheetItem(
                                icon = Icons.AutoMirrored.Filled.DriveFileMove,
                                text = stringResource(R.string.chat_list_move_to, folder.name),
                                contentDescription = stringResource(R.string.chat_list_move_to_cd, folder.name),
                                onClick = {
                                    showMenu = false
                                    onMoveToFolder(folder.id)
                                },
                            )
                        }
                    }
                    ActionSheetItem(
                        icon = Icons.Default.Edit,
                        text = stringResource(R.string.chat_list_rename),
                        contentDescription = stringResource(R.string.chat_list_rename),
                        onClick = {
                            showMenu = false
                            renameText = session.title
                            showRenameDialog = true
                        },
                    )
                    // v0.45: 归档 / 取消归档
                    if (onArchive != null) {
                        ActionSheetItem(
                            icon = Icons.Outlined.Archive,
                            text = stringResource(R.string.chat_list_filter_archived),
                            contentDescription = stringResource(R.string.chat_list_filter_archived),
                            onClick = {
                                showMenu = false
                                MuseHaptics.light(hapticFeedback)
                            onArchive()
                            },
                        )
                    }
                    if (onUnarchive != null) {
                        ActionSheetItem(
                            icon = Icons.Default.Unarchive,
                            text = stringResource(R.string.chat_list_unarchive),
                            contentDescription = stringResource(R.string.chat_list_unarchive),
                            onClick = {
                                showMenu = false
                                onUnarchive()
                            },
                        )
                    }
                    ActionSheetItem(
                        icon = Icons.Default.Delete,
                        text = stringResource(R.string.action_delete),
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.error,
                        onClick = {
                            showMenu = false
                            MuseHaptics.light(hapticFeedback)
                            showDeleteConfirm = true
                        },
                    )
                }
            },
            onConfirm = null,
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showMenu = false },
        )
    }

    if (showRenameDialog) {
        MuseDialog(
            onDismissRequest = { showRenameDialog = false },
            title = stringResource(R.string.chat_list_rename_session_title),
            content = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    shape = MuseShapes.medium,
                )
            },
            confirmText = stringResource(R.string.chat_list_confirm),
            onConfirm = {
                if (renameText.isNotBlank()) {
                    onRenameTo(renameText.trim())
                }
                showRenameDialog = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showRenameDialog = false },
        )
    }

    // v1.48: 删除会话二次确认
    if (showDeleteConfirm) {
        MuseDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = stringResource(R.string.chat_list_delete_session_title),
            content = { Text(stringResource(R.string.chat_list_delete_session_confirm, session.title)) },
            confirmText = stringResource(R.string.action_delete),
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
                MuseToast.show(context.getString(R.string.chat_list_deleted_toast))
            },
            destructive = true,
        )
    }

    // 功能3: 归档确认对话框
    if (showArchiveConfirm) {
        MuseDialog(
            onDismissRequest = { showArchiveConfirm = false },
            title = stringResource(R.string.chat_list_archive_title),
            content = { Text(stringResource(R.string.chat_list_archive_confirm, session.title)) },
            confirmText = stringResource(R.string.chat_list_archive_confirm_button),
            onConfirm = {
                showArchiveConfirm = false
                onArchive?.invoke()
            },
        )
    }
}

/**
 * iOS 风格 ActionSheet 行项 — 图标 + 文字,用于 ChatListItem 长按菜单。
 * v1.0.17: 禁用 Material ripple,用按压色渐变替代,与 IosCardPress 风格一致。
 */
@Composable
private fun ActionSheetItem(
    icon: ImageVector,
    text: String,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressedColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val backgroundColor by animateColorAsState(
        targetValue = if (isPressed) pressedColor else Color.Transparent,
        animationSpec = tween(durationMillis = 180, easing = MuseAnimation.EaseOutCubic),
        label = "action_sheet_press",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .background(backgroundColor)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
        )
    }
}

// H-CL1: SimpleDateFormat 提为文件级 lazy val 复用,避免每次调用都新建(与 MessageBubble.kt 的 sdf24Hour 模式一致)
private val chatListSdf by lazy {
    SimpleDateFormat(MuseDateFormats.DATE_TIME_SHORT, Locale.getDefault())
}

private fun formatTime(timestamp: Long): String {
    if (timestamp <= 0) return ""
    return chatListSdf.format(Date(timestamp))
}
