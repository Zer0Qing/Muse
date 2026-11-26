package io.zer0.muse.ui.agentbot

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.automation.AutomationRuntime
import io.zer0.muse.automation.ui.VirtualDisplayHandoffPanel
import io.zer0.muse.automation.vdisplay.VirtualDisplayHandoffRegistry
import io.zer0.muse.data.agentbot.AgentBotProjection
import io.zer0.muse.data.agentbot.AgentBotUiState
import io.zer0.muse.data.agentbot.AgentGoalEntity
import io.zer0.muse.data.agentbot.AgentGoalMilestone
import io.zer0.muse.data.agentbot.AgentGoalStatus
import io.zer0.muse.data.agentbot.AgentWatcherEntity
import io.zer0.muse.data.agentbot.AgentWatcherStatus
import io.zer0.muse.data.agentbot.BotCardKind
import io.zer0.muse.data.agentbot.BotCardStatus
import io.zer0.muse.data.agentbot.LiveMissionProgressBus
import io.zer0.muse.data.agentbot.ReconnectActionKind
import io.zer0.muse.data.agentbot.ReconnectCardPayload
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.state.MuseProgressBar
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseBorderWidth
import io.zer0.muse.ui.theme.MuseButtonSize
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.theme.statusColors
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private const val FOLLOW_UP_TAG = "WorkbenchFollowUpTab"

private val followUpJson = Json { ignoreUnknownKeys = true }

/**
 * 工作台「跟进与目标」页。
 *
 * 四个分区按用户要处理的顺序排列：先拍板，再看 Watcher 与 Goal，
 * 最后才是正在执行的实况与可折叠的沙盒终端。MuseBottomSheet 外层已带滚动，
 * 本页只使用有界的内层滚动区域。
 */
@Composable
fun WorkbenchFollowUpTab(
    projection: AgentBotProjection?,
    watchers: List<AgentWatcherEntity>,
    goals: List<AgentGoalEntity>,
    sandboxLogs: List<LiveMissionProgressBus.SandboxLogLine>,
    activeVirtualDisplay: LiveMissionProgressBus.ActiveVirtualDisplay?,
    onOpenTask: (String) -> Unit,
    onSteerTask: (String, String) -> Unit,
    onSaveTaskAsRoutine: (String) -> Unit,
    onContinueTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onApprove: (AgentBotUiState.ApprovalRow) -> Unit,
    onDeny: (AgentBotUiState.ApprovalRow) -> Unit,
    onClearSandboxLogs: () -> Unit,
    onCheckWatcherNow: (String) -> Unit,
    onPauseWatcher: (String) -> Unit,
    onResumeWatcher: (String) -> Unit,
    onEndWatcher: (String) -> Unit,
    onToggleGoalMilestone: (goalId: String, milestoneId: String, done: Boolean) -> Unit,
    onRefineGoalInChat: (goalId: String, title: String) -> Unit,
    onFixReconnect: (ReconnectActionKind?) -> Unit,
    onResumeReconnect: (cardId: String, taskId: String) -> Unit,
    onDismissCard: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var endWatcherTarget by remember { mutableStateOf<AgentWatcherEntity?>(null) }
    val nowMillis = System.currentTimeMillis()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap),
    ) {
        WorkbenchPendingSection(
            projection = projection,
            onOpenTask = onOpenTask,
            onApprove = onApprove,
            onDeny = onDeny,
            onFixReconnect = onFixReconnect,
            onResumeReconnect = onResumeReconnect,
            onDismissCard = onDismissCard,
        )
        WorkbenchWatchersSection(
            watchers = watchers,
            nowMillis = nowMillis,
            onCheckNow = onCheckWatcherNow,
            onPause = onPauseWatcher,
            onResume = onResumeWatcher,
            onEnd = { endWatcherTarget = it },
        )
        WorkbenchGoalsSection(
            goals = goals,
            onToggleMilestone = onToggleGoalMilestone,
            onRefineInChat = onRefineGoalInChat,
        )
        WorkbenchLiveSection(
            projection = projection,
            sandboxLogs = sandboxLogs,
            activeVirtualDisplay = activeVirtualDisplay,
            onOpenTask = onOpenTask,
            onSteerTask = onSteerTask,
            onSaveTaskAsRoutine = onSaveTaskAsRoutine,
            onContinueTask = onContinueTask,
            onRetryTask = onRetryTask,
            onCancelTask = onCancelTask,
            onClearSandboxLogs = onClearSandboxLogs,
        )
    }

    endWatcherTarget?.let { watcher ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.agent_follow_up_watcher_end_confirm_title),
            itemName = watcher.title,
            consequence = stringResource(R.string.agent_follow_up_watcher_end_confirm_body),
            onConfirm = {
                endWatcherTarget = null
                onEndWatcher(watcher.watcherId)
            },
            onDismiss = { endWatcherTarget = null },
        )
    }
}

