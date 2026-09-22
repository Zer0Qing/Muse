package io.zer0.muse.ui.settings

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.OAuthConfig
import io.zer0.muse.R
import io.zer0.muse.auth.OAuthManager
import io.zer0.muse.connector.ConnectorConfig
import io.zer0.muse.connector.ConnectorStore
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.launch

/**
 * v1.0.92: OAuth 连接器页 — 连接外部服务(通用 OAuth2)。
 *
 * 作用:为后续"数据接入类"功能(日历/笔记/代码托管等)提供统一的授权通道;
 * 授权走系统浏览器 + Deep Link 回调,凭证由 OAuthManager 加密保存。
 */
@Composable
fun ConnectorSettingsPage(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ConnectorStore(context.applicationContext) }
    var connectors by remember { mutableStateOf<List<ConnectorConfig>>(emptyList()) }
    var showAdd by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<ConnectorConfig?>(null) }
    var deleteTarget by remember { mutableStateOf<ConnectorConfig?>(null) }

    LaunchedEffect(Unit) { connectors = store.load() }

    SettingsSubPageScaffold(
        title = stringResource(R.string.connector_page_title),
        onBack = onBack,
    ) {
        item(key = "intro") {
            CardGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                item {
                    Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                        Text(
                            text = stringResource(R.string.connector_intro),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }

        connectors.forEach { cfg ->
            item(key = cfg.id) {
                CardGroup(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    item {
                        ConnectorRow(
                            config = cfg,
                            onConnect = {
                                val activity = context as? Activity
                                if (activity == null) {
                                    MuseToast.show(context.getString(R.string.connector_connect_failed, "无法获取 Activity"))
                                } else {
                                    scope.launch {
                                        val oauthConfig = OAuthConfig(
                                            clientId = cfg.clientId,
                                            clientSecret = cfg.clientSecret.takeIf { it.isNotBlank() },
                                            authorizeUrl = cfg.authorizeUrl,
                                            tokenUrl = cfg.tokenUrl,
                                            redirectUri = "io.zer0.muse://oauth/callback",
                                            scope = cfg.scope,
                                        )
                                        OAuthManager.launchAuthorizationCodeFlow(
                                            activity = activity,
                                            config = oauthConfig,
                                            providerId = "connector_${cfg.id}",
                                        ).fold(
                                            onSuccess = {
                                                MuseToast.show(context.getString(R.string.connector_connect_success, cfg.name.ifBlank { cfg.id }))
                                            },
                                            onFailure = { e ->
                                                MuseToast.show(context.getString(R.string.connector_connect_failed, e.message ?: ""))
                                            },
                                        )
                                    }
                                }
                            },
                            onEdit = { editTarget = cfg },
                            onDelete = { deleteTarget = cfg },
                        )
                    }
                }
            }
        }

        if (connectors.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.connector_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        }

        item(key = "add") {
            Surface(
                shape = MuseShapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable { showAdd = true },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                    Text(
                        text = stringResource(R.string.connector_add),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    if (showAdd || editTarget != null) {
        ConnectorEditDialog(
            initial = editTarget,
            onDismiss = {
                showAdd = false
                editTarget = null
            },
            onSave = { cfg ->
                scope.launch {
                    val updated = connectors.filterNot { it.id == cfg.id } + cfg
                    store.save(updated)
                    connectors = updated
                    showAdd = false
                    editTarget = null
                }
            },
        )
    }

    deleteTarget?.let { cfg ->
        MuseDialog(
            onDismissRequest = { deleteTarget = null },
            title = stringResource(R.string.channel_delete_confirm),
            content = { Text(cfg.name.ifBlank { cfg.id }) },
            confirmText = stringResource(R.string.skill_delete),
            onConfirm = {
                scope.launch {
                    val updated = connectors.filterNot { it.id == cfg.id }
                    store.save(updated)
                    connectors = updated
                }
                deleteTarget = null
            },
            dismissText = stringResource(R.string.settings_common_cancel),
            onDismiss = { deleteTarget = null },
        )
    }
}

/** 单条连接器行:名称/端点 + 连接/编辑/删除。 */
@Composable
private fun ConnectorRow(
    config: ConnectorConfig,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = config.name.ifBlank { config.clientId },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = config.authorizeUrl.ifBlank { "-" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
            )
        }
        Text(
            text = stringResource(R.string.connector_connect),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(onClick = onConnect)
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Text(
            text = stringResource(R.string.channel_edit),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(onClick = onEdit)
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Text(
            text = stringResource(R.string.skill_delete),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .clickable(onClick = onDelete)
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

/** 添加/编辑连接器对话框。 */
@Composable
private fun ConnectorEditDialog(
    initial: ConnectorConfig?,
    onDismiss: () -> Unit,
    onSave: (ConnectorConfig) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var authorizeUrl by remember { mutableStateOf(initial?.authorizeUrl.orEmpty()) }
    var tokenUrl by remember { mutableStateOf(initial?.tokenUrl.orEmpty()) }
    var scopeText by remember { mutableStateOf(initial?.scope.orEmpty()) }
    var clientId by remember { mutableStateOf(initial?.clientId.orEmpty()) }
    var clientSecret by remember { mutableStateOf(initial?.clientSecret.orEmpty()) }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(if (initial == null) R.string.connector_add else R.string.channel_edit_title),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MuseTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.channel_field_name)) },
                )
                MuseTextField(
                    value = authorizeUrl,
                    onValueChange = { authorizeUrl = it },
                    label = { Text(stringResource(R.string.connector_field_authorize)) },
                )
                MuseTextField(
                    value = tokenUrl,
                    onValueChange = { tokenUrl = it },
                    label = { Text(stringResource(R.string.connector_field_token)) },
                )
                MuseTextField(
                    value = scopeText,
                    onValueChange = { scopeText = it },
                    label = { Text(stringResource(R.string.connector_field_scope)) },
                )
                MuseTextField(
                    value = clientId,
                    onValueChange = { clientId = it },
                    label = { Text(stringResource(R.string.connector_field_client_id)) },
                )
                MuseTextField(
                    value = clientSecret,
                    onValueChange = { clientSecret = it },
                    label = { Text(stringResource(R.string.connector_field_client_secret)) },
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmText = stringResource(R.string.channel_save),
        onConfirm = {
            onSave(
                ConnectorConfig(
                    id = initial?.id ?: ("cn_" + System.currentTimeMillis()),
                    name = name.trim(),
                    authorizeUrl = authorizeUrl.trim(),
                    tokenUrl = tokenUrl.trim(),
                    scope = scopeText.trim(),
                    clientId = clientId.trim(),
                    clientSecret = clientSecret.trim(),
                    createdAt = initial?.createdAt ?: System.currentTimeMillis(),
                ),
            )
        },
        dismissText = stringResource(R.string.settings_common_cancel),
        onDismiss = onDismiss,
    )
}
