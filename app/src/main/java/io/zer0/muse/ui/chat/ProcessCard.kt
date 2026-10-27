package io.zer0.muse.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.common.animation.MuseInkGlyph
import io.zer0.muse.ui.common.animation.MuseThinkingGlyphSize
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.taskcard.TaskCardData
import io.zer0.muse.ui.taskcard.TaskStep
import io.zer0.muse.ui.taskcard.TaskStepStatus
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.delay

/**
 * 过程卡：正在做什么的一个整体视图。
 *
 * 分两层，与「点开才铺开细节」的阅读节奏一致：
 *
 *  **一层（摘要，常显）**
 *   - 一枚正在变形的墨形（执行中）/ 完成态图标
 *   - 一句话说明当前在做什么（"正在搜索…" / "已完成 3 步"）
 *   - 步数与已用时长
 *   - 右侧展开箭头
 *
 *  **二层（展开）**
 *   - 每一轮的思考片段（如果有）
 *   - 工具步骤逐条列出：工具名、状态、单步耗时、结果摘要
 *   - 嵌套子步骤（团队 / 工作流下钻）再缩进一级
 *
 * 配色跟随主题：
 *   - 卡底用 primaryContainer 低透明，与 MOOD 块同族但不相同
 *   - 计时与步数用 primary，强调「还在动」
 *   - 步骤状态色只区分成功/失败/进行中三档，不引入额外色板
 *
 * @param taskCard 工具步骤数据；null 时只显示摘要行（等待首个 token 等阶段）
 * @param progressText 兜底文案（视觉分析进度、OCR 进度等）
 * @param isActive 是否仍在执行，决定墨形动画与计时是否继续走
 * @param startedAt 起始时间戳，用于计时；null 时按首次组合时刻算
 */
@Composable
internal fun ProcessCard(
    taskCard: TaskCardData?,
    progressText: String? = null,
    isActive: Boolean = true,
    startedAt: Long? = null,
    modifier: Modifier = Modifier,
) {
    val steps = taskCard?.steps.orEmpty()
    val totalSteps = steps.size
    val doneSteps = steps.count { it.status.isTerminal }
    val runningStep = steps.firstOrNull { it.status == TaskStepStatus.RUNNING }

    // 展开状态：执行中默认收起（别遮挡正文），完成后也不自动弹开
    var expanded by remember(taskCard?.id) { mutableStateOf(false) }

    val scheme = MaterialTheme.colorScheme
    // 与 MOOD 区分：MOOD 用 primaryContainer，过程卡用 secondaryContainer，
    // 两者都在主题色系里，但一眼能看出是不同性质的块。
    val containerColor = scheme.secondaryContainer.copy(alpha = 0.5f)
    val borderColor = scheme.secondary.copy(alpha = 0.2f)

    // 计时：执行中每秒自增，结束时定格
    val elapsedMs = rememberElapsed(startedAt ?: taskCard?.createdAt, isActive)

    val summaryText = resolveSummaryText(progressText, runningStep, doneSteps, totalSteps, isActive)

    Surface(
        color = containerColor,
        shape = MuseShapes.medium,
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, borderColor),
        modifier =
        modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen, vertical = 3.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── 一层：摘要 ──
            ProcessCardSummary(
                summary =
                ProcessSummary(
                    text = summaryText,
                    doneSteps = doneSteps,
                    totalSteps = totalSteps,
                    elapsedMs = elapsedMs,
                    isActive = isActive,
                    hasFailure = taskCard?.hasFailedSteps == true,
                    expanded = expanded,
                ),
                onToggle = { expanded = !expanded },
            )

            // 进度条：只在执行中且有步骤时显示，完成态收起
            if (isActive && totalSteps > 0) {
                ProcessCardProgress(doneSteps = doneSteps, totalSteps = totalSteps)
            }

            // ── 二层：细分 ──
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(180)) + fadeIn(tween(180)),
                exit = shrinkVertically(animationSpec = tween(160)) + fadeOut(tween(120)),
            ) {
                ProcessStepList(steps = steps)
            }
        }
    }
}

/** 摘要文案：优先用外部进度，其次当前步骤标题，最后是步数概况。 */
private fun resolveSummaryText(progressText: String?, runningStep: TaskStep?, doneSteps: Int, totalSteps: Int, isActive: Boolean): String =
    when {
        progressText != null -> progressText
        runningStep != null ->
            runningStep.title.ifBlank { runningStep.progressText.orEmpty() }.ifBlank { "执行中" }
        totalSteps > 0 && doneSteps == totalSteps -> "已完成 $totalSteps 步"
        totalSteps > 0 -> "已进行 $doneSteps / $totalSteps 步"
        isActive -> "思考中"
        else -> "已完成"
    }

