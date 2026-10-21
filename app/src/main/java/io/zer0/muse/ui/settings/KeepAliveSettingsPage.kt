package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.schedule.KeepAliveService
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.theme.MusePaddings
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
    var showGuide by remember { mutableStateOf(false) }

    SettingsSubPageScaffold(
        title = stringResource(R.string.settings_keep_alive_title),
        onBack = onBack,
    ) {
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = MuseIcons.deviceMobile,
                    title = stringResource(R.string.settings_keep_alive_title),
                    subtitle = stringResource(R.string.settings_keep_alive_subtitle),
                    checked = enabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveKeepAliveEnabled(v) }
                        if (v) KeepAliveService.start(context) else KeepAliveService.stop(context)
                    },
                )
                if (enabled) {
                    SettingsGroupDivider()
                    SettingsSwitchRow(
                        icon = MuseIcons.clock,
                        title = stringResource(R.string.settings_keep_alive_wakelock_title),
                        subtitle = stringResource(R.string.settings_keep_alive_wakelock_subtitle),
                        checked = wakeLock,
                        onCheckedChange = { v ->
                            scope.launch { settings.saveKeepAliveWakeLock(v) }
                        },
                    )
                }
            }
        }
        item {
            Text(
                text = stringResource(R.string.settings_keep_alive_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = 4.dp),
            )
        }
        item {
            SettingsGroup {
                SettingsItemRow(
                    icon = MuseIcons.battery,
                    title = stringResource(R.string.settings_keep_alive_battery_title),
                    subtitle = stringResource(R.string.settings_keep_alive_battery_subtitle),
                    onClick = { showGuide = true },
                ) {
                    ChevronRight()
                }
                SettingsGroupDivider()
                SettingsItemRow(
                    icon = MuseIcons.sliders,
                    title = stringResource(R.string.settings_keep_alive_rom_title),
                    subtitle = stringResource(R.string.settings_keep_alive_rom_subtitle),
                    onClick = { showGuide = true },
                ) {
                    ChevronRight()
                }
            }
        }
    }

    if (showGuide) {
        KeepAliveGuideDialog(onDismiss = { showGuide = false })
    }
}
