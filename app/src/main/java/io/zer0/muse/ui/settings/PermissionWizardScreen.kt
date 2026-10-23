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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.automation.AutomationInitializer
import io.zer0.muse.automation.executors.RootRequestFailure
import io.zer0.muse.tools.system.AccessibilityProviderInstaller
import io.zer0.muse.tools.system.AndroidPermissionLevel
import io.zer0.muse.tools.system.RootAuthorizer
import io.zer0.muse.tools.system.ShizukuAuthorizer
import io.zer0.muse.tools.system.ShizukuInstaller
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.theme.statusColors
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
@Composable
fun PermissionWizardScreen(onBack: () -> Unit) {
    val a11yInstaller: AccessibilityProviderInstaller = koinInject()
    val shizukuInstaller: ShizukuInstaller = koinInject()
    val shizukuAuthorizer: ShizukuAuthorizer = koinInject()
    val rootAuthorizer: RootAuthorizer = koinInject()
    // v2.x: Root 授权请求(自「UI 自动化」页迁移 — 该页权限卡已收敛到本向导)
    val automationManager = remember { AutomationInitializer.manager }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 各通道状态
    var a11yEnabled by remember { mutableStateOf(false) }
    var shizukuInstalled by remember { mutableStateOf(false) }
    var shizukuAvailable by remember { mutableStateOf(false) }
    var shizukuAuthorized by remember { mutableStateOf(false) }
    var rootAvailable by remember { mutableStateOf(false) }
    var rootRequesting by remember { mutableStateOf(false) }
    var suiAvailable by remember { mutableStateOf(false) }

    // v2.2.1: Termux 扩展通道(可选;不参与"三通道权限等级"判定)
    val termuxChannel = remember { io.zer0.muse.terminal.TermuxChannel.get(context) }
    // v2.6.7 修复: Termux 的 RUN_COMMAND 是 dangerous 运行时权限，
    // 必须由系统弹框授予（设置页里没有这个开关）。
    val termuxPermissionLauncher =
        androidx.activity.compose.rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (!granted) {
                android.widget.Toast
                    .makeText(
                        context,
                        context.getString(R.string.permission_termux_grant_denied_hint),
                        android.widget.Toast.LENGTH_LONG,
                    )
                    .show()
            }
        }
    var termuxInstalled by remember { mutableStateOf(false) }
    var termuxPermitted by remember { mutableStateOf(false) }
    var termuxProbe by remember { mutableStateOf<Boolean?>(null) }

    // v2.2.1: 无障碍独立 Provider(可选增强;不参与"三通道权限等级"判定)
    var providerInstalled by remember { mutableStateOf(false) }
    var providerBundled by remember { mutableStateOf(false) }

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
            // v2.2.1: Termux 通道静态检测 + 已就绪时轻量探测(验证 allow-external-apps)
            termuxInstalled = termuxChannel.isInstalled()
            termuxPermitted = termuxChannel.hasPermission()
            termuxProbe =
                if (termuxInstalled && termuxPermitted) {
                    termuxChannel.probe() is io.zer0.muse.terminal.TermuxChannel.Availability.Ready
                } else {
                    null
                }
            // v2.2.1: 独立 Provider 静态检测(装没装;APK 是否随包附带决定是否展示安装入口)
            providerInstalled = a11yInstaller.isProviderInstalled()
            providerBundled = a11yInstaller.isProviderApkBundled()
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

    val currentLevel =
        AndroidPermissionLevel.highestOf(
            AndroidPermissionLevel.ACCESSIBILITY to a11yEnabled,
            AndroidPermissionLevel.SHIZUKU to (shizukuAvailable && shizukuAuthorized),
            AndroidPermissionLevel.ROOT to (rootAvailable || suiAvailable),
        )

    MusePageScaffold(
        topBar = {
            // ST-08: 统一 MuseTopBar 视觉(此前用 Material TopAppBar + 文字箭头,与全站风格脱节)
            MuseTopBar(
                title = stringResource(R.string.permission_wizard_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 当前权限等级总览
            // ST-08b: M3 Card → 项目自有 MuseSurface(与全站卡片视觉统一)
            MuseSurface(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.permission_current_level),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    val levelText =
                        when (currentLevel) {
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

            // 通道 1.5(v2.2.1): 无障碍独立 Provider — 可选增强:主应用更新不打断无障碍授权
            if (providerBundled || providerInstalled) {
                A11yProviderCard(
                    installed = providerInstalled,
                    bundled = providerBundled,
                    onInstall = {
                        val launched = a11yInstaller.installProvider()
                        if (!launched) {
                            MuseToast.show(context.getString(R.string.permission_a11y_provider_install_failed))
                        }
                        refreshAsync()
                    },
                    onRefresh = { refreshAsync() },
                )
            }

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

            // 通道 3: Root — 未授权时主行为是发起 su 授权请求(Magisk/KernelSU 弹窗)
            ChannelCard(
                title = stringResource(R.string.root_section_title),
                enabled = rootAvailable,
                enabledText = stringResource(R.string.root_status_available),
                disabledText = stringResource(R.string.root_status_unavailable),
                actionText = if (!rootAvailable) stringResource(R.string.automation_root_action_request) else "",
                onAction = {
                    if (!rootRequesting) {
                        scope.launch {
                            rootRequesting = true
                            val result = automationManager.requestRoot()
                            rootRequesting = false
                            if (result.granted) {
                                MuseToast.show(context.getString(R.string.automation_root_granted))
                            } else {
                                val failure = result.failure ?: RootRequestFailure.ERROR
                                val msgRes =
                                    when (failure) {
                                        RootRequestFailure.NO_SU_BINARY -> R.string.automation_root_no_su
                                        RootRequestFailure.TIMEOUT -> R.string.automation_root_timeout
                                        RootRequestFailure.DENIED -> R.string.automation_root_denied
                                        RootRequestFailure.ERROR -> R.string.automation_root_failed
                                    }
                                MuseToast.show(context.getString(msgRes))
                            }
                            refresh()
                        }
                    }
                },
                onRefresh = { refreshAsync() },
            )

            // 通道 4(v2.2.1): Termux 扩展通道 — 完整 Linux 环境命令执行(可选)
            TermuxChannelCard(
                installed = termuxInstalled,
                permitted = termuxPermitted,
                probeOk = termuxProbe,
                onGet = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://f-droid.org/packages/com.termux/"),
                            ),
                        )
                    }
                },
                onGrant = {
                    // v2.6.7 修复（用户反馈：Termux 永远授不上权限）:
                    // `com.termux.permission.RUN_COMMAND` 是 Termux 自己声明的
                    // `dangerous` 运行时权限（Termux 官方安全公告原文:
                    // "a `dangerous` runtime permission, to be granted to the calling app"）。
                    //
                    // 这类权限只能由系统弹框授予，或 `adb shell pm grant`，
                    // **不会**出现在设置页的权限列表里。旧实现只跳
                    // ACTION_APPLICATION_DETAILS_SETTINGS，于是:
                    //   跳过去 → 那里没有这个开关 → 回来 hasPermission() 仍 false → 永远卡住。
                    // 多数 ROM 都没有「附加权限」二级页，文案里的入口实际不存在。
                    //
                    // 正确做法:直接在 Muse 侧发起运行时请求，让系统弹授权框。
                    // Termux 未安装 / 未声明该权限时，系统会直接回调拒绝，
                    // 此时再回落成 adb 引导（下面 tryStart 那段保留）。
                    val activity = context.findActivity()
                    // launcher 始终已注册（Compose 组合期创建），有 Activity 就发请求。
                    // 未装 Termux 时系统会直接回调拒绝，由 denied 提示引导用户先装。
                    if (activity != null) {
                        termuxPermissionLauncher.launch(io.zer0.muse.terminal.TermuxChannel.PERMISSION)
                        return@TermuxChannelCard
                    }
                    // 回落: 无法发起运行时请求时，给可自查的 adb 命令而不是指错路。
                    val pkg = io.zer0.muse.terminal.TermuxChannel.PKG
                    val pkgUri = android.net.Uri.fromParts("package", pkg, null)

                    // ① 部分 ROM(ColorOS/MIUI/EMUI)支持直接开应用详情并定位到权限页
                    fun tryStart(action: String, uri: android.net.Uri?): Boolean = runCatching {
                        val i = if (uri != null) {
                            android.content.Intent(action, uri)
                        } else {
                            android.content.Intent(action)
                        }
                        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(i)
                    }.isSuccess

                    val ok = tryStart(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri) ||
                        tryStart(android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, null)

                    if (!ok) {
                        android.widget.Toast
                            .makeText(
                                context,
                                context.getString(R.string.permission_termux_grant_manual_hint),
                                android.widget.Toast.LENGTH_LONG,
                            )
                            .show()
                    } else {
                        // 跳转成功也要给逐步指引 —— 应用信息首屏看不到这个开关。
                        android.widget.Toast
                            .makeText(
                                context,
                                context.getString(R.string.permission_termux_grant_toast),
                                android.widget.Toast.LENGTH_LONG,
                            )
                            .show()
                    }
                },
                onOpenApp = {
                    runCatching {
                        context.packageManager.getLaunchIntentForPackage(io.zer0.muse.terminal.TermuxChannel.PKG)
                            ?.let { context.startActivity(it) }
                    }
                },
                onRefresh = { refreshAsync() },
            )

            // Sui 后端兼容提示
            if (suiAvailable) {
                MuseSurface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = MuseIcons.circleCheck,
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
            MuseSurface(modifier = Modifier.fillMaxWidth()) {
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
    MuseSurface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    // LAYOUT-01: Row 内标题取宽 + 限行，防放大字号下被右侧状态图标挤成一列
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = if (enabled) MuseIcons.circleCheck else MuseIcons.alertTriangle,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.statusColors.success else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (enabled) enabledText else disabledText,
                style = MaterialTheme.typography.bodyMedium,
                color =
                if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            if (actionText.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                // ST-08b: M3 Button → MuseCapsuleButton(主样式)
                MuseCapsuleButton(text = actionText, onClick = onAction)
            }
            Spacer(Modifier.height(8.dp))
            // ST-08b: M3 OutlinedButton → MuseCapsuleButton(次样式)
            MuseCapsuleButton(
                text = stringResource(R.string.permission_refresh_status),
                onClick = onRefresh,
                variant = IosCapsuleButtonVariant.Secondary,
            )
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
    MuseSurface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.shizuku_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    // LAYOUT-01: 限 1 行（见 PROJECT_GUIDE §5）
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val ready = installed && available && authorized
                Icon(
                    imageVector = if (ready) MuseIcons.circleCheck else MuseIcons.alertTriangle,
                    contentDescription = null,
                    tint = if (ready) MaterialTheme.statusColors.success else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            // 分步状态展示
            StatusLine(
                done = installed,
                text = if (installed) {
                    stringResource(R.string.shizuku_apk_installed)
                } else {
                    stringResource(R.string.shizuku_apk_not_installed)
                },
            )
            StatusLine(
                done = available,
                text = if (available) {
                    stringResource(R.string.shizuku_service_running)
                } else {
                    stringResource(R.string.shizuku_service_not_running)
                },
            )
            StatusLine(
                done = authorized,
                text = if (authorized) {
                    stringResource(R.string.shizuku_status_authorized)
                } else {
                    stringResource(R.string.shizuku_status_unauthorized_long)
                },
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // ST-08b: 三个动作按钮 M3 Button → MuseCapsuleButton(fillWidth = false 保持并排)
                if (!installed) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.shizuku_install_btn),
                        onClick = onInstall,
                        fillWidth = false,
                    )
                } else if (!available) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.permission_open_shizuku_service),
                        onClick = onStartService,
                        fillWidth = false,
                    )
                } else if (!authorized) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.shizuku_authorize_btn),
                        onClick = onAuthorize,
                        fillWidth = false,
                    )
                }
                MuseCapsuleButton(
                    text = stringResource(R.string.permission_refresh_status),
                    onClick = onRefresh,
                    variant = IosCapsuleButtonVariant.Secondary,
                    fillWidth = false,
                )
            }
        }
    }
}

