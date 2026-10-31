package io.zer0.muse.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.qrcode.QrCodeGenerator
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.web.WebServer
import io.zer0.muse.web.WebServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.security.SecureRandom

/** 访问地址与 PIN 需要「一眼看到」的信息快照，组合成一屏之内的展示单元。 */
private data class WebAccessSnapshot(
    /** 服务真实运行状态（取自 WebServer，不看配置里的 enabled）。 */
    val running: Boolean,
    /** 实际监听端口；服务未运行时为配置端口。 */
    val port: Int,
    /** 最近一次启动失败原因。 */
    val lastError: String?,
    /** 当前可用访问地址。 */
    val address: WebAccessAddress,
    /** 当前 6 位 PIN；未生成时为空串。 */
    val pin: String,
)

/** 二维码位图边长（像素）。 */
private const val QR_BITMAP_PX = 640

/**
 * 设置 → 连接 → 网页版访问。
 *
 * 一屏给出「地址 + PIN + 二维码」，扫一下就能从 iPhone / PC 连上：
 *  - 开关写 [WebServerConfig.enabled]，开启后若服务未起就启动，关闭后停止并释放端口
 *  - 局域网开关对应 [WebServerConfig.allowLan]，关闭时明确写「仅本机可访问」
 *  - 地址取实际运行端口，局域网 IP 由 [resolveLanIpAddress] 枚举网卡得到
 *  - PIN 可重新生成；服务会随配置重启，旧 PIN 立即失效
 *  - 二维码由 zxing 把访问地址渲成位图，外侧留白由白底卡片提供
 */
