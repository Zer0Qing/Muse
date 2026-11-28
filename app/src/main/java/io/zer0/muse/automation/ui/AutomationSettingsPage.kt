package io.zer0.muse.automation.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.automation.AutomationRuntime
import io.zer0.muse.automation.appcontrol.AndroidAppController
import io.zer0.muse.automation.appcontrol.InstalledAppInfo
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.automation.core.AutonomousAccessibilityGrant
import io.zer0.muse.automation.vdisplay.VirtualDisplayHandoffRegistry
import io.zer0.muse.data.AutomationSettingsStore
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.permission.PermissionCoordinator
import io.zer0.muse.ui.chat.ChatViewModel
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.actionItem
import io.zer0.muse.ui.common.surface.navigationItem
import io.zer0.muse.ui.common.surface.switchItem
import io.zer0.muse.ui.settings.SettingsSubPageScaffold
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.largeCard
import io.zer0.muse.ui.theme.titleMediumStrong
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent

/**
 * UI 自动化设置页。
 *
 * 收敛为「总览（能力仪表盘 + 总闸 + 三大入口）+ 三个专注子页」，
 * 避免子页继续重复渲染总览横幅和主开关，并在回到前台时自动刷新无障碍与服务状态。
 */
@Suppress("LongMethod", "FunctionNaming")
@Composable
fun AutomationSettingsPage(
    manager: AutomationManager,
    onBack: () -> Unit = {},
    /** v1.xxx: F-24 编排入口 — 跳到定时任务页编排自动化动作。 */
    onOpenScheduledTasks: () -> Unit = {},
    /** v2.x: 权限配置向导入口。 */
    onOpenPermissionWizard: () -> Unit = {},
    /** Tasker/外部自动化开关；可选以保持 Robolectric 页面测试不依赖 Koin。 */
    settings: SettingsRepository? = null,
    permissionCoordinator: PermissionCoordinator? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val resolvedCoordinator = remember(permissionCoordinator) {
        permissionCoordinator ?: runCatching {
            KoinJavaComponent.getOrNull<PermissionCoordinator>(PermissionCoordinator::class.java)
        }.getOrNull()
    }
    val chatViewModel: ChatViewModel? = remember {
        runCatching { KoinJavaComponent.getOrNull<ChatViewModel>(ChatViewModel::class.java) }.getOrNull()
    }

    val taskerEnabled by settings?.taskerTriggerEnabledFlow?.collectAsStateWithLifecycle(initialValue = false)
        ?: remember { mutableStateOf(false) }
    val automationEnabled by settings?.automationEnabledFlow?.collectAsStateWithLifecycle(initialValue = true)
        ?: remember { mutableStateOf(true) }
    val visionEnabled by settings?.visionEnabledFlow?.collectAsStateWithLifecycle(initialValue = false)
        ?: remember { mutableStateOf(false) }
    val visionModelId by settings?.visionModelIdFlow?.collectAsStateWithLifecycle(initialValue = null)
        ?: remember { mutableStateOf<String?>(null) }

    var handoffExpanded by remember { mutableStateOf<Int?>(null) }
    val permissionState by manager.permissionState.collectAsStateWithLifecycle()
    val coordSnapshotState = resolvedCoordinator?.snapshot?.collectAsStateWithLifecycle()
    val a11yDead = coordSnapshotState?.value?.accessibilityServiceDead == true
    val handoffs by VirtualDisplayHandoffRegistry.handoffsFlow.collectAsStateWithLifecycle()

    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<FourPointSelfTestResult?>(null) }
    val automationSettingsStore = remember { AutomationSettingsStore(context.applicationContext) }
    var autonomousDraft by remember { mutableStateOf(AutonomousAccessibilityDraftState()) }
    var packageEditor by remember { mutableStateOf(AutonomousAccessibilityDraftState().allowedPackages.joinToString("\n")) }
    var autonomousLoaded by remember { mutableStateOf(false) }
    var taskPreview by remember { mutableStateOf<ChatViewModel.AutonomousAccessibilityTaskPreview?>(null) }
    var taskGrant by remember { mutableStateOf<AutonomousAccessibilityGrant?>(null) }
    var installedApps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }

    var subPage by remember { mutableStateOf(AutomationSubPage.LANDING) }
    BackHandler(enabled = subPage != AutomationSubPage.LANDING) { subPage = AutomationSubPage.LANDING }

    DisposableEffect(lifecycleOwner, manager, resolvedCoordinator) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    runCatching { manager.refreshPermissions() }
                    runCatching { resolvedCoordinator?.refresh() }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(automationSettingsStore, chatViewModel) {
        autonomousDraft =
            AutonomousAccessibilityDraftState.fromWindow(
                automationSettingsStore.autonomousAccessibilityWindowFlow.first(),
            )
        packageEditor = autonomousDraft.allowedPackages.joinToString("\n")
        taskPreview = runCatching { chatViewModel?.autonomousAccessibilityTaskPreview() }.getOrNull()
        taskGrant = automationSettingsStore.autonomousAccessibilityGrantFlow.first()
        autonomousLoaded = true
    }

    LaunchedEffect(subPage, manager) {
        if (subPage == AutomationSubPage.AUTONOMOUS && installedApps.isEmpty()) {
            installedApps = AndroidAppController(context.applicationContext, manager).listLaunchableApps()
        }
    }

    AnimatedContent(
        targetState = subPage,
        transitionSpec = {
            (slideInHorizontally(animationSpec = tween(220)) { full -> full } + fadeIn(tween(220))) togetherWith
                (slideOutHorizontally(animationSpec = tween(220)) { full -> -full / 4 } + fadeOut(tween(160)))
        },
        label = "automationSubPage",
    ) { page ->
        SettingsSubPageScaffold(
            title = stringResource(automationSubPageTitleRes(page)),
            onBack = {
                if (page != AutomationSubPage.LANDING) subPage = AutomationSubPage.LANDING else onBack()
            },
        ) {
            when (page) {
                AutomationSubPage.LANDING -> {
                    landingSubPage(
                        manager = manager,
                        settings = settings,
                        automationEnabled = automationEnabled,
                        taskerEnabled = taskerEnabled,
                        permissionState = permissionState,
                        a11yDead = a11yDead,
                        visionEnabled = visionEnabled,
                        visionModelId = visionModelId,
                        scope = scope,
                        onOpenPermissionWizard = onOpenPermissionWizard,
                        onNavigate = { subPage = it },
                    )
                }

                AutomationSubPage.ACCESS -> {
                    item(key = "permissions-entry") {
                        CardGroup {
                            navigationItem(
                                key = "permission_wizard",
                                title = stringResource(R.string.permission_wizard_title),
                                subtitle = stringResource(R.string.permission_wizard_desc),
                                onClick = onOpenPermissionWizard,
                                leadingContent = { SettingsRowIcon(MuseIcons.shieldCheck) },
                            )
                        }
                    }
                    val liveHandoffs = handoffs.filter { !it.isExpired(System.currentTimeMillis()) }
                    if (liveHandoffs.isNotEmpty()) {
                        item(key = "vdisplay-handoff") {
                            VirtualDisplayHandoffSection(
                                handoffs = liveHandoffs,
                                expandedId = handoffExpanded,
                                onExpand = { id -> handoffExpanded = id },
                                onClose = { id ->
                                    VirtualDisplayHandoffRegistry.release(id)
                                    handoffExpanded = null
                                    scope.launch { AutomationRuntime.destroy(id) }
                                },
                            )
                        }
                    }
                }

                AutomationSubPage.AUTONOMOUS -> {
                    item(key = "autonomous-accessibility") {
                        AutonomousAccessibilityItem(
                            loaded = autonomousLoaded,
                            draft = autonomousDraft,
                            packageEditor = packageEditor,
                            taskPreview = taskPreview,
                            grant = taskGrant,
                            installedApps = installedApps,
                            store = automationSettingsStore,
                            onDraftChange = { autonomousDraft = it },
                            onPackageEditorChange = { packageEditor = it },
                            onGrantChange = { taskGrant = it },
                        )
                    }
                }

                AutomationSubPage.ORCHESTRATION -> {
                    orchestrationSubPage(
                        testing = testing,
                        testResult = testResult,
                        onRunTest = {
                            scope.launch {
                                testing = true
                                testResult = null
                                testResult = runFourPointSelfTest(manager, context, a11yDead)
                                testing = false
                            }
                        },
                        onOpenScheduledTasks = onOpenScheduledTasks,
                    )
                }
            }
        }
    }
}

