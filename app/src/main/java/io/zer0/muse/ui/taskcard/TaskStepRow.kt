package io.zer0.muse.ui.taskcard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.formatToolDuration
import io.zer0.muse.ui.common.icons.MuseIcons

/**
 * 任务卡的单个步骤行。
 *
 * v2.6.8 从 [TaskCard.kt] 拆出 —— 一处原因是文件里 @Composable 函数数超了
 * detekt TooManyFunctions；更实际的原因是这段与「任务卡外壳」是两件不同的东西：
 * 外壳负责阶段/进度/展开，步骤行负责「这一步干了什么、产出了什么」。
 *
 * 步骤结果分成「过程」与「交付物」两条路（见 [TaskStepResult]）。
 */

/** 单步步骤行(含状态图标 + 标题 + 详情 + 结果 + 耗时 + 重试按钮)。 */
@Composable
internal fun TaskStepRow(
    step: TaskStep,
    onRetry: () -> Unit,
    onTogglePause: () -> Unit = {},
    /** v2.6.8: 产物卡的卡片身份 —— 传消息 id(与单条内联卡一致)。 */
    cardId: String? = null,
    /** v2.6.8: 产物卡的回传桥；null 时卡片只渲染不交互。 */
    onCardAction: ((String, io.zer0.muse.ui.markdown.CardAction) -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        StepStatusIcon(status = step.status)
        // 步骤内容
        Column(modifier = Modifier.weight(1f)) {
            StepTitleRow(step = step)
            StepDetailAndProgress(step = step)
            // v1.200: 结果(可展开/收起) + 子步骤下钻
            if (step.result.isNotBlank() && step.status != TaskStepStatus.PENDING) {
                Spacer(Modifier.height(2.dp))
                TaskStepResult(
                    step = step,
                    cardId = cardId,
                    onCardAction = onCardAction,
                )
            }
            // v1.200: 子步骤泳道
            if (step.subSteps.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                SubStepLane(subSteps = step.subSteps)
            }
            StepFooter(step = step, onRetry = onRetry)
        }
        // 仅允许暂停尚未开始的工具;运行中的副作用不强杀。
        if (step.status == TaskStepStatus.PENDING) {
            MuseTactileButton(
                icon = if (step.pauseRequested) MuseIcons.play else MuseIcons.pause,
                onClick = onTogglePause,
                contentDescription = stringResource(
                    if (step.pauseRequested) R.string.task_card_resume_step else R.string.task_card_pause_step,
                ),
                tint = if (step.pauseRequested) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                size = 32.dp,
                iconSize = 16.dp,
            )
        }
    }
}

/** 步骤结果的展开预览长度（折叠态）。 */
private const val RESULT_PREVIEW_LENGTH = 120

