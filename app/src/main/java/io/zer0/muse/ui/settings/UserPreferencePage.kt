package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.PreferenceStore
import io.zer0.muse.data.UserPreference
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v2.6.7 fix (AUDIT F9): 用户偏好管理页。
 *
 * 背景：`PreferenceStore` 的 `preferencesFlow` / `remove` / `setEnabled` / `clear`
 * 此前全仓零调用 —— 助手会把「以后别…」记成硬约束，但用户看不到、删不掉、也关不掉。
 * 一条记错的偏好会永久生效。
 *
 * 这里把已有能力接成可用界面：列出、停用/启用、删除。
 * 新增条目仍由助手经 `remember_preference` 工具完成（那是它的自然入口）。
 */
@Composable
@Suppress("LongMethod") // 页级组合函数：弹窗与列表同在一处更易读，拆分会引入参数传递
fun UserPreferencePage(onBack: () -> Unit, store: PreferenceStore = koinInject()) {
    val preferences by store.preferencesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<UserPreference?>(null) }

    MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.settings_preference_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        if (preferences.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                MuseEmptyState(
                    icon = MuseIcons.check,
                    title = stringResource(R.string.settings_preference_empty),
                    subtitle = stringResource(R.string.settings_preference_empty_hint),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = MusePaddings.screen,
                    end = MusePaddings.screen,
                    bottom = MusePaddings.listBottomClearance,
                ),
            ) {
                item(key = "desc") {
                    Text(
                        text = stringResource(R.string.settings_preference_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = MusePaddings.itemGap),
                    )
                }
                item(key = "label") {
                    SectionLabel(stringResource(R.string.settings_preference_subtitle))
                }
                items(preferences, key = { it.id }) { pref ->
                    PreferenceRow(
                        pref = pref,
                        onToggle = { enabled ->
                            scope.launch {
                                runCatching { store.setEnabled(pref.id, enabled) }
                                    .onFailure {
                                        Logger.w("UserPreferencePage", "切换偏好状态失败: ${it.message}")
                                    }
                            }
                        },
                        onDelete = { deleting = pref },
                    )
                    if (pref != preferences.lastOrNull()) {
                        SettingsGroupDivider()
                    }
                }
            }
        }
    }

    deleting?.let { target ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.settings_preference_delete),
            itemName = target.rule,
            consequence = stringResource(R.string.settings_preference_delete_confirm),
            onConfirm = {
                deleting = null
                scope.launch {
                    runCatching { store.remove(target.id) }
                        .onFailure { Logger.w("UserPreferencePage", "删除偏好失败: ${it.message}") }
                }
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun PreferenceRow(pref: UserPreference, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    SettingsGroup {
        SettingsItemRow(
            icon = if (pref.enabled) MuseIcons.check else MuseIcons.circle,
            title = pref.rule,
            titleMaxLines = 3,
            subtitle = buildString {
                if (!pref.enabled) {
                    append(stringResource(R.string.settings_preference_disabled_tag))
                    if (pref.source.isNotBlank()) append(" · ")
                }
                if (pref.source.isNotBlank()) {
                    append(stringResource(R.string.settings_preference_source, pref.source))
                }
            }.ifBlank { null },
            onClick = { onToggle(!pref.enabled) },
        ) {
            Text(
                text = stringResource(
                    if (pref.enabled) {
                        R.string.settings_preference_disable
                    } else {
                        R.string.settings_preference_enable
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        SettingsGroupDivider()
        SettingsItemRow(
            icon = MuseIcons.trash,
            title = stringResource(R.string.settings_preference_delete),
            onClick = onDelete,
        ) {
            MuseCapsuleButton(
                text = stringResource(R.string.settings_preference_delete),
                onClick = onDelete,
                fillWidth = false,
            )
        }
    }
}
