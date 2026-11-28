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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.ai.core.ProviderConfig
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.channel.ChannelConfig
import io.zer0.muse.computenode.ComputeNodeSnapshot
import io.zer0.muse.data.agentbot.AgentBotProjection
import io.zer0.muse.data.agentbot.AgentBotRepository
import io.zer0.muse.data.agentbot.AgentBotUiState
import io.zer0.muse.data.agentbot.AgentConnectorHealthSnapshot
import io.zer0.muse.data.agentbot.AgentGoalEntity
import io.zer0.muse.data.agentbot.AgentModelRouter
import io.zer0.muse.data.agentbot.AgentRoutineEngine
import io.zer0.muse.data.agentbot.AgentSquadIdentityStore
import io.zer0.muse.data.agentbot.AgentTeachRecorder
import io.zer0.muse.data.agentbot.AgentWatcherEntity
import io.zer0.muse.data.agentbot.BotRoutineEntity
import io.zer0.muse.data.agentbot.ConnectorHealthStatus
import io.zer0.muse.data.agentbot.LiveMissionProgressBus
import io.zer0.muse.data.agentbot.ReconnectActionKind
import io.zer0.muse.data.agentbot.RoutineTriggerType
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.computenode.RemoteComputeNodeEntity
import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.data.skill.SkillRepository
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
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.theme.statusColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

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
    watchers: List<AgentWatcherEntity>,
    goals: List<AgentGoalEntity>,
    aboutYouProfile: AboutYouProfile,
    connectorHealth: AgentConnectorHealthSnapshot,
    computeNodes: List<RemoteComputeNodeEntity>,
    computeNodeSnapshots: Map<String, ComputeNodeSnapshot>,
    onDeleteMemoryFact: (AboutYouFact) -> Unit,
    onTogglePinMemoryFact: (AboutYouFact) -> Unit,
    onCorrectFactInChat: (String) -> Unit,
    onFixConnector: (String) -> Unit,
    onOpenComputeNodeSetup: () -> Unit,
    onRefreshComputeNode: (String) -> Unit,
    onInstallComputeBrowserPack: (String) -> Unit,
    onRepairComputeNode: (String) -> Unit,
    onDeleteComputeNode: (String) -> Unit,
    onOpenComputeTerminal: (String) -> Unit,
    onOpenComputeFiles: (String) -> Unit,
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
    onCheckWatcherNow: (String) -> Unit,
    onPauseWatcher: (String) -> Unit,
    onResumeWatcher: (String) -> Unit,
    onEndWatcher: (String) -> Unit,
    onToggleGoalMilestone: (goalId: String, milestoneId: String, done: Boolean) -> Unit,
    onRefineGoalInChat: (goalId: String, title: String) -> Unit,
    onFixReconnect: (ReconnectActionKind?) -> Unit,
    onResumeReconnect: (cardId: String, taskId: String) -> Unit,
    onDismissCard: (String) -> Unit,
    onOpenArtifactCenter: (() -> Unit)? = null,
    viewModel: AgentBotViewModel = koinViewModel(),
) {
    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }
    var deleteRoutineTarget by remember { mutableStateOf<BotRoutineEntity?>(null) }
    var discardCandidateTarget by remember { mutableStateOf<String?>(null) }
    val teachControlState by viewModel.teachControlState.collectAsStateWithLifecycle()
    val teachOverlayGranted = viewModel.canShowTeachOverlay()
    val routineEngine: AgentRoutineEngine? = koinInject()
    val agentBotRepository: AgentBotRepository? = koinInject()
    val workbenchScope = rememberCoroutineScope()
    // 「做过即学会」：完成任务的旧入口改为先沉淀共享技能+惯例；任一步不可用时退回纯惯例路径。
    val saveTaskAsSkillAndRoutine: (String) -> Unit = { taskId ->
        val engine = routineEngine
        val repository = agentBotRepository
        if (engine == null || repository == null) {
            onSaveTaskAsRoutine(taskId)
        } else {
            workbenchScope.launch {
                val task = repository.getTask(taskId)
                if (task == null) {
                    onSaveTaskAsRoutine(taskId)
                    return@launch
                }
                val bundle = try {
                    engine.saveTaskAsSkillAndRoutine(task)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: java.io.IOException) {
                    Logger.w("AgentWorkbenchSheet", "任务沉淀为技能失败: ${e.message}", e)
                    null
                }
                if (bundle == null) onSaveTaskAsRoutine(taskId)
            }
        }
    }

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
                    stringResource(R.string.agent_hub_tab_follow_up),
                    stringResource(R.string.agent_hub_tab_spaces),
                    stringResource(R.string.agent_hub_tab_routines),
                    stringResource(R.string.agent_hub_tab_squad),
                    stringResource(R.string.agent_hub_tab_about_you),
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
                            selectedTab = AgentWorkbenchTab.entries.getOrElse(idx) { AgentWorkbenchTab.FOLLOW_UP }
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
                AgentWorkbenchTab.FOLLOW_UP -> Column(
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap),
                ) {
                    WorkbenchFollowUpTab(
                        projection = projection,
                        watchers = watchers,
                        goals = goals,
                        sandboxLogs = sandboxLogs,
                        activeVirtualDisplay = activeVirtualDisplay,
                        onOpenTask = onOpenTask,
                        onSteerTask = onSteerTask,
                        onSaveTaskAsRoutine = saveTaskAsSkillAndRoutine,
                        onContinueTask = onContinueTask,
                        onRetryTask = onRetryTask,
                        onCancelTask = onCancelTask,
                        onApprove = onApprove,
                        onDeny = onDeny,
                        onClearSandboxLogs = onClearSandboxLogs,
                        onCheckWatcherNow = onCheckWatcherNow,
                        onPauseWatcher = onPauseWatcher,
                        onResumeWatcher = onResumeWatcher,
                        onEndWatcher = onEndWatcher,
                        onToggleGoalMilestone = onToggleGoalMilestone,
                        onRefineGoalInChat = onRefineGoalInChat,
                        onFixReconnect = onFixReconnect,
                        onResumeReconnect = onResumeReconnect,
                        onDismissCard = onDismissCard,
                    )
                    WorkbenchModelRoutingSection(
                        modelRoutes = modelRoutes,
                        providers = providers,
                        onSetModelRoute = onSetModelRoute,
                        onClearModelRoute = onClearModelRoute,
                    )
                }
                AgentWorkbenchTab.SPACES -> {
                    val spaces by viewModel.spacesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
                    val refreshingSpaceIds by viewModel.refreshingSpaceIds.collectAsStateWithLifecycle()
                    val spaceRefreshFailures by viewModel.spaceRefreshFailures.collectAsStateWithLifecycle(initialValue = emptyMap())
                    WorkbenchSpacesTab(
                        cards = spaces,
                        refreshingIds = refreshingSpaceIds,
                        refreshFailures = spaceRefreshFailures,
                        onRefresh = viewModel::refreshSpaceNow,
                        onSetRefreshPolicy = { cardId, minutes, prompt ->
                            viewModel.setSpaceRefreshPolicy(cardId, minutes, prompt)
                        },
                        onUnpin = viewModel::unpinSpace,
                        onRefineInChat = viewModel::refineSpaceInChat,
                        onCreateTemplate = viewModel::createSpaceTemplate,
                        onCardAction = viewModel::handleSpaceCardAction,
                    )
                }
                AgentWorkbenchTab.ROUTINES -> WorkbenchRoutinesTab(
                    routines = routines,
                    teachState = teachState,
                    teachControl = teachControlState,
                    teachOverlayGranted = teachOverlayGranted,
                    onCreateRoutine = onCreateRoutine,
                    onRunRoutine = onRunRoutine,
                    onToggleRoutine = onToggleRoutine,
                    onDeleteRoutine = { deleteRoutineTarget = it },
                    onStartTeach = onStartTeach,
                    onUpdateTeachTitle = onUpdateTeachTitle,
                    onAddTeachStep = onAddTeachStep,
                    onFinishTeach = onFinishTeach,
                    onCancelTeach = onCancelTeach,
                    onPauseOrResumeTeach = viewModel::pauseOrResumeTeachRecording,
                    onRequestOverlayPermission = viewModel::requestTeachOverlayPermission,
                )
                AgentWorkbenchTab.SQUAD -> WorkbenchSquadTab(
                    currentBotId = currentBotId,
                    assistants = assistants,
                    channels = channels,
                    squadIdentities = squadIdentities,
                    routines = routines,
                    recentTaskCount = projection?.recent?.size ?: 0,
                    connectorHealth = connectorHealth,
                    computeNodes = computeNodes,
                    computeNodeSnapshots = computeNodeSnapshots,
                    onOpenComputeNodeSetup = onOpenComputeNodeSetup,
                    onRefreshComputeNode = onRefreshComputeNode,
                    onInstallComputeBrowserPack = onInstallComputeBrowserPack,
                    onRepairComputeNode = onRepairComputeNode,
                    onDeleteComputeNode = onDeleteComputeNode,
                    onOpenComputeTerminal = onOpenComputeTerminal,
                    onOpenComputeFiles = onOpenComputeFiles,
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
                    onFixConnector = onFixConnector,
                )
                AgentWorkbenchTab.ABOUT_YOU -> Column(
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap),
                ) {
                    val chatPreferences by viewModel.chatPreferencesFlow.collectAsStateWithLifecycle(
                        initialValue = io.zer0.muse.data.ChatPreferences(),
                    )
                    WorkbenchAboutYouTab(
                        profile = aboutYouProfile,
                        onDelete = onDeleteMemoryFact,
                        onTogglePin = onTogglePinMemoryFact,
                        onCorrectFact = onCorrectFactInChat,
                        aboutYouEnabled = chatPreferences.aboutYouProfileEnabled,
                        onToggleAboutYou = viewModel::setAboutYouProfileEnabled,
                        onDeleteDomain = viewModel::deleteProfileDomain,
                    )
                    WorkbenchArtifactsTab(
                        projection = projection,
                        artifacts = artifacts,
                        onOpenCandidate = onOpenCandidate,
                        onDiscardCandidate = { discardCandidateTarget = it },
                        onOpenArtifactCenter = onOpenArtifactCenter?.let { open ->
                            {
                                onDismiss()
                                open()
                            }
                        },
                    )
                }
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
private fun WorkbenchModelRoutingSection(
    modelRoutes: AgentModelRouter.Snapshot,
    providers: List<ProviderConfig>,
    onSetModelRoute: (AgentModelRouter.Role, String, String) -> Unit,
    onClearModelRoute: (AgentModelRouter.Role) -> Unit,
) {
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

@Composable
private fun WorkbenchSharedSkillsSection(skills: List<SkillEntity>, onToggle: (SkillEntity, Boolean) -> Unit) {
    if (skills.isEmpty()) {
        MuseEmptyState(
            title = stringResource(R.string.skill_no_skill_title),
            subtitle = stringResource(R.string.skill_no_skill_subtitle),
        )
        return
    }
    skills.forEach { skill ->
        Surface(
            shape = MuseShapes.semiLarge,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(MuseBorderWidth.hairline, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(MusePaddings.cardInner),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = skill.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (skill.description.isNotBlank()) {
                        Text(
                            text = skill.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = stringResource(R.string.skill_custom),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                MuseSwitch(
                    checked = skill.enabled,
                    onCheckedChange = { enabled -> onToggle(skill, enabled) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkbenchRoutinesTab(
    routines: List<BotRoutineEntity>,
    teachState: AgentTeachRecorder.State,
    teachControl: TeachOverlayController.UiState,
    teachOverlayGranted: Boolean,
    onCreateRoutine: (title: String, goal: String, triggerType: String, triggerExpr: String) -> Unit,
    onRunRoutine: (BotRoutineEntity) -> Unit,
    onToggleRoutine: (BotRoutineEntity, Boolean) -> Unit,
    onDeleteRoutine: (BotRoutineEntity) -> Unit,
    onStartTeach: (String) -> Unit,
    onUpdateTeachTitle: (String) -> Unit,
    onAddTeachStep: (String) -> Unit,
    onFinishTeach: () -> Unit,
    onCancelTeach: () -> Unit,
    onPauseOrResumeTeach: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    skillRepository: SkillRepository = koinInject(),
) {
    val allSkills by skillRepository.observeAll.collectAsStateWithLifecycle(initialValue = emptyList())
    val sharedSkills = remember(allSkills) {
        allSkills.filter { skill -> skill.category == "user" || skill.category == "custom" }
    }
    val routineScope = rememberCoroutineScope()
    var creatingOpen by rememberSaveable { mutableStateOf(false) }
    var draftTitle by rememberSaveable { mutableStateOf("") }
    var draftGoal by rememberSaveable { mutableStateOf("") }
    var draftTrigger by rememberSaveable { mutableStateOf(RoutineTriggerType.MANUAL) }
    var draftExpr by rememberSaveable { mutableStateOf("") }
    var teachStepDraft by rememberSaveable { mutableStateOf("") }
    var manualStepOpen by rememberSaveable { mutableStateOf(false) }

    // 上半段：共享技能库。技能只保存“怎么做”，跨队友共享，不绑定任何 botId。
    SectionLabel(text = stringResource(R.string.skill_management))
    Text(
        text = stringResource(
            R.string.skill_bulk_summary,
            sharedSkills.size,
            sharedSkills.count { skill -> skill.enabled },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    WorkbenchSharedSkillsSection(
        skills = sharedSkills,
        onToggle = { skill, enabled ->
            routineScope.launch {
                try {
                    skillRepository.setEnabled(skill.id, enabled)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: java.io.IOException) {
                    Logger.w("AgentWorkbenchSheet", "切换技能开关失败: ${e.message}", e)
                }
            }
        },
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.disabled))

    // 下半段：定时 / 事件触发器。这里只管理“什么时候做”，执行剧本交给上方技能或任务沉淀。
    SectionLabel(text = stringResource(R.string.schedule_condition_title))
    // 聊天优先引导：由对话中的助手自然语言编排自动化，这里作为已生效自动化的监督与启停台
    Text(
        text = stringResource(R.string.agent_hub_routines_chat_first_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    MuseCapsuleButton(
        text = stringResource(R.string.agent_hub_routines_new),
        onClick = { creatingOpen = true },
        leadingIcon = MuseIcons.plus,
        variant = IosCapsuleButtonVariant.Secondary,
        fillWidth = false,
        size = MuseButtonSize.Compact,
    )

    val teachActive = teachState.recording || teachControl.paused
    val teachStepCount = if (teachControl.paused) teachControl.stepCount else teachState.steps.size

    if (!teachActive) {
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
                Text(
                    text = stringResource(R.string.agent_hub_quick_teach),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.agent_hub_teach_step_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.agent_hub_teach_start),
                        onClick = { onStartTeach("") },
                        leadingIcon = MuseIcons.microphone,
                        fillWidth = false,
                        size = MuseButtonSize.Compact,
                    )
                    if (!teachOverlayGranted) {
                        MuseCapsuleButton(
                            text = stringResource(R.string.quick_capture_overlay_title),
                            onClick = onRequestOverlayPermission,
                            variant = IosCapsuleButtonVariant.Secondary,
                            fillWidth = false,
                            size = MuseButtonSize.Compact,
                        )
                    }
                }
            }
        }
    }

    if (teachActive) {
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
                    text = stringResource(R.string.agent_hub_teach_recording, teachStepCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (teachState.recording) {
                    MuseTextField(
                        value = teachState.titleDraft,
                        onValueChange = onUpdateTeachTitle,
                        placeholder = { Text(stringResource(R.string.agent_hub_routines_title_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    teachState.steps.takeLast(5).forEach { step ->
                        Text(
                            text = "${step.index}. ${step.action}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
                    MuseCapsuleButton(
                        text = stringResource(
                            if (teachControl.paused) R.string.speech_resume_cd else R.string.speech_pause_cd,
                        ),
                        onClick = onPauseOrResumeTeach,
                        leadingIcon = if (teachControl.paused) MuseIcons.play else MuseIcons.pause,
                        variant = IosCapsuleButtonVariant.Secondary,
                        fillWidth = false,
                        size = MuseButtonSize.Compact,
                    )
                    MuseCapsuleButton(
                        text = stringResource(R.string.agent_hub_teach_finish),
                        onClick = onFinishTeach,
                        enabled = teachStepCount > 0,
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
                if (teachState.recording) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.agent_hub_teach_add_step),
                        onClick = { manualStepOpen = !manualStepOpen },
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                        size = MuseButtonSize.Compact,
                    )
                    if (manualStepOpen) {
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
                    }
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
        val linkedSkillName = sharedSkills.firstOrNull { skill -> skill.id == routine.skillId }?.name.orEmpty()
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
                        if (linkedSkillName.isNotBlank()) {
                            Text(
                                text = stringResource(R.string.agent_hub_routine_bound_skill, linkedSkillName),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
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

    val context = LocalContext.current
    var wakeDiagnostics by remember {
        mutableStateOf<io.zer0.muse.schedule.WakeDiagnostics?>(null)
    }
    LaunchedEffect(Unit) {
        wakeDiagnostics = io.zer0.muse.schedule.BackgroundWakeDiagnostics.collectWithSettings(context)
    }
    val deviceLabel = wakeDiagnostics?.device?.takeIf { it.isNotBlank() }
        ?: "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
    val batteryExempt = wakeDiagnostics?.batteryOptimizationExempt == true
    val bgEnabled = wakeDiagnostics?.backgroundWorkEnabled != false
    Surface(
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(MuseBorderWidth.hairline, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                Icon(
                    imageVector = MuseIcons.shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
                Text(
                    text = stringResource(R.string.agent_hub_rom_diag_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = stringResource(R.string.agent_hub_rom_diag_body, deviceLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                Surface(
                    shape = MuseShapes.pill,
                    color = if (batteryExempt) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                ) {
                    Text(
                        text = stringResource(
                            if (batteryExempt) {
                                R.string.agent_hub_rom_diag_battery_ok
                            } else {
                                R.string.agent_hub_rom_diag_battery_restricted
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (batteryExempt) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                        modifier = Modifier.padding(
                            horizontal = MuseSpacing.sm,
                            vertical = MuseSpacing.xxs,
                        ),
                    )
                }
                Surface(
                    shape = MuseShapes.pill,
                    color = if (bgEnabled) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                ) {
                    Text(
                        text = stringResource(
                            if (bgEnabled) {
                                R.string.agent_hub_rom_diag_bg_enabled
                            } else {
                                R.string.agent_hub_rom_diag_bg_disabled
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (bgEnabled) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                        modifier = Modifier.padding(
                            horizontal = MuseSpacing.sm,
                            vertical = MuseSpacing.xxs,
                        ),
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                MuseCapsuleButton(
                    text = stringResource(R.string.agent_hub_rom_diag_open_battery),
                    onClick = {
                        runCatching {
                            val batteryIntent = android.content.Intent(
                                android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(batteryIntent)
                        }.onFailure {
                            runCatching {
                                val fallback = android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.fromParts("package", context.packageName, null),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(fallback)
                            }.onFailure { err ->
                                Logger.w("AgentWorkbenchSheet", "打开电池设置失败: ${err.message}", err)
                            }
                        }
                    },
                    leadingIcon = MuseIcons.battery,
                    variant = IosCapsuleButtonVariant.Secondary,
                    fillWidth = false,
                    size = MuseButtonSize.Compact,
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.agent_hub_rom_diag_open_accessibility),
                    onClick = {
                        runCatching {
                            val intent = android.content.Intent(
                                android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS,
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }.onFailure { err ->
                            Logger.w("AgentWorkbenchSheet", "打开无障碍设置失败: ${err.message}", err)
                        }
                    },
                    variant = IosCapsuleButtonVariant.Secondary,
                    fillWidth = false,
                    size = MuseButtonSize.Compact,
                )
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
    routines: List<BotRoutineEntity>,
    recentTaskCount: Int,
    connectorHealth: AgentConnectorHealthSnapshot,
    computeNodes: List<RemoteComputeNodeEntity>,
    computeNodeSnapshots: Map<String, ComputeNodeSnapshot>,
    onOpenComputeNodeSetup: () -> Unit,
    onRefreshComputeNode: (String) -> Unit,
    onInstallComputeBrowserPack: (String) -> Unit,
    onRepairComputeNode: (String) -> Unit,
    onDeleteComputeNode: (String) -> Unit,
    onOpenComputeTerminal: (String) -> Unit,
    onOpenComputeFiles: (String) -> Unit,
    onSwitchAssistant: (String) -> Unit,
    onToggleChannelForBot: (String, String) -> Unit,
    onOpenGroupChats: (() -> Unit)?,
    onFixConnector: (String) -> Unit,
    skillRepository: SkillRepository = koinInject(),
) {
    val allSkills by skillRepository.observeAll.collectAsStateWithLifecycle(initialValue = emptyList())
    val sharedSkillCount = remember(allSkills) {
        allSkills.count { it.category == "user" || it.category == "custom" }
    }
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
        ComputeNodesSection(
            nodes = computeNodes,
            snapshots = computeNodeSnapshots,
            onOpenSetup = onOpenComputeNodeSetup,
            onRefresh = onRefreshComputeNode,
            onInstallBrowserPack = onInstallComputeBrowserPack,
            onRepair = onRepairComputeNode,
            onDelete = onDeleteComputeNode,
            onOpenTerminal = onOpenComputeTerminal,
            onOpenFiles = onOpenComputeFiles,
        )
        ConnectorHealthCard(snapshot = connectorHealth, onFix = onFixConnector)
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
                        val identity = squadIdentities[assistant.id]
                        val tagsLabel = identity?.specialtyTags.orEmpty()
                            .joinToString(" · ")
                            .takeIf { it.isNotBlank() }
                        val specialtyText = tagsLabel ?: identity?.specialty.orEmpty()
                        if (specialtyText.isNotBlank()) {
                            Text(
                                text = stringResource(R.string.agent_hub_squad_specialty, specialtyText),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val nodeId = identity?.defaultComputeNodeId.orEmpty()
                        val computeLabel = if (nodeId.isBlank()) {
                            stringResource(R.string.agent_hub_squad_local_compute)
                        } else {
                            computeNodes.firstOrNull { it.nodeId == nodeId }?.name
                                ?.takeIf { it.isNotBlank() } ?: nodeId
                        }
                        Text(
                            text = stringResource(R.string.agent_hub_squad_compute_target, computeLabel),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val dedicatedRoutineCount = routines.count { routine ->
                            routine.botId == assistant.id && routine.skillId.isNotBlank()
                        }
                        Text(
                            text = stringResource(
                                R.string.agent_hub_squad_skill_counts,
                                sharedSkillCount,
                                dedicatedRoutineCount,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.agent_hub_squad_recent_completed, recentTaskCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (boundChannels.isNotEmpty()) {
                            Text(
                                text = boundChannels.size.toString() + " · " +
                                    stringResource(R.string.agent_hub_squad_channels),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun ComputeNodesSection(
    nodes: List<RemoteComputeNodeEntity>,
    snapshots: Map<String, ComputeNodeSnapshot>,
    onOpenSetup: () -> Unit,
    onRefresh: (String) -> Unit,
    onInstallBrowserPack: (String) -> Unit,
    onRepair: (String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenTerminal: (String) -> Unit,
    onOpenFiles: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            SectionLabel(
                text = stringResource(R.string.compute_node_section_title),
                modifier = Modifier.weight(1f),
            )
            MuseCapsuleButton(
                text = stringResource(R.string.compute_node_add),
                onClick = onOpenSetup,
                variant = IosCapsuleButtonVariant.Secondary,
                fillWidth = false,
                size = MuseButtonSize.Compact,
                leadingIcon = MuseIcons.plus,
            )
        }
        if (nodes.isEmpty()) {
            Text(
                text = stringResource(R.string.compute_node_section_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            nodes.forEach { node ->
                ComputeNodeCard(
                    node = node,
                    snapshot = snapshots[node.nodeId],
                    onRefresh = { onRefresh(node.nodeId) },
                    onRepair = { onRepair(node.nodeId) },
                    onInstallBrowserPack = { onInstallBrowserPack(node.nodeId) },
                    onOpenTerminal = { onOpenTerminal(node.nodeId) },
                    onOpenFiles = { onOpenFiles(node.nodeId) },
                    onDelete = { onDelete(node.nodeId) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectorHealthCard(snapshot: AgentConnectorHealthSnapshot, onFix: (String) -> Unit) {
    if (snapshot.items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
        SectionLabel(text = stringResource(R.string.agent_connector_health_title))
        Surface(
            shape = MuseShapes.semiLarge,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                width = MuseBorderWidth.hairline,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.subtleBorder),
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            FlowRow(
                modifier = Modifier.padding(MusePaddings.cardInner),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                snapshot.items.forEach { item ->
                    val statusColor = when (item.status) {
                        ConnectorHealthStatus.OK -> MaterialTheme.statusColors.success
                        ConnectorHealthStatus.WARN -> MaterialTheme.statusColors.warning
                        ConnectorHealthStatus.ERROR -> MaterialTheme.statusColors.error
                        ConnectorHealthStatus.UNKNOWN -> MaterialTheme.statusColors.neutral
                    }
                    Surface(
                        shape = MuseShapes.pill,
                        color = statusColor.copy(alpha = MuseAlpha.statusTint),
                        border = BorderStroke(
                            width = MuseBorderWidth.hairline,
                            color = statusColor.copy(alpha = MuseAlpha.mutedSurface),
                        ),
                        modifier = Modifier.clickable(
                            enabled = item.fixable && item.status != ConnectorHealthStatus.OK,
                        ) { onFix(item.key) },
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                horizontal = MusePaddings.contentGap,
                                vertical = MusePaddings.tightGap,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(MuseSpacing.sm)
                                    .background(statusColor, androidx.compose.foundation.shape.CircleShape),
                            )
                            Text(
                                text = stringResource(item.titleRes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(item.detailRes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkbenchArtifactsTab(
    projection: AgentBotProjection?,
    artifacts: List<ArtifactEntity>,
    onOpenCandidate: (String) -> Unit,
    onDiscardCandidate: (String) -> Unit,
    onOpenArtifactCenter: (() -> Unit)? = null,
) {
    var viewingArtifact by remember { mutableStateOf<ArtifactEntity?>(null) }
    val candidates = projection?.candidates.orEmpty()
    if (candidates.isEmpty() && artifacts.isEmpty()) {
        MuseEmptyState(
            icon = MuseIcons.fileText,
            title = stringResource(R.string.artifact_center_empty),
            subtitle = stringResource(R.string.artifact_center_empty_hint),
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

    if (artifacts.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel(text = stringResource(R.string.agent_hub_artifacts_history))
                if (onOpenArtifactCenter != null) {
                    Text(
                        text = stringResource(R.string.artifact_view_all),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onOpenArtifactCenter),
                    )
                }
            }
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
                                imageVector = io.zer0.muse.ui.artifact.artifactTypeIcon(artifact.type, artifact.language),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(MuseIconSizes.iconSmall),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = artifact.title.ifBlank { stringResource(R.string.artifact_untitled) },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = io.zer0.muse.ui.artifact.artifactMetaLabel(artifact.type, artifact.language),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
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

    io.zer0.muse.ui.artifact.ArtifactOpenHost(
        artifact = viewingArtifact,
        onDismiss = { viewingArtifact = null },
    )
}