/** v2.6.8: 步骤标题 + 来源徽章（从 [TaskStepRow] 抽出）。 */
@Composable
private fun StepTitleRow(step: TaskStep) {
    // v1.200: 标题 + 来源徽章
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = step.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
        if (step.source.isNotBlank()) {
            Text(
                text = step.source,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.secondaryContainer,
                        RoundedCornerShape(percent = 50),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** v2.6.8: 步骤详情 + 执行进度文本（从 [TaskStepRow] 抽出）。 */
@Composable
private fun StepDetailAndProgress(step: TaskStep) {
    if (step.detail.isNotBlank()) {
        Text(
            text = step.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
    // v0.49: 工具执行进度文本(RUNNING 状态下由 SkillExecutor.onProgress 回调更新)
    if (step.status == TaskStepStatus.RUNNING && !step.progressText.isNullOrBlank()) {
        Text(
            text = step.progressText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 2.dp),
        )
    }
}

/** v2.6.8: 步骤耗时 + 重试按钮（从 [TaskStepRow] 抽出）。 */
@Composable
private fun StepFooter(step: TaskStep, onRetry: () -> Unit) {
    // 耗时(完成后显示)
    step.durationMs?.let { dur ->
        if (dur > 0) {
            Text(
                text = stringResource(R.string.task_card_step_duration, formatToolDuration(dur)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    // 重试按钮(FAILED / TIMED_OUT 步骤)
    if (step.status.isRetryable) {
        MuseTactileButton(
            icon = MuseIcons.refresh,
            onClick = onRetry,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** v2.6.8: 单步状态图标列（从 [TaskStepRow] 抽出，降低主行嵌套分支）。 */
@Composable
private fun StepStatusIcon(status: TaskStepStatus) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .padding(top = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            TaskStepStatus.PENDING -> Box(
                Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
            )
            TaskStepStatus.RUNNING ->
                // v2.6.12: 运行中用全应用统一的墨形（叶→星→环），
                // 不再用转圈：同一个「正在算」信号在等待气泡与工具行上是同一个形状。
                io.zer0.muse.ui.common.animation.MuseInkGlyph(
                    modifier = Modifier.size(16.dp),
                )
            TaskStepStatus.SUCCESS -> Icon(
                MuseIcons.check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            TaskStepStatus.FAILED -> Icon(
                MuseIcons.alertCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
            TaskStepStatus.CANCELLED -> Icon(
                MuseIcons.x,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            TaskStepStatus.TIMED_OUT -> Icon(
                MuseIcons.timer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** v2.6.8: 子步骤泳道（从 [TaskStepRow] 抽出，降低主行嵌套深度）。 */
@Composable
private fun SubStepLane(subSteps: List<TaskStep>) {
    Row(
        modifier = Modifier.height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            subSteps.forEach { subStep ->
                key(subStep.id) {
                    TaskStepRow(step = subStep, onRetry = {})
                }
            }
        }
    }
}

/**
 * 步骤结果区 —— 「过程」与「交付物」的分界就在这里。
 *
 * 三类结果分开渲染：
 *  - 交付物（show_card）：不在这里画，只留一行引用 —— 卡片本体在消息流中作为
 *    一等内联卡渲染，用户不用展开执行计划就能看到并操作；
 *  - 其它产物（图片/文件/render_data）：直接渲染产物，不截成文本；
 *  - 普通文本结果：截断预览 + 展开/收起。
 *
 * v2.6.8 从 [TaskStepRow] 抽出：一是 LongMethod/CyclomaticComplexMethod 整改，
 * 二是这段本来就是一个独立语义（“这一步产出了什么”），和状态图标、重试按钮无关。
 */
@Composable
private fun TaskStepResult(step: TaskStep, cardId: String?, onCardAction: ((String, io.zer0.muse.ui.markdown.CardAction) -> Unit)?) {
    // v2.6.7: 步骤结果是产物(卡片/图片/文件)时直接渲染产物，
    // 不再被截成 120 字纯文本埋在里面 —— 用户看不到就等于没生成。
    val stepOutput = remember(step.toolName, step.result, step.rawArgs) {
        io.zer0.muse.ui.resolveDirectOutput(step.toolName, step.rawArgs, step.result)
    }
    when {
        stepOutput == null -> StepResultText(step = step)
        // v2.6.8: 交付物(show_card)不在这里画。
        //
        // 卡片是用户要的东西本身，不是「调了个工具」的过程；把它压在可折叠的
        // 步骤里，用户得先展开执行计划才能看见自己刚生成的卡 —— 而它就在同一条
        // 消息流里。所以卡片本体在消息流中作为一等内联卡渲染（见 ChatScreen /
        // MessageBubble 路径），任务卡里只留一行引用。
        io.zer0.muse.ui.isInlineDeliverableTool(step.toolName) -> GeneratedCardReference()
        else -> StepOutputCard(
            output = stepOutput,
            cardId = cardId,
            onCardAction = onCardAction,
        )
    }
}

/** v2.6.8: 任务卡里的「已生成卡片」引用行 —— 卡片本体在消息流里。 */
@Composable
private fun GeneratedCardReference() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    ) {
        Icon(
            imageVector = MuseIcons.sparkle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = stringResource(R.string.task_card_card_generated),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** v2.6.8: 步骤产物卡（图片/文件/非交付物卡片）。 */
@Composable
private fun StepOutputCard(
    output: io.zer0.muse.ui.DirectOutput,
    cardId: String?,
    onCardAction: ((String, io.zer0.muse.ui.markdown.CardAction) -> Unit)?,
) {
    // 产物是给用户看的主体,不该被步骤行的状态图标列缩进挤窄。
    // 用 layout 向左扩展出图标列宽度,让卡片横向对齐到外层任务卡的左边缘;
    // 不能用负 padding —— Compose 会直接抛异常。
    io.zer0.muse.ui.DirectToolOutputInline(
        output = output,
        // v2.6.8: 卡片身份用消息 id(任务卡的 id 即挂载它的助手消息 id)，
        // 不再用 toolCallId —— 两条卡片路径的身份必须同一个空间，否则
        // 卡片读自己的数据读不到、保存工件也挂错消息。
        cardId = cardId,
        onCardAction = onCardAction?.let { handler ->
            { action -> handler(cardId.orEmpty(), action) }
        },
        modifier = Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val extra = STEP_ICON_COLUMN_WIDTH.roundToPx()
                val placeable = measurable.measure(
                    constraints.copy(
                        minWidth = constraints.minWidth + extra,
                        maxWidth = constraints.maxWidth + extra,
                    ),
                )
                layout(placeable.width, placeable.height) {
                    placeable.place(-extra, 0)
                }
            }
            .padding(top = 4.dp, bottom = 4.dp),
    )
}

/** v2.6.8: 普通文本结果（截断预览 + 展开/收起）。 */
@Composable
private fun StepResultText(step: TaskStep) {
    var isResultExpanded by remember { mutableStateOf(false) }
    val showExpand = step.result.length > RESULT_PREVIEW_LENGTH
    val displayResult = if (isResultExpanded) step.result else step.result.take(RESULT_PREVIEW_LENGTH)
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "→ $displayResult",
            style = MaterialTheme.typography.bodySmall,
            color = if (step.status == TaskStepStatus.FAILED ||
                step.status == TaskStepStatus.TIMED_OUT
            ) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
            // LAYOUT-01: Row 内文本取宽 + 限行，防放大字号下逐字换行；
            // 折叠态本就是预览(限 2 行)，展开态保留完整结果不截断
            maxLines = if (isResultExpanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (showExpand) {
            Text(
                text = stringResource(
                    if (isResultExpanded) R.string.action_collapse else R.string.action_expand,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .clickable { isResultExpanded = !isResultExpanded },
            )
        }
    }
}
