package io.zer0.muse.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.runtime.RuntimeInstallService
import io.zer0.muse.runtime.RuntimeInstallStatus
import io.zer0.muse.runtime.RuntimeInstaller
import io.zer0.muse.runtime.RuntimeManager
import io.zer0.muse.runtime.RuntimeState
import io.zer0.muse.runtime.RuntimeStore
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.launch

/**
 * 运行时设置页：显示安装状态、占用空间，并提供安装 / 卸载入口。
 *
 * 安装走 [RuntimeInstallService]，与聊天里的安装卡同源；此处只读 [RuntimeInstallStatus]。
 */
@Composable
fun RuntimeSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by RuntimeInstallStatus.state.collectAsStateWithLifecycle()
    val progress by RuntimeInstallStatus.progress.collectAsStateWithLifecycle()
    val error by RuntimeInstallStatus.lastError.collectAsStateWithLifecycle()
    val manager = remember(context) {
        RuntimeManager(
            context = context,
            store = RuntimeStore(context),
            installer = RuntimeInstaller(context, RuntimeStore(context)),
        )
    }

    LaunchedEffect(Unit) { RuntimeInstallStatus.refresh(context) }

    SettingsSubPageScaffold(
        title = stringResource(R.string.runtime_notif_channel_name),
        onBack = onBack,
    ) {
        item { SectionLabel(stringResource(R.string.runtime_notif_channel_name)) }
        item {
            SettingsGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = MuseIcons.terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when (val current = state) {
                                is RuntimeState.Ready -> stringResource(R.string.runtime_notif_done)
                                is RuntimeState.Broken -> stringResource(R.string.runtime_card_failed_detail, current.reason)
                                RuntimeState.NotInstalled -> stringResource(R.string.runtime_card_title)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = when (val current = state) {
                                is RuntimeState.Ready ->
                                    stringResource(R.string.runtime_card_desc) + " " +
                                        Formatter.formatFileSize(context, current.installedBytes)
                                else -> stringResource(R.string.runtime_card_desc)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                SettingsGroupDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { RuntimeInstallService.start(context) },
                        enabled = progress == null && state !is RuntimeState.Ready,
                    ) {
                        Text(stringResource(R.string.runtime_card_install))
                    }
                    if (state is RuntimeState.Ready) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    manager.uninstall()
                                    RuntimeInstallStatus.refresh(context)
                                }
                            },
                        ) {
                            Text(stringResource(R.string.runtime_card_later))
                        }
                    }
                }
                val currentProgress = progress
                if (currentProgress != null) {
                    SettingsGroupDivider()
                    Text(
                        text = when (currentProgress) {
                            is RuntimeInstaller.Progress.Downloading -> {
                                val percent = if (currentProgress.total > 0) {
                                    ((currentProgress.downloaded * 100) / currentProgress.total).toInt()
                                } else {
                                    0
                                }
                                "${stringResource(R.string.runtime_notif_installing)} $percent%"
                            }
                            RuntimeInstaller.Progress.Done -> stringResource(R.string.runtime_notif_done)
                            else -> stringResource(R.string.runtime_notif_installing)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = 8.dp),
                    )
                }
                error?.let { message ->
                    Text(
                        text = stringResource(R.string.runtime_card_failed_detail, message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = 8.dp),
                    )
                }
            }
        }
    }
}