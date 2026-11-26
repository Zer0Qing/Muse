@file:Suppress("LongMethod", "LongParameterList")

package io.zer0.muse.ui.agentbot

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.computenode.ComputeNodeSnapshot
import io.zer0.muse.data.computenode.ComputeNodeMode
import io.zer0.muse.data.computenode.ComputeNodeStatus
import io.zer0.muse.data.computenode.RemoteComputeNodeEntity
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseProgressBar
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseBorderWidth
import io.zer0.muse.ui.theme.MuseButtonSize
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.theme.statusColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 工作台里的云端电脑状态卡。
 *
 * 在线时展示 CPU / 内存 / 磁盘水位与已装能力包；离线或异常时把最近一次
 * 错误原因放在卡内，并提供「修复」入口（SSH 节点重跑幂等安装，直连节点重做健康校验）。
 * 卡片只消费 [ComputeNodeSnapshot] 与节点元数据，所有网络动作通过回调上抛。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComputeNodeCard(
    node: RemoteComputeNodeEntity,
    snapshot: ComputeNodeSnapshot?,
    onRefresh: () -> Unit,
    onRepair: () -> Unit,
    onInstallBrowserPack: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenFiles: () -> Unit,
    onDelete: () -> Unit,
) {
    val status = snapshot?.status ?: ComputeNodeStatus.fromWire(node.status) ?: ComputeNodeStatus.UNINITIALIZED
    val health = snapshot?.health
    val packs = health?.packs?.takeIf { it.isNotEmpty() } ?: parsePacks(node.installedPacksJson)
    val online = status == ComputeNodeStatus.ONLINE
    val busy = status == ComputeNodeStatus.BOOTSTRAPPING
    val hasBrowserPack = packs.contains(PACK_BROWSER)
    val sshTunnel = ComputeNodeMode.fromWire(node.connectionMode) == ComputeNodeMode.SSH_TUNNEL

    Surface(
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(MuseBorderWidth.hairline, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Icon(
                    imageVector = MuseIcons.server,
                    contentDescription = null,
                    tint = statusColor(status),
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = node.name.ifBlank { node.host },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = node.host + " · " + modeLabel(node.connectionMode),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusPill(status = status, busy = busy)
            }

            if (health != null) {
                MetricBar(
                    label = stringResource(R.string.compute_node_cpu),
                    value = stringResource(R.string.compute_node_cpu_value, health.cpuPercent),
                    progress = (health.cpuPercent / 100.0).toFloat(),
                )
                MetricBar(
                    label = stringResource(R.string.compute_node_memory),
                    value = stringResource(
                        R.string.compute_node_memory_value,
                        health.memUsedMb,
                        health.memTotalMb,
                    ),
                    progress = if (health.memTotalMb > 0L) {
                        (health.memUsedMb / health.memTotalMb.toDouble()).toFloat()
                    } else {
                        0f
                    },
                )
                MetricBar(
                    label = stringResource(R.string.compute_node_disk),
                    value = stringResource(R.string.compute_node_disk_free_gb, health.diskFreeMb / 1024.0),
                    progress = (health.diskFreeMb.toDouble() / DISK_REFERENCE_MB).toFloat(),
                )
            }

            if (packs.isNotEmpty() || online) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    packs.forEach { pack ->
                        PackChip(text = packLabel(pack), installed = true)
                    }
                    if (!hasBrowserPack) {
                        PackChip(text = stringResource(R.string.compute_node_pack_browser_missing), installed = false)
                    }
                }
            }

            if (snapshot?.lastError?.isNotBlank() == true) {
                Text(
                    text = stringResource(R.string.compute_node_last_error, snapshot.lastError),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.statusColors.error,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                MuseCapsuleButton(
                    text = stringResource(R.string.compute_node_open_terminal),
                    onClick = onOpenTerminal,
                    variant = IosCapsuleButtonVariant.Secondary,
                    fillWidth = false,
                    size = MuseButtonSize.Compact,
                    leadingIcon = MuseIcons.terminal,
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.compute_node_open_files),
                    onClick = onOpenFiles,
                    variant = IosCapsuleButtonVariant.Secondary,
                    fillWidth = false,
                    size = MuseButtonSize.Compact,
                    leadingIcon = MuseIcons.folder,
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.compute_node_refresh),
                    onClick = onRefresh,
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                    size = MuseButtonSize.Compact,
                    leadingIcon = MuseIcons.refresh,
                )
                if (online && !hasBrowserPack && sshTunnel) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.compute_node_install_browser),
                        onClick = onInstallBrowserPack,
                        variant = IosCapsuleButtonVariant.Secondary,
                        fillWidth = false,
                        size = MuseButtonSize.Compact,
                        leadingIcon = MuseIcons.download,
                    )
                }
                if (!online) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.compute_node_repair),
                        onClick = onRepair,
                        fillWidth = false,
                        size = MuseButtonSize.Compact,
                        leadingIcon = MuseIcons.wrench,
                    )
                }
                MuseCapsuleButton(
                    text = stringResource(R.string.compute_node_delete),
                    onClick = onDelete,
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                    size = MuseButtonSize.Compact,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(status: ComputeNodeStatus, busy: Boolean) {
    val color = statusColor(status)
    Surface(
        shape = MuseShapes.pill,
        color = color.copy(alpha = MuseAlpha.statusTint),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = MusePaddings.contentGap, vertical = MusePaddings.tinyGap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
        ) {
            if (busy) {
                MuseSpinner(size = MuseIconSizes.iconTiny, color = color)
            } else {
                Icon(
                    imageVector = MuseIcons.circle,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(MuseIconSizes.iconTiny),
                )
            }
            Text(
                text = statusLabel(status),
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}

@Composable
private fun MetricBar(label: String, value: String, progress: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MuseProgressBar(progress = progress)
    }
}

@Composable
private fun PackChip(text: String, installed: Boolean) {
    Surface(
        shape = MuseShapes.pill,
        color = if (installed) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MuseAlpha.mutedSurface)
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (installed) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = MusePaddings.contentGap, vertical = MusePaddings.tinyGap),
        )
    }
}

