package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import io.zer0.muse.ui.common.form.MuseChip
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.ProxyConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
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
    // 前端修复 (持久化-9): 表单草稿改 rememberSaveable,旋转/进程重建不丢已填内容;
    // key 沿用各字段原值(均为可 saveable 标量,且 ProxyConfig 无 id 字段,故不用 config 实例作 key)
    var enabled by rememberSaveable(proxyConfig.enabled) { mutableStateOf(proxyConfig.enabled) }
    var type by rememberSaveable(proxyConfig.type) { mutableStateOf(proxyConfig.type) }
    var host by rememberSaveable(proxyConfig.host) { mutableStateOf(proxyConfig.host) }
    var portText by rememberSaveable(proxyConfig.port) { mutableStateOf(proxyConfig.port.toString()) }
    var username by rememberSaveable(proxyConfig.username) { mutableStateOf(proxyConfig.username) }
    var password by rememberSaveable(proxyConfig.password) { mutableStateOf(proxyConfig.password) }
    var saving by rememberSaveable { mutableStateOf(false) }

    val proxyTypes = listOf("HTTP" to stringResource(R.string.proxy_type_http), "SOCKS5" to stringResource(R.string.proxy_type_socks5))
    val savedText = stringResource(R.string.action_save)

    SettingsSubPageScaffold(title = stringResource(R.string.proxy_title), onBack = onBack) {
        item { SectionLabel(stringResource(R.string.proxy_title)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = TablerIcons.Route,
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
                        MuseChip(
                            selected = type == value,
                            onClick = { type = value },
                            label = label,
                        )
                    }
                }
                SettingsGroupDivider()
                MuseTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.proxy_host)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                SettingsGroupDivider()
                MuseTextField(
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
                MuseTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.proxy_username)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                SettingsGroupDivider()
                MuseTextField(
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
                                )
                            )
                            MuseToast.show(savedText)
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}
