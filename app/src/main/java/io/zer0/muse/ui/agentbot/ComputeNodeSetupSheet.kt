@file:Suppress("LongMethod", "LongParameterList")

package io.zer0.muse.ui.agentbot

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.muse.R
import io.zer0.muse.computenode.BootstrapPhase
import io.zer0.muse.computenode.BootstrapStatus
import io.zer0.muse.data.computenode.ComputeNodeMode
import io.zer0.muse.data.computenode.RemoteComputeNodeEntity
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseCapsuleTab
import io.zer0.muse.ui.common.form.MuseSecretTextField
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.state.MuseIndeterminateProgressBar
import io.zer0.muse.ui.common.state.MuseProgressBar
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.qrcode.QrCodeScanDialog
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseBorderWidth
import io.zer0.muse.ui.theme.MuseButtonSize
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.theme.statusColors

/**
 * 工作台「它的云端电脑」接入向导。
 *
 * 两个入口共用同一套四阶段进度：SSH 一键接入在手机侧完成自举，扫码配对
 * 只做一次带证书指纹钉扎的健康校验。凭据输入全部走掩码输入框；真正落库
 * 前由 ViewModel 调用 SecureKeyStore 加密，本文件不接触任何持久化。
 */
@Composable
fun ComputeNodeSetupSheet(
    state: ComputeNodeSetupUiState,
    nodes: List<RemoteComputeNodeEntity>,
    onDismiss: () -> Unit,
    onStartSsh: (ComputeNodeSshRequest) -> Unit,
    onStartPairing: (String) -> Unit,
    onDeleteNode: (String) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var deleteTarget by remember { mutableStateOf<RemoteComputeNodeEntity?>(null) }
    val busy = state.running

    MuseBottomSheet(
        onDismissRequest = onDismiss,
        maxHeightFraction = 0.92f,
        headerContent = {
            Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    Text(
                        text = stringResource(R.string.compute_node_setup_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    MuseTactileButton(
                        icon = MuseIcons.x,
                        contentDescription = stringResource(R.string.common_close),
                        onClick = onDismiss,
                        compact = true,
                    )
                }
                MuseCapsuleTab(
                    tabs = listOf(
                        stringResource(R.string.compute_node_tab_ssh),
                        stringResource(R.string.compute_node_tab_pairing),
                    ),
                    selectedIndex = selectedTab,
                    onSelect = { index -> selectedTab = index.coerceIn(0, 1) },
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.disabled),
                )
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap)) {
            if (nodes.isNotEmpty()) {
                ExistingNodesSection(nodes = nodes, onDelete = { deleteTarget = it })
            }
            when (selectedTab) {
                0 -> SshSetupForm(busy = busy, onStart = onStartSsh)
                else -> PairingSetupForm(busy = busy, onStart = onStartPairing)
            }
            if (state.running || state.finished || state.error.isNotBlank() || state.phaseRows.isNotEmpty()) {
                ComputeNodeSetupProgress(state = state)
            }
        }
    }

    deleteTarget?.let { target ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.compute_node_delete),
            itemName = target.name.ifBlank { target.host },
            consequence = stringResource(R.string.compute_node_delete_consequence),
            onConfirm = {
                deleteTarget = null
                onDeleteNode(target.nodeId)
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun ExistingNodesSection(
    nodes: List<RemoteComputeNodeEntity>,
    onDelete: (RemoteComputeNodeEntity) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
        SectionLabel(text = stringResource(R.string.compute_node_existing_title))
        Surface(
            shape = MuseShapes.semiLarge,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MuseAlpha.mutedSurface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                nodes.forEachIndexed { index, node ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    ) {
                        Icon(
                            imageVector = MuseIcons.server,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = node.name.ifBlank { node.host },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = node.host + " · " + connectionModeLabel(node.connectionMode),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        MuseCapsuleButton(
                            text = stringResource(R.string.compute_node_delete),
                            onClick = { onDelete(node) },
                            variant = IosCapsuleButtonVariant.Text,
                            fillWidth = false,
                            size = MuseButtonSize.Compact,
                        )
                    }
                    if (index != nodes.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.mutedSurface),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SshSetupForm(
    busy: Boolean,
    onStart: (ComputeNodeSshRequest) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    var sshPort by rememberSaveable { mutableStateOf("22") }
    var username by rememberSaveable { mutableStateOf("") }
    var authIndex by rememberSaveable { mutableStateOf(0) }
    // 密码与私钥只用 remember 持有：不能进入 savedInstanceState，避免被系统写成明文落盘。
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var daemonPort by rememberSaveable { mutableStateOf("18765") }
    var createSwap by rememberSaveable { mutableStateOf(true) }
    var installBrowser by rememberSaveable { mutableStateOf(true) }

    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
        SectionLabel(text = stringResource(R.string.compute_node_ssh_section))
        MuseTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.compute_node_field_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        MuseTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text(stringResource(R.string.compute_node_field_host)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            MuseTextField(
                value = sshPort,
                onValueChange = { sshPort = it.filter(Char::isDigit).take(5) },
                label = { Text(stringResource(R.string.compute_node_field_ssh_port)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            MuseTextField(
                value = daemonPort,
                onValueChange = { daemonPort = it.filter(Char::isDigit).take(5) },
                label = { Text(stringResource(R.string.compute_node_field_daemon_port)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        MuseTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(R.string.compute_node_field_username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        MuseCapsuleTab(
            tabs = listOf(
                stringResource(R.string.compute_node_auth_password),
                stringResource(R.string.compute_node_auth_private_key),
            ),
            selectedIndex = authIndex,
            onSelect = { authIndex = it.coerceIn(0, 1) },
        )
        if (authIndex == 0) {
            MuseSecretTextField(
                value = password,
                onValueChange = { password = it },
                label = stringResource(R.string.compute_node_field_password),
                placeholder = stringResource(R.string.compute_node_field_password_hint),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            MuseTextField(
                value = privateKey,
                onValueChange = { privateKey = it },
                label = { Text(stringResource(R.string.compute_node_field_private_key)) },
                placeholder = { Text(stringResource(R.string.compute_node_field_private_key_hint)) },
                visualTransformation = PasswordVisualTransformation(),
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = stringResource(R.string.compute_node_password_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OptionSwitchRow(
            title = stringResource(R.string.compute_node_swap_title),
            subtitle = stringResource(R.string.compute_node_swap_subtitle),
            checked = createSwap,
            onCheckedChange = { createSwap = it },
        )
        SectionLabel(text = stringResource(R.string.compute_node_packs_title))
        OptionSwitchRow(
            title = stringResource(R.string.compute_node_pack_core),
            subtitle = stringResource(R.string.compute_node_pack_core_desc),
            checked = true,
            onCheckedChange = null,
        )
        OptionSwitchRow(
            title = stringResource(R.string.compute_node_pack_browser),
            subtitle = stringResource(R.string.compute_node_pack_browser_desc),
            checked = installBrowser,
            onCheckedChange = { installBrowser = it },
        )
        MuseCapsuleButton(
            text = stringResource(R.string.compute_node_start_ssh),
            onClick = {
                onStart(
                    ComputeNodeSshRequest(
                        name = name,
                        host = host,
                        sshPort = sshPort.toIntOrNull() ?: 0,
                        username = username,
                        authType = if (authIndex == 0) {
                            ComputeNodeSshAuthType.PASSWORD
                        } else {
                            ComputeNodeSshAuthType.PRIVATE_KEY
                        },
                        password = password,
                        privateKey = privateKey,
                        daemonPort = daemonPort.toIntOrNull() ?: 0,
                        createSwap = createSwap,
                        installBrowserPack = installBrowser,
                    ),
                )
            },
            enabled = !busy,
            loading = busy,
            leadingIcon = MuseIcons.cloudUpload,
            size = MuseButtonSize.Medium,
        )
    }
}

@Composable
private fun PairingSetupForm(
    busy: Boolean,
    onStart: (String) -> Unit,
) {
    // 配对串内含节点 Token，同样不进入 savedInstanceState。
    var pairingRaw by remember { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
        SectionLabel(text = stringResource(R.string.compute_node_pairing_section))
        Text(
            text = stringResource(R.string.compute_node_pairing_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MuseSecretTextField(
            value = pairingRaw,
            onValueChange = { pairingRaw = it },
            label = stringResource(R.string.compute_node_pairing_field),
            placeholder = stringResource(R.string.compute_node_pairing_placeholder),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MuseCapsuleButton(
                text = stringResource(R.string.compute_node_start_pairing),
                onClick = { onStart(pairingRaw) },
                enabled = !busy && pairingRaw.isNotBlank(),
                loading = busy,
                leadingIcon = MuseIcons.qrcode,
                size = MuseButtonSize.Medium,
                fillWidth = false,
            )
            MuseCapsuleButton(
                text = stringResource(R.string.compute_node_pairing_scan),
                onClick = { showScanner = true },
                variant = IosCapsuleButtonVariant.Secondary,
                fillWidth = false,
                size = MuseButtonSize.Medium,
                leadingIcon = MuseIcons.camera,
            )
        }
    }
    if (showScanner) {
        QrCodeScanDialog(
            onDismiss = { showScanner = false },
            onResult = { content ->
                pairingRaw = content
                showScanner = false
            },
        )
    }
}

@Composable
private fun connectionModeLabel(mode: String): String = when (ComputeNodeMode.fromWire(mode)) {
    ComputeNodeMode.SSH_TUNNEL -> stringResource(R.string.compute_node_mode_ssh)
    ComputeNodeMode.DIRECT_TLS -> stringResource(R.string.compute_node_mode_direct)
    null -> stringResource(R.string.compute_node_mode_unknown)
}

@Composable
private fun OptionSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    Surface(
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MuseAlpha.mutedSurface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MuseSwitch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun ComputeNodeSetupProgress(state: ComputeNodeSetupUiState) {
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
                    imageVector = MuseIcons.cloudUpload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
                Text(
                    text = stringResource(R.string.compute_node_progress_title),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = state.totalPercent.toString() + "%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (state.phaseRows.isEmpty()) {
                if (state.running) {
                    MuseIndeterminateProgressBar()
                }
            } else {
                MuseProgressBar(progress = state.totalPercent / 100f)
                state.phaseRows.forEach { row ->
                    PhaseRow(row = row)
                }
            }
            if (state.message.isNotBlank() && state.error.isBlank()) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.error.isNotBlank()) {
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.statusColors.error,
                )
            }
            if (state.finished && state.error.isBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    Icon(
                        imageVector = MuseIcons.circleCheck,
                        contentDescription = null,
                        tint = MaterialTheme.statusColors.success,
                        modifier = Modifier.size(MuseIconSizes.iconTiny),
                    )
                    Text(
                        text = stringResource(R.string.compute_node_setup_done),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.statusColors.success,
                    )
                }
            }
        }
    }
}

@Composable
private fun PhaseRow(row: ComputeNodePhaseUi) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        val status = row.status
        if (status == BootstrapStatus.RUNNING) {
            MuseSpinner(size = MuseIconSizes.iconTiny)
        } else {
            Icon(
                imageVector = phaseIcon(status),
                contentDescription = null,
                tint = phaseColor(status),
                modifier = Modifier.size(MuseIconSizes.iconTiny),
            )
        }
        Text(
            text = phaseTitle(row.phase),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = phaseStatusLabel(status),
            style = MaterialTheme.typography.labelSmall,
            color = phaseColor(status),
        )
    }
}

@Composable
private fun phaseTitle(phase: BootstrapPhase): String = stringResource(
    when (phase) {
        BootstrapPhase.PROBE -> R.string.compute_node_phase_probe
        BootstrapPhase.DAEMON -> R.string.compute_node_phase_daemon
        BootstrapPhase.PACKS -> R.string.compute_node_phase_packs
        BootstrapPhase.VERIFY -> R.string.compute_node_phase_verify
    },
)

@Composable
private fun phaseStatusLabel(status: BootstrapStatus?): String = stringResource(
    when (status) {
        null -> R.string.compute_node_phase_pending
        BootstrapStatus.RUNNING -> R.string.compute_node_phase_running
        BootstrapStatus.OK -> R.string.compute_node_phase_ok
        BootstrapStatus.WARN -> R.string.compute_node_phase_warn
        BootstrapStatus.ERROR -> R.string.compute_node_phase_error
    },
)

private fun phaseIcon(status: BootstrapStatus?): androidx.compose.ui.graphics.vector.ImageVector = when (status) {
    null -> MuseIcons.circle
    BootstrapStatus.RUNNING -> MuseIcons.circle
    BootstrapStatus.OK -> MuseIcons.circleCheck
    BootstrapStatus.WARN -> MuseIcons.alertTriangle
    BootstrapStatus.ERROR -> MuseIcons.alertCircle
}

@Composable
private fun phaseColor(status: BootstrapStatus?): Color = when (status) {
    null -> MaterialTheme.colorScheme.onSurfaceVariant
    BootstrapStatus.RUNNING -> MaterialTheme.colorScheme.primary
    BootstrapStatus.OK -> MaterialTheme.statusColors.success
    BootstrapStatus.WARN -> MaterialTheme.statusColors.warning
    BootstrapStatus.ERROR -> MaterialTheme.statusColors.error
}
