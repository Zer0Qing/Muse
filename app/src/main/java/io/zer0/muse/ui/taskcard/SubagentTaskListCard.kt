package io.zer0.muse.ui.taskcard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.tools.DeferredResultStore
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.data.subagent.SubagentSessionStore
import io.zer0.muse.data.subagent.SubagentThreadStore
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.tiny
import org.koin.compose.koinInject

/**
 * v1.202: 后台子 Agent 任务列表卡片。
 *
 * 展示当前会话中活跃的后台子 agent 线程 + 待处理(PENDING)任务,
 * 让用户感知非阻塞委派的进行中工作,并提供取消入口。
 *
 * 数据来源:
 *  - [activeThreads]: SubagentThreadStore 中状态为 ACTIVE 的线程(每个线程对应一个可续接的子 agent 会话)
 *  - [pendingTasks]: DeferredResultStore 中状态为 PENDING 的任务(尚未 resolve/fail/abort)
 *
 * 两者通常一一对应(注册线程时同时 defer 一个任务),但分别展示以应对解耦场景
 * (例如线程已结束但任务结果未消费,或任务已 abort 但线程未关闭)。
 *
 * UI 行为:
 *  - 默认折叠(避免占用过多视觉空间),点击标题行展开/折叠
 *  - 每个任务一行:状态图标(转圈=运行中 / ✓=完成 / ✗=失败)+ label/taskSummary + threadId(截断)+ 取消按钮
 *  - 空列表(无线程且无待处理任务)时不渲染卡片容器(返回空)
 *
 * @param activeThreads 活跃子 agent 线程列表
 * @param pendingTasks 待处理(PENDING)的延迟任务列表
 * @param onCancel 取消任务的回调,参数为 taskId
 * @param modifier 外部修饰
 */