private fun LazyListScope.landingSubPage(
    manager: AutomationManager,
    settings: SettingsRepository?,
    automationEnabled: Boolean,
    taskerEnabled: Boolean,
    permissionState: AutomationManager.PermissionState,
    a11yDead: Boolean,
    visionEnabled: Boolean,
    visionModelId: String?,
    scope: kotlinx.coroutines.CoroutineScope,
    onOpenPermissionWizard: () -> Unit,
    onNavigate: (AutomationSubPage) -> Unit,
) {
    item(key = "intro") {
        AutomationNoticeCard(
            icon = MuseIcons.computer,
            text = stringResource(R.string.automation_settings_intro),
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = MuseAlpha.mutedSurface),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            iconTint = MaterialTheme.colorScheme.primary,
        )
    }

    val bannerKind = automationBannerKind(
        automationEnabled = automationEnabled,
        accessibilityEnabled = permissionState.accessibilityEnabled,
    )
    if (bannerKind == AutomationBannerKind.MASTER_OFF) {
        item(key = "master-off-banner") {
            AutomationNoticeCard(
                icon = MuseIcons.shield,
                text = stringResource(R.string.automation_master_off_banner),
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = MuseAlpha.mutedSurface),
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                iconTint = MaterialTheme.colorScheme.error,
            )
        }
    } else if (bannerKind == AutomationBannerKind.A11Y_OFF || a11yDead) {
        item(key = "a11y-off-banner") {
            AutomationNoticeCard(
                icon = MuseIcons.shield,
                text = stringResource(
                    if (a11yDead) R.string.automation_a11y_dead_banner else R.string.automation_a11y_off_banner,
                ),
                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = MuseAlpha.mutedSurface),
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                action = {
                    Text(
                        text = stringResource(R.string.automation_a11y_off_action),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = MuseSpacing.smTight)
                            .clip(MuseShapes.small)
                            .clickable {
                                manager.openSystemSettings(
                                    AutomationManager.SystemSettingsTarget.ACCESSIBILITY,
                                )
                            }
                            .padding(horizontal = MuseSpacing.xs, vertical = MuseSpacing.xs),
                    )
                },
            )
        }
    }

    item(key = "readiness-dashboard") {
        CapabilityReadinessDashboard(
            permissionState = permissionState,
            a11yDead = a11yDead,
            visionEnabled = visionEnabled,
            visionModelId = visionModelId,
            onOpenAccessibility = {
                manager.openSystemSettings(AutomationManager.SystemSettingsTarget.ACCESSIBILITY)
            },
            onOpenPermissionWizard = onOpenPermissionWizard,
        )
    }

    val switchEnabled = settings != null
    item(key = "automation-master") {
        CardGroup {
            switchItem(
                key = "automation_master",
                title = stringResource(R.string.automation_master_title),
                subtitle = stringResource(R.string.automation_master_desc),
                checked = automationEnabled,
                onCheckedChange = { enabled ->
                    settings?.let { repository -> scope.launch { repository.saveAutomationEnabled(enabled) } }
                },
                enabled = switchEnabled,
                leadingContent = { SettingsRowIcon(MuseIcons.switch) },
            )
            switchItem(
                key = "automation_tasker",
                title = stringResource(R.string.automation_tasker_title),
                subtitle = stringResource(R.string.automation_tasker_desc),
                checked = taskerEnabled,
                onCheckedChange = { enabled ->
                    settings?.let { repository ->
                        scope.launch { repository.saveTaskerTriggerEnabled(enabled) }
                    }
                },
                enabled = switchEnabled,
                leadingContent = { SettingsRowIcon(MuseIcons.link) },
            )
        }
    }

    item(key = "sub-menu") {
        CardGroup {
            navigationItem(
                key = "sub_access",
                title = stringResource(R.string.automation_sub_access),
                subtitle = stringResource(R.string.automation_sub_access_desc),
                onClick = { onNavigate(AutomationSubPage.ACCESS) },
                leadingContent = { SettingsRowIcon(MuseIcons.shieldCheck) },
            )
            navigationItem(
                key = "sub_autonomous",
                title = stringResource(R.string.automation_sub_autonomous),
                subtitle = stringResource(R.string.automation_sub_autonomous_desc),
                onClick = { onNavigate(AutomationSubPage.AUTONOMOUS) },
                leadingContent = { SettingsRowIcon(MuseIcons.computer) },
            )
            navigationItem(
                key = "sub_orchestration",
                title = stringResource(R.string.automation_sub_orchestration),
                subtitle = stringResource(R.string.automation_sub_orchestration_desc),
                onClick = { onNavigate(AutomationSubPage.ORCHESTRATION) },
                leadingContent = { SettingsRowIcon(MuseIcons.clock) },
            )
        }
    }
}

