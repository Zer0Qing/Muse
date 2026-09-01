package io.zer0.muse.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.notification.MuseNotificationListenerService
import io.zer0.muse.notification.NotificationRecord
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.settings.SettingsSubPageScaffold
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * v1.0.4: 通知监听页 — 把 [MuseNotificationListenerService] 已有但未 UI 化的能力透出给用户。
 *
 * 后端能力(均已实现):
 *  - [MuseNotificationListenerService.isConnected] 查询授权状态
 *  - [MuseNotificationListenerService.recentNotifications] Flow 暴露最近 200 条通知
 *  - [MuseNotificationListenerService.clearAll] 清空通知记录
 *  - LLM 工具 `get_recent_notifications` 已注册,授权后用户可在聊天中问"最近的通知"
 *
 * 本页职责:
 *  1. 展示授权状态(已授权/未授权)+ 最近通知条数
 *  2. 一键跳转系统"通知使用权"设置页(`Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`)
 *  3. 从系统设置返回时自动刷新状态(DisposableEffect 监听 ON_RESUME)
 *  4. 列出最近通知(packageName + title + text + 时间)
 *  5. 清空通知记录按钮
 *  6. 解释卡片:说明本功能用途、隐私边界(全部本地存储,不上报)
 */
@Composable
fun NotificationListenerScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // 先恢复持久化记录,再订阅 StateFlow;服务未启动时页面也能正常显示历史数据。
    LaunchedEffect(context) {
        MuseNotificationListenerService.initialize(context)
    }
    val notifications by MuseNotificationListenerService.recentNotifications
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // 授权状态由服务回调与系统已授权列表共同判断,避免刚从系统设置返回时短暂误报未授权。
    var connected by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var packageFilter by remember { mutableStateOf<String?>(null) }
    var unreadOnly by remember { mutableStateOf(false) }
    var activeOnly by remember { mutableStateOf(false) }
    var packageMenuExpanded by remember { mutableStateOf(false) }
    var selectedRecord by remember { mutableStateOf<NotificationRecord?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            val json = MuseNotificationListenerService.exportJson()
            scope.launch(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error(context.getString(R.string.notif_listener_export_write_failed))
                }.onSuccess {
                    MuseToast.show(context.getString(R.string.notif_listener_export_success))
                }.onFailure {
                    MuseToast.show(context.getString(R.string.notif_listener_export_failed, it.message))
                }
            }
        }
    }

    val packages = remember(notifications) {
        notifications.map { it.packageName }.distinct().sorted()
    }
    val visibleNotifications = remember(notifications, query, packageFilter, unreadOnly, activeOnly) {
        val normalizedQuery = query.trim().lowercase()
        notifications.filter { record ->
            (packageFilter == null || record.packageName == packageFilter) &&
                (!unreadOnly || !record.isRead) &&
                (!activeOnly || record.isActive) &&
                (normalizedQuery.isBlank() ||
                    record.packageName.lowercase().contains(normalizedQuery) ||
                    record.title.lowercase().contains(normalizedQuery) ||
                    record.text.lowercase().contains(normalizedQuery))
        }
    }

    // 从系统设置返回时(ON_RESUME)刷新授权状态 — isConnected 是 @Volatile 字段,
    // 系统绑定/解绑服务时会更新;返回页面时必须重读才能反映用户在系统设置中的操作
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                connected = MuseNotificationListenerService.isConnected() ||
                    MuseNotificationListenerService.hasListenerAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(context) {
        connected = MuseNotificationListenerService.isConnected() ||
            MuseNotificationListenerService.hasListenerAccess(context)
    }

    SettingsSubPageScaffold(
        title = stringResource(R.string.notif_listener_screen_title),
        onBack = onBack,
    ) {
        // ── 状态卡片 ────────────────────────────────────────────────────
        item(key = "status") {
            CardGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                item {
                    StatusRow(
                        connected = connected,
                        recentCount = notifications.size,
                        activeCount = notifications.count { it.isActive },
                        unreadCount = notifications.count { !it.isRead },
                        onRefresh = {
                            MuseNotificationListenerService.refreshActiveNotifications()
                            connected = MuseNotificationListenerService.isConnected() ||
                                MuseNotificationListenerService.hasListenerAccess(context)
                        },
                    )
                }
            }
        }

        // ── F-15: 通知权限(Android 13+ POST_NOTIFICATIONS)状态与拒绝引导 ──
        item(key = "notif_permission") {
            CardGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                item {
                    val notifEnabled = androidx.core.app.NotificationManagerCompat.from(context)
                        .areNotificationsEnabled()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = if (notifEnabled) Icons.Filled.CheckCircle else Icons.Outlined.Warning,
                            contentDescription = null,
                            tint = if (notifEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(
                                    if (notifEnabled) R.string.notif_permission_status_on
                                    else R.string.notif_permission_status_off,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (!notifEnabled) {
                                Text(
                                    text = stringResource(R.string.notif_permission_guide),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        // 未授权时提供"去开启"入口(跳系统应用通知设置)
                        if (!notifEnabled) {
                            TextButton(
                                onClick = {
                                    runCatching {
                                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        context.startActivity(intent)
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.notif_permission_open))
                            }
                        }
                    }
                }
            }
        }

        // ── 搜索与筛选 ─────────────────────────────────────────────────
        item(key = "filters") {
            CardGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                item {
                    Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                        MuseTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(R.string.notif_listener_search_hint)) },
                            leadingIcon = {
                                Icon(imageVector = Icons.Outlined.Search, contentDescription = null)
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box {
                                TextButton(onClick = { packageMenuExpanded = true }) {
                                    Text(packageFilter ?: stringResource(R.string.notif_listener_all_apps))
                                }
                                DropdownMenu(
                                    expanded = packageMenuExpanded,
                                    onDismissRequest = { packageMenuExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.notif_listener_all_apps)) },
                                        onClick = {
                                            packageFilter = null
                                            packageMenuExpanded = false
                                        },
                                    )
                                    packages.forEach { pkg ->
                                        DropdownMenuItem(
                                            text = { Text(pkg) },
                                            onClick = {
                                                packageFilter = pkg
                                                packageMenuExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                            TextButton(onClick = { unreadOnly = !unreadOnly }) {
                                Text(
                                    if (unreadOnly) {
                                        stringResource(R.string.notif_listener_show_all)
                                    } else {
                                        stringResource(R.string.notif_listener_unread_only)
                                    },
                                )
                            }
                            TextButton(onClick = { activeOnly = !activeOnly }) {
                                Text(
                                    if (activeOnly) {
                                        stringResource(R.string.notif_listener_show_history)
                                    } else {
                                        stringResource(R.string.notif_listener_active_only)
                                    },
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = "${visibleNotifications.size}/${notifications.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.width(44.dp),
                            )
                        }
                    }
                }
            }
        }

        // ── 操作按钮 ────────────────────────────────────────────────────
        item(key = "actions") {
            CardGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 主按钮:跳转系统通知使用权设置
                        Button(
                            onClick = {
                                runCatching {
                                    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                }.onFailure {
                                    MuseToast.show(context.getString(R.string.notif_listener_open_settings_failed))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.notif_listener_auth_settings))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // 次按钮:清空通知记录(历史记录即使服务暂时断开也可以管理)
                            OutlinedButton(
                                onClick = {
                                    MuseNotificationListenerService.clearAll()
                                    MuseToast.show(context.getString(R.string.notif_listener_cleared_toast))
                                },
                                enabled = notifications.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.CleaningServices,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.size(6.dp))
                                Text(stringResource(R.string.notif_listener_clear_records))
                            }
                            OutlinedButton(
                                onClick = { MuseNotificationListenerService.markAllRead() },
                                enabled = notifications.any { !it.isRead },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.MarkEmailRead,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.size(6.dp))
                                Text(stringResource(R.string.notif_listener_mark_all_read))
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    exportLauncher.launch("muse-notifications-${System.currentTimeMillis()}.json")
                                }.onFailure {
                                    MuseToast.show(context.getString(R.string.notif_listener_export_failed, it.message))
                                }
                            },
                            enabled = notifications.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.notif_listener_export))
                        }
                    }
                }
            }
        }

        // ── 解释卡片 ────────────────────────────────────────────────────
        item(key = "explanation") {
            CardGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                item {
                    Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                        Text(
                            text = stringResource(R.string.notif_listener_feature_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(MusePaddings.itemGap))
                        Text(
                            text = stringResource(R.string.notif_listener_feature_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(MusePaddings.contentGap))
                        Text(
                            text = stringResource(R.string.notif_listener_privacy_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(MusePaddings.itemGap))
                        Text(
                            text = stringResource(R.string.notif_listener_privacy_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ── 最近通知列表 ────────────────────────────────────────────────
        item(key = "recent_header") {
            Text(
                text = stringResource(R.string.notif_listener_recent_header, visibleNotifications.size),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        if (visibleNotifications.isEmpty()) {
            item(key = "recent_empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.Notifications,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(MusePaddings.contentGap))
                        Text(
                            text = if (notifications.isEmpty()) {
                                if (connected) {
                                    stringResource(R.string.notif_listener_empty_connected)
                                } else {
                                    stringResource(R.string.notif_listener_empty_disconnected)
                                }
                            } else {
                                stringResource(R.string.notif_listener_filter_empty)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        } else {
            items(
                count = visibleNotifications.size,
                key = { index ->
                    MuseNotificationListenerService.keyOf(visibleNotifications[index])
                },
            ) { index ->
                val record = visibleNotifications[index]
                NotificationRecordItem(
                    record = record,
                    onClick = {
                        selectedRecord = record
                        MuseNotificationListenerService.markRead(record)
                    },
                    onDelete = { MuseNotificationListenerService.delete(record) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }

    selectedRecord?.let { record ->
        MuseDialog(
            onDismissRequest = { selectedRecord = null },
            title = record.title.ifBlank { record.packageName },
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = record.appLabel.ifBlank { record.packageName },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (record.appLabel.isNotBlank() && record.appLabel != record.packageName) {
                        Text(
                            text = record.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    record.channelId?.takeIf { it.isNotBlank() }?.let { channel ->
                        Text(
                            text = stringResource(R.string.notif_listener_channel_label, channel),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    record.category?.takeIf { it.isNotBlank() }?.let { category ->
                        Text(
                            text = stringResource(R.string.notif_listener_category_label, category),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Text(
                        text = record.text.ifBlank {
                            stringResource(R.string.notif_listener_no_text)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            .format(Date(record.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            },
            confirmText = stringResource(R.string.common_confirm),
            onConfirm = { selectedRecord = null },
            dismissText = null,
        )
    }
}

/**
 * 状态行:授权状态徽章 + 最近通知数 + 刷新按钮。
 */
@Composable
private fun StatusRow(
    connected: Boolean,
    recentCount: Int,
    activeCount: Int,
    unreadCount: Int,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusBadge(connected = connected)
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (connected) stringResource(R.string.notif_listener_authorized) else stringResource(R.string.notif_listener_unauthorized),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (connected) {
                    stringResource(R.string.notif_listener_counts, recentCount, activeCount, unreadCount)
                } else {
                    stringResource(R.string.notif_listener_recent_count_disconnected, recentCount)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRefresh) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = stringResource(R.string.notif_listener_refresh_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 授权状态徽章 — 圆形背景 + 图标 + 颜色区分(绿=已授权,橙=未授权)。
 */
@Composable
private fun StatusBadge(connected: Boolean) {
    val tint = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val bg = if (connected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
    }
    val icon: ImageVector = if (connected) Icons.Filled.CheckCircle else Icons.Outlined.Warning
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * 单条通知记录卡片。
 */
@Composable
private fun NotificationRecordItem(
    record: NotificationRecord,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeStr = remember(record.timestamp) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(record.timestamp))
    }
    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.appLabel.ifBlank { record.packageName },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (record.appLabel.isNotBlank() && record.appLabel != record.packageName) {
                        Text(
                            text = record.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!record.isRead) {
                    Text(
                        text = stringResource(R.string.notif_listener_unread_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = stringResource(
                        if (record.isActive) {
                            R.string.notif_listener_active_label
                        } else {
                            R.string.notif_listener_removed_label
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (record.isActive) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
            }
            if (record.title.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (record.text.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = record.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDelete) {
                Text(
                    text = stringResource(R.string.common_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
