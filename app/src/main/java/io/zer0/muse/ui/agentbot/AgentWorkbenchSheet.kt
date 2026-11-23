@file:Suppress("LongMethod", "LongParameterList")

package io.zer0.muse.ui.agentbot

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.ai.core.ProviderConfig
import io.zer0.muse.R
import io.zer0.muse.automation.AutomationRuntime
import io.zer0.muse.automation.ui.VirtualDisplayHandoffPanel
import io.zer0.muse.automation.vdisplay.VirtualDisplayHandoffRegistry
import io.zer0.muse.channel.ChannelConfig
import io.zer0.muse.data.agentbot.AgentBotProjection
import io.zer0.muse.data.agentbot.AgentBotUiState
import io.zer0.muse.data.agentbot.AgentModelRouter
import io.zer0.muse.data.agentbot.AgentSquadIdentityStore
import io.zer0.muse.data.agentbot.AgentTeachRecorder
import io.zer0.muse.data.agentbot.BotRoutineEntity
import io.zer0.muse.data.agentbot.LiveMissionProgressBus
import io.zer0.muse.data.agentbot.RoutineTriggerType
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseAnchoredMenu
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseCapsuleTab
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseBorderWidth
import io.zer0.muse.ui.theme.MuseButtonSize
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.theme.statusColors

enum class AgentWorkbenchTab { LIVE, ROUTINES, SQUAD, ARTIFACTS }

/**
 * 按需拉起的四域 Agent 工作台：
 * 1. 实况（活跃任务、审批、沙盒终端流、后台虚拟屏观战/接管、分角色模型路由）；
 * 2. 惯例（示范学习录制、定时/事件/手动惯例管理与一键运行）；
 * 3. 队友（专职智能体切换、外部渠道数字身份绑定、多智能体群聊入口）；
 * 4. 产物（待审阅工作区变更、已放行工具收回、历史交付产物归档）。
 */
