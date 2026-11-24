package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.PreferenceStore
import io.zer0.muse.data.UserPreference
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.theme.MuseSpacing
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 用户偏好管理页：列出助手经 `remember_preference` 记下的硬性交互偏好，支持启停与删除。
 */
@Composable
fun UserPreferencePage(onBack: () -> Unit, store: PreferenceStore = koinInject()) {
    val preferences by store.preferencesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<UserPreference?>(null) }
    val stateEnabledText = stringResource(R.string.common_state_enabled)
    val stateDisabledText = stringResource(R.string.common_state_disabled)

    SettingsSubPageScaffold(
        title = stringResource(R.string.settings_preference_title),
        onBack = onBack,
    ) {
        if (preferences.isEmpty()) {
            item(key = "preference_empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MuseSpacing.xxxl),
                    contentAlignment = Alignment.Center,
                ) {
                    MuseEmptyState(
                        icon = MuseIcons.check,
                        title = stringResource(R.string.settings_preference_empty),
                    )
                }
            }
        } else {
            item(key = "preference_desc") {
                Text(
                    text = stringResource(R.string.settings_preference_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = MuseSpacing.xs),
                )
            }
            item(key = "preference_list") {
                CardGroup(title = { Text(stringResource(R.string.settings_preference_subtitle)) }) {
                    preferences.forEach { pref ->
                        item(
                            key = pref.id,
                            onClick = {
                                scope.launch {
                                    runCatching { store.setEnabled(pref.id, !pref.enabled) }
                                        .onFailure {
                                            Logger.w("UserPreferencePage", "切换偏好状态失败: ${it.message}")
                                        }
                                }
                            },
                            headlineContent = {
                                Text(
                                    text = pref.rule,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                val subtitle = buildString {
                                    if (!pref.enabled) {
                                        append(stringResource(R.string.settings_preference_disabled_tag))
                                        if (pref.source.isNotBlank()) append(" · ")
                                    }
                                    if (pref.source.isNotBlank()) {
                                        append(stringResource(R.string.settings_preference_source, pref.source))
                                    }
                                }
                                if (subtitle.isNotBlank()) {
                                    Text(text = subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            },
                            trailingContent = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                                ) {
                                    MuseSwitch(
                                        checked = pref.enabled,
                                        onCheckedChange = { enabled ->
                                            scope.launch {
                                                runCatching { store.setEnabled(pref.id, enabled) }
                                                    .onFailure {
                                                        Logger.w("UserPreferencePage", "切换偏好状态失败: ${it.message}")
                                                    }
                                            }
                                        },
                                        modifier = Modifier.semantics {
                                            stateDescription = if (pref.enabled) stateEnabledText else stateDisabledText
                                        },
                                    )
                                    MuseTactileButton(
                                        icon = MuseIcons.trash,
                                        tint = MaterialTheme.colorScheme.error,
                                        contentDescription = stringResource(R.string.common_delete),
                                        compact = true,
                                        onClick = { deleting = pref },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    deleting?.let { target ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.common_delete),
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
