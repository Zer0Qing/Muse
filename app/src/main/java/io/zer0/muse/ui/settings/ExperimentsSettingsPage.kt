package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.ExperimentsConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.switchItem
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v0.32: 实验性功能页。
 *
 * 默认全部关闭，用户主动开启后才会启用对应实验性功能。
 * 顶部有醒目提示告诉用户这些功能可能不稳定。
 */
@Composable
fun ExperimentsSettingsPage(onBack: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val config by settings.experimentsFlow.collectAsStateWithLifecycle(initialValue = ExperimentsConfig())
    val scope = rememberCoroutineScope()

    SettingsSubPageScaffold(title = stringResource(R.string.settings_experiments_title), onBack = onBack) {
        item(key = "experiments_warning") {
            CardGroup(title = { Text(stringResource(R.string.settings_experiments_hint)) }) {
                content(key = "experiments_warning_body") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.md),
                    ) {
                        Icon(
                            imageVector = MuseIcons.flask,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                        Text(
                            text = stringResource(R.string.settings_experiments_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }

        item(key = "experiments_features") {
            CardGroup(title = { Text(stringResource(R.string.settings_experiments_features)) }) {
                switchItem(
                    key = "experiments_force_mood",
                    title = stringResource(R.string.settings_experiments_force_mood),
                    checked = config.forceMoodBlock,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveExperiments(config.copy(forceMoodBlock = v)) }
                    },
                    leadingContent = { ExperimentIcon(MuseIcons.atom) },
                )
                switchItem(
                    key = "experiments_debug_mode",
                    title = stringResource(R.string.settings_experiments_debug_mode),
                    checked = config.debugMode,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveExperiments(config.copy(debugMode = v)) }
                    },
                    leadingContent = { ExperimentIcon(MuseIcons.bug) },
                )
                switchItem(
                    key = "experiments_self_reflection",
                    title = stringResource(R.string.settings_experiments_self_reflection),
                    checked = config.selfReflection,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveExperiments(config.copy(selfReflection = v)) }
                    },
                    leadingContent = { ExperimentIcon(MuseIcons.atom) },
                )
                switchItem(
                    key = "experiments_long_memory",
                    title = stringResource(R.string.settings_experiments_long_memory_compression),
                    checked = config.longMemoryCompression,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveExperiments(config.copy(longMemoryCompression = v)) }
                    },
                    leadingContent = { ExperimentIcon(MuseIcons.server) },
                )
                switchItem(
                    key = "experiments_parallel_tools",
                    title = stringResource(R.string.settings_experiments_parallel_tools),
                    checked = config.parallelReadOnlyTools,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveExperiments(config.copy(parallelReadOnlyTools = v)) }
                    },
                    leadingContent = { ExperimentIcon(MuseIcons.arrowRight) },
                )
            }
        }
    }
}

@Composable
private fun ExperimentIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(MuseIconSizes.iconMedium),
    )
}
