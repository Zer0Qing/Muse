package io.zer0.muse.ui.agentbot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key as composeKey
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import io.zer0.muse.R
import io.zer0.muse.data.agentbot.AgentBotUiState
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MusePaddings

/**
 * 带操作的行列表区块。
 *
 * ## 为什么抽出来
 *
 * 中枢页面有四类这样的区块：任务看板、待审批、已放行、待处理候选。
 * 它们形状完全一样 —— 标题 + 若干行（主文案 / 副文案 / 尾部动作）。
 * 复制四遍的代价不是"代码多"，是**改一处要改四处**，而且很容易漏。
 *
 * ## 它不做什么
 *
 * 不决定行里显示什么、也不决定动作是什么。那些由调用方给。
 * 这里只统一间距、对齐、文字截断与动作位置 —— 也就是"看起来是一套东西"的那部分。
 */
@Composable
fun <T> AgentBotRowSection(
    title: String,
    rows: List<T>,
    key: (T) -> String,
    primary: (T) -> String,
    secondary: @Composable (T) -> String?,
    actionLabel: String,
    onAction: (T) -> Unit,
    modifier: Modifier = Modifier,
    /** 是否把动作用次要样式（用于"丢弃"这类破坏性较低的操作）。 */
    secondaryAction: Boolean = true,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
        )
        rows.forEach { row -> composeKey(key(row)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = primary(row),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    secondary(row)?.let { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                io.zer0.muse.ui.common.form.MuseCapsuleButton(
                    text = actionLabel,
                    onClick = { onAction(row) },
                    variant = if (secondaryAction) {
                        io.zer0.muse.ui.common.form.IosCapsuleButtonVariant.Text
                    } else {
                        io.zer0.muse.ui.common.form.IosCapsuleButtonVariant.Secondary
                    },
                    fillWidth = false,
                )
            }
        } }
    }
}

@Composable
fun AgentBotTaskRow(
    row: AgentBotUiState.TaskRow,
    status: String,
    note: String,
    onOpen: (String) -> Unit,
    onCancel: (String) -> Unit,
    onContinue: (String) -> Unit,
    onRetry: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by remember(row.taskId) { mutableStateOf<AgentBotUiState.TaskAction?>(null) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.goal,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (note.isNotBlank()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (AgentBotUiState.TaskAction.CONTINUE in row.actions) {
            MuseCapsuleButton(
                text = stringResource(R.string.agent_bot_continue),
                onClick = { confirming = AgentBotUiState.TaskAction.CONTINUE },
                leadingIcon = MuseIcons.play,
                fillWidth = false,
            )
        }
        if (AgentBotUiState.TaskAction.RETRY in row.actions) {
            MuseCapsuleButton(
                text = stringResource(R.string.agent_screen_retry_task),
                onClick = { confirming = AgentBotUiState.TaskAction.RETRY },
                leadingIcon = MuseIcons.refresh,
                variant = IosCapsuleButtonVariant.Secondary,
                fillWidth = false,
            )
        }
        if (AgentBotUiState.TaskAction.VERIFY in row.actions) {
            MuseCapsuleButton(
                text = stringResource(R.string.agent_screen_verify_result),
                onClick = { onOpen(row.taskId) },
                leadingIcon = MuseIcons.search,
                fillWidth = false,
            )
        }
        if (AgentBotUiState.TaskAction.CANCEL in row.actions) {
            MuseTactileButton(
                icon = MuseIcons.x,
                contentDescription = stringResource(R.string.common_cancel),
                onClick = { onCancel(row.taskId) },
            )
        }
        if (AgentBotUiState.TaskAction.VERIFY !in row.actions) {
            MuseTactileButton(
                icon = MuseIcons.chevronRight,
                contentDescription = stringResource(R.string.agent_screen_open_task),
                onClick = { onOpen(row.taskId) },
            )
        }
    }

    confirming?.let { action ->
        val continuing = action == AgentBotUiState.TaskAction.CONTINUE
        io.zer0.muse.ui.common.feedback.MuseDialog(
            onDismissRequest = { confirming = null },
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
                if (continuing) R.string.agent_bot_continue_confirm
                else R.string.agent_screen_retry_task,
            ),
            onConfirm = {
                confirming = null
                if (continuing) onContinue(row.taskId) else onRetry(row.taskId)
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { confirming = null },
        )
    }
}