@Composable
private fun TermuxChannelCard(
    installed: Boolean,
    permitted: Boolean,
    probeOk: Boolean?,
    onGet: () -> Unit,
    onGrant: () -> Unit,
    onOpenApp: () -> Unit,
    onRefresh: () -> Unit,
) {
    MuseSurface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.permission_termux_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    // LAYOUT-01: 限 1 行（见 PROJECT_GUIDE §5）
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val ready = installed && permitted && probeOk == true
                Icon(
                    imageVector = if (ready) MuseIcons.circleCheck else MuseIcons.alertTriangle,
                    contentDescription = null,
                    tint = if (ready) MaterialTheme.statusColors.success else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            StatusLine(
                done = installed,
                text =
                if (installed) {
                    stringResource(R.string.permission_termux_apk_installed)
                } else {
                    stringResource(R.string.permission_termux_apk_not_installed)
                },
            )
            StatusLine(
                done = permitted,
                text =
                if (permitted) {
                    stringResource(R.string.permission_termux_permission_ok)
                } else {
                    stringResource(R.string.permission_termux_permission_missing)
                },
            )
            StatusLine(
                done = probeOk == true,
                text =
                when (probeOk) {
                    true -> stringResource(R.string.permission_termux_probe_ok)
                    false -> stringResource(R.string.permission_termux_probe_failed)
                    null -> stringResource(R.string.permission_termux_probe_unchecked)
                },
            )
            if (installed && permitted && probeOk != true) {
                Spacer(Modifier.height(8.dp))
                // issue #3.4: 用户反映「手册里追加的允许外部调用指令也无效」。
                // 根因是只写 termux.properties 不会生效,必须 termux-reload-settings
                // (旧文案只写了「重启 Termux」,部分 ROM/后台场景下重启不生效)。
                // 这里给出完整两步命令,并支持点一下复制,避免手敲出错。
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                val termuxCmd = "echo 'allow-external-apps = true' >> ~/.termux/termux.properties && termux-reload-settings"
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.permission_termux_config_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    val toastCtx = androidx.compose.ui.platform.LocalContext.current
                    // lint LocalContextGetResourceValueCall: 资源取值必须在组合期完成,
                    // 不能在点击回调里调 getString。这里文案无占位符,用无参 stringResource。
                    val copyDoneText = stringResource(R.string.permission_termux_copy_cmd_done)
                    MuseCapsuleButton(
                        text = stringResource(R.string.permission_termux_copy_cmd),
                        onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(termuxCmd))
                            android.widget.Toast
                                .makeText(
                                    toastCtx,
                                    copyDoneText,
                                    android.widget.Toast.LENGTH_SHORT,
                                )
                                .show()
                        },
                        variant = IosCapsuleButtonVariant.Secondary,
                        fillWidth = false,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    !installed -> {
                        MuseCapsuleButton(
                            text = stringResource(R.string.permission_termux_get_btn),
                            onClick = onGet,
                            fillWidth = false,
                        )
                    }

                    !permitted -> {
                        MuseCapsuleButton(
                            text = stringResource(R.string.permission_termux_grant_btn),
                            onClick = onGrant,
                            fillWidth = false,
                        )
                    }

                    probeOk != true -> {
                        MuseCapsuleButton(
                            text = stringResource(R.string.permission_termux_open_btn),
                            onClick = onOpenApp,
                            fillWidth = false,
                        )
                    }
                }
                MuseCapsuleButton(
                    text = stringResource(R.string.permission_refresh_status),
                    onClick = onRefresh,
                    variant = IosCapsuleButtonVariant.Secondary,
                    fillWidth = false,
                )
            }
        }
    }
}