@Composable
fun AgentWorkbenchSheet(
    initialTab: AgentWorkbenchTab,
    currentBotId: String,
    projection: AgentBotProjection?,
    allowedTools: List<String>,
    sandboxLogs: List<LiveMissionProgressBus.SandboxLogLine>,
    activeVirtualDisplay: LiveMissionProgressBus.ActiveVirtualDisplay?,
    modelRoutes: AgentModelRouter.Snapshot,
    providers: List<ProviderConfig>,
    routines: List<BotRoutineEntity>,
    teachState: AgentTeachRecorder.State,
    assistants: List<AssistantEntity>,
    channels: List<ChannelConfig>,
    squadIdentities: Map<String, AgentSquadIdentityStore.IdentityBinding>,
    artifacts: List<ArtifactEntity>,
    onDismiss: () -> Unit,
    onOpenTask: (String) -> Unit,
    onSteerTask: (String, String) -> Unit,
    onSaveTaskAsRoutine: (String) -> Unit,
    onContinueTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onApprove: (AgentBotUiState.ApprovalRow) -> Unit,
    onDeny: (AgentBotUiState.ApprovalRow) -> Unit,
    onClearSandboxLogs: () -> Unit,
    onSetModelRoute: (AgentModelRouter.Role, String, String) -> Unit,
    onClearModelRoute: (AgentModelRouter.Role) -> Unit,
    onCreateRoutine: (title: String, goal: String, triggerType: String, triggerExpr: String) -> Unit,
    onRunRoutine: (BotRoutineEntity) -> Unit,
    onToggleRoutine: (BotRoutineEntity, Boolean) -> Unit,
    onDeleteRoutine: (BotRoutineEntity) -> Unit,
    onStartTeach: (String) -> Unit,
    onUpdateTeachTitle: (String) -> Unit,
    onAddTeachStep: (String) -> Unit,
    onFinishTeach: () -> Unit,
    onCancelTeach: () -> Unit,
    onSwitchAssistant: (String) -> Unit,
    onToggleChannelForBot: (String, String) -> Unit,
    onRefreshChannels: () -> Unit,
    onOpenGroupChats: (() -> Unit)?,
    onOpenCandidate: (String) -> Unit,
    onDiscardCandidate: (String) -> Unit,
    onRevokeAllowance: (String) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }
    var deleteRoutineTarget by remember { mutableStateOf<BotRoutineEntity?>(null) }
    var discardCandidateTarget by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedTab) {
        if (selectedTab == AgentWorkbenchTab.SQUAD) {
            onRefreshChannels()
        }
    }

    MuseBottomSheet(
        onDismissRequest = onDismiss,
        maxHeightFraction = 0.86f,
        headerContent = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                // 单行四域标准 MuseCapsuleTab + 关闭按钮固定在面板顶部，长内容下滑时不会滚走
                val tabs = listOf(
                    stringResource(R.string.agent_hub_tab_live),
                    stringResource(R.string.agent_hub_tab_routines),
                    stringResource(R.string.agent_hub_tab_squad),
                    stringResource(R.string.agent_hub_tab_artifacts),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.labelVerticalGap),
                ) {
                    MuseCapsuleTab(
                        tabs = tabs,
                        selectedIndex = selectedTab.ordinal,
                        onSelect = { idx ->
                            selectedTab = AgentWorkbenchTab.entries.getOrElse(idx) { AgentWorkbenchTab.LIVE }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    MuseTactileButton(
                        icon = MuseIcons.x,
                        contentDescription = stringResource(R.string.common_close),
                        onClick = onDismiss,
                        compact = true,
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.disabled))
            }
        },
    ) {
        // MuseBottomSheet 外层已带 verticalScroll，此处不得再嵌套无界 verticalScroll（会触发 Compose 无限高度崩溃）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = MusePaddings.largeGap),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap),
        ) {
            when (selectedTab) {
                AgentWorkbenchTab.LIVE -> WorkbenchLiveTab(
                    projection = projection,
                    sandboxLogs = sandboxLogs,
                    activeVirtualDisplay = activeVirtualDisplay,
                    modelRoutes = modelRoutes,
                    providers = providers,
                    onOpenTask = onOpenTask,
                    onSteerTask = onSteerTask,
                    onSaveTaskAsRoutine = onSaveTaskAsRoutine,
                    onContinueTask = onContinueTask,
                    onRetryTask = onRetryTask,
                    onCancelTask = onCancelTask,
                    onApprove = onApprove,
                    onDeny = onDeny,
                    onClearSandboxLogs = onClearSandboxLogs,
                    onSetModelRoute = onSetModelRoute,
                    onClearModelRoute = onClearModelRoute,
                )
                AgentWorkbenchTab.ROUTINES -> WorkbenchRoutinesTab(
                    routines = routines,
                    teachState = teachState,
                    onCreateRoutine = onCreateRoutine,
                    onRunRoutine = onRunRoutine,
                    onToggleRoutine = onToggleRoutine,
                    onDeleteRoutine = { deleteRoutineTarget = it },
                    onStartTeach = onStartTeach,
                    onUpdateTeachTitle = onUpdateTeachTitle,
                    onAddTeachStep = onAddTeachStep,
                    onFinishTeach = onFinishTeach,
                    onCancelTeach = onCancelTeach,
                )
                AgentWorkbenchTab.SQUAD -> WorkbenchSquadTab(
                    currentBotId = currentBotId,
                    assistants = assistants,
                    channels = channels,
                    squadIdentities = squadIdentities,
                    onSwitchAssistant = { id ->
                        onSwitchAssistant(id)
                        onDismiss()
                    },
                    onToggleChannelForBot = onToggleChannelForBot,
                    onOpenGroupChats = onOpenGroupChats?.let { open ->
                        {
                            onDismiss()
                            open()
                        }
                    },
                )
                AgentWorkbenchTab.ARTIFACTS -> WorkbenchArtifactsTab(
                    projection = projection,
                    allowedTools = allowedTools,
                    artifacts = artifacts,
                    onOpenCandidate = onOpenCandidate,
                    onDiscardCandidate = { discardCandidateTarget = it },
                    onRevokeAllowance = onRevokeAllowance,
                )
            }
        }
    }

    deleteRoutineTarget?.let { target ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.common_delete),
            itemName = target.title,
            onConfirm = {
                deleteRoutineTarget = null
                onDeleteRoutine(target)
            },
            onDismiss = { deleteRoutineTarget = null },
        )
    }

    discardCandidateTarget?.let { stagedId ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.agent_bot_discard),
            itemName = stagedId,
            onConfirm = {
                discardCandidateTarget = null
                onDiscardCandidate(stagedId)
            },
            onDismiss = { discardCandidateTarget = null },
        )
    }
}