@Composable
private fun statusLabel(status: ComputeNodeStatus): String = stringResource(
    when (status) {
        ComputeNodeStatus.UNINITIALIZED -> R.string.compute_node_status_uninitialized
        ComputeNodeStatus.BOOTSTRAPPING -> R.string.compute_node_status_bootstrapping
        ComputeNodeStatus.ONLINE -> R.string.compute_node_status_online
        ComputeNodeStatus.OFFLINE -> R.string.compute_node_status_offline
        ComputeNodeStatus.ERROR -> R.string.compute_node_status_error
    },
)

@Composable
private fun statusColor(status: ComputeNodeStatus): Color = when (status) {
    ComputeNodeStatus.ONLINE -> MaterialTheme.statusColors.success
    ComputeNodeStatus.BOOTSTRAPPING -> MaterialTheme.statusColors.warning
    ComputeNodeStatus.ERROR -> MaterialTheme.statusColors.error
    ComputeNodeStatus.OFFLINE -> MaterialTheme.statusColors.warning
    ComputeNodeStatus.UNINITIALIZED -> MaterialTheme.statusColors.neutral
}

@Composable
private fun modeLabel(mode: String): String = when (ComputeNodeMode.fromWire(mode)) {
    ComputeNodeMode.SSH_TUNNEL -> stringResource(R.string.compute_node_mode_ssh)
    ComputeNodeMode.DIRECT_TLS -> stringResource(R.string.compute_node_mode_direct)
    null -> stringResource(R.string.compute_node_mode_unknown)
}

@Composable
private fun packLabel(pack: String): String = when (pack) {
    PACK_CORE -> stringResource(R.string.compute_node_pack_core)
    PACK_BROWSER -> stringResource(R.string.compute_node_pack_browser)
    else -> pack
}

private fun parsePacks(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    val array = try {
        Json.parseToJsonElement(raw) as? JsonArray
    } catch (e: Exception) {
        Logger.w("ComputeNodeCard", "云端电脑能力包记录无法解析: " + e.javaClass.simpleName)
        null
    } ?: return emptyList()
    return array.mapNotNull { element -> (element as? JsonPrimitive)?.contentOrNull }
}

/**
 * 远程终端面板。只负责输入与输出展示，命令执行由 ViewModel 的 MuseNodeClient 链路完成。
 */
