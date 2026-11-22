package io.zer0.muse.ui.agentbot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import kotlinx.coroutines.delay
import io.zer0.muse.data.agentbot.AgentBotError
import io.zer0.muse.data.agentbot.AgentBotPrompt
import io.zer0.muse.data.agentbot.AgentBotUiState
import io.zer0.muse.data.agentbot.BotTaskStatus
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.chat.ChatScreen

/**
 * Agent 中枢页。
 *
 * ## 与 ChatScreen 的关系
 *
 * 刻意不复用 ChatScreen。那个文件已经 3814 行、70+ 参数，再塞第四个形态
 * 只会让它的过载更严重（审查报告里 `-dontoptimize` 的根因就是它）。
 * 这里共用底层的输入/按钮/表面组件，但不共享那个巨型 Composable。
 *
 * ## 界面只呈现什么
 *
 * 接单承诺、必要进展、需要用户决定的项、审核后的结果。
 * 子 Agent 名称、工具流水、内部事件一律不出现 —— 隐藏的是执行细节，
 * 不是费用、风险或用户该做的决定。
 */
@Composable
fun AgentBotScreen(
    viewModel: AgentBotViewModel,
    modifier: Modifier = Modifier,
) {
    val composer by viewModel.composer.collectAsStateWithLifecycle()
    val transcript by viewModel.transcript.collectAsStateWithLifecycle()
    val board by viewModel.taskBoard.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    // 页面可见期间驱动中枢：领任务、消费事件、刷新看板。
    //
    // 真机验收抓到的缺陷：调度器的后台 driveJob 一直在跑（任务确实在执行），
    // 但 tickScheduler/refreshBoard 这条"UI 侧取状态"的路没人调用 ——
    // 用户看到的看板永远停在任务创建那一刻的快照，"排队中"三个分钟不变。
    // 状态机拒绝 EXECUTE_DONE 时日志里看得清清楚楚，界面上却什么都看不见。
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.tickScheduler()
            viewModel.runTurnOnce()
            delay(3_000)
        }
    }

    MusePageScaffold(modifier = modifier, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = MusePaddings.pageMargin),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            // 任务看板放最上面：用户打开这个页面最先想知道"它在做什么"。
            if (board.tasks.isNotEmpty()) {
                TaskBoardSection(
                    tasks = board.tasks,
                    onCancel = viewModel::cancelTask,
                    onContinue = viewModel::continueTask,
                )
            }
            if (board.approvals.isNotEmpty()) {
                ApprovalSection(
                    rows = board.approvals,
                    // 必须回传卡片上的 hash 与版本：服务端拿它们判断参数有没有漂移。
                // 传空串会让每一次批准都被判 INVALIDATED —— 用户看到"参数已变更"，
                // 任务永久停在 WAITING_APPROVAL。
                onApprove = { row ->
                    viewModel.approve(row.approvalId, row.argumentsHash, row.resourceVersion)
                },
                    onDeny = { row -> viewModel.deny(row.approvalId) },
                )
            }
            // V26 最后一条：用户必须能看见自己给过哪些放行，并逐条收回。
            val allowed by viewModel.allowedTools.collectAsStateWithLifecycle()
            if (allowed.isNotEmpty()) {
                AllowanceSection(tools = allowed.toList(), onRevoke = viewModel::revokeAllowance)
            }
            // V04：冲突的候选必须可见。用户需要知道"这份结果本来想改成什么"，
            // 而不是只被告知"冲突了"。
            val candidates by viewModel.pendingCandidates.collectAsStateWithLifecycle()
            if (candidates.isNotEmpty()) {
                CandidateSection(
                    rows = candidates,
                    onDiscard = viewModel::discardCandidate,
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                items(transcript, key = { it.messageId }) { message ->
                    TranscriptRow(message)
                }
            }

            // 发送错误不用私有文本兜底：给明确提示与重试入口。
            // 文案在这里解析（stringResource），状态层只给枚举 —— 7 语言才不会漏翻。
            busy.error?.let { error ->
                Text(
                    text = errorLabel(error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                MuseTextField(
                    value = composer.text,
                    onValueChange = viewModel::onComposerTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.agent_bot_input_hint)) },
                )
                // 正在发送时，发送按钮换成"停止"。
                //
                // 清单 G1 要求"停止回复"与"取消任务"是两个不同操作：
                // 前者只让主 Bot 闭嘴，后者放弃已交代的活。
                //
                // `stopReply()` 早就实现了（V11），但**界面上一直没有入口** ——
                // 用户想让它别说了，只能去取消任务，结果把正在做的事也丢了。
                if (busy.sending) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.agent_bot_stop_reply),
                        onClick = viewModel::stopReply,
                        fillWidth = false,
                    )
                } else {
                    MuseCapsuleButton(
                        text = stringResource(R.string.action_send),
                        onClick = viewModel::send,
                        enabled = composer.text.isNotBlank(),
                        fillWidth = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskBoardSection(
    tasks: List<AgentBotUiState.TaskRow>,
    onCancel: (String) -> Unit,
    onContinue: (String) -> Unit,
) {
    // 这一块没有复用 AgentBotRowSection：任务行需要两种动作
    // （NEEDS_USER 给"继续"，其余给"取消"），而共享组件只支持一个。
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
        Text(
            text = stringResource(R.string.agent_bot_tasks_title),
            style = MaterialTheme.typography.titleSmall,
        )
        tasks.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.goal,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        // 用户看到的是人话，不是 QUEUED / WAITING_APPROVAL。
                        text = row.needsUser?.let { promptLabel(it) } ?: statusLabel(row.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 按行上声明的操作集渲染，不靠 status 猜。
                // NEEDS_USER 有两种来源，操作完全不同 —— 猜错就会在
                // "需要你处理"的行上放一个"继续重跑"按钮。
                if (AgentBotUiState.TaskAction.CONTINUE in row.actions) {
                    var confirming by remember { mutableStateOf(false) }
                    if (confirming) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MuseCapsuleButton(
                                text = stringResource(R.string.agent_bot_continue_confirm),
                                onClick = {
                                    confirming = false
                                    onContinue(row.taskId)
                                },
                                fillWidth = false,
                            )
                            MuseCapsuleButton(
                                text = stringResource(R.string.agent_bot_continue_cancel),
                                onClick = { confirming = false },
                                variant = io.zer0.muse.ui.common.form.IosCapsuleButtonVariant.Text,
                                fillWidth = false,
                            )
                        }
                    } else {
                        MuseCapsuleButton(
                            text = stringResource(R.string.agent_bot_continue),
                            onClick = { confirming = true },
                            fillWidth = false,
                        )
                    }
                } else if (AgentBotUiState.TaskAction.CANCEL in row.actions) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.common_cancel),
                        onClick = { onCancel(row.taskId) },
                        fillWidth = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun ApprovalSection(
    rows: List<AgentBotUiState.ApprovalRow>,
    onApprove: (AgentBotUiState.ApprovalRow) -> Unit,
    onDeny: (AgentBotUiState.ApprovalRow) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
        Text(
            text = stringResource(R.string.agent_bot_approvals_title),
            style = MaterialTheme.typography.titleSmall,
        )
        rows.forEach { row ->
            Column {
                Text(
                    text = row.toolName,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.agent_bot_approve),
                        onClick = { onApprove(row) },
                        fillWidth = false,
                    )
                    MuseCapsuleButton(
                        text = stringResource(R.string.agent_bot_deny),
                        onClick = { onDeny(row) },
                        variant = io.zer0.muse.ui.common.form.IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                    )
                }
            }
        }
    }
}