@Composable
private fun WorkbenchLiveTab(
    projection: AgentBotProjection?,
    sandboxLogs: List<LiveMissionProgressBus.SandboxLogLine>,
    activeVirtualDisplay: LiveMissionProgressBus.ActiveVirtualDisplay?,
    modelRoutes: AgentModelRouter.Snapshot,
    providers: List<ProviderConfig>,
    onOpenTask: (String) -> Unit,
    onSteerTask: (String, String) -> Unit,
    onSaveTaskAsRoutine: (String) -> Unit,
    onContinueTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onApprove: (AgentBotUiState.ApprovalRow) -> Unit,
    onDeny: (AgentBotUiState.ApprovalRow) -> Unit,
    onClearSandboxLogs: () -> Unit,
    onSetModelRoute: (AgentModelRouter.Role, String, String) -> Unit,
    onClearModelRoute: (AgentModelRouter.Role) -> Unit,
) {
    var handoffRefreshToken by remember { mutableIntStateOf(0) }
    var vdPreviewOpen by rememberSaveable { mutableStateOf(false) }
    val handoffs = remember(handoffRefreshToken, activeVirtualDisplay) {
        VirtualDisplayHandoffRegistry.list(System.currentTimeMillis())
    }
    val liveVdDisplayId = activeVirtualDisplay?.displayId ?: handoffs.firstOrNull()?.displayId
    val liveVdPackage = activeVirtualDisplay?.packageName
        ?: handoffs.firstOrNull()?.packageName.orEmpty()

    // 1. 待拍板审批
    projection?.pendingApprovals.orEmpty().takeIf { it.isNotEmpty() }?.let { approvals ->
        Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
            SectionLabel(text = stringResource(R.string.agent_bot_approvals_title))
            approvals.forEach { approval ->
                AgentInlineApprovalCard(
                    row = approval,
                    onApprove = onApprove,
                    onDeny = onDeny,
                    onOpenTask = onOpenTask,
                )
            }
        }
    }

    // 2. 进行中与需处理任务
    val activeTasks = projection?.let { it.inProgress + it.needsAction }.orEmpty()
    if (activeTasks.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
            SectionLabel(text = stringResource(R.string.agent_screen_running))
            activeTasks.forEach { task ->
                AgentLiveMissionCard(
                    row = task,
                    onOpenDetails = onOpenTask,
                    onOpenComputer = { vdPreviewOpen = true },
                    onSteer = onSteerTask,
                    onSaveAsRoutine = onSaveTaskAsRoutine,
                    onContinue = onContinueTask,
                    onRetry = onRetryTask,
                    onCancel = onCancelTask,
                )
            }
        }
    }

    // 3. 活跃虚拟屏（仅在真实存在后台虚拟屏时显示，空闲不留空框）
    if (liveVdDisplayId != null) {
        Surface(
            shape = MuseShapes.semiLarge,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MuseAlpha.mutedSurface),
            border = BorderStroke(MuseBorderWidth.hairline, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(MusePaddings.cardInner),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    Icon(
                        imageVector = MuseIcons.deviceMobile,
                        contentDescription = null,
                        tint = MaterialTheme.statusColors.info,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                    Text(
                        text = stringResource(R.string.agent_hub_computer_vdisplay_title),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    MuseCapsuleButton(
                        text = stringResource(R.string.agent_hub_computer_vdisplay_takeover),
                        onClick = { vdPreviewOpen = !vdPreviewOpen },
                        variant = IosCapsuleButtonVariant.Secondary,
                        fillWidth = false,
                        size = MuseButtonSize.Compact,
                    )
                }
                if (vdPreviewOpen) {
                    VirtualDisplayHandoffPanel(
                        displayId = liveVdDisplayId,
                        packageName = liveVdPackage,
                        screenshot = { AutomationRuntime.screenshot(liveVdDisplayId) },
                        tap = { x, y -> AutomationRuntime.tap(liveVdDisplayId, x, y) },
                        bringToMain = { AutomationRuntime.launchOnMain(liveVdPackage) },
                        onClose = {
                            VirtualDisplayHandoffRegistry.release(liveVdDisplayId)
                            vdPreviewOpen = false
                            handoffRefreshToken++
                        },
                    )
                }
            }
        }
    }

    // 4. 沙盒终端输出（有输出时才出现，空闲不占画布）
    if (sandboxLogs.isNotEmpty()) {
        Surface(
            shape = MuseShapes.semiLarge,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MuseAlpha.mutedSurface),
            border = BorderStroke(MuseBorderWidth.hairline, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(MusePaddings.cardInner),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = MuseIcons.terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                    Spacer(modifier = Modifier.size(MusePaddings.contentGap))
                    Text(
                        text = stringResource(R.string.agent_hub_computer_sandbox_logs),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    MuseCapsuleButton(
                        text = stringResource(R.string.agent_hub_computer_clear_logs),
                        onClick = onClearSandboxLogs,
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                        size = MuseButtonSize.Compact,
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = MusePaddings.dialogContentMaxHeight)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    sandboxLogs.takeLast(60).forEach { line ->
                        Text(
                            text = "[${line.source}] ${line.text}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = if (line.isError) {
                                MaterialTheme.statusColors.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }

    // 5. 分角色模型路由（合并为单张细线分组卡）
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
        SectionLabel(text = stringResource(R.string.agent_hub_model_routing_title))
        Surface(
            shape = MuseShapes.semiLarge,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MuseAlpha.mutedSurface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                ModelRouteRow(
                    label = stringResource(R.string.agent_hub_route_foreground),
                    binding = modelRoutes.foreground,
                    providers = providers,
                    onSelect = { pId, mId -> onSetModelRoute(AgentModelRouter.Role.FOREGROUND, pId, mId) },
                    onReset = { onClearModelRoute(AgentModelRouter.Role.FOREGROUND) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.mutedSurface))
                ModelRouteRow(
                    label = stringResource(R.string.agent_hub_route_background),
                    binding = modelRoutes.background,
                    providers = providers,
                    onSelect = { pId, mId -> onSetModelRoute(AgentModelRouter.Role.BACKGROUND, pId, mId) },
                    onReset = { onClearModelRoute(AgentModelRouter.Role.BACKGROUND) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.mutedSurface))
                ModelRouteRow(
                    label = stringResource(R.string.agent_hub_route_vision),
                    binding = modelRoutes.vision,
                    providers = providers,
                    onSelect = { pId, mId -> onSetModelRoute(AgentModelRouter.Role.VISION, pId, mId) },
                    onReset = { onClearModelRoute(AgentModelRouter.Role.VISION) },
                )
            }
        }
    }
}

@Composable
private fun ModelRouteRow(
    label: String,
    binding: AgentModelRouter.Binding,
    providers: List<ProviderConfig>,
    onSelect: (providerId: String, modelId: String) -> Unit,
    onReset: () -> Unit,
) {
    var expanded by rememberSaveable(label) { mutableStateOf(false) }
    val followDefault = stringResource(R.string.agent_hub_route_follow_default)
    val allModels = remember(providers) {
        providers.flatMap { provider ->
            provider.models.map { model -> Triple(provider.id, provider.displayName, model.id) }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (binding.isConfigured) binding.modelId else followDefault,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            Icon(
                imageVector = MuseIcons.chevronDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(MuseIconSizes.iconSmallTiny),
            )
            MuseAnchoredMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                alignEnd = true,
            ) {
                MuseListItem(
                    onClick = {
                        expanded = false
                        onReset()
                    },
                    headlineContent = {
                        Text(
                            text = followDefault,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                )
                allModels.take(30).forEach { (providerId, providerName, modelId) ->
                    MuseListItem(
                        onClick = {
                            expanded = false
                            onSelect(providerId, modelId)
                        },
                        headlineContent = {
                            Text(
                                text = "$modelId · $providerName",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkbenchRoutinesTab(
    routines: List<BotRoutineEntity>,
    teachState: AgentTeachRecorder.State,
    onCreateRoutine: (title: String, goal: String, triggerType: String, triggerExpr: String) -> Unit,
    onRunRoutine: (BotRoutineEntity) -> Unit,
    onToggleRoutine: (BotRoutineEntity, Boolean) -> Unit,
    onDeleteRoutine: (BotRoutineEntity) -> Unit,
    onStartTeach: (String) -> Unit,
    onUpdateTeachTitle: (String) -> Unit,
    onAddTeachStep: (String) -> Unit,
    onFinishTeach: () -> Unit,
    onCancelTeach: () -> Unit,
) {
    var creatingOpen by rememberSaveable { mutableStateOf(false) }
    var draftTitle by rememberSaveable { mutableStateOf("") }
    var draftGoal by rememberSaveable { mutableStateOf("") }
    var draftTrigger by rememberSaveable { mutableStateOf(RoutineTriggerType.MANUAL) }
    var draftExpr by rememberSaveable { mutableStateOf("") }
    var teachStepDraft by rememberSaveable { mutableStateOf("") }

    // 顶部极简操作栏：新建 + 录制示范（空闲不占大卡片）
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        MuseCapsuleButton(
            text = stringResource(R.string.agent_hub_routines_new),
            onClick = { creatingOpen = !creatingOpen },
            leadingIcon = MuseIcons.plus,
            variant = IosCapsuleButtonVariant.Secondary,
            fillWidth = false,
            size = MuseButtonSize.Compact,
        )
        if (!teachState.recording) {
            MuseCapsuleButton(
                text = stringResource(R.string.agent_hub_teach_start),
                onClick = { onStartTeach("") },
                leadingIcon = MuseIcons.handFinger,
                variant = IosCapsuleButtonVariant.Secondary,
                fillWidth = false,
                size = MuseButtonSize.Compact,
            )
        }
    }

    // 仅在录制示范时展开录制卡片
    if (teachState.recording) {
        Surface(
            shape = MuseShapes.semiLarge,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = MuseAlpha.handle),
            border = BorderStroke(MuseBorderWidth.hairline, MaterialTheme.colorScheme.primary.copy(alpha = MuseAlpha.handle)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(MusePaddings.cardInner),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Text(
                    text = stringResource(R.string.agent_hub_teach_recording, teachState.steps.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                MuseTextField(
                    value = teachState.titleDraft,
                    onValueChange = onUpdateTeachTitle,
                    placeholder = { Text(stringResource(R.string.agent_hub_routines_title_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                teachState.steps.forEach { step ->
                    Text(
                        text = "${step.index}. ${step.action}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    MuseTextField(
                        value = teachStepDraft,
                        onValueChange = { teachStepDraft = it },
                        placeholder = { Text(stringResource(R.string.agent_hub_teach_step_hint)) },
                        modifier = Modifier.weight(1f),
                    )
                    MuseCapsuleButton(
                        text = stringResource(R.string.agent_hub_teach_add_step),
                        onClick = {
                            if (teachStepDraft.isNotBlank()) {
                                onAddTeachStep(teachStepDraft)
                                teachStepDraft = ""
                            }
                        },
                        enabled = teachStepDraft.isNotBlank(),
                        variant = IosCapsuleButtonVariant.Secondary,
                        fillWidth = false,
                        size = MuseButtonSize.Compact,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.agent_hub_teach_finish),
                        onClick = onFinishTeach,
                        enabled = teachState.steps.isNotEmpty(),
                        fillWidth = false,
                        size = MuseButtonSize.Compact,
                    )
                    MuseCapsuleButton(
                        text = stringResource(R.string.common_cancel),
                        onClick = onCancelTeach,
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                        size = MuseButtonSize.Compact,
                    )
                }
            }
        }
    }

    if (creatingOpen) {
        Surface(
            shape = MuseShapes.semiLarge,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MuseAlpha.disabled),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(MusePaddings.cardInner),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                MuseTextField(
                    value = draftTitle,
                    onValueChange = { draftTitle = it },
                    placeholder = { Text(stringResource(R.string.agent_hub_routines_title_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                MuseTextField(
                    value = draftGoal,
                    onValueChange = { draftGoal = it },
                    placeholder = { Text(stringResource(R.string.agent_hub_routines_goal_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MusePaddings.labelVerticalGap)) {
                    listOf(
                        RoutineTriggerType.MANUAL to R.string.agent_hub_trigger_manual,
                        RoutineTriggerType.CRON to R.string.agent_hub_trigger_cron,
                        RoutineTriggerType.NOTIFICATION to R.string.agent_hub_trigger_notification,
                        RoutineTriggerType.CHANNEL to R.string.agent_hub_trigger_channel,
                    ).forEach { (type, resId) ->
                        Surface(
                            onClick = { draftTrigger = type },
                            shape = MuseShapes.pill,
                            color = if (draftTrigger == type) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        ) {
                            Text(
                                text = stringResource(resId),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (draftTrigger == type) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.padding(
                                    horizontal = MusePaddings.inputPadding,
                                    vertical = MusePaddings.labelVerticalGap,
                                ),
                            )
                        }
                    }
                }
                if (draftTrigger != RoutineTriggerType.MANUAL) {
                    MuseTextField(
                        value = draftExpr,
                        onValueChange = { draftExpr = it },
                        placeholder = { Text(stringResource(R.string.agent_hub_routines_trigger_expr_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.common_save),
                        onClick = {
                            onCreateRoutine(draftTitle, draftGoal, draftTrigger, draftExpr)
                            draftTitle = ""
                            draftGoal = ""
                            draftExpr = ""
                            creatingOpen = false
                        },
                        enabled = draftTitle.isNotBlank() && draftGoal.isNotBlank(),
                        fillWidth = false,
                        size = MuseButtonSize.Compact,
                    )
                    MuseCapsuleButton(
                        text = stringResource(R.string.common_cancel),
                        onClick = { creatingOpen = false },
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                        size = MuseButtonSize.Compact,
                    )
                }
            }
        }
    }

    routines.forEach { routine ->
            Surface(
                shape = MuseShapes.semiLarge,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(MuseBorderWidth.hairline, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(MusePaddings.cardInner),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = routine.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val triggerLabel = when (routine.triggerType) {
                                RoutineTriggerType.CRON -> stringResource(R.string.agent_hub_trigger_cron)
                                RoutineTriggerType.NOTIFICATION -> stringResource(R.string.agent_hub_trigger_notification)
                                RoutineTriggerType.CHANNEL -> stringResource(R.string.agent_hub_trigger_channel)
                                else -> stringResource(R.string.agent_hub_trigger_manual)
                            }
                            Text(
                                text = if (routine.triggerExpr.isNotBlank()) {
                                    "$triggerLabel · ${routine.triggerExpr}"
                                } else {
                                    triggerLabel
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        MuseSwitch(
                            checked = routine.enabled,
                            onCheckedChange = { onToggleRoutine(routine, it) },
                        )
                    }
                    if (routine.stepsSummary.isNotBlank()) {
                        Text(
                            text = routine.stepsSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MuseCapsuleButton(
                            text = stringResource(R.string.agent_hub_routines_run_now),
                            onClick = { onRunRoutine(routine) },
                            leadingIcon = MuseIcons.play,
                            variant = IosCapsuleButtonVariant.Secondary,
                            fillWidth = false,
                            size = MuseButtonSize.Compact,
                        )
                        MuseTactileButton(
                            icon = MuseIcons.trash,
                            contentDescription = stringResource(R.string.common_delete),
                            onClick = { onDeleteRoutine(routine) },
                            compact = true,
                        )
                    }
                }
            }
        }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkbenchSquadTab(
    currentBotId: String,
    assistants: List<AssistantEntity>,
    channels: List<ChannelConfig>,
    squadIdentities: Map<String, AgentSquadIdentityStore.IdentityBinding>,
    onSwitchAssistant: (String) -> Unit,
    onToggleChannelForBot: (String, String) -> Unit,
    onOpenGroupChats: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
        assistants.forEach { assistant ->
            val active = assistant.id == currentBotId
            val boundChannels = squadIdentities[assistant.id]?.channelIds.orEmpty()
            Surface(
                shape = MuseShapes.semiLarge,
                color = if (active) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = MuseAlpha.disabled)
                } else {
                    MaterialTheme.colorScheme.surface
                },
                border = BorderStroke(
                    width = MuseBorderWidth.hairline,
                    color = if (active) {
                        MaterialTheme.colorScheme.primary.copy(alpha = MuseAlpha.mutedSurface)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    AssistantAvatar(
                        assistant = assistant,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = assistant.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val channelsLabel = stringResource(R.string.agent_hub_squad_channels)
                        val desc = assistant.summary.ifBlank {
                            if (boundChannels.isNotEmpty()) "${boundChannels.size} · $channelsLabel" else ""
                        }
                        if (desc.isNotBlank()) {
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (active) {
                        Text(
                            text = stringResource(R.string.agent_hub_squad_active),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        MuseCapsuleButton(
                            text = stringResource(R.string.agent_hub_squad_switch),
                            onClick = { onSwitchAssistant(assistant.id) },
                            variant = IosCapsuleButtonVariant.Secondary,
                            fillWidth = false,
                            size = MuseButtonSize.Compact,
                        )
                    }
                }
            }
        }
    }

    // 仅存在已配置外部渠道时展示绑定区，不展示空态说明文案
    if (channels.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
            SectionLabel(text = stringResource(R.string.agent_hub_squad_channels))
            val activeChannels = squadIdentities[currentBotId]?.channelIds.orEmpty()
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                channels.forEach { channel ->
                    val bound = channel.id in activeChannels
                    Surface(
                        onClick = { onToggleChannelForBot(currentBotId, channel.id) },
                        shape = MuseShapes.pill,
                        color = if (bound) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MuseAlpha.mutedSurface)
                        },
                        border = BorderStroke(
                            width = MuseBorderWidth.hairline,
                            color = if (bound) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                horizontal = MusePaddings.inputPadding,
                                vertical = MusePaddings.contentGap,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                        ) {
                            Icon(
                                imageVector = if (bound) MuseIcons.check else MuseIcons.plug,
                                contentDescription = null,
                                tint = if (bound) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(MuseIconSizes.iconTiny),
                            )
                            Text(
                                text = channel.name.ifBlank { channel.platform.name },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (bound) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (onOpenGroupChats != null) {
        MuseCapsuleButton(
            text = stringResource(R.string.agent_hub_squad_open_group),
            onClick = onOpenGroupChats,
            leadingIcon = MuseIcons.users,
            variant = IosCapsuleButtonVariant.Secondary,
            size = MuseButtonSize.Compact,
        )
    }
}

@Composable
private fun WorkbenchArtifactsTab(
    projection: AgentBotProjection?,
    allowedTools: List<String>,
    artifacts: List<ArtifactEntity>,
    onOpenCandidate: (String) -> Unit,
    onDiscardCandidate: (String) -> Unit,
    onRevokeAllowance: (String) -> Unit,
) {
    var viewingArtifact by remember { mutableStateOf<ArtifactEntity?>(null) }
    val candidates = projection?.candidates.orEmpty()
    if (candidates.isEmpty() && artifacts.isEmpty() && allowedTools.isEmpty()) {
        MuseEmptyState(
            icon = MuseIcons.fileText,
            title = stringResource(R.string.artifact_center_empty),
        )
        return
    }

    if (candidates.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
            SectionLabel(text = stringResource(R.string.agent_bot_candidates_title))
            candidates.forEach { candidate ->
                AgentArtifactDeliveryCard(
                    candidate = candidate,
                    onOpen = onOpenCandidate,
                    onDiscard = onDiscardCandidate,
                )
            }
        }
    }

    if (allowedTools.isNotEmpty()) {
        AgentBotRowSection(
            title = stringResource(R.string.agent_bot_allowed_title),
            rows = allowedTools,
            key = { it },
            primary = { it },
            secondary = { null },
            actionLabel = stringResource(R.string.agent_bot_revoke),
            onAction = onRevokeAllowance,
        )
    }

    if (artifacts.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
            SectionLabel(text = stringResource(R.string.agent_hub_artifacts_history))
            artifacts.take(25).forEach { artifact ->
                Surface(
                    onClick = { viewingArtifact = artifact },
                    shape = MuseShapes.semiLarge,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MuseAlpha.disabled),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(MuseIconSizes.topBarSolid)
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = MuseAlpha.subtleBorder),
                                    MuseShapes.small,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = MuseIcons.fileText,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(MuseIconSizes.iconSmall),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = artifact.title.ifBlank { artifact.id },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = artifact.type,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = MuseIcons.chevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconSmallTiny),
                        )
                    }
                }
            }
        }
    }

    viewingArtifact?.let { artifact ->
        val context = androidx.compose.ui.platform.LocalContext.current
        io.zer0.muse.ui.artifact.ArtifactViewerDialog(
            artifact = artifact,
            onDismiss = { viewingArtifact = null },
            onCopy = { text ->
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(null, text))
                io.zer0.muse.ui.common.feedback.MuseToast.show(context.getString(R.string.chat_copied_toast))
            },
        )
    }
}