@Composable
fun ComputeNodeTerminalSheet(
    state: ComputeRemoteTerminalUiState,
    onDismiss: () -> Unit,
    onRun: (String) -> Unit,
    onClear: () -> Unit,
) {
    var command by rememberSaveable { mutableStateOf("") }
    val scrollState = rememberScrollState()

    LaunchedEffect(state.lines.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    MuseBottomSheet(
        onDismissRequest = onDismiss,
        maxHeightFraction = 0.85f,
        headerContent = {
            Text(
                text = stringResource(R.string.compute_node_terminal_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
            Surface(
                shape = MuseShapes.semiLarge,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MuseAlpha.mutedSurface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = MusePaddings.dialogContentMaxHeight)
                        .verticalScroll(scrollState)
                        .padding(MusePaddings.cardInner),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
                ) {
                    if (state.lines.isEmpty()) {
                        Text(
                            text = stringResource(R.string.compute_node_terminal_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.lines.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            if (state.error.isNotBlank()) {
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.statusColors.error,
                )
            }
            MuseTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text(stringResource(R.string.compute_node_terminal_hint)) },
                placeholder = { Text(stringResource(R.string.compute_node_terminal_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MuseCapsuleButton(
                    text = stringResource(R.string.compute_node_terminal_run),
                    onClick = {
                        onRun(command)
                        command = ""
                    },
                    enabled = !state.running && command.isNotBlank(),
                    loading = state.running,
                    fillWidth = false,
                    size = MuseButtonSize.Compact,
                    leadingIcon = MuseIcons.play,
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.compute_node_terminal_clear),
                    onClick = onClear,
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                    size = MuseButtonSize.Compact,
                )
            }
        }
    }
}

/**
 * 远程文件列表与文本预览面板。目录通过一次 ls -1Ap 获取，路径边界由服务端
 * 的 ~/muse-workspace realpath 防线兜底，这里只做相对路径规范化。
 */
@Composable
fun ComputeNodeFilesSheet(
    state: ComputeRemoteFilesUiState,
    onDismiss: () -> Unit,
    onOpenDirectory: (String) -> Unit,
    onPreview: (String) -> Unit,
    onClosePreview: () -> Unit,
) {
    var pathInput by remember(state.path) { mutableStateOf(state.path) }

    MuseBottomSheet(
        onDismissRequest = onDismiss,
        maxHeightFraction = 0.85f,
        headerContent = {
            Text(
                text = stringResource(R.string.compute_node_files_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
            MuseTextField(
                value = pathInput,
                onValueChange = { pathInput = it },
                label = { Text(stringResource(R.string.compute_node_files_path)) },
                placeholder = { Text(stringResource(R.string.compute_node_files_path_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
                MuseCapsuleButton(
                    text = stringResource(R.string.compute_node_files_refresh),
                    onClick = { onOpenDirectory(pathInput) },
                    enabled = !state.loading,
                    loading = state.loading,
                    fillWidth = false,
                    size = MuseButtonSize.Compact,
                    leadingIcon = MuseIcons.refresh,
                )
            }
            if (state.error.isNotBlank()) {
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.statusColors.error,
                )
            }
            if (state.previewPath.isNotBlank()) {
                Surface(
                    shape = MuseShapes.semiLarge,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MuseAlpha.mutedSurface),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(MusePaddings.cardInner),
                        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = state.previewPath,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            MuseCapsuleButton(
                                text = stringResource(R.string.compute_node_files_back),
                                onClick = onClosePreview,
                                variant = IosCapsuleButtonVariant.Text,
                                fillWidth = false,
                                size = MuseButtonSize.Compact,
                            )
                        }
                        Text(
                            text = state.previewText.ifBlank {
                                stringResource(R.string.compute_node_files_loading)
                            },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = MusePaddings.dialogContentMaxHeight)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            } else {
                Surface(
                    shape = MuseShapes.semiLarge,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MuseAlpha.mutedSurface),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = MusePaddings.dialogContentMaxHeight)
                            .verticalScroll(rememberScrollState())
                            .padding(MusePaddings.cardInner),
                        verticalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
                    ) {
                        if (state.entries.isEmpty()) {
                            Text(
                                text = stringResource(R.string.compute_node_files_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            state.entries.forEach { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (entry.isDirectory) {
                                                onOpenDirectory(joinRemotePath(state.path, entry.name))
                                            } else {
                                                onPreview(entry.name)
                                            }
                                        }
                                        .padding(vertical = MusePaddings.tightGap),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                                ) {
                                    Icon(
                                        imageVector = if (entry.isDirectory) MuseIcons.folder else MuseIcons.fileText,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                                    )
                                    Text(
                                        text = entry.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun joinRemotePath(base: String, name: String): String =
    if (base.isEmpty()) name else base + "/" + name

private const val PACK_CORE = "core"
private const val PACK_BROWSER = "browser"

/** 磁盘水位基准线：5GB 视为健康满格，仅用于可视化，不代表服务器总容量。 */
private const val DISK_REFERENCE_MB = 5120.0