@Composable
private fun A11yProviderCard(installed: Boolean, bundled: Boolean, onInstall: () -> Unit, onRefresh: () -> Unit) {
    MuseSurface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.permission_a11y_provider_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    // LAYOUT-01: 限 1 行（见 PROJECT_GUIDE §5）
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 可选增强:未安装时用中性色,避免与"未授权的必需通道"混淆
                Icon(
                    imageVector = if (installed) MuseIcons.circleCheck else MuseIcons.alertTriangle,
                    contentDescription = null,
                    tint =
                    if (installed) {
                        MaterialTheme.statusColors.success
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text =
                if (installed) {
                    stringResource(R.string.permission_a11y_provider_installed)
                } else {
                    stringResource(R.string.permission_a11y_provider_not_installed)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.permission_a11y_provider_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!installed && bundled) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.permission_a11y_provider_install_btn),
                        onClick = onInstall,
                        fillWidth = false,
                    )
                }
                MuseCapsuleButton(
                    text = stringResource(R.string.permission_refresh_status),
                    onClick = onRefresh,
                    variant = IosCapsuleButtonVariant.Secondary,
                    fillWidth = false,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(done: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(
            imageVector = if (done) MuseIcons.circleCheck else MuseIcons.alertTriangle,
            contentDescription = null,
            // ST-06: 未授权与已授权用不同色区分(此前未授权为灰色 outline,与已授权区分不足)
            tint = if (done) MaterialTheme.statusColors.success else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color =
            if (done) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

private fun buildGlobalActionsHelp(context: Context): String = context.getString(R.string.permission_action_id_help)

/**
 * v2.6.7: 从 Context 向上找 Activity。
 *
 * 运行时权限请求必须从 Activity 发起（ContextWrapper 链上不一定直挂 Activity）。
 * 找不到时返回 null，由调用方回落到手动引导，不抛异常。
 */
private fun Context.findActivity(): android.app.Activity? {
    var ctx: Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
