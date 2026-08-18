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
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import compose.icons.TablerIcons
import compose.icons.tablericons.DeviceMobile
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
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
        if (appOrder.isNotEmpty()) {
            item {
                TextButton(
                    onClick = { scope.launch { settings.saveMiniPhoneAppOrder(emptyList()) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("恢复默认桌面顺序")
                }
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
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowUp,
                contentDescription = "上移",
            )
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = "下移",
            )
        }
        MuseSwitch(
            checked = visible,
            onCheckedChange = onVisibleChange,
        )
    }
}
