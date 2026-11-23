package io.zer0.muse.ui.runtime

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.zer0.muse.R
import io.zer0.muse.runtime.RuntimeVersion
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.theme.MuseButtonSize
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuntimeVersionPicker(
    versions: List<RuntimeVersion>,
    currentRevision: String?,
    onDismiss: () -> Unit,
    onInstallRemote: (RuntimeVersion) -> Unit,
    onInstallLocal: (RuntimeVersion) -> Unit,
    onInstallCustom: (RuntimeVersion) -> Unit,
) {
    val context = LocalContext.current
    var selectedRevision by remember(versions, currentRevision) {
        mutableStateOf(
            versions.firstOrNull { it.revision == currentRevision }?.revision
                ?: versions.firstOrNull()?.revision,
        )
    }
    val selectedVersion = versions.firstOrNull { it.revision == selectedRevision }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.runtime_version_picker_title),
        content = {
            // MuseDialog 外层已提供 verticalScroll，此处不再嵌套 verticalScroll，避免无限高度约束崩溃。
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                versions.forEach { version ->
                    val selected = selectedRevision == version.revision
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected, onClick = { selectedRevision = version.revision })
                            .padding(vertical = MusePaddings.tightGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = { selectedRevision = version.revision })
                        Column(modifier = Modifier.weight(1f).padding(start = MusePaddings.tightGap)) {
                            Text(
                                text = version.label.ifBlank { version.revision },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = version.revision + if (version.revision == currentRevision) {
                                    " · ${stringResource(R.string.runtime_version_current)}"
                                } else {
                                    ""
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(
                                    R.string.runtime_version_download_size,
                                    Formatter.formatFileSize(context, version.archive.sizeBytes),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (versions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.runtime_version_list_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    HorizontalDivider(modifier = Modifier.padding(vertical = MuseSpacing.xs))
                    // 次要安装方式放在正文 FlowRow，避免把多个按钮塞进弹窗底部导致与取消按钮重叠。
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                    ) {
                        MuseCapsuleButton(
                            text = stringResource(R.string.runtime_action_local_install),
                            onClick = { selectedVersion?.let(onInstallLocal) },
                            enabled = selectedVersion != null,
                            variant = IosCapsuleButtonVariant.Secondary,
                            size = MuseButtonSize.Compact,
                            fillWidth = false,
                        )
                        MuseCapsuleButton(
                            text = stringResource(R.string.runtime_action_custom_url),
                            onClick = { selectedVersion?.let(onInstallCustom) },
                            enabled = selectedVersion != null,
                            variant = IosCapsuleButtonVariant.Secondary,
                            size = MuseButtonSize.Compact,
                            fillWidth = false,
                        )
                    }
                }
            }
        },
        confirmText = stringResource(R.string.runtime_card_install),
        onConfirm = { selectedVersion?.let(onInstallRemote) },
        confirmEnabled = selectedVersion != null,
        dismissText = stringResource(R.string.common_cancel),
        onDismiss = onDismiss,
    )
}

@Composable
fun RuntimeCustomUrlDialog(
    version: RuntimeVersion,
    onDismiss: () -> Unit,
    onInstall: (url: String, sha256: String?) -> Unit,
) {
    var url by remember(version.revision) { mutableStateOf("") }
    var sha256 by remember(version.revision) { mutableStateOf("") }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.runtime_custom_url_title, version.revision),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
                MuseTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.runtime_custom_url_address)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MuseTextField(
                    value = sha256,
                    onValueChange = { sha256 = it },
                    label = { Text(stringResource(R.string.runtime_custom_url_sha256)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (sha256.isBlank()) {
                    Text(
                        text = stringResource(R.string.runtime_custom_url_unverified_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmText = stringResource(R.string.runtime_card_install),
        onConfirm = { onInstall(url.trim(), sha256.trim().takeIf(String::isNotEmpty)) },
        confirmEnabled = url.isNotBlank(),
        dismissText = stringResource(R.string.common_cancel),
        onDismiss = onDismiss,
    )
}
