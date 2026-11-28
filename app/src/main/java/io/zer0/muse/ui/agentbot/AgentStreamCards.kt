package io.zer0.muse.ui.agentbot

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.muse.R
import io.zer0.muse.data.agentbot.AgentBotPrompt
import io.zer0.muse.data.agentbot.AgentBotUiState
import io.zer0.muse.data.agentbot.AgentStreamCoordinator
import io.zer0.muse.data.agentbot.AgentWatcherEngine
import io.zer0.muse.data.agentbot.BotCardStatus
import io.zer0.muse.data.agentbot.BotTaskStatus
import io.zer0.muse.data.agentbot.LiveMissionProgressBus
import io.zer0.muse.data.agentbot.ReconnectCardPayload
import io.zer0.muse.data.agentbot.StepExecutionStatus
import io.zer0.muse.tools.WorkspaceTool
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseBorderWidth
import io.zer0.muse.ui.theme.MuseButtonSize
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.theme.statusColors
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 1. 流内动态任务进度卡（Live Mission Card）。
 *
 * 展示任务目标、状态胶囊、可展开的真实执行步骤时间线、排队中的活体插话徽章，
 * 以及直接内联的「插话调整 / 看电脑实况 / 存为惯例 / 继续 / 重试 / 取消」操作。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentLiveMissionCard(
    row: AgentBotUiState.TaskRow,
    onOpenDetails: (String) -> Unit,
    onOpenComputer: () -> Unit,
    onSteer: (String, String) -> Unit,
    onSaveAsRoutine: (String) -> Unit,
    onContinue: (String) -> Unit,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    activeVirtualDisplay: LiveMissionProgressBus.ActiveVirtualDisplay? = null,
    onOpenVirtualDisplay: (() -> Unit)? = null,
    remoteBrowser: RemoteBrowserLiveState? = null,
    onOpenRemoteBrowser: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isActive = row.status in BotTaskStatus.NON_TERMINAL
    val isSucceeded = row.status == BotTaskStatus.SUCCEEDED
    var stepsExpanded by rememberSaveable(row.taskId) { mutableStateOf(isActive) }
    var steeringOpen by rememberSaveable(row.taskId) { mutableStateOf(false) }
    var steerDraft by rememberSaveable(row.taskId) { mutableStateOf("") }
    var savedToast by rememberSaveable(row.taskId) { mutableStateOf(false) }
    var confirmingAction by remember(row.taskId) { mutableStateOf<AgentBotUiState.TaskAction?>(null) }

    val badgeColor = when (row.status) {
        BotTaskStatus.SUCCEEDED -> MaterialTheme.statusColors.success
        BotTaskStatus.FAILED, BotTaskStatus.CANCELLED -> MaterialTheme.statusColors.error
        BotTaskStatus.WAITING_APPROVAL, BotTaskStatus.NEEDS_USER, BotTaskStatus.UNKNOWN ->
            MaterialTheme.statusColors.warning
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = MuseElevation.card,
        border = BorderStroke(
            width = MuseBorderWidth.hairline,
            color = if (isActive) {
                badgeColor.copy(alpha = MuseAlpha.disabled)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.subtleBorder)
            },
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            AgentLiveMissionHeader(
                row = row,
                badgeColor = badgeColor,
                stepsExpanded = stepsExpanded,
                onToggleSteps = { stepsExpanded = !stepsExpanded },
                onOpenDetails = onOpenDetails,
            )
            AgentLiveMissionRemoteStatus(
                remoteBrowser = remoteBrowser,
                activeVirtualDisplay = activeVirtualDisplay,
                onOpenRemoteBrowser = onOpenRemoteBrowser,
                onOpenVirtualDisplay = onOpenVirtualDisplay,
            )
            AgentLiveMissionTimeline(row = row, expanded = stepsExpanded)
            AgentLiveMissionSteering(
                visible = steeringOpen && isActive,
                draft = steerDraft,
                onDraftChange = { steerDraft = it },
                onSubmit = { content ->
                    onSteer(row.taskId, content)
                    steerDraft = ""
                    steeringOpen = false
                },
            )

            if (savedToast) {
                Text(
                    text = stringResource(R.string.agent_hub_mission_saved_routine),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.statusColors.success,
                )
            }

            AgentLiveMissionActions(
                row = row,
                isActive = isActive,
                isSucceeded = isSucceeded,
                savedToast = savedToast,
                onOpenComputer = onOpenComputer,
                onToggleSteering = { steeringOpen = !steeringOpen },
                onContinue = { confirmingAction = AgentBotUiState.TaskAction.CONTINUE },
                onRetry = { confirmingAction = AgentBotUiState.TaskAction.RETRY },
                onSaveAsRoutine = {
                    onSaveAsRoutine(row.taskId)
                    savedToast = true
                },
                onOpenDetails = { onOpenDetails(row.taskId) },
                onCancel = { onCancel(row.taskId) },
            )
        }
    }

    AgentLiveMissionConfirmation(
        action = confirmingAction,
        onDismiss = { confirmingAction = null },
        onContinue = { onContinue(row.taskId) },
        onRetry = { onRetry(row.taskId) },
    )
}

