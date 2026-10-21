package io.zer0.muse.ui.moment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseActionSheetRow
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.settings.SettingsSubPageScaffold
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v1.0.74: 小手机设置页。
 * 第一个: 总开关(首页右上角小手机图标显隐)。
 */
@Composable
fun MiniPhoneSettingsPage(
    onBack: () -> Unit,
    onOpenRelationships: () -> Unit = {},
    onOpenKeepAlive: () -> Unit = {},
    onOpenAppearance: () -> Unit = {},
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
                    icon = MuseIcons.deviceMobile,
                    title = stringResource(R.string.settings_miniphone_enable_title),
                    subtitle = stringResource(R.string.settings_miniphone_enable_subtitle),
                    checked = enabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveMiniPhoneEnabled(v) }
                    },
                )
            }
        }
        // ── v2.6.9: 外观自定义入口 ──
        item {
            SettingsGroup {
                SettingsItemRow(
                    icon = MuseIcons.typography,
                    title = stringResource(R.string.settings_appearance_customize_title),
                    subtitle = stringResource(R.string.settings_appearance_customize_hint),
                    onClick = onOpenAppearance,
                ) {
                    ChevronRight()
                }
            }
        }
        // ── v2.6.9: 常驻后台入口 ──
        item {
            SettingsGroup {
                SettingsItemRow(
                    icon = MuseIcons.deviceMobile,
                    title = stringResource(R.string.settings_keep_alive_title),
                    subtitle = stringResource(R.string.settings_keep_alive_subtitle),
                    onClick = onOpenKeepAlive,
                ) {
                    ChevronRight()
                }
            }
        }
        // ── v2.6.7（P1-1）: 角色关系入口 ──
        item {
            SettingsGroup {
                SettingsItemRow(
                    icon = MuseIcons.users,
                    title = stringResource(R.string.settings_relationship_title),
                    subtitle = stringResource(R.string.settings_relationship_hint),
                    onClick = onOpenRelationships,
                ) {
                    ChevronRight()
                }
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
                MuseCapsuleButton(
                    text = if (lastOrderBeforeReset != null) {
                        stringResource(R.string.miniphone_undo_restore)
                    } else {
                        stringResource(R.string.miniphone_restore_reset)
                    },
                    // MEM-10: 重置前确认;重置后可一键撤销
                    onClick = {
                        if (lastOrderBeforeReset != null) {
                            scope.launch { settings.saveMiniPhoneAppOrder(lastOrderBeforeReset.orEmpty()) }
                            lastOrderBeforeReset = null
                        } else {
                            showResetConfirm = true
                        }
                    },
                    variant = IosCapsuleButtonVariant.Text,
                )
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
    // v2.x 统一化: 行内动作收敛为「更多」菜单(防止窄屏文本列被多个按钮挤压)
    val moreCd = stringResource(R.string.settings_mcp_more)
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        Icon(
            imageVector = MuseIcons.deviceMobile,
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
        MuseSwitch(
            checked = visible,
            onCheckedChange = onVisibleChange,
        )
        MuseTactileButton(
            icon = MuseIcons.moreVertical,
            onClick = { menuExpanded = true },
            contentDescription = moreCd,
        )
    }

    if (menuExpanded) {
        MuseDialog(
            onDismissRequest = { menuExpanded = false },
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    MuseActionSheetRow(
                        icon = MuseIcons.chevronUp,
                        text = stringResource(R.string.prompt_template_manager_move_up),
                        enabled = canMoveUp,
                        onClick = {
                            menuExpanded = false
                            onMoveUp()
                        },
                    )
                    MuseActionSheetRow(
                        icon = MuseIcons.chevronDown,
                        text = stringResource(R.string.prompt_template_manager_move_down),
                        enabled = canMoveDown,
                        onClick = {
                            menuExpanded = false
                            onMoveDown()
                        },
                    )
                }
            },
            confirmText = stringResource(R.string.common_close),
            onConfirm = { menuExpanded = false },
            onDismiss = { menuExpanded = false },
        )
    }
}
