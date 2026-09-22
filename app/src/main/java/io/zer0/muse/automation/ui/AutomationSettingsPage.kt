package io.zer0.muse.automation.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.automation.executors.RootRequestFailure
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.settings.SettingsSubPageScaffold
import io.zer0.muse.tools.system.ShizukuAuthorizer
import kotlinx.coroutines.launch

/**
 * UI 自动化权限设置页 —— 三层梯度卡片。
 *
 * 每层一张卡片,展示:
 * - 状态指示点(绿=已开启/灰=未开启)
 * - 标题 + 副标题(说明该层能力)
 * - 右侧操作按钮(主行为)
 *
 * 第三层 Root 的主行为是向 root 管理器发起授权请求(Magisk/KernelSU 弹窗),失败时保留
 * Magisk/应用设置的兜底入口。视觉风格与 Muse 其他设置页统一:二级页顶栏(含返回键)
 * + surfaceVariant 浅灰底圆角卡片。
 */
@Composable
fun AutomationSettingsPage(
    manager: AutomationManager,
    onBack: () -> Unit = {},
    /** v1.xxx: F-24 编排入口 — 跳到定时任务页编排自动化动作。 */
    onOpenScheduledTasks: () -> Unit = {},
) {
    val state by manager.permissionState.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ScreenTestResult?>(null) }
    var rootRequesting by remember { mutableStateOf(false) }
    var rootFailure by remember { mutableStateOf<RootRequestFailure?>(null) }
    // ST-01: 权限探测加载态 — 探测期间卡片禁用并显示进度,避免误读为"未启用"
    var isRefreshingPermissions by remember { mutableStateOf(false) }

    // Shizuku/Magisk 在外部页面完成授权后，返回 Muse 必须重新探测；
    // repeatOnLifecycle 统一首屏和回到前台的刷新，避免 LaunchedEffect + observer 重复触发。
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            isRefreshingPermissions = true
            try {
                manager.refreshPermissions()
            } finally {
                isRefreshingPermissions = false
            }
        }
    }

    SettingsSubPageScaffold(
        title = stringResource(R.string.automation_settings_title),
        onBack = onBack,
    ) {
        // 顶部说明卡
        item(key = "intro") {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Computer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.automation_settings_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 第一层:无障碍
        item(key = "accessibility") {
            PermissionCard(
                icon = Icons.Outlined.Shield,
                title = stringResource(R.string.automation_tier_accessibility),
                subtitle = stringResource(R.string.automation_tier_accessibility_desc),
                enabled = state.accessibilityEnabled,
                levelLabel = stringResource(R.string.automation_tier_level, 1),
                actionLabel = stringResource(R.string.automation_action_enable),
                loading = isRefreshingPermissions,
                onAction = {
                    openAutomationSettings(
                        context = context,
                        candidates = listOf(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                            appDetailsIntent(context),
                            Intent(Settings.ACTION_SETTINGS),
                        ),
                    ) {
                        MuseToast.show(context.getString(R.string.automation_settings_unavailable))
                    }
                },
            )
        }

        // 第二层:Shell
        item(key = "shell") {
            PermissionCard(
                icon = Icons.Outlined.Terminal,
                title = stringResource(R.string.automation_tier_shell),
                // 状态文案走资源按枚举映射:授权器返回的 shizukuMessage 是日志用的中文,
                // 直接上 UI 会破坏多语言(onboarding 已出现过同类问题)。
                subtitle = stringResource(R.string.automation_tier_shell_desc) + " " +
                    shizukuStatusLabel(state.shizukuState),
                enabled = state.shellEnabled,
                levelLabel = stringResource(R.string.automation_tier_level, 2),
                actionLabel = stringResource(R.string.automation_action_enable),
                loading = isRefreshingPermissions,
                onAction = {
                    // 打开 Shizuku 应用(若已安装),否则按 ROM 能力逐级降级。
                    val shizukuIntent = context.packageManager
                        .getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    openAutomationSettings(
                        context = context,
                        candidates = buildList {
                            shizukuIntent?.let(::add)
                            add(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                            add(appDetailsIntent(context))
                            add(Intent(Settings.ACTION_SETTINGS))
                        },
                    ) {
                        MuseToast.show(context.getString(R.string.automation_settings_unavailable))
                    }
                },
            )
        }

        // 第三层:Root —— 主行为是发起 su 授权请求,不是直接跳外部页面。
        item(key = "root") {
            PermissionCard(
                icon = Icons.Outlined.AdminPanelSettings,
                title = stringResource(R.string.automation_tier_root),
                subtitle = stringResource(R.string.automation_tier_root_desc),
                enabled = state.rootEnabled,
                levelLabel = stringResource(R.string.automation_tier_level, 3),
                actionLabel = stringResource(R.string.automation_root_action_request),
                actionInProgress = rootRequesting,
                note = rootFailure?.let { stringResource(rootFailureMessage(it)) },
                loading = isRefreshingPermissions,
                onAction = {
                    if (state.rootEnabled) {
                        openRootManagerSettings(context)
                    } else if (!rootRequesting) {
                        scope.launch {
                            rootRequesting = true
                            rootFailure = null
                            // manager 内部会在请求后刷新三层状态,成功时卡片自动变绿。
                            val result = manager.requestRoot()
                            rootRequesting = false
                            if (result.granted) {
                                rootFailure = null
                                MuseToast.show(context.getString(R.string.automation_root_granted))
                            } else {
                                val failure = result.failure ?: RootRequestFailure.ERROR
                                rootFailure = failure
                                MuseToast.show(context.getString(rootFailureMessage(failure)))
                            }
                        }
                    }
                },
                fallbackLabel = stringResource(R.string.automation_root_fallback_action),
                onFallback = { openRootManagerSettings(context) },
            )
        }

        // v1.xxx: F-24 自动化动作如何编排 — 说明 + 一键跳转到定时任务页
        item(key = "orchestration") {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.automation_orchestration_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.automation_orchestration_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    MuseCapsuleButton(
                        text = stringResource(R.string.automation_orchestration_action),
                        onClick = onOpenScheduledTasks,
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = Icons.Outlined.Schedule,
                    )
                }
            }
        }

        // 测试按钮
        item(key = "test") {
            MuseCapsuleButton(
                text = if (testing) stringResource(R.string.automation_test_running)
                else stringResource(R.string.automation_test_action),
                onClick = {
                    if (testing) return@MuseCapsuleButton
                    scope.launch {
                        testing = true
                        testResult = null
                        val outcome = runCatching {
                            manager.refreshPermissions()
                            val screen = manager.readScreen()
                            if (screen.source == "unavailable") {
                                ScreenTestResult(
                                    context.getString(R.string.automation_test_no_channel),
                                    success = false,
                                )
                            } else {
                                val unknown = context.getString(R.string.automation_test_unknown)
                                // ST-04: 包名与数据来源属开发者诊断信息,收进 devInfo 折叠区
                                ScreenTestResult(
                                    message = buildString {
                                        appendLine(
                                            context.getString(
                                                R.string.automation_test_node_count,
                                                screen.nodes.size,
                                            )
                                        )
                                        appendLine(
                                            context.getString(
                                                R.string.automation_test_resolution,
                                                screen.screenWidth,
                                                screen.screenHeight,
                                            )
                                        )
                                    }.trimEnd('\n'),
                                    success = true,
                                    devInfo = buildString {
                                        appendLine(
                                            context.getString(
                                                R.string.automation_test_current_app,
                                                screen.packageName ?: unknown,
                                            )
                                        )
                                        appendLine(
                                            context.getString(
                                                R.string.automation_test_source,
                                                screen.source,
                                            )
                                        )
                                    }.trimEnd('\n'),
                                )
                            }
                        }.getOrElse {
                            ScreenTestResult(
                                context.getString(
                                    R.string.automation_test_failed,
                                    it.message ?: context.getString(R.string.automation_test_unknown),
                                ),
                                success = false,
                            )
                        }
                        testResult = outcome
                        testing = false
                    }
                },
                enabled = !testing,
                loading = testing,
                leadingIcon = Icons.Outlined.PlayArrow,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 测试结果
        testResult?.let { result ->
            item(key = "test-result") {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (result.success) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            text = result.message,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // ST-04: 开发者诊断信息默认折叠,展开显示包名与数据来源
                        result.devInfo?.let { devInfo ->
                            var showDevInfo by remember { mutableStateOf(false) }
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { showDevInfo = !showDevInfo }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.automation_developer_info),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    imageVector = if (showDevInfo) {
                                        Icons.Filled.KeyboardArrowUp
                                    } else {
                                        Icons.Filled.KeyboardArrowDown
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (showDevInfo) {
                                Text(
                                    text = devInfo,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 一次屏幕读取自测的结果:用户可见文案 + 是否成功(决定提示卡配色)。
 * devInfo 为开发者诊断信息(包名/数据来源),ST-04 起默认折叠展示。
 */
private data class ScreenTestResult(
    val message: String,
    val success: Boolean,
    val devInfo: String? = null,
)

/** Root 请求失败原因 → 用户可读文案。 */
@StringRes
private fun rootFailureMessage(failure: RootRequestFailure): Int = when (failure) {
    RootRequestFailure.NO_SU_BINARY -> R.string.automation_root_no_su
    RootRequestFailure.TIMEOUT -> R.string.automation_root_timeout
    RootRequestFailure.DENIED -> R.string.automation_root_denied
    RootRequestFailure.ERROR -> R.string.automation_root_failed
}

/** Shizuku 状态的可读文案(与 [ShizukuAuthorizer.ShizukuState] 一一对应)。 */
@Composable
private fun shizukuStatusLabel(state: ShizukuAuthorizer.ShizukuState): String = stringResource(
    when (state) {
        ShizukuAuthorizer.ShizukuState.NOT_INSTALLED -> R.string.automation_shizuku_not_installed
        ShizukuAuthorizer.ShizukuState.NOT_RUNNING -> R.string.automation_shizuku_not_running
        ShizukuAuthorizer.ShizukuState.NOT_AUTHORIZED -> R.string.automation_shizuku_not_authorized
        ShizukuAuthorizer.ShizukuState.USER_SERVICE_UNAVAILABLE ->
            R.string.automation_shizuku_user_service_unavailable
        ShizukuAuthorizer.ShizukuState.READY -> R.string.automation_shizuku_ready
    },
)

private fun appDetailsIntent(context: Context): Intent = Intent(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    "package:${context.packageName}".toUri(),
)

/** Root 兜底入口:优先 Magisk 应用,其次本应用详情页,最后系统设置。 */
private fun openRootManagerSettings(context: Context) {
    val magiskIntent = context.packageManager
        .getLaunchIntentForPackage("com.topjohnwu.magisk")
    openAutomationSettings(
        context = context,
        candidates = buildList {
            magiskIntent?.let(::add)
            add(appDetailsIntent(context))
            add(Intent(Settings.ACTION_SETTINGS))
        },
    ) {
        MuseToast.show(context.getString(R.string.automation_settings_unavailable))
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    levelLabel: String,
    actionLabel: String,
    onAction: () -> Unit,
    /** 主行为正在进行(如等待 Root 授权弹窗),期间禁止重复点击。 */
    actionInProgress: Boolean = false,
    /** 兜底入口文案(如 Root 层的 Magisk/应用设置);为空时不显示。 */
    fallbackLabel: String? = null,
    onFallback: (() -> Unit)? = null,
    /** 失败原因等补充说明,展示在副标题下方。 */
    note: String? = null,
    /** ST-01: 权限探测加载中 — 期间禁用交互并显示进度,避免误读为"未启用"。 */
    loading: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .clickable(enabled = !actionInProgress && !loading, onClick = onAction)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 图标 + 状态点
            Box(contentAlignment = Alignment.BottomEnd) {
                Surface(
                    shape = CircleShape,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (enabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                // 状态小圆点
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(if (enabled) Color(0xFF4CAF50) else Color.Gray)
                        .then(
                            Modifier.border(
                                2.dp,
                                MaterialTheme.colorScheme.surfaceVariant,
                                CircleShape,
                            )
                        ),
                )
            }

            // 文字
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                    ) {
                        Text(
                            text = levelLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                note?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // 右侧状态
            if (loading) {
                MuseSpinner(
                    size = 20.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (enabled) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = stringResource(R.string.automation_status_enabled),
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(20.dp),
                )
            } else {
                // v2.0 修复: 限制操作列最大宽度,避免按钮测量异常时把左侧文字挤成竖排
                Column(
                    modifier = Modifier.widthIn(max = 160.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    MuseCapsuleButton(
                            text = if (actionInProgress) stringResource(R.string.automation_root_requesting) else actionLabel,
                            onClick = onAction,
                            enabled = !actionInProgress,
                            loading = actionInProgress,
                            fillWidth = false,
                        )
                    if (fallbackLabel != null && onFallback != null) {
                        MuseCapsuleButton(
                            text = fallbackLabel,
                            onClick = onFallback,
                            enabled = !actionInProgress,
                            variant = IosCapsuleButtonVariant.Text,
                            fillWidth = false,
                        )
                    }
                }
            }
        }
    }
}