@Composable
private fun AgentLiveMissionHeader(
    row: AgentBotUiState.TaskRow,
    badgeColor: androidx.compose.ui.graphics.Color,
    stepsExpanded: Boolean,
    onToggleSteps: () -> Unit,
    onOpenDetails: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleSteps),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        Icon(
            imageVector = if (row.status == BotTaskStatus.SUCCEEDED) MuseIcons.circleCheck else MuseIcons.rocket,
            contentDescription = null,
            tint = badgeColor,
            modifier = Modifier.size(MuseIconSizes.iconSmall),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.goal,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.labelVerticalGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.needsUser?.let { agentPromptLabel(it) } ?: agentStatusLabel(row.status),
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeColor,
                )
                if (!stepsExpanded && row.steps.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.agent_hub_mission_steps_count, row.steps.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (row.pendingSteerCount > 0) {
                    Text(
                        text = stringResource(R.string.agent_hub_mission_steered_badge, row.pendingSteerCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.statusColors.info,
                    )
                }
            }
        }
        Icon(
            imageVector = if (stepsExpanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(MuseIconSizes.iconSmallTiny),
        )
        MuseTactileButton(
            icon = MuseIcons.chevronRight,
            contentDescription = stringResource(R.string.agent_screen_task_details),
            onClick = { onOpenDetails(row.taskId) },
            compact = true,
        )
    }
}

@Composable
private fun AgentLiveMissionRemoteStatus(
    remoteBrowser: RemoteBrowserLiveState?,
    activeVirtualDisplay: LiveMissionProgressBus.ActiveVirtualDisplay?,
    onOpenRemoteBrowser: (() -> Unit)?,
    onOpenVirtualDisplay: (() -> Unit)?,
) {
    if (remoteBrowser != null) {
        RemoteBrowserThumbnailStrip(state = remoteBrowser, onClick = { onOpenRemoteBrowser?.invoke() })
    } else if (activeVirtualDisplay != null) {
        Surface(
            shape = MuseShapes.pill,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = MuseAlpha.statusTint),
            modifier = Modifier
                .fillMaxWidth()
                .clip(MuseShapes.pill)
                .clickable(enabled = onOpenVirtualDisplay != null) { onOpenVirtualDisplay?.invoke() },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = MusePaddings.contentGap, vertical = MusePaddings.tightGap),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Icon(
                    imageVector = MuseIcons.computer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            R.string.agent_hub_live_vdisplay,
                            activeVirtualDisplay.packageName.ifBlank { "#${activeVirtualDisplay.displayId}" },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (activeVirtualDisplay.action.isNotBlank()) {
                        Text(
                            text = activeVirtualDisplay.action,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    imageVector = MuseIcons.chevronRight,
                    contentDescription = stringResource(R.string.agent_hub_mission_vdisplay_open_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconTiny),
                )
            }
        }
    }
}

