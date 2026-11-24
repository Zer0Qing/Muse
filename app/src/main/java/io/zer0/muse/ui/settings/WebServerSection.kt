package io.zer0.muse.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.util.NetworkUtils
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.actionItem
import io.zer0.muse.ui.common.surface.navigationItem
import io.zer0.muse.ui.common.surface.switchItem
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.web.WebServer
import io.zer0.muse.web.WebServerConfig
import kotlinx.coroutines.launch

/**
 * 嵌入式 Web 服务器配置区块。
 *
 * 传入 [onOpenWebAccess] 时只保留摘要（运行状态、启停、局域网、访问地址），
 * 端口 / 密码 / PIN / HTTPS 等完整配置交给 [WebAccessPage]，避免两处各改一份。
 * 传 null（无独立配置页的老调用方）时保留完整内联配置，功能不缺失。
 */
@Suppress("LongMethod", "CyclomaticComplexMethod", "FunctionNaming")
@Composable
internal fun WebServerSection(
    settings: SettingsRepository,
    webServer: WebServer,
    onOpenWebAccess: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val config by settings.webServerConfigFlow.collectAsStateWithLifecycle(
        initialValue = WebServerConfig(),
    )
    val isRunning by webServer.isRunning.collectAsStateWithLifecycle(initialValue = false)
    val lastError by webServer.lastError.collectAsStateWithLifecycle(initialValue = null)
    var showPortDialog by remember { mutableStateOf(false) }
    var portInput by remember { mutableStateOf(config.port.toString()) }
    val localIp = remember { NetworkUtils.getLocalIpAddress() }
    val accessUrl = buildAccessUrl(config.allowLan, localIp, config.port, config.httpsEnabled)
    val runningAccessUrl = if (isRunning) accessUrl else null

    val statusText = when {
        isRunning -> stringResource(R.string.settings_web_status_running, config.port)
        lastError != null -> stringResource(R.string.settings_web_status_failed, lastError.orEmpty())
        else -> stringResource(R.string.settings_web_status_stopped)
    }

    fun applyConfig(next: WebServerConfig) {
        scope.launch {
            resultOf { webServer.saveAndApplyConfig(next) }
                .onError { _, t ->
                    MuseToast.show(context.getString(R.string.settings_web_failed, t?.message))
                }
        }
    }

    CardGroup(title = { Text(stringResource(R.string.settings_web_title)) }) {
        switchItem(
            key = "web_server_enabled",
            title = stringResource(R.string.settings_web_enable),
            subtitle = statusText,
            checked = config.enabled,
            onCheckedChange = { enabled -> applyConfig(config.copy(enabled = enabled)) },
            leadingContent = {
                Icon(
                    imageVector = MuseIcons.router,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            },
        )
        switchItem(
            key = "web_server_lan",
            title = stringResource(R.string.settings_web_allow_lan),
            subtitle = stringResource(R.string.settings_web_desc),
            checked = config.allowLan,
            onCheckedChange = { allowLan -> applyConfig(config.copy(allowLan = allowLan)) },
            leadingContent = {
                Icon(
                    imageVector = MuseIcons.wifi,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            },
        )
        item(
            key = "web_server_address",
            leadingContent = {
                Icon(
                    imageVector = MuseIcons.globe,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            },
            headlineContent = { Text(stringResource(R.string.settings_web_access_address)) },
            supportingContent = {
                Text(
                    when {
                        runningAccessUrl != null -> runningAccessUrl
                        lastError != null -> stringResource(R.string.settings_web_status_failed, lastError.orEmpty())
                        else -> stringResource(R.string.settings_web_not_running)
                    },
                )
            },
            trailingContent = if (runningAccessUrl != null) {
                {
                    MuseTactileButton(
                        icon = MuseIcons.copy,
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Muse WebServer", runningAccessUrl))
                            MuseToast.show(context.getString(R.string.settings_web_address_copied, runningAccessUrl))
                        },
                        contentDescription = stringResource(R.string.settings_web_copy_address),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        compact = true,
                    )
                }
            } else {
                null
            },
        )

        if (onOpenWebAccess != null) {
            // 摘要模式：高级配置全部在独立页，这里只负责导航。
            navigationItem(
                key = "web_server_open_settings",
                title = stringResource(R.string.settings_screen_web_access),
                subtitle = stringResource(R.string.settings_web_pin_hint),
                leadingContent = {
                    Icon(
                        imageVector = MuseIcons.sliders,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                },
                onClick = onOpenWebAccess,
            )
        } else {
            // 老调用方：保留完整内联配置，不丢能力。
            switchItem(
                key = "web_server_https",
                title = stringResource(R.string.settings_web_https),
                checked = config.httpsEnabled,
                onCheckedChange = { enabled -> applyConfig(config.copy(httpsEnabled = enabled)) },
                leadingContent = {
                    Icon(
                        imageVector = MuseIcons.lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                },
            )
            actionItem(
                key = "web_server_port",
                title = stringResource(R.string.settings_web_port_subtitle, config.port),
                onClick = {
                    portInput = config.port.toString()
                    showPortDialog = true
                },
            )
            actionItem(
                key = "web_server_regenerate_pin",
                title = stringResource(R.string.settings_web_regenerate_pin),
                onClick = {
                    val newPin = WebServerConfig.generateRandomPin()
                    scope.launch {
                        resultOf { webServer.saveAndApplyConfig(config.copy(pin = newPin)) }
                            .onSuccess {
                                MuseToast.show(context.getString(R.string.settings_web_new_pin, newPin), 3500)
                            }
                            .onError { _, t ->
                                MuseToast.show(context.getString(R.string.settings_web_failed, t?.message))
                            }
                    }
                },
            )
            actionItem(
                key = "web_server_regenerate_password",
                title = stringResource(R.string.settings_web_regenerate_password),
                onClick = {
                    val newPassword = WebServerConfig.generateRandomPassword()
                    scope.launch {
                        resultOf { webServer.saveAndApplyConfig(config.copy(password = newPassword)) }
                            .onSuccess {
                                MuseToast.show(
                                    context.getString(R.string.settings_web_new_password, newPassword),
                                    3500,
                                )
                            }
                            .onError { _, t ->
                                MuseToast.show(context.getString(R.string.settings_web_failed, t?.message))
                            }
                    }
                },
            )
        }
    }

    // P2-13: 明显提示 — 同一 Wi-Fi 下他人可通过 IP + PIN 访问
    if (runningAccessUrl != null && config.pin.isNotBlank() && config.allowLan) {
        CardGroup(modifier = Modifier.padding(top = MusePaddings.cardGap)) {
            content(key = "web_server_lan_warning") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.md),
                ) {
                    Icon(
                        imageVector = MuseIcons.info,
                        contentDescription = stringResource(R.string.settings_web_security_hint),
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.settings_web_access_warning),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.settings_web_address_pin, runningAccessUrl, config.pin),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.settings_web_pin_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    // 端口修改对话框（仅摘要模式以外会用到）
    if (showPortDialog) {
        MuseDialog(
            onDismissRequest = { showPortDialog = false },
            title = stringResource(R.string.settings_web_edit_port),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    MuseTextField(
                        value = portInput,
                        onValueChange = { portInput = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.settings_web_port_number)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.settings_web_port_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = MuseSpacing.sm),
                    )
                }
            },
            confirmText = stringResource(R.string.common_save),
            onConfirm = {
                val port = portInput.toIntOrNull()
                if (port == null || port < 1024 || port > 65535) {
                    MuseToast.show(context.getString(R.string.settings_web_port_invalid))
                    return@MuseDialog
                }
                scope.launch {
                    resultOf { webServer.saveAndApplyConfig(config.copy(port = port)) }
                        .onSuccess {
                            showPortDialog = false
                            MuseToast.show(context.getString(R.string.settings_web_port_updated))
                        }
                        .onError { _, t ->
                            MuseToast.show(context.getString(R.string.settings_web_failed, t?.message))
                        }
                }
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showPortDialog = false },
        )
    }
}

/** R-SEC-03: 默认仅本机;开启局域网访问后才展示局域网 IP 地址。 */
private fun buildAccessUrl(allowLan: Boolean, localIp: String?, port: Int, httpsEnabled: Boolean): String? {
    val scheme = if (httpsEnabled) "https" else "http"
    return if (allowLan) localIp?.let { "$scheme://$it:$port" } else "$scheme://127.0.0.1:$port"
}
