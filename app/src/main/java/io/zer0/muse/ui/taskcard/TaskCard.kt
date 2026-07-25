package io.zer0.muse.ui.taskcard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.tools.DelegationChainTracker
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.Uuid

/**
 * Phase 10.1: 任务卡完整调度。
 *
 * 在 Phase 8.8 基础上增强:
 *  - 独立计划阶段(PLANNING / EXECUTING / DONE)
 *  - 时间线(每步 startedAt / finishedAt,展示执行耗时)
 *  - 展开 / 折叠交互(点击标题切换)
 *  - 重试失败步骤(onRetry 回调)
 *  - 视觉风格(primary 月桂绿渐变标题栏,适配"深夜台灯"调性)
 *
 * @param id 任务卡 id(绑定到 assistant 消息 id)
 * @param title 任务标题
 * @param steps 步骤列表
 * @param phase 当前阶段
 * @param isExpanded 是否展开(默认 true)
 * @param createdAt 任务卡创建时间戳
 */
data class TaskCardData(
    val id: String,
    val title: String,
    val steps: List<TaskStep>,
    val phase: TaskCardPhase = TaskCardPhase.PLANNING,
    val isExpanded: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** 整体进度(0..1),只算 SUCCESS。 */
    val progress: Float
        get() = if (steps.isEmpty()) 0f
        else steps.count { it.status == TaskStepStatus.SUCCESS }.toFloat() / steps.size

    /** 是否全部完成(SUCCESS 或 FAILED)。 */
    val isAllDone: Boolean
        get() = steps.isNotEmpty() && steps.all {
            it.status == TaskStepStatus.SUCCESS || it.status == TaskStepStatus.FAILED
        }

    /** 是否有失败步骤(可重试)。 */
    val hasFailedSteps: Boolean
        get() = steps.any { it.status == TaskStepStatus.FAILED }

    /** 总耗时(毫秒),所有已完成步骤的 finishedAt - startedAt 之和。 */
    val totalDurationMs: Long
        get() = steps.mapNotNull { s ->
            if (s.startedAt != null && s.finishedAt != null) s.finishedAt - s.startedAt else null
        }.sum()

    companion object {
        /**
         * 从工具调用列表构建任务卡(PLANNING 阶段,所有步骤 PENDING)。
         */
        fun fromToolCalls(assistantId: Uuid, toolCalls: List<Pair<String, String>>): TaskCardData {
            val steps = toolCalls.mapIndexed { idx, (name, args) ->
                val delegateArgs = if (name == "delegate_agent") {
                    parseDelegateAgentArgs(args)
                } else {
                    DelegateAgentArgs("", "")
                }
                val detail = if (name == "delegate_agent") {
                    delegateArgs.task.take(60)
                } else {
                    args.take(200)
                }
                TaskStep(
                    id = "${assistantId}_$idx",
                    title = name,
                    detail = detail,
                    status = TaskStepStatus.PENDING,
                    toolCallIndex = idx,
                    source = delegateArgs.assistantId,
                )
            }
            return TaskCardData(
                id = assistantId.toString(),
                title = "执行计划(${steps.size} 步)",
                steps = steps,
                phase = TaskCardPhase.PLANNING,
            )
        }

        /**
         * v1.25: 解析 delegate_agent 工具的参数,提取 assistantId 与 task。
         */
        fun parseDelegateAgentArgs(argumentsJson: String): DelegateAgentArgs {
            return runCatching {
                val obj = io.zer0.common.AppJson.decodeFromString(JsonObject.serializer(), argumentsJson)
                val assistantId = (obj["assistantId"] as? JsonPrimitive)?.content?.trim().orEmpty()
                val task = (obj["task"] as? JsonPrimitive)?.content?.trim().orEmpty()
                DelegateAgentArgs(assistantId, task)
            }.getOrDefault(DelegateAgentArgs("", ""))
        }
    }

    /** v1.25: delegate_agent 参数解析结果。 */
    data class DelegateAgentArgs(
        val assistantId: String,
        val task: String,
    )
}

