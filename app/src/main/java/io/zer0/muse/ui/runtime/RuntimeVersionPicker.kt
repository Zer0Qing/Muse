package io.zer0.muse.ui.runtime

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.zer0.muse.ui.theme.MusePaddings

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
    var selectedRevision by remember(versions) { mutableStateOf<String?>(null) }
    val selectedVersion = versions.firstOrNull { it.revision == selectedRevision }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.runtime_version_picker_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
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
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(
                    onClick = { selectedVersion?.let(onInstallCustom) },
                    enabled = selectedVersion != null,
                ) { Text(stringResource(R.string.runtime_action_custom_url)) }
                Row(horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
                    TextButton(
                        onClick = { selectedVersion?.let(onInstallLocal) },
                        enabled = selectedVersion != null,
                    ) {
                        Text(stringResource(R.string.runtime_action_local_install))
                    }
                    Button(
                        onClick = { selectedVersion?.let(onInstallRemote) },
                        enabled = selectedVersion != null,
                    ) {
                        Text(stringResource(R.string.runtime_card_install))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.runtime_custom_url_title, version.revision)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.runtime_custom_url_address)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = sha256,
                    onValueChange = { sha256 = it },
                    label = { Text(stringResource(R.string.runtime_custom_url_sha256)) },
                    singleLine = true,
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
        confirmButton = {
            Button(
                onClick = { onInstall(url.trim(), sha256.trim().takeIf(String::isNotEmpty)) },
                enabled = url.isNotBlank(),
            ) {
                Text(stringResource(R.string.runtime_card_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
