package io.zer0.muse.ui.settings

import android.text.format.Formatter
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.runtime.RuntimeInstallService
import io.zer0.muse.runtime.RuntimeInstallStatus
import io.zer0.muse.runtime.RuntimeInstallOperation
import io.zer0.muse.runtime.RuntimeInstallPhase
import io.zer0.muse.runtime.RuntimeInstaller
import io.zer0.muse.runtime.RuntimeManager
import io.zer0.muse.runtime.RuntimeState
import io.zer0.muse.runtime.RuntimeStore
import io.zer0.muse.runtime.RuntimeVersion
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.runtime.RuntimeVersionPicker
import io.zer0.muse.ui.runtime.RuntimeCustomUrlDialog
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 运行时设置页：显示安装状态、占用空间，并提供安装 / 卸载入口。
 *
 * 安装走 [RuntimeInstallService]，与聊天里的安装卡同源；此处只读 [RuntimeInstallStatus]。
 */
@OptIn(ExperimentalLayoutApi::class)
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
    var versions by remember { mutableStateOf<List<RuntimeVersion>>(emptyList()) }
    var operation by remember { mutableStateOf<RuntimeInstallOperation?>(null) }
    var showVersionPicker by remember { mutableStateOf(false) }
    var showUninstallConfirm by remember { mutableStateOf(false) }
    var customUrlVersion by remember { mutableStateOf<RuntimeVersion?>(null) }
    var localInstallRevision by remember { mutableStateOf<String?>(null) }
    val localArchivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val revision = localInstallRevision
        val version = versions.firstOrNull { it.revision == revision }
        if (uri != null && version != null) {
            scope.launch {
                try {
                    val archive = copySelectedArchive(context, uri, version)
                    RuntimeInstallService.startLocal(context, version.revision, archive)
                } catch (error: Exception) {
                    RuntimeInstallStatus.fail(error.message)
                }
            }
        }
        localInstallRevision = null
    }

    LaunchedEffect(manager) {
        versions = runCatching { manager.versions() }.getOrElse { error ->
            RuntimeInstallStatus.fail(error.message)
            emptyList()
        }
        RuntimeInstallStatus.refresh(context)
        operation = manager.operation()
    }
    LaunchedEffect(state, progress, error) { operation = manager.operation() }

    SettingsSubPageScaffold(
        title = stringResource(R.string.offline_resources_title),
        onBack = onBack,
    ) {
        item { SectionLabel(stringResource(R.string.offline_resources_runtime)) }
        item {
            SettingsGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.md),
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
                            modifier = Modifier.padding(top = MuseSpacing.xs),
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                    ) {
                        if (progress != null) {
                            MuseCapsuleButton(
                                text = stringResource(R.string.runtime_action_cancel),
                                onClick = { RuntimeInstallService.cancel(context) },
                                variant = IosCapsuleButtonVariant.Secondary,
                                fillWidth = false,
                            )
                        } else {
                            MuseCapsuleButton(
                                text = stringResource(
                                    if (state is RuntimeState.Ready) R.string.runtime_action_update
                                    else R.string.runtime_card_install,
                                ),
                                onClick = { showVersionPicker = true },
                                fillWidth = false,
                            )
                        }
                        if (state is RuntimeState.Ready) {
                            MuseCapsuleButton(
                                text = stringResource(R.string.runtime_action_repair),
                                enabled = progress == null,
                                onClick = { RuntimeInstallService.startRepair(context) },
                                variant = IosCapsuleButtonVariant.Secondary,
                                fillWidth = false,
                            )
                            if (manager.hasRollbackVersion()) {
                                MuseCapsuleButton(
                                    text = stringResource(R.string.runtime_action_rollback),
                                    enabled = progress == null,
                                    onClick = {
                                        scope.launch {
                                            manager.rollback()
                                            RuntimeInstallStatus.refresh(context)
                                        }
                                    },
                                    variant = IosCapsuleButtonVariant.Secondary,
                                    fillWidth = false,
                                )
                            }
                        }
                        if (
                            progress == null &&
                                (operation?.phase == RuntimeInstallPhase.CANCELLED ||
                                    operation?.phase == RuntimeInstallPhase.FAILED_RETRYABLE)
                        ) {
                            MuseCapsuleButton(
                                text = stringResource(R.string.runtime_action_resume),
                                onClick = { RuntimeInstallService.startResume(context) },
                                variant = IosCapsuleButtonVariant.Secondary,
                                fillWidth = false,
                            )
                        }
                    }
                    // 卸载单独成行，与更新/修复保持同一胶囊样式，避免三键并排把文字压成竖排，并带二次确认防误删 230MB 环境。
                    if (state is RuntimeState.Ready) {
                        MuseCapsuleButton(
                            text = stringResource(R.string.runtime_action_uninstall),
                            enabled = progress == null,
                            onClick = { showUninstallConfirm = true },
                            destructive = true,
                            fillWidth = false,
                        )
                    }
                }
                val currentProgress = progress
                if (currentProgress != null) {
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
                        modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = MuseSpacing.sm),
                    )
                }
                error?.let { message ->
                    Text(
                        text = stringResource(R.string.runtime_card_failed_detail, message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = MuseSpacing.sm),
                    )
                }
            }
        }

        item {
            if (showUninstallConfirm) {
                ConfirmDeleteDialog(
                    title = stringResource(R.string.runtime_action_uninstall),
                    itemName = stringResource(R.string.offline_resources_runtime),
                    onConfirm = {
                        showUninstallConfirm = false
                        scope.launch {
                            manager.uninstall()
                            RuntimeInstallStatus.refresh(context)
                        }
                    },
                    onDismiss = { showUninstallConfirm = false },
                )
            }
        }
        item {
            if (showVersionPicker) {
                RuntimeVersionPicker(
                    versions = versions,
                    currentRevision = manager.currentRevision(),
                    onDismiss = { showVersionPicker = false },
                    onInstallRemote = { version ->
                        showVersionPicker = false
                        RuntimeInstallService.startSelected(context, version.revision)
                    },
                    onInstallLocal = { version ->
                        showVersionPicker = false
                        localInstallRevision = version.revision
                        localArchivePicker.launch(
                            arrayOf("application/gzip", "application/x-gzip", "application/octet-stream"),
                        )
                    },
                    onInstallCustom = { version ->
                        showVersionPicker = false
                        customUrlVersion = version
                    },
                )
            }
        }
        item {
            customUrlVersion?.let { version ->
                RuntimeCustomUrlDialog(
                    version = version,
                    onDismiss = { customUrlVersion = null },
                    onInstall = { url, sha256 ->
                        customUrlVersion = null
                        RuntimeInstallService.startCustomUrl(context, version.revision, url, sha256)
                    },
                )
            }
        }

        // 语音模型和运行环境是同一类东西（都是几百 MB 的按需下载），
        // 以前一个在语音设置页里再点一层、一个在数据与备份里，用户得记住两处。
        // 现在统一在这页：先看清单，再决定下哪个。
        item {
            SectionLabel(
                stringResource(R.string.offline_resources_asr),
                modifier = Modifier.padding(top = MusePaddings.cardGap),
            )
        }
        item { LocalModelDownloadCard() }
        item {
            Text(
                text = stringResource(R.string.asr_local_model_page_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private suspend fun copySelectedArchive(context: android.content.Context, uri: Uri, version: RuntimeVersion): File =
    withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "runtime/imports").apply { mkdirs() }
        if (!directory.isDirectory) throw IOException("无法创建本地归档目录")
        val destination = File(directory, "import-${version.revision}-${System.currentTimeMillis()}.tar.gz")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("无法读取所选文件")
            input.use { source ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        copied += read
                        if (copied > version.archive.sizeBytes) throw IOException("本地归档大于所选版本的声明大小")
                        output.write(buffer, 0, read)
                    }
                    if (copied != version.archive.sizeBytes) {
                        throw IOException("本地归档大小不匹配：$copied/${version.archive.sizeBytes} bytes")
                    }
                }
            }
            destination
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }
