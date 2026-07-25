package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.ExperimentsConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.SectionLabel
import io.zer0.muse.ui.common.SettingsGroup
import io.zer0.muse.ui.common.SettingsGroupDivider
import io.zer0.muse.ui.common.SettingsSwitchRow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v0.32: 实验性功能页。
 *
 * 默认全部关闭,用户主动开启后才会启用对应实验性功能。
 * 顶部有醒目提示告诉用户这些功能可能不稳定。
 */
@Composable
fun ExperimentsSettingsPage(
    onBack: () -> Unit,
) {
    val settings: SettingsRepository = koinInject()
    val config by settings.experimentsFlow.collectAsStateWithLifecycle(initialValue = ExperimentsConfig())
    val scope = rememberCoroutineScope()

    SettingsSubPageScaffold(title = stringResource(R.string.settings_experiments_title), onBack = onBack) {
        item { SectionLabel(stringResource(R.string.settings_experiments_hint)) }
        item {
            SettingsGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Science,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_experiments_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }

        item { SectionLabel(stringResource(R.string.settings_experiments_features)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = Icons.Outlined.Psychology,
                    title = stringResource(R.string.settings_experiments_force_mood),
                    subtitle = stringResource(R.string.settings_experiments_force_mood_subtitle),
                    checked = config.forceMoodBlock,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveExperiments(config.copy(forceMoodBlock = v)) }
                    },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = Icons.Outlined.BugReport,
                    title = stringResource(R.string.settings_experiments_debug_mode),
                    subtitle = stringResource(R.string.settings_experiments_debug_mode_subtitle),
                    checked = config.debugMode,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveExperiments(config.copy(debugMode = v)) }
                    },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = Icons.Outlined.Psychology,
                    title = stringResource(R.string.settings_experiments_self_reflection),
                    subtitle = stringResource(R.string.settings_experiments_self_reflection_subtitle),
                    checked = config.selfReflection,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveExperiments(config.copy(selfReflection = v)) }
                    },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = Icons.Outlined.Memory,
                    title = stringResource(R.string.settings_experiments_long_memory_compression),
                    subtitle = stringResource(R.string.settings_experiments_long_memory_compression_subtitle),
                    checked = config.longMemoryCompression,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveExperiments(config.copy(longMemoryCompression = v)) }
                    },
                )
            }
        }
    }
}