@Composable
fun SubagentTaskListCard(
    activeThreads: List<SubagentThreadStore.ThreadEntry>,
    pendingTasks: List<DeferredResultStore.DeferredTask>,
    onCancel: (taskId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 空列表时不渲染(避免无意义的空卡片噪声)
    if (activeThreads.isEmpty() && pendingTasks.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    var selectedThread by remember { mutableStateOf<Pair<String, String>?>(null) }
    val sessionStore: SubagentSessionStore = koinInject()
    // 任务总数:线程数与待处理任务数的较大值(两者通常一致,取较大值兜底解耦场景)
    val totalCount = maxOf(activeThreads.size, pendingTasks.size)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            // ── 标题行:图标 + 标题 + 任务总数 + 展开/折叠图标 ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.iconPadding),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
            ) {
                val assistantRepository: AssistantRepository = koinInject()
                val allAssistants by assistantRepository.observeAll.collectAsStateWithLifecycle(initialValue = emptyList())
                val assistantById = remember(allAssistants) { allAssistants.associateBy { it.id } }
                val firstAssistantId = activeThreads.firstOrNull()?.assistantId
                    ?: pendingTasks.firstNotNullOfOrNull { task ->
                        activeThreads.firstOrNull { it.threadId == task.threadId }?.assistantId
                    }
                val headerAssistant = firstAssistantId?.let { assistantById[it] }
                if (headerAssistant != null) {
                    AssistantAvatar(assistant = headerAssistant, avatarSize = 24.dp)
                } else {
                    Icon(
                        imageVector = Icons.Default.SmartToy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.subagent_task_list_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = MuseShapes.tiny,
                ) {
                    Text(
                        text = stringResource(R.string.subagent_task_list_count, totalCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── 展开态:任务明细列表 ──
            AnimatedVisibility(
                visible = expanded,
                enter = MuseMotion.expandFadeEnter(),
                exit = MuseMotion.expandFadeExit(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // 渲染待处理任务(PENDING) —— 这些是尚未完成的延迟任务,显示进度 + 取消按钮
                    pendingTasks.forEach { task ->
                        SubagentTaskRow(
                            label = task.label,
                            summary = task.taskSummary,
                            threadId = task.threadId,
                            status = task.status,
                            taskId = task.taskId,
                            assistantId = activeThreads.firstOrNull { it.threadId == task.threadId }?.assistantId,
                            onCancel = onCancel,
                            onClick = { task.threadId?.let { tid -> selectedThread = tid to (activeThreads.firstOrNull { it.threadId == tid }?.assistantId ?: "") } },
                        )
                    }

                    // 渲染活跃线程中没有对应 PENDING 任务的(理论上少见,兜底展示)
                    // 通过 taskId 匹配过滤掉已展示的线程
                    val coveredThreadIds = pendingTasks.mapNotNull { it.threadId }.toSet()
                    activeThreads
                        .filter { it.threadId !in coveredThreadIds }
                        .forEach { thread ->
                            SubagentThreadRow(
                                threadId = thread.threadId,
                                assistantId = thread.assistantId,
                                onClick = { selectedThread = thread.threadId to thread.assistantId },
                            )
                        }

                    if (pendingTasks.isEmpty() && activeThreads.isEmpty()) {
                        Text(
                            text = stringResource(R.string.subagent_task_list_empty),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }

    selectedThread?.let { (threadId, assistantId) ->
        SubagentTaskDetailSheet(
            threadId = threadId,
            assistantId = assistantId,
            onDismiss = { selectedThread = null },
        )
    }
}

@Composable
private fun SubagentTaskDetailSheet(
    threadId: String,
    assistantId: String,
    onDismiss: () -> Unit,
) {
    val sessionStore: SubagentSessionStore = koinInject()
    val assistantRepository: AssistantRepository = koinInject()
    val assistants by assistantRepository.observeAll.collectAsStateWithLifecycle(initialValue = emptyList())
    val assistant = assistants.firstOrNull { it.id == assistantId }
    var messages by remember(threadId) { mutableStateOf<List<io.zer0.ai.core.UIMessage>>(emptyList()) }
    var loading by remember(threadId) { mutableStateOf(true) }

    LaunchedEffect(threadId) {
        loading = true
        messages = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { sessionStore.load(threadId, maxContextTokens = 200_000) }.getOrDefault(emptyList())
        }
        loading = false
    }

    io.zer0.muse.ui.common.form.MuseBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MusePaddings.iconPadding)) {
                if (assistant != null) {
                    AssistantAvatar(assistant = assistant, avatarSize = 40.dp)
                } else {
                    Icon(
                        imageVector = Icons.Default.SmartToy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Column {
                    Text(
                        text = assistant?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.subagent_task_list_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "thread: ${threadId.take(16)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.subagent_task_detail_count, messages.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when {
                loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MusePaddings.iconPadding)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.subagent_task_detail_loading), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                messages.isEmpty() -> {
                    Text(stringResource(R.string.subagent_task_detail_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    messages.forEach { msg ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MuseShapes.medium,
                            color = if (msg.role == io.zer0.ai.core.MessageRole.USER) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            tonalElevation = 1.dp,
                        ) {
                            Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                                Text(
                                    text = if (msg.role == io.zer0.ai.core.MessageRole.USER) stringResource(R.string.subagent_task_detail_user) else stringResource(R.string.subagent_task_detail_assistant),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = msg.content.ifBlank { "(…)" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
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
 * 单个延迟任务行:状态图标 + label/summary + threadId(截断)+ 取消按钮。
 *
 * 状态映射:
 *  - PENDING / RESOLVED → 视为"运行中"显示转圈(RESOLVED 已回灌但尚未清理的瞬间状态)
 *  - FAILED → ✗ 红色
 *  - ABORTED → 视为已取消,不显示取消按钮(已不可再取消)
 */
@Composable
private fun SubagentTaskRow(
    label: String?,
    summary: String,
    threadId: String?,
    status: DeferredResultStore.TaskStatus,
    taskId: String,
    assistantId: String? = null,
    onCancel: (taskId: String) -> Unit,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 2.dp),
    ) {
        // 助手头像（可用时）；否则显示状态图标
        if (assistantId != null) {
            val assistantRepository: AssistantRepository = koinInject()
            val assistants by assistantRepository.observeAll.collectAsStateWithLifecycle(initialValue = emptyList())
            val assistant = assistants.firstOrNull { it.id == assistantId }
            if (assistant != null) {
                AssistantAvatar(assistant = assistant, avatarSize = 24.dp)
            } else {
                StatusIcon(status = status)
            }
        } else {
            StatusIcon(status = status)
        }

        // label + summary
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val displayLabel = label?.takeIf { it.isNotBlank() }
                ?: threadId?.take(8)
                ?: taskId.take(8)
            Text(
                text = displayLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!threadId.isNullOrBlank()) {
                Text(
                    text = "thread: ${threadId.take(12)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 取消按钮:仅 PENDING 状态可取消(已 resolve/fail/abort 的不再显示)
        if (status == DeferredResultStore.TaskStatus.PENDING) {
            IconButton(
                onClick = { onCancel(taskId) },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.subagent_task_cancel),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * 兜底:活跃线程但无对应 PENDING 任务时,仅显示线程基本信息。
 * 这种情况理论上少见(任务与线程通常 1:1),仅作展示兜底。
 */
@Composable
private fun SubagentThreadRow(
    threadId: String,
    assistantId: String,
    onClick: (() -> Unit)? = null,
) {
    val assistantRepository: AssistantRepository = koinInject()
    val assistants by assistantRepository.observeAll.collectAsStateWithLifecycle(initialValue = emptyList())
    val assistant = assistants.firstOrNull { it.id == assistantId }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 2.dp),
    ) {
        if (assistant != null) {
            AssistantAvatar(assistant = assistant, avatarSize = 24.dp)
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = assistant?.name?.takeIf { it.isNotBlank() } ?: "assistant: ${assistantId.take(12)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "thread: ${threadId.take(12)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 状态图标:
 *  - PENDING → 转圈(运行中)
 *  - RESOLVED → ✓ 绿色(已完成)
 *  - FAILED → ✗ 红色
 *  - ABORTED → ✗ 灰色(已取消)
 */
@Composable
private fun StatusIcon(status: DeferredResultStore.TaskStatus) {
    when (status) {
        DeferredResultStore.TaskStatus.PENDING -> {
            // 运行中:用脉冲圆点(与 DelegationChainCard 的 RUNNING 状态视觉一致)
            val alpha = if (MuseMotion.isReducedMotion()) {
                0.65f
            } else {
                val transition = rememberInfiniteTransition(label = "subagentPulse")
                val animatedAlpha by transition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = MuseMotion.tween(MuseAnimation.LOOP_SLOW_MS),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "subagentPulseAlpha",
                )
                animatedAlpha
            }
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
            )
        }
        DeferredResultStore.TaskStatus.RESOLVED -> {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = stringResource(R.string.subagent_task_completed),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
        }
        DeferredResultStore.TaskStatus.FAILED -> {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = stringResource(R.string.subagent_task_failed),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(14.dp),
            )
        }
        DeferredResultStore.TaskStatus.ABORTED -> {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.subagent_task_cancel),
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
