package io.zer0.muse.automation.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.data.AutomationSettingsStore
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.ChatViewModel
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.settings.SettingsSubPageScaffold
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * UI 自动化设置页。
 *
 * v2.x: 三通道权限配置已收敛到「权限配置向导」页(消除两页逐层重复);
 * 本页保留:功能说明、向导入口、动作编排、屏幕读取测试与开发者诊断。
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
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val taskerEnabled by settings?.taskerTriggerEnabledFlow?.collectAsStateWithLifecycle(initialValue = false)
        ?: remember { mutableStateOf(false) }
    val automationEnabled by settings?.automationEnabledFlow?.collectAsStateWithLifecycle(initialValue = true)
        ?: remember { mutableStateOf(true) }
    // P1-02：助手留下的虚拟屏（可预览、可自己操作）。展开的是 displayId。
    var handoffExpanded by remember { mutableStateOf<Int?>(null) }
    // R1: 无障碍被系统回收时，页面要主动提示 —— 否则用户只会看到「开关都开着」，
    // 却不知道为什么读屏/点击全都失败。实时读 permissionState（不是一次性探测）。
    val permissionState by manager.permissionState.collectAsStateWithLifecycle()
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ScreenTestResult?>(null) }
    val automationSettingsStore = remember { AutomationSettingsStore(context.applicationContext) }
    var autonomousDraft by remember { mutableStateOf(AutonomousAccessibilityDraftState()) }
    var packageEditor by remember { mutableStateOf(AutonomousAccessibilityDraftState().allowedPackages.joinToString("\n")) }
    var autonomousLoaded by remember { mutableStateOf(false) }
    var taskPreview by remember { mutableStateOf<ChatViewModel.AutonomousAccessibilityTaskPreview?>(null) }
    var taskGrant by remember { mutableStateOf<io.zer0.muse.automation.core.AutonomousAccessibilityGrant?>(null) }
    val chatViewModel: ChatViewModel? = koinInject()

    // 25: 把堆砌的单页拆成「总览 + 三个子页」的菜单。用页内状态而非新导航路由，
    // 免得动导航图/设置目录/web 镜像与一堆计数测试；返回键先回总览再退出。
    var subPage by remember { mutableStateOf(AutomationSubPage.LANDING) }
    BackHandler(enabled = subPage != AutomationSubPage.LANDING) { subPage = AutomationSubPage.LANDING }

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

    // 2.6.24: 子页按「整页横向推入」呈现（含各自页头），不再在原页上只换标题与按钮。
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
                            imageVector = MuseIcons.computer,
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

            // 总闸关闭时，页面顶部先给一条醒目提示：否则用户只会看到「开关是关的」，
            // 却不知道这意味着所有屏幕自动化工具此刻都在拒绝执行。
            // 两条横幅**必须互斥**：一条说「为什么现在不能用」，一条说「去哪儿开回来」；
            // 同时出现只会让用户读到两句互相矛盾的话。
            // 以前这个互斥只靠两句手写条件（`!enabled` 与 `enabled && !a11y`）维持，
            // 谁改一句就可能同时点亮 —— 所以把判定抽出来，见文件末尾的 [automationBannerKind]。
            val bannerKind = automationBannerKind(
                automationEnabled = automationEnabled,
                accessibilityEnabled = permissionState.accessibilityEnabled,
            )
            when (bannerKind) {
                AutomationBannerKind.MASTER_OFF -> {
                    item(key = "master-off-banner") {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    imageVector = MuseIcons.shield,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    text = stringResource(R.string.automation_master_off_banner),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }

                // R1: 总闸开着、但无障碍服务当前不可用（多半是被系统回收/用户关了开关）。
                // 与上面的「总闸关闭」横幅互斥：那条说「为什么不能用」，这条说「去哪开回来」。
                AutomationBannerKind.A11Y_OFF -> {
                    item(key = "a11y-off-banner") {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    imageVector = MuseIcons.shield,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.automation_a11y_off_banner),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                    Text(
                                        text = stringResource(R.string.automation_a11y_off_action),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .padding(top = 6.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                manager.openSystemSettings(
                                                    AutomationManager.SystemSettingsTarget.ACCESSIBILITY,
                                                )
                                            }
                                            .padding(horizontal = 4.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                AutomationBannerKind.NONE -> Unit
            }

            // v2.x: 三通道权限配置已收敛到「权限配置向导」(消除两页逐层重复)
            if (page == AutomationSubPage.ACCESS) {
                item(key = "permissions-entry") {
                    SettingsGroup {
                        SettingsItemRow(
                            icon = MuseIcons.shieldCheck,
                            title = stringResource(R.string.permission_wizard_title),
                            subtitle = stringResource(R.string.permission_wizard_desc),
                            onClick = onOpenPermissionWizard,
                        ) {
                            ChevronRight()
                        }
                    }
                }
            }

            // 虚拟屏交接（P1-02）：任务跑完把屏留下来时，用户在这里看得到、也能自己点。
            val handoffs = io.zer0.muse.automation.vdisplay.VirtualDisplayHandoffRegistry
                .list(System.currentTimeMillis())
            if (page == AutomationSubPage.ACCESS && handoffs.isNotEmpty()) {
                item(key = "vdisplay-handoff") {
                    VirtualDisplayHandoffSection(
                        handoffs = handoffs,
                        expandedId = handoffExpanded,
                        onExpand = { id -> handoffExpanded = id },
                        onClose = { id ->
                            // 关闭 = 放弃交接 + 销毁：留着它就是一块用户看不见、还占资源的屏
                            io.zer0.muse.automation.vdisplay.VirtualDisplayHandoffRegistry.release(id)
                            handoffExpanded = null
                            scope.launch { io.zer0.muse.automation.AutomationRuntime.destroy(id) }
                        },
                    )
                }
            }

            // 25: 两个并列总闸收进同一张卡，减少「一项一卡」的堆砌感；行为与开关各自独立不变。
            item(key = "automation-master") {
                SettingsGroup {
                    SettingsItemRow(
                        icon = MuseIcons.switch,
                        title = stringResource(R.string.automation_master_title),
                        subtitle = stringResource(R.string.automation_master_desc),
                        onClick = null,
                    ) {
                        MuseSwitch(
                            checked = automationEnabled,
                            onCheckedChange = settings?.let { repository ->
                                { enabled -> scope.launch { repository.saveAutomationEnabled(enabled) } }
                            },
                            contentDescription = stringResource(R.string.automation_master_title),
                        )
                    }
                    // AUD-20261011-TASKER-ABUSE: exported broadcast remains opt-in and visibly warns about model cost.
                    SettingsItemRow(
                        icon = MuseIcons.link,
                        title = stringResource(R.string.automation_tasker_title),
                        subtitle = stringResource(R.string.automation_tasker_desc),
                        onClick = null,
                    ) {
                        MuseSwitch(
                            checked = taskerEnabled,
                            onCheckedChange = settings?.let { repository ->
                                { enabled -> scope.launch { repository.saveTaskerTriggerEnabled(enabled) } }
                            },
                            contentDescription = stringResource(R.string.automation_tasker_title),
                        )
                    }
                }
            }

            // 25: 总览页的三个子页入口（默认全开：点进去即可配置，不再一页堆砌）。
            if (page == AutomationSubPage.LANDING) {
                item(key = "sub-menu") {
                    SettingsGroup {
                        SettingsItemRow(
                            icon = MuseIcons.shieldCheck,
                            title = stringResource(R.string.automation_sub_access),
                            subtitle = stringResource(R.string.automation_sub_access_desc),
                            onClick = { subPage = AutomationSubPage.ACCESS },
                        ) { ChevronRight() }
                        SettingsItemRow(
                            icon = MuseIcons.computer,
                            title = stringResource(R.string.automation_sub_autonomous),
                            subtitle = stringResource(R.string.automation_sub_autonomous_desc),
                            onClick = { subPage = AutomationSubPage.AUTONOMOUS },
                        ) { ChevronRight() }
                        SettingsItemRow(
                            icon = MuseIcons.clock,
                            title = stringResource(R.string.automation_sub_orchestration),
                            subtitle = stringResource(R.string.automation_sub_orchestration_desc),
                            onClick = { subPage = AutomationSubPage.ORCHESTRATION },
                        ) { ChevronRight() }
                    }
                }
            }

            if (page == AutomationSubPage.AUTONOMOUS) {
                item(key = "autonomous-accessibility") {
                    AutonomousAccessibilityItem(
                        loaded = autonomousLoaded,
                        draft = autonomousDraft,
                        packageEditor = packageEditor,
                        taskPreview = taskPreview,
                        grant = taskGrant,
                        store = automationSettingsStore,
                        onDraftChange = { autonomousDraft = it },
                        onPackageEditorChange = { packageEditor = it },
                        onGrantChange = { taskGrant = it },
                    )
                }
            }

            // v1.xxx: F-24 编排与测试子页（抽成独立函数，页内只留分流，降复杂度）。
            if (page == AutomationSubPage.ORCHESTRATION) {
                orchestrationSubPage(
                    testing = testing,
                    testResult = testResult,
                    onRunTest = {
                        scope.launch {
                            testing = true
                            testResult = null
                            testResult = runScreenReadTest(manager, context)
                            testing = false
                        }
                    },
                    onOpenScheduledTasks = onOpenScheduledTasks,
                )
            }
        }
    }
}

/** 25: 编排与测试子页。从主页抽出，页内只留子页分流，避免单函数分支复杂度超标。 */
private fun LazyListScope.orchestrationSubPage(
    testing: Boolean,
    testResult: ScreenTestResult?,
    onRunTest: () -> Unit,
    onOpenScheduledTasks: () -> Unit,
) {
    item(key = "orchestration") {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = MuseIcons.clock,
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
                    leadingIcon = MuseIcons.clock,
                )
            }
        }
    }
    item(key = "test") {
        MuseCapsuleButton(
            text = if (testing) {
                stringResource(R.string.automation_test_running)
            } else {
                stringResource(R.string.automation_test_action)
            },
            onClick = onRunTest,
            enabled = !testing,
            loading = testing,
            leadingIcon = MuseIcons.play,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    testResult?.let { result ->
        item(key = "test-result") {
            ScreenTestResultCard(result)
        }
    }
}

/** 屏幕读取自测结果卡（含默认折叠的开发者诊断信息）。 */
@Composable
private fun ScreenTestResultCard(result: ScreenTestResult) {
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
            Text(text = result.message, style = MaterialTheme.typography.bodySmall)
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
                        imageVector = if (showDevInfo) MuseIcons.chevronUp else MuseIcons.chevronDown,
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

/** 25: UI 自动化页内菜单层级（总览 + 三子页），用页内状态切换，不新增导航路由。 */
private enum class AutomationSubPage { LANDING, ACCESS, AUTONOMOUS, ORCHESTRATION }

/**
 * 一次屏幕读取自测的结果:用户可见文案 + 是否成功(决定提示卡配色)。
 * devInfo 为开发者诊断信息(包名/数据来源),ST-04 起默认折叠展示。
 */
private data class ScreenTestResult(
    val message: String,
    val success: Boolean,
    val devInfo: String? = null,
)

/** 25: 子页标题映射（从页面抽出，降低页内分支复杂度）。 */
@androidx.annotation.StringRes
private fun automationSubPageTitleRes(subPage: AutomationSubPage): Int = when (subPage) {
    AutomationSubPage.LANDING -> R.string.automation_settings_title
    AutomationSubPage.ACCESS -> R.string.automation_sub_access
    AutomationSubPage.AUTONOMOUS -> R.string.automation_sub_autonomous
    AutomationSubPage.ORCHESTRATION -> R.string.automation_sub_orchestration
}

/** 25: 屏幕读取自测的执行逻辑（从页面 onClick 抽出，页面只负责状态翻转）。 */
private suspend fun runScreenReadTest(manager: AutomationManager, context: android.content.Context): ScreenTestResult = runCatching {
    manager.refreshPermissions()
    val screen = manager.readScreen()
    if (screen.source == "unavailable") {
        ScreenTestResult(context.getString(R.string.automation_test_no_channel), success = false)
    } else {
        val unknown = context.getString(R.string.automation_test_unknown)
        // ST-04: 包名与数据来源属开发者诊断信息,收进 devInfo 折叠区
        ScreenTestResult(
            message = buildString {
                appendLine(context.getString(R.string.automation_test_node_count, screen.nodes.size))
                appendLine(context.getString(R.string.automation_test_resolution, screen.screenWidth, screen.screenHeight))
            }.trimEnd('\n'),
            success = true,
            devInfo = buildString {
                appendLine(context.getString(R.string.automation_test_current_app, screen.packageName ?: unknown))
                appendLine(context.getString(R.string.automation_test_source, screen.source))
            }.trimEnd('\n'),
        )
    }
}.getOrElse {
    ScreenTestResult(
        context.getString(R.string.automation_test_failed, it.message ?: context.getString(R.string.automation_test_unknown)),
        success = false,
    )
}

/**
 * 虚拟屏交接区：一行入口 + 展开后的预览/触摸面板。
 *
 * 抽成独立 composable 的原因：设置页的分支复杂度已经顶到 detekt 上限，
 * 而这块本身是"看 + 操作一块外部屏幕"的完整单元，独立更好读。
 */
@Composable
private fun VirtualDisplayHandoffSection(
    handoffs: List<io.zer0.muse.automation.vdisplay.VirtualDisplayHandoffRegistry.Handoff>,
    expandedId: Int?,
    onExpand: (Int?) -> Unit,
    onClose: (Int) -> Unit,
) {
    if (!io.zer0.muse.automation.AutomationRuntime.isReady) return
    val first = handoffs.firstOrNull() ?: return
    val expanded = expandedId == first.displayId
    SettingsGroup {
        SettingsItemRow(
            icon = MuseIcons.bolt,
            title = stringResource(R.string.automation_handoff_title),
            subtitle = stringResource(R.string.automation_handoff_desc, handoffs.size),
            onClick = { onExpand(if (expanded) null else first.displayId) },
        ) {
            ChevronRight()
        }
    }
    if (expanded) {
        VirtualDisplayHandoffPanel(
            displayId = first.displayId,
            packageName = first.packageName,
            screenshot = { io.zer0.muse.automation.AutomationRuntime.screenshot(first.displayId) },
            tap = { x, y -> io.zer0.muse.automation.AutomationRuntime.tap(first.displayId, x, y) },
            bringToMain = {
                io.zer0.muse.automation.AutomationRuntime.launchOnMain(first.packageName)
            },
            onClose = { onClose(first.displayId) },
        )
    }
}

/**
 * 自主无障碍那一整块（从 [AutomationSettingsPage] 外提）。
 *
 * 为什么要外提：那一块里有"没加载完不显示 / 预览或目标包缺失 / 授权建不出来 / 已有授权"
 * 四个判定，全留在页面函数里会让它复杂到没人敢改。外提后页面只负责**决定放不放这一块**，
 * 判定留在自己的函数里，各自的复杂度都能被读得下。
 *
 * `scope`/`context` 在这里自取而不是当参数传：两者本来就是组合作用域内的东西
 * （`rememberCoroutineScope()` / `LocalContext.current`），传参反而多两个可传错的入口。
 */
@Composable
private fun AutonomousAccessibilityItem(
    loaded: Boolean,
    draft: AutonomousAccessibilityDraftState,
    packageEditor: String,
    taskPreview: ChatViewModel.AutonomousAccessibilityTaskPreview?,
    grant: io.zer0.muse.automation.core.AutonomousAccessibilityGrant?,
    store: AutomationSettingsStore,
    onDraftChange: (AutonomousAccessibilityDraftState) -> Unit,
    onPackageEditorChange: (String) -> Unit,
    onGrantChange: (io.zer0.muse.automation.core.AutonomousAccessibilityGrant?) -> Unit,
) {
    if (!loaded) return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    AutonomousAccessibilitySettingsCard(
        draft = draft,
        packageEditor = packageEditor,
        taskPreview = taskPreview,
        grant = grant,
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
            val targetPackage = draft.allowedPackages.firstOrNull()
            if (preview == null || targetPackage == null) {
                MuseToast.show(context.getString(R.string.automation_autonomous_confirm_required))
            } else {
                scope.launch {
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
 *
 * 规则：总闸关着时只说「为什么现在不能用」—— 那时候去开无障碍是没意义的，
 * 两句同时冒出来只会互相矛盾。因此总闸优先，且三者互斥。
 */
internal fun automationBannerKind(automationEnabled: Boolean, accessibilityEnabled: Boolean): AutomationBannerKind = when {
    !automationEnabled -> AutomationBannerKind.MASTER_OFF
    !accessibilityEnabled -> AutomationBannerKind.A11Y_OFF
    else -> AutomationBannerKind.NONE
}
