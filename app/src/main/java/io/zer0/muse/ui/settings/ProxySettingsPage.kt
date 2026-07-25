package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material3.Button
import io.zer0.muse.ui.common.IosChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.ProxyConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.MuseToast
import io.zer0.muse.ui.common.SectionLabel
import io.zer0.muse.ui.common.SettingsGroup
import io.zer0.muse.ui.common.SettingsGroupDivider
import io.zer0.muse.ui.common.SettingsSwitchRow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 全局网络代理设置页。
 *
 * 支持 HTTP / SOCKS5 代理,可配置主机、端口、用户名与密码。
 * 修改后需重启应用生效(OkHttpClient 在 Koin 初始化时读取一次配置)。
 */
@Composable
fun ProxySettingsPage(
    onBack: () -> Unit,
) {
    val settings: SettingsRepository = koinInject()
    val proxyConfig by settings.proxyConfigFlow.collectAsStateWithLifecycle(initialValue = ProxyConfig())
    val scope = rememberCoroutineScope()

    // 本地草稿,点击保存后才写入 DataStore
    var enabled by remember(proxyConfig.enabled) { mutableStateOf(proxyConfig.enabled) }
    var type by remember(proxyConfig.type) { mutableStateOf(proxyConfig.type) }
    var host by remember(proxyConfig.host) { mutableStateOf(proxyConfig.host) }
    var portText by remember(proxyConfig.port) { mutableStateOf(proxyConfig.port.toString()) }
    var username by remember(proxyConfig.username) { mutableStateOf(proxyConfig.username) }
    var password by remember(proxyConfig.password) { mutableStateOf(proxyConfig.password) }

    val proxyTypes = listOf("HTTP" to stringResource(R.string.proxy_type_http), "SOCKS5" to stringResource(R.string.proxy_type_socks5))
    val savedText = stringResource(R.string.action_save)

    SettingsSubPageScaffold(title = stringResource(R.string.proxy_title), onBack = onBack) {
        item { SectionLabel(stringResource(R.string.proxy_title)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = Icons.Outlined.SettingsEthernet,
                    title = stringResource(R.string.proxy_enable),
                    subtitle = if (enabled) stringResource(R.string.proxy_enabled) else stringResource(R.string.proxy_disabled),
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                )
                SettingsGroupDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.proxy_type),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    proxyTypes.forEach { (value, label) ->
                        IosChip(
                            selected = type == value,
                            onClick = { type = value },
                            label = label,
                        )
                    }
                }
                SettingsGroupDivider()
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.proxy_host)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                SettingsGroupDivider()
                OutlinedTextField(
                    value = portText,
                    onValueChange = { v ->
                        portText = v.filter { it.isDigit() }.take(5)
                    },
                    label = { Text(stringResource(R.string.proxy_port)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                SettingsGroupDivider()
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.proxy_username)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                SettingsGroupDivider()
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.proxy_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        item {
            Text(
                text = stringResource(R.string.proxy_restart_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        item {
            Button(
                onClick = {
                    val port = portText.toIntOrNull()?.coerceIn(1, 65535) ?: 7890
                    scope.launch {
                        settings.saveProxyConfig(
                            ProxyConfig(
                                enabled = enabled,
                                type = type,
                                host = host.trim(),
                                port = port,
                                username = username.trim(),
                                password = password,
                            )
                        )
                        MuseToast.show(savedText)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}
