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
import androidx.compose.foundation.layout.Spacer
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
import io.zer0.muse.data.agentbot.BotCardStatus
import io.zer0.muse.data.agentbot.BotTaskStatus
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { stepsExpanded = !stepsExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Icon(
                    imageVector = if (isSucceeded) MuseIcons.circleCheck else MuseIcons.rocket,
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

            // 实时步骤时间线
            AnimatedVisibility(visible = stepsExpanded && row.steps.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.labelVerticalGap),
                    modifier = Modifier.padding(top = MusePaddings.tightGap),
                ) {
                    row.steps.takeLast(6).forEach { step ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                        ) {
                            val stepTint = when (step.status) {
                                StepExecutionStatus.SUCCEEDED -> MaterialTheme.statusColors.success
                                StepExecutionStatus.FAILED -> MaterialTheme.statusColors.error
                                else -> MaterialTheme.colorScheme.primary
                            }
                            Icon(
                                imageVector = when (step.status) {
                                    StepExecutionStatus.SUCCEEDED -> MuseIcons.check
                                    StepExecutionStatus.FAILED -> MuseIcons.alertCircle
                                    else -> MuseIcons.activity
                                },
                                contentDescription = null,
                                tint = stepTint,
                                modifier = Modifier
                                    .padding(top = MusePaddings.tinyGap)
                                    .size(MuseIconSizes.iconTiny),
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

            // 活体插话内联输入行
            AnimatedVisibility(visible = steeringOpen && isActive) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    MuseTextField(
                        value = steerDraft,
                        onValueChange = { steerDraft = it },
                        placeholder = { Text(stringResource(R.string.agent_hub_mission_steer_hint)) },
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                    )
                    MuseCapsuleButton(
                        text = stringResource(R.string.agent_hub_mission_steer_send),
                        onClick = {
                            val content = steerDraft.trim()
                            if (content.isNotBlank()) {
                                onSteer(row.taskId, content)
                                steerDraft = ""
                                steeringOpen = false
                            }
                        },
                        enabled = steerDraft.isNotBlank(),
                        fillWidth = false,
                        size = MuseButtonSize.Compact,
                    )
                }
            }

            if (savedToast) {
                Text(
                    text = stringResource(R.string.agent_hub_mission_saved_routine),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.statusColors.success,
                )
            }

            // 动作胶囊行
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
                        onClick = { steeringOpen = !steeringOpen },
                        leadingIcon = MuseIcons.edit,
                        variant = IosCapsuleButtonVariant.Secondary,
                        fillWidth = false,
                        size = MuseButtonSize.Compact,
                    )
                }
                if (AgentBotUiState.TaskAction.CONTINUE in row.actions) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.agent_bot_continue),
                        onClick = { confirmingAction = AgentBotUiState.TaskAction.CONTINUE },
                        leadingIcon = MuseIcons.play,
                        fillWidth = false,
                        size = MuseButtonSize.Compact,
                    )
                } else if (row.status == BotTaskStatus.NEEDS_USER) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.agent_task_decision_action),
                        onClick = { onOpenDetails(row.taskId) },
                        leadingIcon = MuseIcons.edit,
                        fillWidth = false,
                        size = MuseButtonSize.Compact,
                    )
                }
                if (AgentBotUiState.TaskAction.RETRY in row.actions) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.agent_screen_retry_task),
                        onClick = { confirmingAction = AgentBotUiState.TaskAction.RETRY },
                        leadingIcon = MuseIcons.refresh,
                        variant = IosCapsuleButtonVariant.Secondary,
                        fillWidth = false,
                        size = MuseButtonSize.Compact,
                    )
                }
                if (AgentBotUiState.TaskAction.VERIFY in row.actions) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.agent_screen_verify_result),
                        onClick = { onOpenDetails(row.taskId) },
                        leadingIcon = MuseIcons.search,
                        fillWidth = false,
                        size = MuseButtonSize.Compact,
                    )
                }
                if (isSucceeded && !savedToast) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.agent_hub_mission_save_routine),
                        onClick = {
                            onSaveAsRoutine(row.taskId)
                            savedToast = true
                        },
                        leadingIcon = MuseIcons.bookmark,
                        variant = IosCapsuleButtonVariant.Secondary,
                        fillWidth = false,
                        size = MuseButtonSize.Compact,
                    )
                }
                if (AgentBotUiState.TaskAction.CANCEL in row.actions) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.common_cancel),
                        onClick = { onCancel(row.taskId) },
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                        size = MuseButtonSize.Compact,
                    )
                }
            }
        }
    }

    confirmingAction?.let { action ->
        val continuing = action == AgentBotUiState.TaskAction.CONTINUE
        MuseDialog(
            onDismissRequest = { confirmingAction = null },
            title = stringResource(
                if (continuing) R.string.agent_bot_prompt_approved_retry
                else R.string.agent_screen_retry_task_confirm,
            ),
            content = {
                Text(
                    text = stringResource(
                        if (continuing) R.string.agent_bot_continue_confirm
                        else R.string.agent_screen_retry_task_confirm_body,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(
                if (continuing) R.string.agent_bot_continue
                else R.string.agent_screen_retry_task,
            ),
            onConfirm = {
                confirmingAction = null
                if (continuing) onContinue(row.taskId) else onRetry(row.taskId)
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { confirmingAction = null },
        )
    }
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
                Surface(
                    shape = MuseShapes.pill,
                    color = riskContainer,
                ) {
                    Text(
                        text = agentApprovalRiskLabel(row.risk),
                        style = MaterialTheme.typography.labelSmall,
                        color = riskColor,
                        modifier = Modifier.padding(
                            horizontal = MusePaddings.contentGap,
                            vertical = MusePaddings.tinyGap,
                        ),
                    )
                }
                MuseTactileButton(
                    icon = MuseIcons.chevronRight,
                    contentDescription = stringResource(R.string.agent_screen_open_task),
                    onClick = { onOpenTask(row.taskId) },
                    compact = true,
                )
            }
            Text(
                text = stringResource(
                    R.string.agent_approval_intent_summary,
                    agentApprovalToolLabel(row.toolName),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (row.argumentsPreview.isNotBlank()) {
                Surface(
                    onClick = { argsExpanded = !argsExpanded },
                    shape = MuseShapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MuseAlpha.subtleBorder),
                ) {
                    Text(
                        text = if (argsExpanded) {
                            stringResource(R.string.agent_screen_approval_arguments, row.argumentsPreview)
                        } else {
                            stringResource(R.string.agent_approval_view_details)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (argsExpanded) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = if (argsExpanded) 24 else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.contentGap),
                    )
                }
            }
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
            Row(horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
                MuseCapsuleButton(
                    text = stringResource(R.string.agent_bot_approve),
                    onClick = { onApprove(row) },
                    leadingIcon = MuseIcons.check,
                    fillWidth = false,
                    size = MuseButtonSize.Compact,
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.agent_bot_deny),
                    onClick = { onDeny(row) },
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