@Composable
private fun CapabilityReadinessDashboard(
    permissionState: AutomationManager.PermissionState,
    a11yDead: Boolean,
    visionEnabled: Boolean,
    visionModelId: String?,
    onOpenAccessibility: () -> Unit,
    onOpenPermissionWizard: () -> Unit,
) {
    val a11yStatus = when {
        permissionState.accessibilityEnabled && !a11yDead -> CheckState.PASS
        a11yDead -> CheckState.WARN
        else -> CheckState.FAIL
    }
    val shellStatus = if (permissionState.shellEnabled || permissionState.rootEnabled) {
        CheckState.PASS
    } else {
        CheckState.FAIL
    }
    val vdStatus = if (AutomationRuntime.isReady && (permissionState.shellEnabled || permissionState.rootEnabled)) {
        CheckState.PASS
    } else {
        CheckState.FAIL
    }
    val visionStatus = if (visionEnabled && !visionModelId.isNullOrBlank()) CheckState.PASS else CheckState.WARN

    CardGroup(title = { Text(stringResource(R.string.automation_readiness_title)) }) {
        item(
            key = "ready_a11y",
            onClick = onOpenAccessibility,
            leadingContent = { SettingsRowIcon(MuseIcons.shieldCheck) },
            headlineContent = {
                Text(
                    stringResource(R.string.automation_readiness_accessibility),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = { ReadinessPill(a11yStatus) },
        )
        item(
            key = "ready_shell",
            onClick = onOpenPermissionWizard,
            leadingContent = { SettingsRowIcon(MuseIcons.bolt) },
            headlineContent = { Text(stringResource(R.string.automation_readiness_shell), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingContent = { ReadinessPill(shellStatus) },
        )
        item(
            key = "ready_vdisplay",
            onClick = onOpenPermissionWizard,
            leadingContent = { SettingsRowIcon(MuseIcons.deviceMobile) },
            headlineContent = {
                Text(
                    stringResource(R.string.automation_readiness_vdisplay),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = { ReadinessPill(vdStatus) },
        )
        item(
            key = "ready_vision",
            leadingContent = { SettingsRowIcon(MuseIcons.image) },
            headlineContent = {
                Text(
                    stringResource(R.string.automation_readiness_vision),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                Text(
                    text = when {
                        !visionEnabled -> stringResource(R.string.automation_readiness_vision_off)
                        !visionModelId.isNullOrBlank() -> stringResource(R.string.automation_readiness_vision_on, visionModelId)
                        else -> stringResource(R.string.automation_readiness_vision_on_unnamed)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = { ReadinessPill(visionStatus) },
        )
    }
}

@Composable
private fun ReadinessPill(state: CheckState) {
    val labelRes = when (state) {
        CheckState.PASS -> R.string.automation_readiness_ready
        CheckState.WARN -> R.string.automation_readiness_dead
        CheckState.FAIL -> R.string.automation_readiness_off
    }
    StatusPill(label = stringResource(labelRes), state = state)
}

@Composable
private fun StatusPill(label: String, state: CheckState) {
    val (bg, fg) = when (state) {
        CheckState.PASS -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        CheckState.WARN -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        CheckState.FAIL -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        shape = MuseShapes.small,
        color = bg,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = MuseSpacing.smTight, vertical = MuseSpacing.xxs),
        )
    }
}

@Composable
private fun AutomationNoticeCard(
    icon: ImageVector,
    text: String,
    containerColor: Color,
    contentColor: Color,
    iconTint: Color,
    action: (@Composable () -> Unit)? = null,
) {
    Surface(
        shape = MuseShapes.largeCard,
        color = containerColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(MusePaddings.sectionGap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor,
                )
                val actionContent = action
                if (actionContent != null) {
                    actionContent()
                }
            }
        }
    }
}

@Composable
private fun SettingsRowIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(MuseIconSizes.icon),
    )
}

private fun LazyListScope.orchestrationSubPage(
    testing: Boolean,
    testResult: FourPointSelfTestResult?,
    onRunTest: () -> Unit,
    onOpenScheduledTasks: () -> Unit,
) {
    item(key = "orchestration") {
        CardGroup {
            content(key = "orchestration_intro") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = MuseIcons.clock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                        Spacer(Modifier.width(MusePaddings.contentGap))
                        Text(
                            text = stringResource(R.string.automation_orchestration_title),
                            style = MaterialTheme.typography.titleMediumStrong,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(MusePaddings.contentGap))
                    Text(
                        text = stringResource(R.string.automation_orchestration_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            actionItem(
                key = "orchestration_action",
                title = stringResource(R.string.automation_orchestration_action),
                onClick = onOpenScheduledTasks,
            )
            actionItem(
                key = "test_action",
                title = if (testing) {
                    stringResource(R.string.automation_test_running)
                } else {
                    stringResource(R.string.automation_selftest_action)
                },
                onClick = onRunTest,
                enabled = !testing,
                loading = testing,
            )
        }
    }
    testResult?.let { result ->
        item(key = "test-result") {
            FourPointSelfTestCard(result)
        }
    }
}

@Composable
private fun FourPointSelfTestCard(result: FourPointSelfTestResult) {
    CardGroup(title = { Text(stringResource(R.string.automation_selftest_title)) }) {
        result.items.forEachIndexed { index, entry ->
            item(
                key = "check_$index",
                headlineContent = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(entry.detail) },
                trailingContent = {
                    val labelRes = when (entry.state) {
                        CheckState.PASS -> R.string.automation_selftest_pass
                        CheckState.WARN -> R.string.automation_selftest_warn
                        CheckState.FAIL -> R.string.automation_selftest_fail
                    }
                    StatusPill(label = stringResource(labelRes), state = entry.state)
                },
            )
        }
        result.devInfo?.let { devInfo ->
            content(key = "self_test_dev_info") {
                var showDevInfo by remember { mutableStateOf(false) }
                Column(Modifier.padding(MusePaddings.cardInner)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MuseShapes.small)
                            .clickable { showDevInfo = !showDevInfo }
                            .padding(vertical = MuseSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.automation_developer_info),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (showDevInfo) MuseIcons.chevronUp else MuseIcons.chevronDown,
                            contentDescription = null,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (showDevInfo) {
                        Text(
                            text = devInfo,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = MuseSpacing.xs),
                        )
                    }
                }
            }
        }
    }
}

private enum class AutomationSubPage { LANDING, ACCESS, AUTONOMOUS, ORCHESTRATION }

private enum class CheckState { PASS, WARN, FAIL }

private data class SelfTestItem(
    val title: String,
    val detail: String,
    val state: CheckState,
)

private data class FourPointSelfTestResult(
    val items: List<SelfTestItem>,
    val devInfo: String? = null,
)

@androidx.annotation.StringRes
private fun automationSubPageTitleRes(subPage: AutomationSubPage): Int = when (subPage) {
    AutomationSubPage.LANDING -> R.string.automation_settings_title
    AutomationSubPage.ACCESS -> R.string.automation_sub_access
    AutomationSubPage.AUTONOMOUS -> R.string.automation_sub_autonomous
    AutomationSubPage.ORCHESTRATION -> R.string.automation_sub_orchestration
}

private suspend fun runFourPointSelfTest(manager: AutomationManager, context: Context, a11yDead: Boolean): FourPointSelfTestResult {
    val perms = runCatching { manager.refreshPermissions() }.getOrElse { manager.permissionState.value }
    val a11yItem = when {
        perms.accessibilityEnabled && !a11yDead -> SelfTestItem(
            title = context.getString(R.string.automation_selftest_check_a11y),
            detail = context.getString(R.string.automation_selftest_a11y_live),
            state = CheckState.PASS,
        )
        a11yDead -> SelfTestItem(
            title = context.getString(R.string.automation_selftest_check_a11y),
            detail = context.getString(R.string.automation_selftest_a11y_dead),
            state = CheckState.WARN,
        )
        else -> SelfTestItem(
            title = context.getString(R.string.automation_selftest_check_a11y),
            detail = context.getString(R.string.automation_selftest_a11y_off),
            state = CheckState.FAIL,
        )
    }

    val shellChannels = buildList {
        if (perms.shellEnabled) add("Shizuku")
        if (perms.rootEnabled) add("Root")
    }
    val shellItem = if (shellChannels.isNotEmpty()) {
        SelfTestItem(
            title = context.getString(R.string.automation_selftest_check_shell),
            detail = context.getString(R.string.automation_selftest_shell_ok, shellChannels.joinToString(" + ")),
            state = CheckState.PASS,
        )
    } else {
        SelfTestItem(
            title = context.getString(R.string.automation_selftest_check_shell),
            detail = context.getString(R.string.automation_selftest_shell_off),
            state = CheckState.FAIL,
        )
    }

    val vdItem = if (AutomationRuntime.isReady && shellChannels.isNotEmpty()) {
        SelfTestItem(
            title = context.getString(R.string.automation_selftest_check_vdisplay),
            detail = context.getString(R.string.automation_selftest_vd_ok, shellChannels.first()),
            state = CheckState.PASS,
        )
    } else {
        val reason = if (shellChannels.isEmpty()) {
            context.getString(R.string.automation_selftest_shell_off)
        } else {
            context.getString(R.string.automation_readiness_off)
        }
        SelfTestItem(
            title = context.getString(R.string.automation_selftest_check_vdisplay),
            detail = context.getString(R.string.automation_selftest_vd_off, reason),
            state = CheckState.FAIL,
        )
    }

    var devInfo: String? = null
    val screenItem = runCatching {
        val screen = manager.readScreen()
        if (screen.source == "unavailable") {
            SelfTestItem(
                title = context.getString(R.string.automation_selftest_check_screen),
                detail = context.getString(R.string.automation_test_no_channel),
                state = CheckState.FAIL,
            )
        } else {
            val unknown = context.getString(R.string.automation_test_unknown)
            devInfo = buildString {
                appendLine(context.getString(R.string.automation_test_current_app, screen.packageName ?: unknown))
                appendLine(context.getString(R.string.automation_test_source, screen.source))
            }.trimEnd('\n')
            SelfTestItem(
                title = context.getString(R.string.automation_selftest_check_screen),
                detail = context.getString(
                    R.string.automation_selftest_screen_ok,
                    screen.nodes.size,
                    screen.screenWidth,
                    screen.screenHeight,
                ),
                state = CheckState.PASS,
            )
        }
    }.getOrElse {
        SelfTestItem(
            title = context.getString(R.string.automation_selftest_check_screen),
            detail = context.getString(
                R.string.automation_test_failed,
                it.message ?: context.getString(R.string.automation_test_unknown),
            ),
            state = CheckState.FAIL,
        )
    }

    return FourPointSelfTestResult(
        items = listOf(a11yItem, shellItem, vdItem, screenItem),
        devInfo = devInfo,
    )
}

@Composable
private fun VirtualDisplayHandoffSection(
    handoffs: List<VirtualDisplayHandoffRegistry.Handoff>,
    expandedId: Int?,
    onExpand: (Int?) -> Unit,
    onClose: (Int) -> Unit,
) {
    if (!AutomationRuntime.isReady) return
    val first = handoffs.firstOrNull() ?: return
    val expanded = expandedId == first.displayId
    CardGroup {
        navigationItem(
            key = "handoff_entry",
            title = stringResource(R.string.automation_handoff_title),
            subtitle = stringResource(R.string.automation_handoff_desc, handoffs.size),
            onClick = { onExpand(if (expanded) null else first.displayId) },
            leadingContent = { SettingsRowIcon(MuseIcons.bolt) },
        )
    }
    if (expanded) {
        VirtualDisplayHandoffPanel(
            displayId = first.displayId,
            packageName = first.packageName,
            screenshot = { AutomationRuntime.screenshot(first.displayId) },
            tap = { x, y -> AutomationRuntime.tap(first.displayId, x, y) },
            bringToMain = { AutomationRuntime.launchOnMain(first.packageName) },
            onClose = { onClose(first.displayId) },
        )
    }
}

@Composable
private fun AutonomousAccessibilityItem(
    loaded: Boolean,
    draft: AutonomousAccessibilityDraftState,
    packageEditor: String,
    taskPreview: ChatViewModel.AutonomousAccessibilityTaskPreview?,
    grant: AutonomousAccessibilityGrant?,
    installedApps: List<InstalledAppInfo>,
    store: AutomationSettingsStore,
    onDraftChange: (AutonomousAccessibilityDraftState) -> Unit,
    onPackageEditorChange: (String) -> Unit,
    onGrantChange: (AutonomousAccessibilityGrant?) -> Unit,
) {
    if (!loaded) return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    AutonomousAccessibilitySettingsCard(
        draft = draft,
        packageEditor = packageEditor,
        taskPreview = taskPreview,
        grant = grant,
        installedApps = installedApps,
        onDraftChange = onDraftChange,
        onPackageEditorChange = onPackageEditorChange,
        onSave = {
            val next = draft.copy(allowedPackages = parseAutonomousPackages(packageEditor))
            onDraftChange(next)
            scope.launch {
                runCatching { store.saveAutonomousAccessibilityWindow(next.toWindow()) }
                    .onSuccess { MuseToast.show(context.getString(R.string.automation_autonomous_saved)) }
                    .onFailure { Logger.w("AutomationSettings", "save autonomous plan failed: ${it.message}") }
            }
        },
        onConfirmTask = {
            val preview = taskPreview
            val editedPackages = parseAutonomousPackages(packageEditor)
            val resolvedPackages = editedPackages.ifEmpty { draft.allowedPackages }
            val targetPackage = resolvedPackages.firstOrNull()
            if (preview == null || targetPackage == null) {
                MuseToast.show(context.getString(R.string.automation_autonomous_confirm_required))
            } else {
                scope.launch {
                    if (editedPackages.isNotEmpty() && editedPackages != draft.allowedPackages) {
                        val updatedDraft = draft.copy(allowedPackages = editedPackages)
                        onDraftChange(updatedDraft)
                        runCatching { store.saveAutonomousAccessibilityWindow(updatedDraft.toWindow()) }
                    }
                    val newGrant = buildAutonomousGrant(
                        sessionId = preview.sessionId,
                        latestUserMessage = preview.message,
                        packageName = targetPackage,
                        nowMillis = System.currentTimeMillis(),
                    )
                    if (newGrant == null) {
                        MuseToast.show(context.getString(R.string.automation_autonomous_confirm_required))
                    } else {
                        store.saveAutonomousAccessibilityGrant(newGrant)
                        onGrantChange(newGrant)
                        MuseToast.show(context.getString(R.string.automation_autonomous_task_confirmed))
                    }
                }
            }
        },
        onClearTask = {
            val current = grant
            if (current != null) {
                scope.launch {
                    store.clearAutonomousAccessibilityGrant(expected = current)
                    onGrantChange(null)
                    MuseToast.show(context.getString(R.string.automation_autonomous_task_cleared))
                }
            }
        },
    )
}

/** 页面顶部该显示哪一条拦截说明。 */
internal enum class AutomationBannerKind { NONE, MASTER_OFF, A11Y_OFF }

/**
 * 总闸与无障碍权限两条横幅的**互斥判定**（纯函数，所以能被测）。
 */
internal fun automationBannerKind(automationEnabled: Boolean, accessibilityEnabled: Boolean): AutomationBannerKind = when {
    !automationEnabled -> AutomationBannerKind.MASTER_OFF
    !accessibilityEnabled -> AutomationBannerKind.A11Y_OFF
    else -> AutomationBannerKind.NONE
}
