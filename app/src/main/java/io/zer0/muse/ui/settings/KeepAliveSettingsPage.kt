package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.schedule.KeepAliveService
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.navigationItem
import io.zer0.muse.ui.common.surface.switchItem
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v2.6.9: 常驻后台设置页。
 *
 * 做的是「在用户授权下把能用的保活手段都用上」，而不是伪装或隐藏：
 *  - 常驻前台服务（唯一合规且有效的进程保活手段）
 *  - 可选 WakeLock（屏幕关闭也保持连接，更耗电）
 *  - 电池优化豁免引导 + 厂商自启动白名单引导（复用既有 KeepAliveGuide）
 *
 * 明确不做：改包名、仿冒其他应用图标、隐藏通知。
 * 那些是恶意软件特征，会被系统标记并影响开源项目声誉。
 */
@Composable
fun KeepAliveSettingsPage(onBack: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val enabled by settings.keepAliveEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val wakeLock by settings.keepAliveWakeLockFlow.collectAsStateWithLifecycle(initialValue = false)
    val keepAliveTitle by settings.keepAliveNotificationTitleFlow
        .collectAsStateWithLifecycle(initialValue = "")
    val keepAliveText by settings.keepAliveNotificationTextFlow
        .collectAsStateWithLifecycle(initialValue = "")
    var showGuide by remember { mutableStateOf(false) }
    var showNotificationEditor by remember { mutableStateOf(false) }

    SettingsSubPageScaffold(
        title = stringResource(R.string.settings_keep_alive_title),
        onBack = onBack,
    ) {
        item(key = "keep_alive_switches") {
            CardGroup {
                switchItem(
                    key = "keep_alive_enabled",
                    title = stringResource(R.string.settings_keep_alive_title),
                    subtitle = stringResource(R.string.settings_keep_alive_hint),
                    checked = enabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveKeepAliveEnabled(v) }
                        if (v) KeepAliveService.start(context) else KeepAliveService.stop(context)
                    },
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.deviceMobile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                if (enabled) {
                    switchItem(
                        key = "keep_alive_wakelock",
                        title = stringResource(R.string.settings_keep_alive_wakelock_title),
                        checked = wakeLock,
                        onCheckedChange = { v ->
                            scope.launch { settings.saveKeepAliveWakeLock(v) }
                        },
                        leadingContent = {
                            Icon(
                                imageVector = MuseIcons.clock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(MuseIconSizes.iconMedium),
                            )
                        },
                    )
                    navigationItem(
                        key = "keep_alive_notification",
                        title = stringResource(R.string.settings_keep_alive_notification_custom_title),
                        leadingContent = {
                            Icon(
                                imageVector = MuseIcons.edit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(MuseIconSizes.iconMedium),
                            )
                        },
                        onClick = { showNotificationEditor = true },
                    )
                }
            }
        }

        item(key = "keep_alive_guides") {
            CardGroup {
                navigationItem(
                    key = "keep_alive_battery",
                    title = stringResource(R.string.settings_keep_alive_battery_title),
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.battery,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                    onClick = { showGuide = true },
                )
                navigationItem(
                    key = "keep_alive_rom",
                    title = stringResource(R.string.settings_keep_alive_rom_title),
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.sliders,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                    onClick = { showGuide = true },
                )
            }
        }
    }

    if (showGuide) {
        KeepAliveGuideDialog(onDismiss = { showGuide = false })
    }

    if (showNotificationEditor) {
        NotificationTextDialog(
            currentTitle = keepAliveTitle,
            currentText = keepAliveText,
            onDismiss = { showNotificationEditor = false },
            onSave = { title, text ->
                scope.launch {
                    settings.saveKeepAliveNotification(title, text)
                    showNotificationEditor = false
                }
            },
        )
    }
}

/**
 * 常驻通知文案编辑器。
 *
 * 通知栏会常驻显示这段文字，可改标题与正文，避免直接把对话内容暴露给旁人。
 * 两项都留空时回落到内置默认文案（见 KeepAliveService.buildNotification）。
 */
@Composable
private fun NotificationTextDialog(
    currentTitle: String,
    currentText: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var title by remember { mutableStateOf(currentTitle) }
    var text by remember { mutableStateOf(currentText) }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_keep_alive_notification_custom_title),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
            ) {
                Text(
                    text = stringResource(R.string.settings_keep_alive_notification_custom_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MuseTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = {
                        Text(stringResource(R.string.settings_keep_alive_notification_title_placeholder))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MuseTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = {
                        Text(stringResource(R.string.settings_keep_alive_notification_text_placeholder))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmText = stringResource(R.string.common_save),
        onConfirm = { onSave(title, text) },
        dismissText = stringResource(R.string.common_cancel),
        onDismiss = onDismiss,
    )
}
