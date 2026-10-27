package io.zer0.muse.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.ToolCallRecord
import io.zer0.muse.ui.common.animation.MuseInkGlyph
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.common.form.MuseBottomSheet
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer

/**
 * 输入栏上方的过程扁条。
 *
 * 为什么要有它：一轮任务里工具卡在消息流中会占掉大半屏，可它折叠时其实只回答
 * 一个问题——「现在在干什么」。这件事用一个扁条就能说清，而且不挤压正在读的内容。
 *
 * 分工：
 *  - 扁条：只说「此刻」，随任务结束消失
 *  - 消息流里的过程卡：留作事后回看
 *
 * 点击扁条弹出本轮步骤明细，而不是就地展开 —— 就地展开会把输入栏顶上去。
 */
@Composable
internal fun ActiveProcessBar(
    records: List<ToolCallRecord>,
    progressMessage: String?,
    subagent: SubagentActivity = SubagentActivity(),
    onExpand: () -> Unit,
) {
    if (records.isEmpty() && progressMessage.isNullOrBlank() && subagent.isEmpty) return
    val scheme = MaterialTheme.colorScheme
    val latest = records.lastOrNull()
    // 子任务排第一：它是唯一会跨轮次持续存在的执行态，其余都是「这一轮」的事。
    // 其次说「此刻正在跑的那一件事」，最后才退回整批的数量描述。
    val running = records.lastOrNull { it.finishedAt == null } ?: latest
    val statusText = when {
        subagent.count > 0 -> stringResource(R.string.chat_process_subagent_running, subagent.count)
        !progressMessage.isNullOrBlank() -> progressMessage
        running != null -> describeProcess(running)
        else -> stringResource(R.string.chat_process_thinking_now)
    }
    val failed = records.count { !it.isSuccess }

    Surface(
        color = scheme.surfaceVariant.copy(alpha = 0.72f),
        shape = MuseShapes.medium,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            // 上下只留 2dp：多条扁条同时出现时，间距由列表统一给，
            // 每条各自撑开会在两条之间留出一条明显空档。
            .padding(horizontal = MusePaddings.screen, vertical = 2.dp)
            .clickable(onClick = onExpand),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MuseInkGlyph(
                modifier = Modifier.size(MuseIconSizes.iconSmall),
                animate = true,
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (failed > 0) {
                Icon(
                    imageVector = MuseIcons.alertTriangle,
                    contentDescription = null,
                    tint = scheme.error,
                    modifier = Modifier.size(MuseIconSizes.iconTiny),
                )
            }
            if (records.isNotEmpty() && subagent.isEmpty) {
                Text(
                    text = stringResource(R.string.chat_process_tool_count, records.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.outline,
                    maxLines = 1,
                )
            }
            Icon(
                imageVector = MuseIcons.chevronUp,
                contentDescription = null,
                tint = scheme.outline,
                modifier = Modifier.size(MuseIconSizes.iconTiny),
            )
        }
    }
}

/**
 * 本轮步骤明细。
 *
 * 用 sheet 而不是就地展开：就地展开会把输入栏顶上去，而用户点这个条的时刻
 * 往往正在打字。
 */
@Composable
internal fun ActiveProcessSheet(
    records: List<ToolCallRecord>,
    subagent: SubagentActivity = SubagentActivity(),
    onCancelSubagent: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    MuseBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            Text(
                text = stringResource(R.string.chat_process_sheet_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(8.dp))
            if (!subagent.isEmpty) {
                Text(
                    text = stringResource(R.string.chat_process_subagent_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                subagent.threads.forEach { thread ->
                    SubagentRow(label = thread.assistantId, onCancel = { onCancelSubagent(thread.threadId) })
                }
                subagent.pending
                    .filter { task -> subagent.threads.none { it.threadId == task.taskId } }
                    .forEach { task -> SubagentRow(label = task.taskId, onCancel = { onCancelSubagent(task.taskId) }) }
                Spacer(Modifier.size(8.dp))
            }
            if (records.isEmpty()) {
                Text(
                    text = stringResource(R.string.chat_process_sheet_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(records) { index, record ->
                        ProcessRecordRow(record = record, index = index + 1)
                    }
                }
            }
        }
    }
}

/** 单条工具记录：序号 + 名称 + 耗时 + 结果摘要。 */
@Composable
private fun ProcessRecordRow(record: ToolCallRecord, index: Int) {
    val scheme = MaterialTheme.colorScheme
    val duration = record.finishedAt?.let { finish ->
        record.startedAt?.let { start -> finish - start }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                // 同一条记录：成功用动作类型图标，失败用警告三角（状态压过类型）。
                imageVector = if (record.isSuccess) processIconFor(record.toolName) else MuseIcons.alertTriangle,
                contentDescription = null,
                tint = if (record.isSuccess) scheme.primary else scheme.error,
                modifier = Modifier.size(MuseIconSizes.iconTiny),
            )
            Text(
                text = "$index. ${record.toolName}",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (duration != null && duration > 0) {
                Text(
                    text = "${duration}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.outline,
                    maxLines = 1,
                )
            }
        }
        val detail = record.result.trim().ifBlank { record.arguments.trim() }
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.outline,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 18.dp, top = 1.dp),
            )
        }
    }
}

/** 把工具名翻成人话：委派 / 运行命令 / 普通工具。 */
@Composable
private fun describeProcess(record: ToolCallRecord): String {
    val name = record.toolName
    val target = record.arguments.trim()
        .takeIf { it.isNotBlank() }
        ?.let { extractDelegateTarget(it) }
    return when {
        isDelegateTool(name) && target != null -> stringResource(R.string.chat_process_delegating, target)
        isDelegateTool(name) -> stringResource(R.string.chat_process_delegating, name)
        isCommandTool(name) -> stringResource(R.string.chat_process_running_command)
        else -> stringResource(R.string.tool_running, name)
    }
}

/** 子 Agent 委派类工具：名字里带这些片段的都算。 */
private fun isDelegateTool(name: String): Boolean {
    val n = name.lowercase()
    return n.contains("subagent") || n.contains("delegate") || n.contains("agent_discuss") || n.contains("ask_agent")
}

/** 命令执行类工具。 */
private fun isCommandTool(name: String): Boolean {
    val n = name.lowercase()
    return n.contains("exec_command") || n.contains("run_command") || n.contains("termux") || n == "shell"
}

/**
 * 从委派参数里抠出助手名。
 *
 * 只做浅层提取：参数可能是 JSON，字典序不稳定，拿不到就返回 null 让调用方退回工具名。
 */
private fun extractDelegateTarget(arguments: String): String? =
    listOf("assistant_name", "assistant", "agent", "name", "target").firstNotNullOfOrNull { key ->
        val marker = "\"$key\""
        val at = arguments.indexOf(marker)
        val colon = if (at < 0) -1 else arguments.indexOf(':', at + marker.length)
        val raw = if (colon < 0) "" else arguments.substring(colon + 1).trimStart()
        val end = if (raw.startsWith('"')) raw.indexOf('"', 1) else -1
        if (end <= 1) {
            null
        } else {
            raw.substring(1, end).trim().takeIf { it.isNotBlank() }
        }
    }

/** 一条后台子任务：谁在跑 + 取消。 */
@Composable
private fun SubagentRow(label: String, onCancel: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MuseInkGlyph(modifier = Modifier.size(MuseIconSizes.iconTiny), animate = true)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.subagent_task_cancel),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.error,
            modifier = Modifier.clickable(onClick = onCancel),
        )
    }
}
