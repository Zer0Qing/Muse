package io.zer0.muse.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Http
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.util.NetworkUtils
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.MuseToast
import io.zer0.muse.ui.common.SectionLabel
import io.zer0.muse.ui.common.SettingsGroup
import io.zer0.muse.ui.common.SettingsGroupDivider
import io.zer0.muse.ui.common.SettingsItemRow
import io.zer0.muse.ui.common.SettingsSwitchRow
import io.zer0.muse.web.WebServerConfig
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.launch

/**
 * P1-2 修复: 嵌入式 Web 服务器配置 section — iOS 风格分组列表。
 *
 * 阶段 C 升级: 动态获取局域网 IP + 一键复制访问地址。
 *
 * P2-13:WebServer PIN 安全
 *  - 显示当前 6 位 PIN(明文),用于 Web 端首次访问验证
 *  - 提供"重新生成 PIN"按钮,旧 PIN 立即失效
 *  - 明显提示"同一 Wi-Fi 下他人可通过 IP + PIN 访问"
 *
 * 后端 WebServerConfig + WebServer 服务在 MuseApp 启动时自动启动,
 * 但前端完全无入口,服务"暗跑"。此 section 让用户可控:
 *  - 启停开关(关闭后下次启动 App 不再启动 WebServer)
 *  - 端口配置(默认 8765)
 *  - 密码(用于 JWT 登录,可重新生成)
 *  - PIN(用于 Web 端首次访问验证,可重新生成)
 *  - 访问地址(动态 IP + 复制按钮)
 */
@Composable
internal fun WebServerSection(
    settings: SettingsRepository,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val config by settings.webServerConfigFlow.collectAsStateWithLifecycle(
        initialValue = WebServerConfig()
    )
    var showPortDialog by remember { mutableStateOf(false) }
    var portInput by remember { mutableStateOf(config.port.toString()) }
    // 动态获取局域网 IP
    val localIp = remember { NetworkUtils.getLocalIpAddress() }
    val accessUrl = if (localIp != null) "http://$localIp:${config.port}" else null

    SectionLabel(stringResource(R.string.settings_web_title))
    Text(
        text = stringResource(R.string.settings_web_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 4.dp),
    )

    // P2-13: 明显提示 — 同一 Wi-Fi 下他人可通过 IP + PIN 访问
    if (accessUrl != null && config.pin.isNotBlank()) {
        Surface(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(),
            shape = MuseShapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.settings_web_security_hint),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp),
                )
                Column {
                    Text(
                        text = stringResource(R.string.settings_web_access_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.settings_web_address_pin, accessUrl, config.pin),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = stringResource(R.string.settings_web_pin_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }

    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        // 启停开关
        SettingsSwitchRow(
            icon = Icons.Outlined.Router,
            title = stringResource(R.string.settings_web_enable),
            subtitle = stringResource(R.string.settings_web_enable_subtitle),
            checked = config.enabled,
            onCheckedChange = { enabled ->
                scope.launch {
                    runCatching { settings.saveWebServerConfig(config.copy(enabled = enabled)) }
                        .onFailure {
                            MuseToast.show(context.getString(R.string.settings_web_failed, it.message))
                        }
                }
            },
        )
        SettingsGroupDivider()
        // 端口
        SettingsItemRow(
            icon = Icons.Outlined.Http,
            title = stringResource(R.string.settings_web_port),
            subtitle = stringResource(R.string.settings_web_port_subtitle, config.port),
            onClick = {
                portInput = config.port.toString()
                showPortDialog = true
            },
        )
        SettingsGroupDivider()
        // 密码(脱敏显示)
        SettingsItemRow(
            icon = Icons.Outlined.Lock,
            title = stringResource(R.string.settings_web_password),
            subtitle = if (config.password.isBlank()) stringResource(R.string.settings_web_password_not_set) else "${config.password.take(2)}****",
        )
        SettingsGroupDivider()
        // 重新生成密码
        SettingsItemRow(
            icon = Icons.Outlined.Lock,
            title = stringResource(R.string.settings_web_regenerate_password),
            subtitle = stringResource(R.string.settings_web_regenerate_password_subtitle),
            onClick = {
                val newPassword = WebServerConfig.generateRandomPassword()
                scope.launch {
                    runCatching { settings.saveWebServerConfig(config.copy(password = newPassword)) }
                        .onSuccess {
                            MuseToast.show(context.getString(R.string.settings_web_new_password, newPassword), 3500)
                        }
                        .onFailure {
                            MuseToast.show(context.getString(R.string.settings_web_failed, it.message))
                        }
                }
            },
        )
        SettingsGroupDivider()
        // P2-13: 当前 PIN(明文显示,带复制按钮)
        SettingsItemRow(
            icon = Icons.Outlined.Password,
            title = stringResource(R.string.settings_web_pin),
            subtitle = if (config.pin.isBlank()) stringResource(R.string.settings_web_pin_not_set) else config.pin,
        ) {
            if (config.pin.isNotBlank()) {
                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Muse WebServer PIN", config.pin))
                    MuseToast.show(context.getString(R.string.settings_web_pin_copied, config.pin))
                }) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.settings_web_copy_pin),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        SettingsGroupDivider()
        // P2-13: 重新生成 PIN
        SettingsItemRow(
            icon = Icons.Outlined.Password,
            title = stringResource(R.string.settings_web_regenerate_pin),
            subtitle = stringResource(R.string.settings_web_regenerate_pin_subtitle),
            onClick = {
                val newPin = WebServerConfig.generateRandomPin()
                scope.launch {
                    runCatching { settings.saveWebServerConfig(config.copy(pin = newPin)) }
                        .onSuccess {
                            MuseToast.show(context.getString(R.string.settings_web_new_pin, newPin), 3500)
                        }
                        .onFailure {
                            MuseToast.show(context.getString(R.string.settings_web_failed, it.message))
                        }
                }
            },
        )
        SettingsGroupDivider()
        // 访问地址(动态 IP + 复制按钮)
        SettingsItemRow(
            icon = Icons.Outlined.Wifi,
            title = stringResource(R.string.settings_web_access_address),
            subtitle = accessUrl ?: stringResource(R.string.settings_web_no_lan),
        ) {
            if (accessUrl != null) {
                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Muse WebServer", accessUrl))
                    MuseToast.show(context.getString(R.string.settings_web_address_copied, accessUrl))
                }) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.settings_web_copy_address),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }

    // 端口修改对话框
    if (showPortDialog) {
        MuseDialog(
            onDismissRequest = { showPortDialog = false },
            title = stringResource(R.string.settings_web_edit_port),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = portInput,
                        onValueChange = { portInput = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.settings_web_port_number)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.settings_web_port_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmText = stringResource(R.string.settings_common_save),
            onConfirm = {
                val port = portInput.toIntOrNull()
                if (port == null || port < 1024 || port > 65535) {
                    MuseToast.show(context.getString(R.string.settings_web_port_invalid))
                    return@MuseDialog
                }
                scope.launch {
                    runCatching { settings.saveWebServerConfig(config.copy(port = port)) }
                        .onSuccess {
                            showPortDialog = false
                            MuseToast.show(context.getString(R.string.settings_web_port_updated))
                        }
                        .onFailure {
                            MuseToast.show(context.getString(R.string.settings_web_failed, it.message))
                        }
                }
            },
            dismissText = stringResource(R.string.settings_common_cancel),
            onDismiss = { showPortDialog = false },
        )
    }
}