/**
 * 已放行列表。
 *
 * 只列工具名与收回按钮 —— 这里不需要解释工具做什么（那是审批卡的事），
 * 需要的是让用户随时能撤掉自己给过的授权。
 */
@Composable
private fun AllowanceSection(
    tools: List<String>,
    onRevoke: (String) -> Unit,
) {
    AgentBotRowSection(
        title = stringResource(R.string.agent_bot_allowed_title),
        rows = tools,
        key = { it },
        primary = { it },
        secondary = { null },
        actionLabel = stringResource(R.string.agent_bot_revoke),
        onAction = onRevoke,
    )
}

/**
 * 待处理候选。
 *
 * 只列目标与版本 —— 这里不需要预览内容（那是点进去之后的事），
 * 需要的是让用户知道"有这么一份结果没落地"，并能丢弃它。
 */
@Composable
private fun CandidateSection(
    rows: List<AgentBotUiState.CandidateRow>,
    onDiscard: (String) -> Unit,
) {
    AgentBotRowSection(
        title = stringResource(R.string.agent_bot_candidates_title),
        rows = rows,
        key = { it.stagedId },
        primary = { it.targetRef },
        secondary = { stringResource(R.string.agent_bot_candidate_hint) },
        actionLabel = stringResource(R.string.agent_bot_discard),
        onAction = { row -> onDiscard(row.stagedId) },
    )
}

@Composable
private fun TranscriptRow(message: AgentBotUiState.PublishedMessage) {
    Text(
        text = message.text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 状态 → 用户能懂的词。内部状态名不出现在界面上。 */
@Composable
private fun statusLabel(status: String): String = when (status) {
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
    // 未知状态不要回落到原始字符串：那会把 QUEUED_V2 之类的内部名直接摆到用户面前。
    else -> stringResource(R.string.agent_bot_status_unknown)
}

/** 错误枚举 → 用户能看懂的话。 */
@Composable
private fun errorLabel(error: AgentBotError): String = when (error) {
    AgentBotError.NO_SESSION -> stringResource(R.string.agent_bot_error_no_session)
    AgentBotError.SAVE_FAILED -> stringResource(R.string.agent_bot_error_save_failed)
    AgentBotError.ALREADY_DECIDED -> stringResource(R.string.agent_bot_error_already_decided)
    AgentBotError.APPROVAL_EXPIRED -> stringResource(R.string.agent_bot_error_approval_expired)
    AgentBotError.APPROVAL_STALE -> stringResource(R.string.agent_bot_error_approval_stale)
    AgentBotError.MISSING_BOT_SAY -> stringResource(R.string.agent_bot_error_missing_say)
    // 用户看到的是"换个 Provider 试试"，不是"工具调用失败"这种内部词。
    AgentBotError.PROVIDER_TOOL_BLIND -> stringResource(R.string.agent_bot_error_tool_blind)
}

/** 需要用户做什么 → 一句话。 */
@Composable
private fun promptLabel(prompt: AgentBotPrompt): String = when (prompt) {
    AgentBotPrompt.APPROVED_AWAITING_RETRY -> stringResource(R.string.agent_bot_prompt_approved_retry)
    AgentBotPrompt.NEEDS_DECISION -> stringResource(R.string.agent_bot_status_needs_user)
    // 这里要回答的是「我该做什么」，不是「现在什么状态」——所以不复用状态文案。
    AgentBotPrompt.VERIFY_RESULT -> stringResource(R.string.agent_bot_prompt_verify_result)
}
