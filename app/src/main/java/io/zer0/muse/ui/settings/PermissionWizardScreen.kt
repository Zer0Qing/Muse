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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import io.zer0.muse.ui.common.findActivity
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
    // 权限体系重设计: 向导以协调层快照为单一事实源（与工具预检/管理器同源），
    // 不再自带一套阻塞探针；刷新走协调层被动探测（off-main、零副作用）。
    val coordinator: io.zer0.muse.permission.PermissionCoordinator = koinInject()
    val snapshot by coordinator.snapshot.collectAsStateWithLifecycle()
    // v2.x: Root 授权请求(自「UI 自动化」页迁移 — 该页权限卡已收敛到本向导)
    // managerOrNull：初始化未完成时为 null，不抛——下面 requestRoot 前判空并给可读提示。
    val automationManager = remember { AutomationInitializer.managerOrNull }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 主通道状态全部派生自协调层快照。
    val a11yEnabled = snapshot.accessibilityEnabled
    val shizukuInstalled = snapshot.shizukuInstalled
    val shizukuAvailable = snapshot.shizukuState in setOf(
        ShizukuAuthorizer.ShizukuState.NOT_AUTHORIZED,
        ShizukuAuthorizer.ShizukuState.USER_SERVICE_UNAVAILABLE,
        ShizukuAuthorizer.ShizukuState.READY,
    )
    val shizukuAuthorized = snapshot.shizukuState == ShizukuAuthorizer.ShizukuState.READY
    val shizukuVersionTooOld = snapshot.shizukuState == ShizukuAuthorizer.ShizukuState.VERSION_TOO_OLD
    val rootAvailable = snapshot.rootEnabled
    val rootManager = snapshot.rootManager
    // 有 root 方案但没装管理器：KernelSU/APatch 无管理器时 su 静默拒绝，需定向指引。
    val rootManagerMissing = snapshot.rootGrantState != RootAuthorizer.GrantState.NoRoot && rootManager == null
    val providerInstalled = snapshot.providerInstalled
    val providerBundled = snapshot.providerBundled
    val termuxInstalled = snapshot.termuxAvailability != io.zer0.muse.terminal.TermuxChannel.Availability.NotInstalled
    val termuxOutdated = snapshot.termuxAvailability is io.zer0.muse.terminal.TermuxChannel.Availability.OutdatedVersion

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
                MuseToast.show(context.getString(R.string.permission_termux_grant_denied_hint), 3500)
            }
        }
    // Termux 权限位与 probe 结果不在协调层被动快照里（probe 会唤醒 Termux），就地被动查询/缓存。
    var termuxPermitted by remember { mutableStateOf(false) }
    var termuxProbe by remember { mutableStateOf<Boolean?>(null) }

    // Root 与 Termux 属于可选升级通道：初始跟随「是否已配置」展开，
    // 但用户手动折叠后必须真的能收起（此前用 `showAdvanced || advancedReady` 恒展开）。
    var showAdvanced by remember { mutableStateOf<Boolean?>(null) }

    // 刷新状态：协调层被动刷新四通道快照；Termux probe 按 force 决定缓存/重探。
    suspend fun refresh(forceTermuxProbe: Boolean = false) {
        runCatching {
            coordinator.refresh()
            suiAvailable = shizukuAuthorizer.isSuiBackendAvailable()
            termuxPermitted = termuxChannel.hasPermission()
            // 用即时被动查询判定是否值得 probe，不捕获快照派生值（协调层刷新后重组才更新，会滞后）。
            val termuxProbeable = termuxChannel.isInstalled() && termuxPermitted &&
                termuxChannel.availability() is io.zer0.muse.terminal.TermuxChannel.Availability.Ready
            termuxProbe =
                if (termuxProbeable) {
                    termuxChannel.probe(forceRefresh = forceTermuxProbe) is io.zer0.muse.terminal.TermuxChannel.Availability.Ready
                } else {
                    null
                }
        }.onFailure { e ->
            // 探针抛的运行时异常种类枚举不完；漏出去一次 repeatOnLifecycle 循环就永久停摆。
            if (e is CancellationException) throw e
            Logger.w("PermissionWizard", "刷新权限状态失败: ${e.message}", e)
        }
    }

    // 手动「刷新状态」按钮强制重探 Termux；页面 RESUMED 用缓存避免反复唤醒。
    fun refreshAsync() {
        // 手动刷新要真的重绑 UserService：自动刷新留下的失败退避不该挡用户主动重试。
        shizukuAuthorizer.resetBindBackoff()
        scope.launch { refresh(forceTermuxProbe = true) }
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            refresh(forceTermuxProbe = false)
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
                    // 五张卡并列时看不出先做哪一步：基础通道没开就点名它，开了就说明其余是可选的。
                    Text(
                        text = if (a11yEnabled) {
                            stringResource(R.string.permission_optional_channels)
                        } else {
                            stringResource(
                                R.string.permission_next_step,
                                stringResource(R.string.accessibility_section_title),
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.permission_wizard_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 动手授权之前先说清边界：只做在本机、可随时撤销。
            MuseSurface(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.permission_privacy_notice_title),
                        style = MaterialTheme.typography.titleMedium,
                        // LAYOUT-01: 卡片标题取宽 + 限行，防放大字号下挤成一列
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.permission_privacy_notice_body),
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
                permissionGranted = snapshot.shizukuPermissionGranted,
                versionTooOld = shizukuVersionTooOld,
                onInstall = { shizukuInstaller.openDownloadPage() },
                onAuthorize = {
                    // 先给一次即时反馈：授权弹窗由 Shizuku 应用弹出，没弹出来时用户也得知道点到了。
                    MuseToast.show(context.getString(R.string.permission_shizuku_request_sent_hint), 4000)
                    scope.launch {
                        when (shizukuAuthorizer.requestPermission()) {
                            ShizukuAuthorizer.PermissionRequestResult.Granted -> Unit
                            ShizukuAuthorizer.PermissionRequestResult.PermanentlyDenied -> {
                                // 系统弹窗已被「不再询问」封死：直开 Shizuku 应用内授权页手动允许。
                                shizukuInstaller.openApp()
                                MuseToast.show(context.getString(R.string.permission_shizuku_manual_grant_hint), 5000)
                            }
                            ShizukuAuthorizer.PermissionRequestResult.Denied ->
                                MuseToast.show(context.getString(R.string.permission_shizuku_denied_toast))
                            ShizukuAuthorizer.PermissionRequestResult.Timeout -> {
                                // 超时多半是弹窗没起来（后台启动限制/管理器不在当前用户）：
                                // 直开 Shizuku，让用户手动确认，而不是只弹一句「超时」。
                                shizukuInstaller.openApp()
                                MuseToast.show(context.getString(R.string.permission_shizuku_manual_grant_hint), 5000)
                            }
                            ShizukuAuthorizer.PermissionRequestResult.Unavailable ->
                                MuseToast.show(context.getString(R.string.permission_shizuku_unavailable_toast))
                        }
                        // 授权状态刚变化过：清掉旧退避，让这次刷新真正尝试绑定 Shell 服务。
                        shizukuAuthorizer.resetBindBackoff()
                        refresh()
                    }
                },
                onStartService = {
                    // 直开已安装的 Shizuku(启动服务要在它里面点),不再错误地跳下载页。
                    shizukuInstaller.openApp()
                    MuseToast.show(context.getString(R.string.permission_shizuku_opened_toast), 5000)
                },
                onRefresh = { refreshAsync() },
            )

            // 五张卡并列时最靠前的是没开通的 Root/Termux，等于同时飘三张红；
            // 收进一个折叠，已经装上或已授权的机器直接展开，不让现有配置看起来消失了。
            val advancedReady = rootAvailable || suiAvailable || termuxInstalled
            val advancedExpanded = showAdvanced ?: advancedReady
            MuseCapsuleButton(
                text = stringResource(R.string.permission_advanced_channels),
                onClick = { showAdvanced = !advancedExpanded },
                variant = IosCapsuleButtonVariant.Secondary,
                trailingIcon = if (advancedExpanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
            )
            if (advancedExpanded) {
                // 通道 3: Root — 未授权时主行为是发起 su 授权请求(Magisk/KernelSU 弹窗)
                ChannelCard(
                    title = stringResource(R.string.root_section_title),
                    optional = true,
                    enabled = rootAvailable,
                    enabledText = stringResource(R.string.root_status_available),
                    disabledText = stringResource(R.string.root_status_unavailable),
                    actionText = if (!rootAvailable) stringResource(R.string.automation_root_action_request) else "",
                    onAction = {
                        if (!rootRequesting) {
                            scope.launch {
                                val manager = automationManager
                                if (manager == null) {
                                    MuseToast.show(context.getString(R.string.automation_root_failed))
                                    return@launch
                                }
                                rootRequesting = true
                                val result = manager.requestRoot()
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

                // 有 root 方案却没装管理器：su 会被静默拒绝，告诉用户去装哪个，而不是笼统报「未授权」。
                if (rootManagerMissing) {
                    Text(
                        text = stringResource(R.string.permission_root_manager_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Termux 版本过旧（Play 商店版停更）拿不到结果回传，先于授权提示给出更新指引。
                if (termuxOutdated) {
                    Text(
                        text = stringResource(R.string.permission_termux_outdated),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

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
                            MuseToast.show(context.getString(R.string.permission_termux_grant_manual_hint), 3500)
                        } else {
                            // 跳转成功也要给逐步指引 —— 应用信息首屏看不到这个开关。
                            MuseToast.show(context.getString(R.string.permission_termux_grant_toast), 3500)
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
            }

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
    // 可选通道没开通不是故障，不该和主通道一起飘红。
    optional: Boolean = false,
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
                    imageVector = if (enabled) {
                        MuseIcons.circleCheck
                    } else if (optional) {
                        MuseIcons.lock
                    } else {
                        MuseIcons.alertTriangle
                    },
                    contentDescription = null,
                    tint = when {
                        enabled -> MaterialTheme.statusColors.success
                        optional -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (enabled) enabledText else disabledText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled || optional) {
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
    permissionGranted: Boolean,
    versionTooOld: Boolean,
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
                    imageVector = if (ready) MuseIcons.circleCheck else MuseIcons.lock,
                    contentDescription = null,
                    tint = if (ready) MaterialTheme.statusColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
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
                text = when {
                    versionTooOld -> stringResource(R.string.permission_shizuku_version_old)
                    available -> stringResource(R.string.shizuku_service_running)
                    else -> stringResource(R.string.shizuku_service_not_running)
                },
            )
            // 服务未运行时给出可操作的激活路径(重启后必掉是非 root 设备的常态,不是故障)。
            // 版本过旧不是「未激活」，别给无线调试指引误导用户。
            if (installed && !available && !versionTooOld) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.permission_shizuku_activation_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusLine(
                done = permissionGranted,
                text = if (permissionGranted) {
                    stringResource(R.string.shizuku_status_authorized)
                } else {
                    stringResource(R.string.shizuku_status_unauthorized_long)
                },
            )
            if (permissionGranted && !authorized) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.permission_shizuku_service_not_ready),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                } else if (!authorized && !permissionGranted) {
                    // 已授权但 Shell 服务没绑上时不再给「授权」按钮：再点只会拿到「已授权」，
                    // 用户看到的就是「点了什么也没发生」。用刷新重试绑定。
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
                    imageVector = if (ready) MuseIcons.circleCheck else MuseIcons.lock,
                    contentDescription = null,
                    tint = if (ready) MaterialTheme.statusColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
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
                    // lint LocalContextGetResourceValueCall: 资源取值必须在组合期完成,
                    // 不能在点击回调里调 getString。这里文案无占位符,用无参 stringResource。
                    val copyDoneText = stringResource(R.string.permission_termux_copy_cmd_done)
                    MuseCapsuleButton(
                        text = stringResource(R.string.permission_termux_copy_cmd),
                        onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(termuxCmd))
                            MuseToast.show(copyDoneText)
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
            imageVector = if (done) MuseIcons.circleCheck else MuseIcons.lock,
            contentDescription = null,
            // 步骤没完成只是"还没开通"，这些通道本身是可选的，报错误色等于同时喊五张卡。
            tint = if (done) MaterialTheme.statusColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun buildGlobalActionsHelp(context: Context): String = context.getString(R.string.permission_action_id_help)