/** 单个步骤。 */
data class TaskStep(
    val id: String,
    val title: String,
    val detail: String = "",
    val status: TaskStepStatus,
    val result: String = "",
    /** 开始执行时间戳(进入 RUNNING 时设置)。 */
    val startedAt: Long? = null,
    /** 结束时间戳(进入 SUCCESS / FAILED 时设置)。 */
    val finishedAt: Long? = null,
    /** 对应的 toolCall 索引(用于重试时定位)。 */
    val toolCallIndex: Int = -1,
    /** v0.49: 工具执行进度文本(如"正在搜索..."),null 或空时不显示。RUNNING 状态下由 SkillExecutor.onProgress 回调更新。 */
    val progressText: String? = null,
    /** v1.200: 步骤来源(Agent/团队名),用于泳道分组。 */
    val source: String = "",
    /** v1.200: 子步骤列表,用于团队/工作流结果下钻。 */
    val subSteps: List<TaskStep> = emptyList(),
) {
    /** 单步耗时(毫秒),null 表示未完成。 */
    val durationMs: Long? get() = if (startedAt != null && finishedAt != null) finishedAt - startedAt else null
}

/** 步骤状态。 */
enum class TaskStepStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
}

/** 任务卡阶段。 */
enum class TaskCardPhase(val label: String) {
    PLANNING("计划中"),
    EXECUTING("执行中"),
    DONE("已完成"),
}

/**
 * Phase 10.1: 任务卡 Composable(完整调度版)。
 *
 * 视觉:primary 月桂绿渐变标题栏 + 卡片底 + 时间线步骤行。
 * 交互:点击标题切换展开/折叠;失败步骤显示"重试"按钮。
 *
 * @param data 任务卡数据
 * @param onToggleExpand 切换展开/折叠回调
 * @param onRetryStep 重试失败步骤回调(参数:stepId)
 * @param delegationChain v1.201: 委派链路根节点列表(可选)。
 *        非空时在步骤列表下方嵌入 [DelegationChainCard],可视化主助手 → 子助手链路。
 *        传 null 或空列表则不渲染。
 */
