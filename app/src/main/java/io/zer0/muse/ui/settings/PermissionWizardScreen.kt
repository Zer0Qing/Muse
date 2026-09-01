package io.zer0.muse.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.tools.system.AccessibilityProviderInstaller
import io.zer0.muse.tools.system.AndroidPermissionLevel
import io.zer0.muse.tools.system.RootAuthorizer
import io.zer0.muse.tools.system.ShizukuAuthorizer
import io.zer0.muse.ui.theme.statusColors
import io.zer0.muse.tools.system.ShizukuInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * P3-3: 权限引导页 — 三通道(无障碍/Shizuku/Root)权限配置向导。
 *
 * 引导用户逐步启用 UI 自动化能力:
 *  1. 无障碍服务(ACCESSIBILITY):基础通道,UI 操作必需
 *  2. Shizuku(SHIZUKU):shell 权限执行命令,无需 root(推荐)
 *  3. Root(ROOT):降级通道,需 root 设备
 *
 * 页面展示当前权限等级 + 各通道状态 + 操作按钮(启用/安装/授权)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionWizardScreen(
    onBack: () -> Unit,
) {
    val a11yInstaller: AccessibilityProviderInstaller = koinInject()
    val shizukuInstaller: ShizukuInstaller = koinInject()
    val shizukuAuthorizer: ShizukuAuthorizer = koinInject()
    val rootAuthorizer: RootAuthorizer = koinInject()

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 各通道状态
    var a11yEnabled by remember { mutableStateOf(false) }
    var shizukuInstalled by remember { mutableStateOf(false) }
    var shizukuAvailable by remember { mutableStateOf(false) }
    var shizukuAuthorized by remember { mutableStateOf(false) }
    var rootAvailable by remember { mutableStateOf(false) }
    var suiAvailable by remember { mutableStateOf(false) }

    // 刷新状态
    suspend fun refresh() {
        try {
            a11yEnabled = a11yInstaller.isEnabled()
            shizukuInstalled = shizukuInstaller.isInstalled()
            shizukuAvailable = shizukuAuthorizer.isAvailable()
            // 授权位为 true 仍可能无法绑定 UserService；这里展示实际可用状态。
            shizukuAuthorized = shizukuAuthorizer.checkReady()
            rootAvailable = rootAuthorizer.checkPermission()
            suiAvailable = shizukuAuthorizer.isSuiBackendAvailable()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Logger.w("PermissionWizard", "刷新权限状态被系统拒绝: ${e.message}", e)
        } catch (e: IllegalStateException) {
            Logger.w("PermissionWizard", "刷新权限状态失败: ${e.message}", e)
        }
    }

    fun refreshAsync() {
        scope.launch { refresh() }
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            refresh()
        }
    }

    val currentLevel = AndroidPermissionLevel.highestOf(
        AndroidPermissionLevel.ACCESSIBILITY to a11yEnabled,
        AndroidPermissionLevel.SHIZUKU to (shizukuAvailable && shizukuAuthorized),
        AndroidPermissionLevel.ROOT to (rootAvailable || suiAvailable),
    )

    MusePageScaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.permission_wizard_title)) },
                navigationIcon = {
                    // 返回按钮由 TopAppBar 默认提供时用 NavigationIcon,这里用简洁文字按钮
                    OutlinedButton(onClick = onBack) { Text("←") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 当前权限等级总览
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.permission_current_level),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    val levelText = when (currentLevel) {
                        AndroidPermissionLevel.NONE -> stringResource(R.string.permission_level_none)
                        AndroidPermissionLevel.ACCESSIBILITY -> stringResource(R.string.permission_level_accessibility)
                        AndroidPermissionLevel.SHIZUKU -> stringResource(R.string.permission_level_shizuku)
                        AndroidPermissionLevel.ROOT -> stringResource(R.string.permission_level_root)
                    }
                    Text(text = levelText, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.permission_wizard_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 通道 1: 无障碍服务
            ChannelCard(
                title = stringResource(R.string.accessibility_section_title),
                enabled = a11yEnabled,
                enabledText = stringResource(R.string.accessibility_status_enabled),
                disabledText = stringResource(R.string.accessibility_status_disabled),
                actionText = stringResource(R.string.accessibility_enable_btn),
                onAction = {
                    a11yInstaller.openSettings()
                },
                onRefresh = { refreshAsync() },
            )

            // 通道 2: Shizuku
            ShizukuChannelCard(
                installed = shizukuInstalled,
                available = shizukuAvailable,
                authorized = shizukuAuthorized,
                onInstall = { shizukuInstaller.openDownloadPage() },
                onAuthorize = {
                    scope.launch {
                        shizukuAuthorizer.requestPermission()
                        refresh()
                    }
                },
                onStartService = {
                    // 引导用户打开 Shizuku 应用启动服务(无法直接拉起)
                    shizukuInstaller.openDownloadPage()
                },
                onRefresh = { refreshAsync() },
            )

            // 通道 3: Root
            ChannelCard(
                title = stringResource(R.string.root_section_title),
                enabled = rootAvailable,
                enabledText = stringResource(R.string.root_status_available),
                disabledText = stringResource(R.string.root_status_unavailable),
                actionText = "",
                onAction = {},
                onRefresh = { refreshAsync() },
            )

            // Sui 后端兼容提示
            if (suiAvailable) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.permission_sui_detected),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // 全局动作常量说明
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.permission_global_action_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = buildGlobalActionsHelp(context),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelCard(
    title: String,
    enabled: Boolean,
    enabledText: String,
    disabledText: String,
    actionText: String,
    onAction: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (enabled) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.statusColors.success else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (enabled) enabledText else disabledText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
            )
            if (actionText.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onAction) { Text(actionText) }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRefresh) {
                Text(stringResource(R.string.permission_refresh_status))
            }
        }
    }
}

@Composable
private fun ShizukuChannelCard(
    installed: Boolean,
    available: Boolean,
    authorized: Boolean,
    onInstall: () -> Unit,
    onAuthorize: () -> Unit,
    onStartService: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.shizuku_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                val ready = installed && available && authorized
                Icon(
                    imageVector = if (ready) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (ready) MaterialTheme.statusColors.success else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            // 分步状态展示
            StatusLine(
                done = installed,
                text = if (installed) stringResource(R.string.shizuku_apk_installed)
                    else stringResource(R.string.shizuku_apk_not_installed),
            )
            StatusLine(
                done = available,
                text = if (available) stringResource(R.string.shizuku_service_running)
                    else stringResource(R.string.shizuku_service_not_running),
            )
            StatusLine(
                done = authorized,
                text = if (authorized) stringResource(R.string.shizuku_status_authorized)
                    else stringResource(R.string.shizuku_status_unauthorized_long),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!installed) {
                    Button(onClick = onInstall) { Text(stringResource(R.string.shizuku_install_btn)) }
                } else if (!available) {
                    Button(onClick = onStartService) {
                        Text(stringResource(R.string.permission_open_shizuku_service))
                    }
                } else if (!authorized) {
                    Button(onClick = onAuthorize) {
                        Text(stringResource(R.string.shizuku_authorize_btn))
                    }
                }
                OutlinedButton(onClick = onRefresh) { Text(stringResource(R.string.permission_refresh_status)) }
            }
        }
    }
}

@Composable
private fun StatusLine(done: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(
            imageVector = if (done) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (done) MaterialTheme.statusColors.success else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun buildGlobalActionsHelp(context: Context): String =
    context.getString(R.string.permission_action_id_help)
