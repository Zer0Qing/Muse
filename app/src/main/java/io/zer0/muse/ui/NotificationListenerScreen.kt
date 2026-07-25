package io.zer0.muse.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.zer0.muse.R
import io.zer0.muse.notification.MuseNotificationListenerService
import io.zer0.muse.notification.NotificationRecord
import io.zer0.muse.ui.common.MuseToast
import io.zer0.muse.ui.components.CardGroup
import io.zer0.muse.ui.settings.SettingsSubPageScaffold
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.0.4: 通知监听页 — 把 [MuseNotificationListenerService] 已有但未 UI 化的能力透出给用户。
 *
 * 后端能力(均已实现):
 *  - [MuseNotificationListenerService.isConnected] 查询授权状态
 *  - [MuseNotificationListenerService.recentNotifications] Flow 暴露最近 50 条通知
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

    // 授权状态 + 最近通知快照(从静态 StateFlow 读取)
    var connected by remember { mutableStateOf(MuseNotificationListenerService.isConnected()) }
    var notifications by remember {
        mutableStateOf(MuseNotificationListenerService.recentNotifications.value)
    }
    // 刷新计数:每次 ON_RESUME 触发 +1,LaunchedEffect 监听后重新读 Flow
    var refreshTick by remember { mutableStateOf(0) }

    // 从系统设置返回时(ON_RESUME)刷新授权状态 — isConnected 是 @Volatile 字段,
    // 系统绑定/解绑服务时会更新;返回页面时必须重读才能反映用户在系统设置中的操作
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                connected = MuseNotificationListenerService.isConnected()
                notifications = MuseNotificationListenerService.recentNotifications.value
                refreshTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                        onRefresh = {
                            connected = MuseNotificationListenerService.isConnected()
                            notifications = MuseNotificationListenerService.recentNotifications.value
                            refreshTick++
                        },
                    )
                }
            }
        }

        // ── 操作按钮 ────────────────────────────────────────────────────
        item(key = "actions") {
            CardGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.notif_listener_auth_settings))
                        }
                        // 次按钮:清空通知记录(仅在已连接且有数据时启用)
                        OutlinedButton(
                            onClick = {
                                MuseNotificationListenerService.clearAll()
                                notifications = emptyList()
                                MuseToast.show(context.getString(R.string.notif_listener_cleared_toast))
                            },
                            enabled = connected && notifications.isNotEmpty(),
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
                text = stringResource(R.string.notif_listener_recent_header, notifications.size),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        if (notifications.isEmpty()) {
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
                            text = if (connected) stringResource(R.string.notif_listener_empty_connected) else stringResource(R.string.notif_listener_empty_disconnected),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        } else {
            items(
                count = notifications.size,
                key = { index ->
                    // 用 packageName + title + timestamp 组合 key,近似唯一
                    "${notifications[index].packageName}_${notifications[index].timestamp}_${index}"
                },
            ) { index ->
                val record = notifications[index]
                NotificationRecordItem(
                    record = record,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        // refreshTick 引用一次,避免编译器警告未使用(实际用于 ON_RESUME 触发 recomposition)
        item(key = "refresh_tick_$refreshTick") { Spacer(Modifier.height(0.dp)) }
    }
}

/**
 * 状态行:授权状态徽章 + 最近通知数 + 刷新按钮。
 */
@Composable
private fun StatusRow(
    connected: Boolean,
    recentCount: Int,
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
                text = if (connected) stringResource(R.string.notif_listener_recent_count_connected, recentCount) else stringResource(R.string.notif_listener_recent_count_disconnected, recentCount),
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
    modifier: Modifier = Modifier,
) {
    val timeStr = remember(record.timestamp) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(record.timestamp))
    }
    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
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
                Text(
                    text = record.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
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
        }
    }
}
