package io.zer0.muse.ui.taskcard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseCornerRadius
import io.zer0.muse.ui.theme.MuseShapes

/**
 * v1.55: Agent 工作流计划卡。
 *
 * 当 LLM 调用 task_plan 工具创建结构化计划后,在消息流中渲染为可追踪的检查清单。
 * 与 TaskCard 的区别:
 *  - TaskCard 显示工具调用步骤(每步是一个工具调用,自动追踪执行状态)
 *  - PlanCard 显示 LLM 规划的步骤(每步是一个描述性任务,由 LLM 主动更新状态)
 */
@Composable
fun PlanCard(
    plan: AgentPlan,
    modifier: Modifier = Modifier,
) {
    // M-TC3 修复: remember 改为 rememberSaveable,展开/折叠状态在配置变更/进程恢复后保留
    var expanded by rememberSaveable { mutableStateOf(true) }

    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
    ) {
        Column {
            // 标题栏:渐变色 + 计划标题 + 进度
            // M-TC1 修复: 文字/图标色从硬编码 Color.White 改为 MaterialTheme.colorScheme.onPrimary,
            // 保证在自定义主题下的对比度
            val onPrimaryColor = MaterialTheme.colorScheme.onPrimary
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary,
                            ),
                        ),
                    )
                    .clip(RoundedCornerShape(topStart = MuseCornerRadius.SEMI_LARGE.dp, topEnd = MuseCornerRadius.SEMI_LARGE.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = plan.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = onPrimaryColor,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${plan.completedSteps}/${plan.totalSteps} 步完成" +
                            if (plan.failedSteps > 0) " · ${plan.failedSteps} 失败" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = onPrimaryColor.copy(alpha = 0.85f),
                    )
                }
                if (!plan.isAllDone) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = onPrimaryColor,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = onPrimaryColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // 进度条
            if (!plan.isAllDone && plan.progress > 0f) {
                LinearProgressIndicator(
                    progress = { plan.progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }

            // 步骤列表
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // L-TC6 修复: forEach 加 key,步骤状态变化时 Compose 能精准重组对应行
                    plan.steps.forEach { step ->
                        key(step.id) {
                            PlanStepRow(step = step)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanStepRow(step: AgentPlanStep) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // 状态图标
        val (icon, color) = when (step.status) {
            AgentPlanStepStatus.PENDING -> Icons.Outlined.Circle to MaterialTheme.colorScheme.outline
            AgentPlanStepStatus.IN_PROGRESS -> null to MaterialTheme.colorScheme.primary
            AgentPlanStepStatus.DONE -> Icons.Filled.Check to MaterialTheme.colorScheme.primary
            AgentPlanStepStatus.FAILED -> Icons.Filled.Close to MaterialTheme.colorScheme.error
            AgentPlanStepStatus.SKIPPED -> Icons.Filled.PlayArrow to MaterialTheme.colorScheme.outline
        }

        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(14.dp),
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = color,
                )
            }
        }

        // M-TC4 修复: 原先 Spacer(height(0.dp).padding(horizontal=6.dp)) 是错误的,
        // height(0.dp) 让 Spacer 无高度,padding 也不生效。改为 width(6.dp) 做水平间距
        Spacer(Modifier.width(6.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (step.status == AgentPlanStepStatus.IN_PROGRESS) FontWeight.SemiBold else FontWeight.Normal,
                color = if (step.status == AgentPlanStepStatus.DONE) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (step.description.isNotBlank()) {
                Text(
                    text = step.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (step.result.isNotBlank() && step.status == AgentPlanStepStatus.DONE) {
                Text(
                    text = step.result.take(200),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
