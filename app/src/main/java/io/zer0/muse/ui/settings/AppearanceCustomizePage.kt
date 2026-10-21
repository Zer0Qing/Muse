package io.zer0.muse.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v2.6.9: 外观自定义设置页。
 *
 * 两块内容：
 *  1. 启动器外观（桌面显示名）—— 用 activity-alias 切换，不改包名、不仿冒其他应用
 *  2. 常驻通知文案 —— 通知标题与正文可改，避免通知栏直接暴露内容
 *
 * 明确边界（页面上也写出来）：
 *  - 只提供中性名称（笔记 / 备忘录 / 学习记录），不允许仿冒第三方应用
 *  - 这是隐私功能，不是伪装工具
 */
@Composable
fun AppearanceCustomizePage(onBack: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val enabled by settings.keepAliveEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    var currentStyle by remember { mutableStateOf(LauncherAppearance.current(context)) }
    var showStylePicker by remember { mutableStateOf(false) }
    var showNotificationEditor by remember { mutableStateOf(false) }

    SettingsSubPageScaffold(
        title = stringResource(R.string.settings_appearance_customize_title),
        onBack = onBack,
    ) {
        item {
            Text(
                text = stringResource(R.string.settings_appearance_customize_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = 4.dp),
            )
        }
        item {
            SettingsGroup {
                SettingsItemRow(
                    icon = MuseIcons.deviceMobile,
                    title = stringResource(R.string.settings_launcher_style_title),
                    subtitle = currentStyle.displayName,
                    onClick = { showStylePicker = true },
                ) {
                    ChevronRight()
                }
                SettingsGroupDivider()
                SettingsItemRow(
                    icon = MuseIcons.edit,
                    title = stringResource(R.string.settings_keep_alive_notification_custom_title),
                    subtitle = stringResource(R.string.settings_keep_alive_notification_custom_subtitle),
                    onClick = { showNotificationEditor = true },
                ) {
                    ChevronRight()
                }
            }
        }
        if (!enabled) {
            item {
                Text(
                    text = stringResource(R.string.settings_appearance_customize_keep_alive_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = 4.dp),
                )
            }
        }
    }

    if (showStylePicker) {
        LauncherStyleDialog(
            current = currentStyle,
            onDismiss = { showStylePicker = false },
            onPick = { style ->
                val ok = LauncherAppearance.apply(context, style)
                if (ok) currentStyle = style
                showStylePicker = false
            },
        )
    }

    if (showNotificationEditor) {
        NotificationTextDialog(
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

@Composable
private fun LauncherStyleDialog(current: LauncherAppearance.Style, onDismiss: () -> Unit, onPick: (LauncherAppearance.Style) -> Unit) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_launcher_style_title),
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.settings_launcher_style_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                LauncherAppearance.Style.entries.forEach { style ->
                    val selected = current == style
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(style) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = style.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        if (selected) {
                            Icon(
                                imageVector = MuseIcons.check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmText = stringResource(R.string.action_cancel),
        onConfirm = onDismiss,
        dismissText = null,
        onDismiss = onDismiss,
    )
}

@Composable
private fun NotificationTextDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_keep_alive_notification_custom_title),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_keep_alive_notification_custom_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
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
        confirmText = stringResource(R.string.action_save),
        onConfirm = { onSave(title, text) },
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
    )
}