@Composable
fun WebAccessPage(onBack: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val webServer: WebServer = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by settings.webServerConfigFlow.collectAsStateWithLifecycle(initialValue = WebServerConfig())
    val isRunning by webServer.isRunning.collectAsStateWithLifecycle(initialValue = false)
    val lastError by webServer.lastError.collectAsStateWithLifecycle(initialValue = null)
    // 端口以服务端实际监听为准：配置里的端口要等服务重启后才生效
    val port = webServer.status().port.takeIf { it > 0 } ?: config.port
    var lanIp by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(config.allowLan) {
        lanIp = withContext(Dispatchers.IO) { resolveLanIpAddress() }
    }
    val snapshot = WebAccessSnapshot(
        running = isRunning,
        port = port,
        lastError = lastError,
        address = resolveWebAccessAddress(config.allowLan, lanIp, port, config.httpsEnabled),
        pin = config.pin,
    )
    val host = remember(settings, webServer) { WebServerAccessHost(settings, webServer) }

    fun toggleEnabled(enabled: Boolean) {
        scope.launch {
            resultOf { applyWebAccessEnabled(host, config, enabled, isRunning) }
                .onSuccess { runningNow ->
                    if (enabled && !runningNow) {
                        MuseToast.show(context.getString(R.string.settings_web_status_failed, lastError.orEmpty()), 3500)
                    }
                }
                .onError { _, t -> MuseToast.show(context.getString(R.string.settings_web_failed, t?.message)) }
        }
    }

    fun toggleLan(allowLan: Boolean) {
        // 绑定地址和 CORS 都要跟着变，只能重启服务
        scope.launch {
            resultOf { webServer.saveAndApplyConfig(config.copy(allowLan = allowLan)) }
                .onError { _, t -> MuseToast.show(context.getString(R.string.settings_web_failed, t?.message)) }
        }
    }

    fun regeneratePin() {
        val newPin = WebServerConfig.generateRandomPin(SecureRandom())
        scope.launch {
            resultOf { webServer.saveAndApplyConfig(config.copy(pin = newPin)) }
                .onSuccess {
                    MuseToast.show(context.getString(R.string.settings_web_access_pin_regenerated, newPin), 3500)
                }
                .onError { _, t -> MuseToast.show(context.getString(R.string.settings_web_failed, t?.message)) }
        }
    }

    SettingsSubPageScaffold(title = stringResource(R.string.settings_screen_web_access), onBack = onBack) {
        item(key = "web_access_intro") {
            Text(
                text = stringResource(R.string.settings_web_access_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        item(key = "web_access_hero") {
            WebAccessHeroCard(
                snapshot = snapshot,
                onCopyAddress = {
                    snapshot.address.url?.let { url ->
                        copyToClipboard(context, "Muse WebServer", url)
                        MuseToast.show(context.getString(R.string.settings_web_address_copied, url))
                    }
                },
                onCopyPin = {
                    if (snapshot.pin.isNotBlank()) {
                        copyToClipboard(context, "Muse WebServer PIN", snapshot.pin)
                        MuseToast.show(context.getString(R.string.settings_web_pin_copied, snapshot.pin))
                    }
                },
            )
        }
        item(key = "web_access_switches") {
            WebAccessSwitchGroup(
                config = config,
                onToggleEnabled = ::toggleEnabled,
                onToggleLan = ::toggleLan,
                onRegeneratePin = ::regeneratePin,
            )
        }
        item(key = "web_access_tips") { WebAccessTips() }
    }
}

/** 运行状态 + 访问地址 + PIN + 二维码的组合卡，让用户一屏看到全部连接信息。 */
@Composable
private fun WebAccessHeroCard(
    snapshot: WebAccessSnapshot,
    onCopyAddress: () -> Unit,
    onCopyPin: () -> Unit,
) {
    val url = snapshot.address.url
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = when {
                    snapshot.running -> stringResource(R.string.settings_web_status_running, snapshot.port)
                    snapshot.lastError != null ->
                        stringResource(R.string.settings_web_status_failed, snapshot.lastError.orEmpty())
                    else -> stringResource(R.string.settings_web_not_running)
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (snapshot.running) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            WebAccessValueRow(
                label = stringResource(R.string.settings_web_access_address),
                value = url ?: stringResource(R.string.settings_web_access_no_ip),
                faded = url == null,
                onCopy = onCopyAddress.takeIf { url != null },
                copyDescription = stringResource(R.string.settings_web_copy_address),
            )
            WebAccessValueRow(
                label = stringResource(R.string.settings_web_pin),
                value = snapshot.pin.ifBlank { stringResource(R.string.settings_web_pin_not_set) },
                faded = snapshot.pin.isBlank(),
                onCopy = onCopyPin.takeIf { snapshot.pin.isNotBlank() },
                copyDescription = stringResource(R.string.settings_web_copy_pin),
            )
            WebAccessQrBlock(address = snapshot.address)
            Text(
                text = stringResource(R.string.settings_web_access_first_login_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

/** 「标签 - 值 + 复制按钮」一行；值为空时用淡色提示文案而不是留白。 */
@Composable
private fun WebAccessValueRow(
    label: String,
    value: String,
    faded: Boolean,
    onCopy: (() -> Unit)?,
    copyDescription: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.SemiBold,
                color = if (faded) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onCopy != null) {
                Spacer(modifier = Modifier.width(4.dp))
                MuseTactileButton(
                    icon = MuseIcons.copy,
                    onClick = onCopy,
                    contentDescription = copyDescription,
                    tint = MaterialTheme.colorScheme.onSurface,
                    iconSize = 20.dp,
                )
            }
        }
    }
}

/** 二维码区块的 testTag，供「仅本机模式不渲染二维码」的行为断言使用。 */
internal const val WEB_ACCESS_QR_TAG = "web_access_qr"

/**
 * 二维码区块。
 *
 * 只在非「仅本机可访问」时渲染：回环地址画成二维码，其他设备扫了也连不上，
 * 所以那种模式下整块不渲染，换成一句说明，不留空框。
 * 局域网访问开着但没拿到 IP 时同样整块不渲染（地址行已经给了提示）。
 */
@Composable
internal fun WebAccessQrBlock(address: WebAccessAddress) {
    if (address.localOnly) {
        Text(
            text = stringResource(R.string.settings_web_access_local_only_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        return
    }
    val url = address.url ?: return
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = withContext(Dispatchers.Default) { QrCodeGenerator.generateQrBitmap(url, QR_BITMAP_PX) }
    }
    val contentDescription = stringResource(R.string.settings_web_access_qr_cd)
    val scanHint = stringResource(R.string.settings_web_access_scan_hint)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(WEB_ACCESS_QR_TAG),
        shape = MuseShapes.medium,
        color = Color.White,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val image = bitmap
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = contentDescription,
                    modifier = Modifier.size(220.dp),
                )
            } else {
                Box(
                    modifier = Modifier.size(220.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MuseIcons.qrcode,
                        contentDescription = null,
                        tint = Color.Black.copy(alpha = 0.2f),
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
            Text(
                text = scanHint,
                style = MaterialTheme.typography.labelSmall,
                color = Color.Black.copy(alpha = 0.6f),
            )
        }
    }
}

/** 启用开关、局域网开关、PIN 重新生成三行。 */
@Composable
private fun WebAccessSwitchGroup(
    config: WebServerConfig,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleLan: (Boolean) -> Unit,
    onRegeneratePin: () -> Unit,
) {
    SettingsGroup {
        SettingsSwitchRow(
            icon = MuseIcons.router,
            title = stringResource(R.string.settings_web_access_enable),
            subtitle = stringResource(R.string.settings_web_access_enable_subtitle),
            checked = config.enabled,
            onCheckedChange = onToggleEnabled,
        )
        SettingsGroupDivider()
        SettingsSwitchRow(
            icon = MuseIcons.wifi,
            title = stringResource(R.string.settings_web_access_lan),
            subtitle = if (config.allowLan) {
                stringResource(R.string.settings_web_access_lan_on)
            } else {
                stringResource(R.string.settings_web_access_lan_off)
            },
            checked = config.allowLan,
            onCheckedChange = onToggleLan,
        )
        SettingsGroupDivider()
        SettingsItemRow(
            icon = MuseIcons.refresh,
            title = stringResource(R.string.settings_web_regenerate_pin),
            subtitle = stringResource(R.string.settings_web_regenerate_pin_subtitle),
            onClick = onRegeneratePin,
        )
    }
}

/** 末尾的三条使用说明：iPhone 全屏、同一 Wi-Fi、关掉即断开。 */
@Composable
private fun WebAccessTips() {
    val tips = listOf(
        stringResource(R.string.settings_web_access_tip_iphone),
        stringResource(R.string.settings_web_access_tip_same_wifi),
        stringResource(R.string.settings_web_access_tip_disable),
    )
    SettingsGroup {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            tips.forEach { tip ->
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = MuseIcons.info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(16.dp),
                    )
                    Text(
                        text = tip,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
}

/** 写入剪贴板；标签只用于系统剪贴板条目的可读描述。 */
private fun copyToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

/** 把 [SettingsRepository] 与 [WebServer] 适配成开关逻辑需要的副作用边界。 */
private class WebServerAccessHost(
    private val settings: SettingsRepository,
    private val webServer: WebServer,
) : WebAccessHost {
    override suspend fun saveConfig(config: WebServerConfig) {
        settings.saveWebServerConfig(config)
    }

    override suspend fun startServer(): Boolean = webServer.start()

    override suspend fun stopServer() {
        webServer.stop()
    }
}