@Composable
private fun WorkbenchPendingSection(
    projection: AgentBotProjection?,
    onOpenTask: (String) -> Unit,
    onApprove: (AgentBotUiState.ApprovalRow) -> Unit,
    onDeny: (AgentBotUiState.ApprovalRow) -> Unit,
    onFixReconnect: (ReconnectActionKind?) -> Unit,
    onResumeReconnect: (cardId: String, taskId: String) -> Unit,
    onDismissCard: (String) -> Unit,
) {
    val approvals = projection?.pendingApprovals.orEmpty()
    val reconnectCards = projection?.cards.orEmpty().filter { card ->
        card.kind == BotCardKind.RECONNECT && card.status == BotCardStatus.ACTIVE
    }
    if (approvals.isEmpty() && reconnectCards.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
        SectionLabel(text = stringResource(R.string.agent_follow_up_pending_title))
        approvals.forEach { approval ->
            AgentInlineApprovalCard(
                row = approval,
                onApprove = onApprove,
                onDeny = onDeny,
                onOpenTask = onOpenTask,
            )
        }
        reconnectCards.forEach { card ->
            val payload = remember(card.payloadJson) { ReconnectCardPayload.decode(card.payloadJson) }
            AgentReconnectCard(
                card = card,
                onFix = { onFixReconnect(payload?.action) },
                onResume = { onResumeReconnect(card.cardId, card.taskId) },
                onDismiss = onDismissCard,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkbenchWatchersSection(
    watchers: List<AgentWatcherEntity>,
    nowMillis: Long,
    onCheckNow: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onEnd: (AgentWatcherEntity) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
        SectionLabel(text = stringResource(R.string.agent_follow_up_watchers_title))
        if (watchers.isEmpty()) {
            Text(
                text = stringResource(R.string.agent_follow_up_watchers_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(MusePaddings.contentGap),
            )
            return@Column
        }
        watchers.forEach { watcher ->
            WatcherCard(
                watcher = watcher,
                nowMillis = nowMillis,
                onCheckNow = { onCheckNow(watcher.watcherId) },
                onPause = { onPause(watcher.watcherId) },
                onResume = { onResume(watcher.watcherId) },
                onEnd = { onEnd(watcher) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WatcherCard(
    watcher: AgentWatcherEntity,
    nowMillis: Long,
    onCheckNow: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onEnd: () -> Unit,
) {
    val statusColor = watcherStatusColor(watcher.status)
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
                Icon(
                    imageVector = MuseIcons.timer,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = watcher.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            R.string.agent_follow_up_watcher_meta,
                            watcherTargetLabel(watcher),
                            watcher.intervalMinutes,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusPill(label = watcherStatusLabel(watcher.status), color = statusColor)
            }
            Text(
                text = watcherLastCheckedLabel(watcher.lastCheckedAt, nowMillis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (watcher.lastError.isNotBlank()) {
                Text(
                    text = stringResource(R.string.agent_follow_up_watcher_last_error, watcher.lastError),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.statusColors.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (watcher.lastResult.isNotBlank()) {
                Text(
                    text = stringResource(R.string.agent_follow_up_watcher_last_result, watcher.lastResult),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (watcher.status != AgentWatcherStatus.ENDED) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.labelVerticalGap),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.agent_follow_up_watcher_check_now),
                        onClick = onCheckNow,
                        leadingIcon = MuseIcons.refresh,
                        variant = IosCapsuleButtonVariant.Secondary,
                        fillWidth = false,
                        size = MuseButtonSize.Compact,
                    )
                    if (watcher.status == AgentWatcherStatus.ACTIVE) {
                        MuseCapsuleButton(
                            text = stringResource(R.string.agent_follow_up_watcher_pause),
                            onClick = onPause,
                            leadingIcon = MuseIcons.pause,
                            variant = IosCapsuleButtonVariant.Secondary,
                            fillWidth = false,
                            size = MuseButtonSize.Compact,
                        )
                    } else {
                        MuseCapsuleButton(
                            text = stringResource(R.string.agent_follow_up_watcher_resume),
                            onClick = onResume,
                            leadingIcon = MuseIcons.play,
                            variant = IosCapsuleButtonVariant.Secondary,
                            fillWidth = false,
                            size = MuseButtonSize.Compact,
                        )
                    }
                    MuseCapsuleButton(
                        text = stringResource(R.string.agent_follow_up_watcher_end),
                        onClick = onEnd,
                        leadingIcon = MuseIcons.trash,
                        destructive = true,
                        fillWidth = false,
                        size = MuseButtonSize.Compact,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkbenchGoalsSection(
    goals: List<AgentGoalEntity>,
    onToggleMilestone: (goalId: String, milestoneId: String, done: Boolean) -> Unit,
    onRefineInChat: (goalId: String, title: String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
        SectionLabel(text = stringResource(R.string.agent_follow_up_goals_title))
        if (goals.isEmpty()) {
            Text(
                text = stringResource(R.string.agent_follow_up_goals_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(MusePaddings.contentGap),
            )
            return@Column
        }
        goals.forEach { goal ->
            GoalCard(
                goal = goal,
                onToggleMilestone = { milestoneId, done ->
                    onToggleMilestone(goal.goalId, milestoneId, done)
                },
                onRefineInChat = { onRefineInChat(goal.goalId, goal.title) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalCard(
    goal: AgentGoalEntity,
    onToggleMilestone: (milestoneId: String, done: Boolean) -> Unit,
    onRefineInChat: () -> Unit,
) {
    val milestones = remember(goal.milestonesJson) { decodeGoalMilestones(goal.milestonesJson) }
    val statusColor = goalStatusColor(goal.status)
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
                Icon(
                    imageVector = MuseIcons.circleCheck,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
                Text(
                    text = goal.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusPill(label = goalStatusLabel(goal.status), color = statusColor)
            }
            if (goal.description.isNotBlank()) {
                Text(
                    text = goal.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Text(
                    text = stringResource(R.string.agent_follow_up_goal_progress, goal.progress),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MuseProgressBar(
                    progress = goal.progress.coerceIn(0, 100) / 100f,
                    modifier = Modifier.weight(1f),
                )
            }
            if (milestones.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.agent_follow_up_goal_milestones),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                milestones.forEach { milestone ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                    ) {
                        MuseTactileButton(
                            icon = if (milestone.done) MuseIcons.circleCheck else MuseIcons.circle,
                            onClick = { onToggleMilestone(milestone.id, !milestone.done) },
                            contentDescription = milestone.title,
                            tint = if (milestone.done) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            size = MuseIconSizes.touchTarget,
                            iconSize = MuseIconSizes.iconSmall,
                        )
                        Text(
                            text = milestone.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (milestone.done) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.labelVerticalGap),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                MuseCapsuleButton(
                    text = stringResource(R.string.agent_follow_up_goal_refine),
                    onClick = onRefineInChat,
                    leadingIcon = MuseIcons.edit,
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
private fun WorkbenchLiveSection(
    projection: AgentBotProjection?,
    sandboxLogs: List<LiveMissionProgressBus.SandboxLogLine>,
    activeVirtualDisplay: LiveMissionProgressBus.ActiveVirtualDisplay?,
    onOpenTask: (String) -> Unit,
    onSteerTask: (String, String) -> Unit,
    onSaveTaskAsRoutine: (String) -> Unit,
    onContinueTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onClearSandboxLogs: () -> Unit,
) {
    var handoffRefreshToken by remember { mutableIntStateOf(0) }
    var vdPreviewOpen by rememberSaveable { mutableStateOf(false) }
    var terminalExpanded by rememberSaveable { mutableStateOf(false) }
    val handoffs = remember(handoffRefreshToken, activeVirtualDisplay) {
        VirtualDisplayHandoffRegistry.list(System.currentTimeMillis())
    }
    val liveVdDisplayId = activeVirtualDisplay?.displayId ?: handoffs.firstOrNull()?.displayId
    val liveVdPackage = activeVirtualDisplay?.packageName
        ?: handoffs.firstOrNull()?.packageName.orEmpty()
    val activeTasks = projection?.let { it.inProgress + it.needsAction }.orEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
        SectionLabel(text = stringResource(R.string.agent_follow_up_live_title))
        if (activeTasks.isEmpty() && liveVdDisplayId == null && sandboxLogs.isEmpty()) {
            Text(
                text = stringResource(R.string.agent_follow_up_live_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(MusePaddings.contentGap),
            )
        } else {
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
                    activeVirtualDisplay = activeVirtualDisplay?.takeIf { it.taskId == task.taskId },
                    onOpenVirtualDisplay = { vdPreviewOpen = true },
                )
            }
        }
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { terminalExpanded = !terminalExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    ) {
                        Icon(
                            imageVector = MuseIcons.terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                        Text(
                            text = stringResource(R.string.agent_follow_up_terminal_title),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.agent_follow_up_terminal_lines, sandboxLogs.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            imageVector = if (terminalExpanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconSmallTiny),
                        )
                    }
                    if (terminalExpanded) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
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
                            sandboxLogs.takeLast(80).forEach { line ->
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
        }
    }
}

@Composable
private fun StatusPill(label: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        shape = MuseShapes.pill,
        color = color.copy(alpha = MuseAlpha.statusTint),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(
                horizontal = MusePaddings.contentGap,
                vertical = MusePaddings.tinyGap,
            ),
        )
    }
}

@Composable
private fun watcherStatusLabel(status: String): String = when (status) {
    AgentWatcherStatus.ACTIVE -> stringResource(R.string.agent_follow_up_watcher_status_active)
    AgentWatcherStatus.PAUSED -> stringResource(R.string.agent_follow_up_watcher_status_paused)
    AgentWatcherStatus.ERROR -> stringResource(R.string.agent_follow_up_watcher_status_error)
    AgentWatcherStatus.ENDED -> stringResource(R.string.agent_follow_up_watcher_status_ended)
    else -> stringResource(R.string.agent_follow_up_watcher_status_ended)
}

@Composable
private fun watcherStatusColor(status: String): androidx.compose.ui.graphics.Color = when (status) {
    AgentWatcherStatus.ACTIVE -> MaterialTheme.statusColors.success
    AgentWatcherStatus.PAUSED -> MaterialTheme.statusColors.warning
    AgentWatcherStatus.ERROR -> MaterialTheme.statusColors.error
    AgentWatcherStatus.ENDED -> MaterialTheme.statusColors.neutral
    else -> MaterialTheme.statusColors.neutral
}

@Composable
private fun watcherTargetLabel(watcher: AgentWatcherEntity): String =
    if (watcher.computeTarget.equals("cloud", ignoreCase = true)) {
        stringResource(R.string.agent_follow_up_watcher_target_cloud)
    } else {
        stringResource(R.string.agent_follow_up_watcher_target_local)
    }

@Composable
private fun watcherLastCheckedLabel(lastCheckedAt: Long, nowMillis: Long): String =
    if (lastCheckedAt > 0L) {
        stringResource(
            R.string.agent_follow_up_watcher_last_checked,
            DateUtils.getRelativeTimeSpanString(
                lastCheckedAt,
                nowMillis,
                DateUtils.MINUTE_IN_MILLIS,
            ).toString(),
        )
    } else {
        stringResource(R.string.agent_follow_up_watcher_never_checked)
    }

@Composable
private fun goalStatusLabel(status: String): String = when (status) {
    AgentGoalStatus.ACTIVE -> stringResource(R.string.agent_follow_up_goal_status_active)
    AgentGoalStatus.PAUSED -> stringResource(R.string.agent_follow_up_goal_status_paused)
    AgentGoalStatus.ACHIEVED -> stringResource(R.string.agent_follow_up_goal_status_achieved)
    AgentGoalStatus.ABANDONED -> stringResource(R.string.agent_follow_up_goal_status_abandoned)
    else -> stringResource(R.string.agent_follow_up_goal_status_active)
}

@Composable
private fun goalStatusColor(status: String): androidx.compose.ui.graphics.Color = when (status) {
    AgentGoalStatus.ACTIVE -> MaterialTheme.colorScheme.primary
    AgentGoalStatus.PAUSED -> MaterialTheme.statusColors.warning
    AgentGoalStatus.ACHIEVED -> MaterialTheme.statusColors.success
    AgentGoalStatus.ABANDONED -> MaterialTheme.statusColors.neutral
    else -> MaterialTheme.colorScheme.primary
}

private fun decodeGoalMilestones(raw: String): List<AgentGoalMilestone> {
    val text = raw.trim()
    if (text.isBlank()) return emptyList()
    return runCatching {
        followUpJson.decodeFromString(ListSerializer(AgentGoalMilestone.serializer()), text)
    }.onFailure { error ->
        Logger.w(FOLLOW_UP_TAG, "解析目标里程碑失败: ${error.message}")
    }.getOrDefault(emptyList())
}