/** 摘要行需要的全部信息，打成一个包，避免参数列表膨胀。 */
private data class ProcessSummary(
    val text: String,
    val doneSteps: Int,
    val totalSteps: Int,
    val elapsedMs: Long,
    val isActive: Boolean,
    val hasFailure: Boolean,
    val expanded: Boolean,
)

/** 一层：摘要行。 */
@Composable
private fun ProcessCardSummary(summary: ProcessSummary, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clickable(enabled = summary.totalSteps > 0, onClick = onToggle)
            // 紧凑折叠态：这一行只回答「跑到哪了」，一行高度就够
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (summary.isActive) {
            MuseInkGlyph(
                modifier = Modifier.size(MuseThinkingGlyphSize * 0.8f),
                animate = true,
            )
        } else {
            Icon(
                imageVector = if (summary.hasFailure) MuseIcons.x else MuseIcons.check,
                contentDescription = null,
                tint = if (summary.hasFailure) scheme.error else scheme.primary,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = summary.text,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary.totalSteps > 0 || summary.elapsedMs > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (summary.totalSteps > 0) {
                        Text(
                            text = "${summary.doneSteps} / ${summary.totalSteps}",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.primary,
                        )
                    }
                    if (summary.elapsedMs > 0) {
                        Text(
                            text = formatElapsed(summary.elapsedMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.outline,
                        )
                    }
                }
            }
        }

        if (summary.totalSteps > 0) {
            Icon(
                imageVector = if (summary.expanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                contentDescription = null,
                tint = scheme.outline,
                modifier = Modifier.size(MuseIconSizes.iconTiny),
            )
        }
    }
}

/** 细进度条：只在执行中显示。 */
@Composable
private fun ProcessCardProgress(doneSteps: Int, totalSteps: Int) {
    val scheme = MaterialTheme.colorScheme
    val progress by
        animateFloatAsState(
            targetValue = if (totalSteps == 0) 0f else doneSteps.toFloat() / totalSteps,
            animationSpec = tween(300),
            label = "process_progress",
        )
    LinearProgressIndicator(
        progress = { progress },
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(2.dp)
            .clip(RoundedCornerShape(1.dp)),
        color = scheme.primary,
        trackColor = scheme.primary.copy(alpha = 0.12f),
    )
}

/** 二层：步骤列表容器。 */
@Composable
private fun ProcessStepList(steps: List<TaskStep>) {
    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .padding(top = 8.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        steps.forEach { step -> ProcessStepRow(step = step, depth = 0) }
    }
}

/**
 * 单条步骤：状态点 + 标题 + 耗时 + 结果摘要；有子步骤时再缩进一层。
 */
@Composable
private fun ProcessStepRow(step: TaskStep, depth: Int) {
    val scheme = MaterialTheme.colorScheme
    val statusColor =
        when (step.status) {
            TaskStepStatus.SUCCESS -> scheme.primary
            TaskStepStatus.FAILED, TaskStepStatus.TIMED_OUT -> scheme.error
            TaskStepStatus.CANCELLED -> scheme.outline
            TaskStepStatus.RUNNING -> scheme.primary
            TaskStepStatus.PENDING -> scheme.outline.copy(alpha = 0.5f)
        }

    Column(
        modifier = Modifier.padding(start = (depth * 12).dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier =
                Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(statusColor),
            )
            Text(
                text = step.title.ifBlank { step.toolName.ifBlank { "步骤" } },
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            step.durationMs?.let { ms ->
                Text(
                    text = formatElapsed(ms),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.outline,
                )
            }
            if (step.status == TaskStepStatus.RUNNING) {
                Text(
                    text = "进行中",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary,
                )
            }
        }
        // 进度文案（RUNNING 时由工具回调更新）
        step.progressText?.takeIf { it.isNotBlank() }?.let { progress ->
            Text(
                text = progress,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
        // 结果摘要（终态时展示前两行）
        if (step.status.isTerminal && step.result.isNotBlank()) {
            Text(
                text = step.result.lineSequence().take(2).joinToString(" ").take(120),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
        // 子步骤再下钻一层
        step.subSteps.forEach { sub -> ProcessStepRow(step = sub, depth = depth + 1) }
    }
}

/** 计时：执行中每 500ms 刷新一次，结束后定格在最终值。 */
@Composable
private fun rememberElapsed(startAt: Long?, isActive: Boolean): Long {
    if (startAt == null || startAt <= 0L) return 0L
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isActive, startAt) {
        while (isActive) {
            now = System.currentTimeMillis()
            delay(500)
        }
        now = System.currentTimeMillis()
    }
    return (now - startAt).coerceAtLeast(0L)
}

/** 毫秒 → 人类可读：<1s 显示 0.x s，<60s 显示 x s，否则 m 分 s 秒。 */
private fun formatElapsed(ms: Long): String = when {
    ms < 1000L -> "${ms}ms"
    ms < 60_000L -> String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
    else -> {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        "${minutes}m ${seconds}s"
    }
}
