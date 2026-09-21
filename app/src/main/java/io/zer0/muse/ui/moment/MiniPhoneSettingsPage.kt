package io.zer0.muse.ui.moment

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import compose.icons.TablerIcons
import compose.icons.tablericons.DeviceMobile
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.settings.SettingsSubPageScaffold
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment

/**
 * v1.0.74: 小手机设置页。
 * 第一个: 总开关(首页右上角小手机图标显隐)。
 */
@Composable
fun MiniPhoneSettingsPage(
    onBack: () -> Unit,
) {
    val settings: SettingsRepository = koinInject()
    val enabled by settings.miniPhoneEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val hiddenApps by settings.miniPhoneHiddenAppsFlow.collectAsStateWithLifecycle(initialValue = emptySet())
    val appOrder by settings.miniPhoneAppOrderFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val defaultOrder = MiniPhoneApps.all.map { it.first }
    val labels = MiniPhoneApps.all.toMap()
    val orderedApps = (appOrder + defaultOrder).distinct().filter { it in labels }
    // MEM-10: 恢复默认需确认;重置后提供「撤销恢复」入口
    var showResetConfirm by remember { mutableStateOf(false) }
    var lastOrderBeforeReset by remember { mutableStateOf<List<String>?>(null) }

    SettingsSubPageScaffold(
        title = stringResource(R.string.settings_miniphone_title),
        onBack = onBack,
    ) {
        // ── 总开关 ──
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = TablerIcons.DeviceMobile,
                    title = stringResource(R.string.settings_miniphone_enable_title),
                    subtitle = stringResource(R.string.settings_miniphone_enable_subtitle),
                    checked = enabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveMiniPhoneEnabled(v) }
                    },
                )
            }
        }
        item {
            SettingsGroup {
                orderedApps.forEachIndexed { index, appId ->
                    MiniPhoneAppSettingRow(
                        label = labels[appId] ?: appId,
                        visible = appId !in hiddenApps,
                        canMoveUp = index > 0,
                        canMoveDown = index < orderedApps.lastIndex,
                        onVisibleChange = { visible ->
                            scope.launch {
                                settings.saveMiniPhoneHiddenApps(
                                    if (visible) hiddenApps - appId else hiddenApps + appId,
                                )
                            }
                        },
                        onMoveUp = {
                            val next = orderedApps.toMutableList()
                            next.add(index - 1, next.removeAt(index))
                            scope.launch { settings.saveMiniPhoneAppOrder(next) }
                        },
                        onMoveDown = {
                            val next = orderedApps.toMutableList()
                            next.add(index + 1, next.removeAt(index))
                            scope.launch { settings.saveMiniPhoneAppOrder(next) }
                        },
                    )
                }
            }
        }
        if (appOrder.isNotEmpty() || lastOrderBeforeReset != null) {
            item {
                TextButton(
                    // MEM-10: 重置前确认;重置后可一键撤销
                    onClick = {
                        if (lastOrderBeforeReset != null) {
                            scope.launch { settings.saveMiniPhoneAppOrder(lastOrderBeforeReset.orEmpty()) }
                            lastOrderBeforeReset = null
                        } else {
                            showResetConfirm = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (lastOrderBeforeReset != null) {
                            stringResource(R.string.miniphone_undo_restore)
                        } else {
                            stringResource(R.string.miniphone_restore_reset)
                        },
                    )
                }
            }
        }
        // MEM-10: 恢复默认确认对话框(LazyListScope 内需包进 item)
        if (showResetConfirm) {
            item {
                MuseDialog(
                    onDismissRequest = { showResetConfirm = false },
                    title = stringResource(R.string.miniphone_reset_default_title),
                    content = { Text(stringResource(R.string.miniphone_reset_default_content)) },
                    confirmText = stringResource(R.string.miniphone_restore_confirm),
                    destructive = true,
                    onConfirm = {
                        showResetConfirm = false
                        lastOrderBeforeReset = appOrder.takeIf { it.isNotEmpty() }
                        scope.launch { settings.saveMiniPhoneAppOrder(emptyList()) }
                    },
                )
            }
        }
        item {
            Surface(
                shape = MuseShapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_miniphone_desc_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.settings_miniphone_desc_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniPhoneAppSettingRow(
    label: String,
    visible: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        Icon(
            imageVector = TablerIcons.DeviceMobile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(MuseIconSizes.iconMedium),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "在小手机桌面显示",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
        MuseTactileButton(
            icon = Icons.Filled.KeyboardArrowUp,
            onClick = onMoveUp,
            contentDescription = "上移",
            enabled = canMoveUp,
        )
        MuseTactileButton(
            icon = Icons.Filled.KeyboardArrowDown,
            onClick = onMoveDown,
            contentDescription = "下移",
            enabled = canMoveDown,
        )
        MuseSwitch(
            checked = visible,
            onCheckedChange = onVisibleChange,
        )
    }
}
