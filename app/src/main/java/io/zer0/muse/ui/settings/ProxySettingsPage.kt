package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.ProxyConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseSecretTextField
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.actionItem
import io.zer0.muse.ui.common.surface.switchItem
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 全局网络代理设置页。
 *
 * 支持 HTTP / SOCKS5 代理，可配置主机、端口、用户名与密码。
 * 代理走动态 ProxySelector，保存后新连接立即生效。
 */
@Composable
@Suppress("LongMethod")
fun ProxySettingsPage(onBack: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val proxyConfig by settings.proxyConfigFlow.collectAsStateWithLifecycle(initialValue = ProxyConfig())
    val scope = rememberCoroutineScope()

    // 本地草稿：口令不进 saved state（部分 ROM 会落盘），其余字段仍走 rememberSaveable 保草稿。
    var enabled by rememberSaveable(proxyConfig.enabled) { mutableStateOf(proxyConfig.enabled) }
    var type by rememberSaveable(proxyConfig.type) { mutableStateOf(proxyConfig.type) }
    var host by rememberSaveable(proxyConfig.host) { mutableStateOf(proxyConfig.host) }
    var portText by rememberSaveable(proxyConfig.port) { mutableStateOf(proxyConfig.port.toString()) }
    var username by rememberSaveable(proxyConfig.username) { mutableStateOf(proxyConfig.username) }
    var password by remember(proxyConfig.password) { mutableStateOf(proxyConfig.password) }
    var bypass by rememberSaveable(proxyConfig.bypassHosts) { mutableStateOf(proxyConfig.bypassHosts) }
    var saving by rememberSaveable { mutableStateOf(false) }

    val proxyTypes = listOf(
        "HTTP" to stringResource(R.string.proxy_type_http),
        "SOCKS5" to stringResource(R.string.proxy_type_socks5),
    )
    val proxySavedText = stringResource(R.string.proxy_saved_needs_restart)

    // 表单是否有未保存改动：返回时提示，避免改了一堆参数直接退出去。
    val dirty = enabled != proxyConfig.enabled ||
        type != proxyConfig.type ||
        host != proxyConfig.host ||
        portText != proxyConfig.port.toString() ||
        username != proxyConfig.username ||
        password != proxyConfig.password ||
        bypass != proxyConfig.bypassHosts

    fun save() {
        val port = portText.toIntOrNull()?.coerceIn(1, 65535) ?: 7890
        scope.launch {
            saving = true
            try {
                settings.saveProxyConfig(
                    ProxyConfig(
                        enabled = enabled,
                        type = type,
                        host = host.trim(),
                        port = port,
                        username = username.trim(),
                        password = password,
                        bypassHosts = bypass.trim(),
                    ),
                )
                MuseToast.show(proxySavedText)
            } finally {
                saving = false
            }
        }
    }

    SettingsFormScaffold(
        title = stringResource(R.string.proxy_title),
        dirty = dirty,
        onSave = { save() },
        onDismiss = onBack,
    ) {
        item(key = "proxy_enable_group") {
            CardGroup {
                switchItem(
                    key = "proxy_enable",
                    title = stringResource(R.string.proxy_enable),
                    subtitle = if (enabled) {
                        stringResource(R.string.common_state_enabled)
                    } else {
                        stringResource(R.string.proxy_disabled)
                    },
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.route,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
            }
        }

        item(key = "proxy_connection_group") {
            CardGroup(title = { Text(stringResource(R.string.proxy_title)) }) {
                content(key = "proxy_type") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                    ) {
                        Text(
                            text = stringResource(R.string.proxy_type),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        proxyTypes.forEach { (value, label) ->
                            MuseChip(
                                selected = type == value,
                                onClick = { type = value },
                                label = label,
                            )
                        }
                    }
                }
                content(key = "proxy_fields") {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                        verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                    ) {
                        MuseTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text(stringResource(R.string.proxy_host)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        MuseTextField(
                            value = portText,
                            onValueChange = { v -> portText = v.filter { it.isDigit() }.take(5) },
                            label = { Text(stringResource(R.string.proxy_port)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        MuseTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text(stringResource(R.string.proxy_username)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        MuseSecretTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = stringResource(R.string.proxy_password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // 不走代理的域名（bypass）：本地模型与内网服务若也走代理会直接失败。
                        MuseTextField(
                            value = bypass,
                            onValueChange = { bypass = it },
                            label = { Text(stringResource(R.string.proxy_bypass)) },
                            placeholder = { Text(stringResource(R.string.proxy_bypass_placeholder)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                content(key = "proxy_bypass_hint") {
                    Text(
                        text = stringResource(R.string.proxy_bypass_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(MusePaddings.cardInner),
                    )
                }
            }
        }

        // 连接测试：以前配完代理完全没有反馈，用户只能到对话里试。
        item(key = "proxy_test_group") {
            CardGroup {
                content(key = "proxy_test") {
                    ProxyTestRow(
                        proxyConfig = ProxyConfig(
                            enabled = enabled,
                            type = type,
                            host = host.trim(),
                            port = portText.toIntOrNull()?.coerceIn(1, 65535) ?: 7890,
                            username = username.trim(),
                            password = password,
                            bypassHosts = bypass,
                        ),
                    )
                }
                content(key = "proxy_restart_hint") {
                    Text(
                        text = stringResource(R.string.proxy_restart_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(MusePaddings.cardInner),
                    )
                }
                actionItem(
                    key = "proxy_save",
                    title = stringResource(R.string.common_save),
                    loading = saving,
                    enabled = !saving,
                    onClick = { save() },
                )
            }
        }
    }
}