@Composable
fun TaskCard(
    data: TaskCardData,
    onToggleExpand: () -> Unit = {},
    onRetryStep: (String) -> Unit = {},
    // M-TC2 修复: 增加 modifier 参数,允许调用方自定义布局修饰
    modifier: Modifier = Modifier,
    // v1.201: 委派链路(可选,非空时在步骤列表下方渲染)
    delegationChain: List<DelegationChainTracker.ChainNode>? = null,
) {
    // 渐变色:primary 月桂绿 → tertiary(适配"深夜台灯"调性,不破坏整体调性)
    val gradientColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
    )

    Surface(
        modifier = modifier
            .padding(vertical = 4.dp),
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(0.dp),
        ) {
            // ── 渐变标题栏(可点击切换展开/折叠)──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.horizontalGradient(gradientColors),
                        shape = MuseShapes.medium,
                    )
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                // 阶段标签
                PhaseBadge(data.phase, data.progress)
                Spacer(Modifier.weight(1f))
                // 进度数字
                val successCount = data.steps.count { it.status == TaskStepStatus.SUCCESS }
                Text(
                    text = "$successCount/${data.steps.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                    fontWeight = FontWeight.Medium,
                )
                // 展开/折叠箭头(根据状态切换图标)
                Icon(
                    imageVector = if (data.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (data.isExpanded) "折叠" else "展开",
                    tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                    modifier = Modifier.size(20.dp),
                )
            }

            // ── 卡片内容(展开时显示)──
            AnimatedVisibility(
                visible = data.isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // v1.200: 泳道说明(多个 source 时显示)
                    val sources = remember(data.steps) {
                        data.steps.map { it.source }.filter { it.isNotBlank() }.distinct()
                    }
                    if (sources.size > 1) {
                        Text(
                            text = "按 Agent 分组: ${sources.joinToString(", ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 标题
                    Text(
                        text = data.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // 进度条(执行中显示)
                    if (data.phase == TaskCardPhase.EXECUTING || (data.phase == TaskCardPhase.DONE && !data.isAllDone)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(MuseShapes.pill)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(data.progress)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.primary, MuseShapes.pill)
                            )
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 2.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    // 步骤列表
                    // L-TC6 修复: forEach 加 key,步骤状态变化时 Compose 能精准重组对应行
                    data.steps.forEach { step ->
                        key(step.id) {
                            TaskStepRow(
                                step = step,
                                onRetry = { onRetryStep(step.id) },
                            )
                        }
                    }
                    // v1.201: 委派链路图(可选,非空时在步骤列表下方渲染)
                    if (!delegationChain.isNullOrEmpty()) {
                        DelegationChainCard(roots = delegationChain)
                    }
                    // 总耗时(DONE 阶段显示)
                    if (data.phase == TaskCardPhase.DONE && data.totalDurationMs > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 2.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Text(
                            text = "总耗时: ${formatDuration(data.totalDurationMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 重试全部失败步骤按钮
                    if (data.hasFailedSteps && data.phase == TaskCardPhase.DONE) {
                        TextButton(
                            onClick = { onRetryStep("ALL_FAILED") },
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.size(4.dp))
                            Text(
                                "重试全部失败步骤",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 阶段徽章(标题栏左侧)。 */
@Composable
private fun PhaseBadge(phase: TaskCardPhase, progress: Float) {
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    // M-TC5 修复: DONE 分支原先两个分支返回值相同(progress>=1f 和 <1f 都是 0.3f)。
    // 改为根据 progress 区分:全部成功(1.0)用较低 alpha 的"完成"色,
    // 部分成功(<1.0,有失败步骤)用较高 alpha 的"警示"色。
    val (text, bg) = when (phase) {
        TaskCardPhase.PLANNING -> phase.label to onPrimary.copy(alpha = 0.25f)
        TaskCardPhase.EXECUTING -> phase.label to onPrimary.copy(alpha = 0.35f)
        TaskCardPhase.DONE -> if (progress >= 1f) {
            // 全部成功:柔和完成色
            phase.label to onPrimary.copy(alpha = 0.3f)
        } else {
            // 部分成功/有失败:稍深背景强调"需关注"
            phase.label to onPrimary.copy(alpha = 0.5f)
        }
    }
    Surface(
        color = bg,
        shape = MuseShapes.medium,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = onPrimary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** 单步步骤行(含状态图标 + 标题 + 详情 + 结果 + 耗时 + 重试按钮)。 */
@Composable
private fun TaskStepRow(
    step: TaskStep,
    onRetry: () -> Unit,
) {
    var isResultExpanded by remember { mutableStateOf(false) }
    val resultPreviewLength = 120

    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // 状态图标
        Box(
            modifier = Modifier
                .size(20.dp)
                .padding(top = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (step.status) {
                TaskStepStatus.PENDING -> Box(
                    Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                )
                TaskStepStatus.RUNNING -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.secondary,
                )
                TaskStepStatus.SUCCESS -> Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                TaskStepStatus.FAILED -> Icon(
                    Icons.Default.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        // 步骤内容
        Column(modifier = Modifier.weight(1f)) {
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
            // v1.200: 结果(可展开/收起) + 子步骤下钻
            if (step.result.isNotBlank() && step.status != TaskStepStatus.PENDING) {
                Spacer(Modifier.height(2.dp))
                val showExpand = step.result.length > resultPreviewLength
                val displayResult = if (isResultExpanded) step.result else step.result.take(resultPreviewLength)
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "→ $displayResult",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (step.status == TaskStepStatus.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (showExpand) {
                        Text(
                            text = if (isResultExpanded) "收起" else "展开",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .clickable { isResultExpanded = !isResultExpanded },
                        )
                    }
                }
            }
            // v1.200: 子步骤泳道
            if (step.subSteps.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        step.subSteps.forEach { subStep ->
                            key(subStep.id) {
                                TaskStepRow(
                                    step = subStep,
                                    onRetry = {},
                                )
                            }
                        }
                    }
                }
            }
            // 耗时(完成后显示)
            step.durationMs?.let { dur ->
                if (dur > 0) {
                    Text(
                        text = "耗时: ${formatDuration(dur)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 重试按钮(FAILED 步骤)
            if (step.status == TaskStepStatus.FAILED) {
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.padding(top = 2.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp, vertical = 0.dp,
                    ),
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "重试",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** 格式化耗时(毫秒 → 可读字符串)。 */
private fun formatDuration(ms: Long): String {
    return when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "${ms / 1000.0}" + "s"
        else -> {
            val min = ms / 60_000
            val sec = (ms % 60_000) / 1000
            "${min}m ${sec}s"
        }
    }
}