@Composable
private fun AgentLiveMissionTimeline(row: AgentBotUiState.TaskRow, expanded: Boolean) {
    AnimatedVisibility(visible = expanded && row.steps.isNotEmpty()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(MusePaddings.labelVerticalGap),
            modifier = Modifier.padding(top = MusePaddings.tightGap),
        ) {
            row.steps.takeLast(6).forEach { step ->
                val stepTint = when (step.status) {
                    StepExecutionStatus.SUCCEEDED -> MaterialTheme.statusColors.success
                    StepExecutionStatus.FAILED -> MaterialTheme.statusColors.error
                    else -> MaterialTheme.colorScheme.primary
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    Icon(
                        imageVector = when (step.status) {
                            StepExecutionStatus.SUCCEEDED -> MuseIcons.check
                            StepExecutionStatus.FAILED -> MuseIcons.alertCircle
                            else -> MuseIcons.activity
                        },
                        contentDescription = null,
                        tint = stepTint,
                        modifier = Modifier.padding(top = MusePaddings.tinyGap).size(MuseIconSizes.iconTiny),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = step.displayLabel.ifBlank { step.toolName },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (step.summary.isNotBlank()) {
                            Text(
                                text = step.summary,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentLiveMissionSteering(
    visible: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
) {
    AnimatedVisibility(visible = visible) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            MuseTextField(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = { Text(stringResource(R.string.agent_hub_mission_steer_hint)) },
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            MuseCapsuleButton(
                text = stringResource(R.string.agent_hub_mission_steer_send),
                onClick = { draft.trim().takeIf(String::isNotBlank)?.let(onSubmit) },
                enabled = draft.isNotBlank(),
                fillWidth = false,
                size = MuseButtonSize.Compact,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentLiveMissionActions(
    row: AgentBotUiState.TaskRow,
    isActive: Boolean,
    isSucceeded: Boolean,
    savedToast: Boolean,
    onOpenComputer: () -> Unit,
    onToggleSteering: () -> Unit,
    onContinue: () -> Unit,
    onRetry: () -> Unit,
    onSaveAsRoutine: () -> Unit,
    onOpenDetails: () -> Unit,
    onCancel: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.labelVerticalGap),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        if (isActive) {
            MuseCapsuleButton(
                text = stringResource(R.string.agent_hub_mission_view_computer),
                onClick = onOpenComputer,
                leadingIcon = MuseIcons.terminal,
                variant = IosCapsuleButtonVariant.Secondary,
                fillWidth = false,
                size = MuseButtonSize.Compact,
            )
            MuseCapsuleButton(
                text = stringResource(R.string.agent_hub_mission_steer),
                onClick = onToggleSteering,
                leadingIcon = MuseIcons.edit,
                variant = IosCapsuleButtonVariant.Secondary,
                fillWidth = false,
                size = MuseButtonSize.Compact,
            )
        }
        if (AgentBotUiState.TaskAction.CONTINUE in row.actions) {
            MuseCapsuleButton(
                text = stringResource(R.string.agent_bot_continue),
                onClick = onContinue,
                leadingIcon = MuseIcons.play,
                fillWidth = false,
                size = MuseButtonSize.Compact,
            )
        } else if (row.status == BotTaskStatus.NEEDS_USER) {
            MuseCapsuleButton(
                text = stringResource(R.string.agent_task_decision_action),
                onClick = onOpenDetails,
                leadingIcon = MuseIcons.edit,
                fillWidth = false,
                size = MuseButtonSize.Compact,
            )
        }
        if (AgentBotUiState.TaskAction.RETRY in row.actions) {
            MuseCapsuleButton(
                text = stringResource(R.string.agent_screen_retry_task),
                onClick = onRetry,
                leadingIcon = MuseIcons.refresh,
                variant = IosCapsuleButtonVariant.Secondary,
                fillWidth = false,
                size = MuseButtonSize.Compact,
            )
        }
        if (AgentBotUiState.TaskAction.VERIFY in row.actions) {
            MuseCapsuleButton(
                text = stringResource(R.string.agent_screen_verify_result),
                onClick = onOpenDetails,
                leadingIcon = MuseIcons.search,
                fillWidth = false,
                size = MuseButtonSize.Compact,
            )
        }
        if (isSucceeded && !savedToast) {
            MuseCapsuleButton(
                text = stringResource(R.string.agent_hub_mission_save_routine),
                onClick = onSaveAsRoutine,
                leadingIcon = MuseIcons.bookmark,
                variant = IosCapsuleButtonVariant.Secondary,
                fillWidth = false,
                size = MuseButtonSize.Compact,
            )
        }
        if (AgentBotUiState.TaskAction.CANCEL in row.actions) {
            MuseCapsuleButton(
                text = stringResource(R.string.common_cancel),
                onClick = onCancel,
                variant = IosCapsuleButtonVariant.Text,
                fillWidth = false,
                size = MuseButtonSize.Compact,
            )
        }
    }
}

@Composable
private fun AgentLiveMissionConfirmation(
    action: AgentBotUiState.TaskAction?,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
    onRetry: () -> Unit,
) {
    action ?: return
    val continuing = action == AgentBotUiState.TaskAction.CONTINUE
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(
            if (continuing) R.string.agent_bot_prompt_approved_retry else R.string.agent_screen_retry_task_confirm,
        ),
        content = {
            Text(
                text = stringResource(
                    if (continuing) R.string.agent_bot_continue_confirm else R.string.agent_screen_retry_task_confirm_body,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmText = stringResource(if (continuing) R.string.agent_bot_continue else R.string.agent_screen_retry_task),
        onConfirm = {
            onDismiss()
            if (continuing) onContinue() else onRetry()
        },
        dismissText = stringResource(R.string.common_cancel),
        onDismiss = onDismiss,
    )
}

/** 2. 流内原生审批拍板卡（保留 CAS 参数哈希与资源版本防篡改校验）。 */
@Composable
fun AgentInlineApprovalCard(
    row: AgentBotUiState.ApprovalRow,
    onApprove: (AgentBotUiState.ApprovalRow) -> Unit,
    onDeny: (AgentBotUiState.ApprovalRow) -> Unit,
    onOpenTask: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var argsExpanded by rememberSaveable(row.approvalId) { mutableStateOf(false) }
    val nowMillis by androidx.compose.runtime.produceState(
        initialValue = System.currentTimeMillis(),
        key1 = row.expiresAt,
    ) {
        while (value < row.expiresAt) {
            kotlinx.coroutines.delay(15_000L)
            value = System.currentTimeMillis()
        }
    }
    val (riskColor, riskContainer) = when (row.risk.uppercase()) {
        "HIGH", "CRITICAL" -> MaterialTheme.statusColors.error to MaterialTheme.statusColors.errorContainer
        "NORMAL" -> MaterialTheme.statusColors.warning to MaterialTheme.statusColors.warningContainer
        else -> MaterialTheme.statusColors.info to MaterialTheme.statusColors.infoContainer
    }
    Surface(
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = MuseElevation.medium,
        border = BorderStroke(
            width = MuseBorderWidth.thin,
            color = riskColor.copy(alpha = MuseAlpha.mutedSurface),
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            AgentApprovalHeader(row = row, riskColor = riskColor, riskContainer = riskContainer, onOpenTask = onOpenTask)
            Text(
                text = stringResource(
                    R.string.agent_approval_intent_summary,
                    agentApprovalToolLabel(row.toolName),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            AgentApprovalArguments(
                preview = row.argumentsPreview,
                expanded = argsExpanded,
                onToggle = { argsExpanded = !argsExpanded },
            )
            if (row.resourceScope.isNotBlank()) {
                Text(
                    text = stringResource(R.string.agent_screen_approval_scope, row.resourceScope),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(
                    R.string.agent_screen_approval_expiry,
                    DateUtils.getRelativeTimeSpanString(
                        row.expiresAt,
                        nowMillis,
                        DateUtils.MINUTE_IN_MILLIS,
                    ),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AgentApprovalActions(onApprove = { onApprove(row) }, onDeny = { onDeny(row) })
        }
    }
}

@Composable
private fun AgentApprovalHeader(
    row: AgentBotUiState.ApprovalRow,
    riskColor: androidx.compose.ui.graphics.Color,
    riskContainer: androidx.compose.ui.graphics.Color,
    onOpenTask: (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        Icon(
            imageVector = MuseIcons.shieldCheck,
            contentDescription = null,
            tint = riskColor,
            modifier = Modifier.size(MuseIconSizes.iconMedium),
        )
        Text(
            text = agentApprovalToolLabel(row.toolName),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Surface(shape = MuseShapes.pill, color = riskContainer) {
            Text(
                text = agentApprovalRiskLabel(row.risk),
                style = MaterialTheme.typography.labelSmall,
                color = riskColor,
                modifier = Modifier.padding(horizontal = MusePaddings.contentGap, vertical = MusePaddings.tinyGap),
            )
        }
        MuseTactileButton(
            icon = MuseIcons.chevronRight,
            contentDescription = stringResource(R.string.agent_screen_open_task),
            onClick = { onOpenTask(row.taskId) },
            compact = true,
        )
    }
}

@Composable
private fun AgentApprovalArguments(preview: String, expanded: Boolean, onToggle: () -> Unit) {
    if (preview.isBlank()) return
    Surface(
        onClick = onToggle,
        shape = MuseShapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MuseAlpha.subtleBorder),
    ) {
        Text(
            text = if (expanded) {
                stringResource(R.string.agent_screen_approval_arguments, preview)
            } else {
                stringResource(R.string.agent_approval_view_details)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (expanded) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) 24 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(MusePaddings.contentGap),
        )
    }
}

@Composable
private fun AgentApprovalActions(onApprove: () -> Unit, onDeny: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
        MuseCapsuleButton(
            text = stringResource(R.string.agent_bot_approve),
            onClick = onApprove,
            leadingIcon = MuseIcons.check,
            fillWidth = false,
            size = MuseButtonSize.Compact,
        )
        MuseCapsuleButton(
            text = stringResource(R.string.agent_bot_deny),
            onClick = onDeny,
            variant = IosCapsuleButtonVariant.Secondary,
            fillWidth = false,
            size = MuseButtonSize.Compact,
        )
    }
}

@Composable
fun AgentForegroundMcpApprovalCard(
    request: AgentBotUiState.ForegroundMcpApproval,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var argsExpanded by rememberSaveable(request.requestId) { mutableStateOf(true) }
    Surface(
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = MuseElevation.medium,
        border = BorderStroke(MuseBorderWidth.thin, MaterialTheme.statusColors.warning.copy(alpha = MuseAlpha.mutedSurface)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Text(
                text = stringResource(R.string.agent_mcp_approval_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.agent_mcp_approval_target, request.serverId, request.toolName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = request.purpose,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                onClick = { argsExpanded = !argsExpanded },
                shape = MuseShapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MuseAlpha.subtleBorder),
            ) {
                Text(
                    text = if (argsExpanded) {
                        stringResource(R.string.agent_mcp_approval_arguments, request.argumentsJson)
                    } else {
                        stringResource(R.string.agent_mcp_approval_view_arguments)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (argsExpanded) 12 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(MusePaddings.contentGap),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
                MuseCapsuleButton(
                    text = stringResource(R.string.agent_mcp_approval_allow_once),
                    onClick = onApprove,
                    leadingIcon = MuseIcons.check,
                    fillWidth = false,
                    size = MuseButtonSize.Compact,
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.agent_mcp_approval_reject),
                    onClick = onDeny,
                    variant = IosCapsuleButtonVariant.Secondary,
                    fillWidth = false,
                    size = MuseButtonSize.Compact,
                )
            }
        }
    }
}

/** 3. 可点选后续建议 / 决策卡片：轻点选项即自动发送继续推进。 */
@Composable
fun AgentChoiceCard(
    card: AgentBotUiState.MessageCardUi,
    onSelectOption: (String, AgentStreamCoordinator.ChoiceOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (card.options.isEmpty()) return
    val isResolved = card.status == BotCardStatus.RESOLVED

    Surface(
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = MuseBorderWidth.hairline,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.subtleBorder),
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            if (card.title.isNotBlank()) {
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            card.options.forEachIndexed { index, option ->
                val selected = isResolved && card.selectedOptionId == option.id
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.disabled))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MuseShapes.small)
                        .clickable(enabled = !isResolved) { onSelectOption(card.cardId, option) }
                        .padding(vertical = MusePaddings.contentGap, horizontal = MusePaddings.tightGap),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else if (isResolved) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Surface(
                        shape = CircleShape,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = MuseAlpha.subtleBorder)
                        },
                        modifier = Modifier.size(MuseIconSizes.icon),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (selected) MuseIcons.check else MuseIcons.arrowRight,
                                contentDescription = if (selected) {
                                    stringResource(R.string.agent_hub_choice_selected)
                                } else {
                                    null
                                },
                                tint = if (selected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                },
                                modifier = Modifier.size(MuseIconSizes.iconTiny),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 4. 主动简报 / 建议行动卡（Cue Briefing）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentCueBriefingCard(
    card: AgentBotUiState.MessageCardUi,
    onSelectAction: (String, AgentStreamCoordinator.ChoiceOption) -> Unit,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = MuseAlpha.statusTint),
        border = BorderStroke(
            width = MuseBorderWidth.hairline,
            color = MaterialTheme.colorScheme.primary.copy(alpha = MuseAlpha.handle),
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.labelVerticalGap),
            ) {
                Icon(
                    imageVector = MuseIcons.sun,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
                Text(
                    text = card.title.ifBlank { stringResource(R.string.agent_hub_cue_briefing_title) },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                MuseTactileButton(
                    icon = MuseIcons.x,
                    contentDescription = stringResource(R.string.common_close),
                    onClick = { onDismiss(card.cardId) },
                    compact = true,
                )
            }
            if (card.subtitle.isNotBlank()) {
                Text(
                    text = card.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (card.options.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    card.options.forEach { action ->
                        MuseCapsuleButton(
                            text = action.label,
                            onClick = { onSelectAction(card.cardId, action) },
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

/** 5. 流内富产物 / 暂存工作区变更交付卡。 */
@Composable
fun AgentArtifactDeliveryCard(
    candidate: AgentBotUiState.CandidateRow,
    onOpen: (String) -> Unit,
    onDiscard: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDiscard by remember(candidate.stagedId) { mutableStateOf(false) }
    if (confirmDiscard) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.agent_bot_discard),
            itemName = candidate.targetRef,
            onConfirm = {
                confirmDiscard = false
                onDiscard(candidate.stagedId)
            },
            onDismiss = { confirmDiscard = false },
        )
    }
    Surface(
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = MuseBorderWidth.hairline,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.subtleBorder),
        ),
        modifier = modifier.fillMaxWidth(),
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
                    .clip(MuseShapes.medium)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = MuseAlpha.subtleBorder)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MuseIcons.fileText,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
            }
            Text(
                text = candidate.targetRef,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            MuseCapsuleButton(
                text = stringResource(R.string.agent_screen_open_artifact),
                onClick = { onOpen(candidate.stagedId) },
                variant = IosCapsuleButtonVariant.Secondary,
                fillWidth = false,
                size = MuseButtonSize.Compact,
            )
            MuseTactileButton(
                icon = MuseIcons.trash,
                contentDescription = stringResource(R.string.agent_bot_discard),
                onClick = { confirmDiscard = true },
                compact = true,
            )
        }
    }
}

/** 6. 可修复故障卡：给出修复入口，并允许用户确认已修好后重试原任务。 */
@Composable
fun AgentReconnectCard(
    card: AgentBotUiState.MessageCardUi,
    onFix: () -> Unit,
    onResume: () -> Unit,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val payload = remember(card.payloadJson) { ReconnectCardPayload.decode(card.payloadJson) }
    val reason = payload?.reason?.takeIf { it.isNotBlank() } ?: card.subtitle
    Surface(
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.statusColors.warningContainer.copy(alpha = MuseAlpha.statusTint),
        border = BorderStroke(
            width = MuseBorderWidth.thin,
            color = MaterialTheme.statusColors.warning.copy(alpha = MuseAlpha.handle),
        ),
        modifier = modifier.fillMaxWidth(),
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
                    imageVector = MuseIcons.wrench,
                    contentDescription = null,
                    tint = MaterialTheme.statusColors.warning,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
                Text(
                    text = card.title.ifBlank { stringResource(R.string.agent_reconnect_title) },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                MuseTactileButton(
                    icon = MuseIcons.x,
                    contentDescription = stringResource(R.string.agent_reconnect_dismiss_cd),
                    onClick = { onDismiss(card.cardId) },
                    compact = true,
                )
            }
            if (reason.isNotBlank()) {
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MuseCapsuleButton(
                    text = stringResource(R.string.agent_reconnect_fix),
                    onClick = onFix,
                    leadingIcon = MuseIcons.wrench,
                    fillWidth = false,
                    size = MuseButtonSize.Compact,
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.agent_reconnect_resume),
                    onClick = onResume,
                    variant = IosCapsuleButtonVariant.Secondary,
                    leadingIcon = MuseIcons.refresh,
                    fillWidth = false,
                    size = MuseButtonSize.Compact,
                )
            }
        }
    }
}

/** 7. 新跟进项确认卡：批准后才开始在后台巡检，避免模型自说自话建任务。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentWatcherConfirmCard(
    card: AgentBotUiState.MessageCardUi,
    onSelectOption: (String, AgentStreamCoordinator.ChoiceOption) -> Unit,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isResolved = card.status == BotCardStatus.RESOLVED
    val watcherDetails = remember(card.cardId, card.options) {
        val json = card.options.firstOrNull()?.prompt
            ?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val target = json?.get("computeTarget")?.jsonPrimitive?.contentOrNull.orEmpty()
        val interval = json?.get("intervalMinutes")?.jsonPrimitive?.intOrNull
        val script = json?.get("script")?.jsonPrimitive?.contentOrNull.orEmpty()
        val webhook = json?.get("webhookUrl")?.jsonPrimitive?.contentOrNull.orEmpty()
        val destination = runCatching { android.net.Uri.parse(webhook).host.orEmpty() }.getOrDefault("")
        listOf(target, interval?.toString().orEmpty(), script, destination)
    }
    var scriptExpanded by rememberSaveable(card.cardId) { mutableStateOf(false) }
    Surface(
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = MuseAlpha.statusTint),
        border = BorderStroke(
            width = MuseBorderWidth.hairline,
            color = MaterialTheme.colorScheme.primary.copy(alpha = MuseAlpha.handle),
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            WatcherConfirmHeader(card = card, onDismiss = onDismiss)
            WatcherConfirmDetails(
                card = card,
                watcherDetails = watcherDetails,
                scriptExpanded = scriptExpanded,
                onToggleScript = { scriptExpanded = !scriptExpanded },
            )
            WatcherConfirmOptions(
                card = card,
                isResolved = isResolved,
                onSelectOption = onSelectOption,
            )
        }
    }
}

@Composable
private fun WatcherConfirmHeader(card: AgentBotUiState.MessageCardUi, onDismiss: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        Icon(
            imageVector = MuseIcons.timer,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(MuseIconSizes.iconMedium),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = card.title.ifBlank { stringResource(R.string.agent_watcher_confirm_card_title) },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.agent_watcher_confirm_badge),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        MuseTactileButton(
            icon = MuseIcons.x,
            contentDescription = stringResource(R.string.agent_watcher_confirm_dismiss_cd),
            onClick = { onDismiss(card.cardId) },
            compact = true,
        )
    }
}

@Composable
private fun WatcherConfirmDetails(
    card: AgentBotUiState.MessageCardUi,
    watcherDetails: List<String>,
    scriptExpanded: Boolean,
    onToggleScript: () -> Unit,
) {
    if (card.subtitle.isNotBlank()) {
        Text(
            text = card.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val intervalMinutes = watcherDetails[1].toIntOrNull()
    Text(
        text = stringResource(
            R.string.agent_watcher_confirm_schedule,
            intervalMinutes ?: AgentWatcherEngine.DEFAULT_INTERVAL_MINUTES,
            stringResource(
                if (watcherDetails[0].equals("cloud", ignoreCase = true)) {
                    R.string.agent_follow_up_watcher_target_cloud
                } else {
                    R.string.agent_follow_up_watcher_target_local
                },
            ),
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val hasExternalWebhook = watcherDetails[0].equals("cloud", ignoreCase = true) && watcherDetails[3].isNotBlank()
    Text(
        text = if (hasExternalWebhook) {
            stringResource(R.string.agent_watcher_confirm_notification, watcherDetails[3])
        } else {
            stringResource(R.string.agent_watcher_confirm_notification_app)
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (watcherDetails[2].isNotBlank()) {
        MuseCapsuleButton(
            text = stringResource(
                if (scriptExpanded) R.string.agent_watcher_confirm_hide_script else R.string.agent_watcher_confirm_show_script,
            ),
            onClick = onToggleScript,
            variant = IosCapsuleButtonVariant.Text,
            fillWidth = false,
            size = MuseButtonSize.Compact,
        )
        if (scriptExpanded) {
            Text(
                text = watcherDetails[2],
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Text(
        text = stringResource(R.string.agent_watcher_confirm_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WatcherConfirmOptions(
    card: AgentBotUiState.MessageCardUi,
    isResolved: Boolean,
    onSelectOption: (String, AgentStreamCoordinator.ChoiceOption) -> Unit,
) {
    if (card.options.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.labelVerticalGap),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        card.options.forEachIndexed { index, option ->
            MuseCapsuleButton(
                text = option.label,
                onClick = { onSelectOption(card.cardId, option) },
                enabled = !isResolved,
                leadingIcon = if (index == 0) MuseIcons.check else null,
                variant = if (index == 0) IosCapsuleButtonVariant.Primary else IosCapsuleButtonVariant.Text,
                fillWidth = false,
                size = MuseButtonSize.Compact,
            )
        }
    }
}

@Composable
internal fun agentStatusLabel(status: String): String = when (status) {
    BotTaskStatus.QUEUED -> stringResource(R.string.agent_bot_status_queued)
    BotTaskStatus.RUNNING -> stringResource(R.string.agent_bot_status_running)
    BotTaskStatus.REVIEWING -> stringResource(R.string.agent_bot_status_reviewing)
    BotTaskStatus.WAITING_APPROVAL -> stringResource(R.string.agent_bot_status_waiting_approval)
    BotTaskStatus.WAITING_RESOURCE -> stringResource(R.string.agent_bot_status_waiting_resource)
    BotTaskStatus.CANCEL_REQUESTED -> stringResource(R.string.agent_bot_status_cancelling)
    BotTaskStatus.SUCCEEDED -> stringResource(R.string.common_done)
    BotTaskStatus.PARTIAL -> stringResource(R.string.agent_bot_status_partial)
    BotTaskStatus.FAILED -> stringResource(R.string.agent_bot_status_failed)
    BotTaskStatus.CANCELLED -> stringResource(R.string.agent_bot_status_cancelled)
    BotTaskStatus.NEEDS_USER -> stringResource(R.string.agent_bot_status_needs_user)
    BotTaskStatus.UNKNOWN -> stringResource(R.string.agent_bot_status_unknown)
    else -> stringResource(R.string.agent_bot_status_unknown)
}

@Composable
internal fun agentApprovalRiskLabel(risk: String): String = when (risk.uppercase()) {
    "LOW" -> stringResource(R.string.agent_screen_risk_low)
    "NORMAL" -> stringResource(R.string.agent_screen_risk_standard)
    "HIGH" -> stringResource(R.string.agent_screen_risk_high)
    "CRITICAL" -> stringResource(R.string.agent_screen_risk_critical)
    else -> stringResource(R.string.agent_screen_risk_unknown)
}

@Composable
internal fun agentApprovalToolLabel(toolName: String): String = when (toolName) {
    WorkspaceTool.NAME_WRITE -> stringResource(R.string.agent_screen_tool_write)
    WorkspaceTool.NAME_EDIT -> stringResource(R.string.agent_screen_tool_edit)
    WorkspaceTool.NAME_DELETE -> stringResource(R.string.agent_screen_tool_delete)
    WorkspaceTool.NAME_MKDIR -> stringResource(R.string.agent_screen_tool_create_folder)
    WorkspaceTool.NAME_MOVE -> stringResource(R.string.agent_screen_tool_move)
    WorkspaceTool.NAME_COPY -> stringResource(R.string.agent_screen_tool_copy)
    WorkspaceTool.NAME_LIST -> stringResource(R.string.agent_screen_tool_list)
    WorkspaceTool.NAME_READ -> stringResource(R.string.agent_screen_tool_read)
    WorkspaceTool.NAME_GREP -> stringResource(R.string.agent_screen_tool_search)
    WorkspaceTool.NAME_MOUNTS -> stringResource(R.string.agent_screen_tool_mounts)
    else -> toolName.replace('_', ' ')
}

@Composable
internal fun agentPromptLabel(prompt: AgentBotPrompt): String = when (prompt) {
    AgentBotPrompt.APPROVED_AWAITING_RETRY -> stringResource(R.string.agent_bot_prompt_approved_retry)
    AgentBotPrompt.NEEDS_DECISION -> stringResource(R.string.agent_bot_status_needs_user)
    AgentBotPrompt.VERIFY_RESULT -> stringResource(R.string.agent_bot_prompt_verify_result)
}
