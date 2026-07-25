package io.zer0.muse.ui.taskcard

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import io.zer0.muse.ui.theme.MuseAnimation
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.tools.DelegationChainTracker
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.tiny

/**
 * v1.201: 委派链路卡片。
 *
 * 可视化展示一次主助手 → 子助手 → 子子助手 的委派链路,
 * 含节点状态(RUNNING/COMPLETED/FAILED/TIMEOUT/CANCELLED/PAUSED)、
 * 失败原因下钻、子节点树形结构与点击查看详情。
 *
 * @param roots 顶级委派节点列表(parentRequestId == null)
 * @param modifier 外部修饰
 */
@Composable
fun DelegationChainCard(
    roots: List<DelegationChainTracker.ChainNode>,
    modifier: Modifier = Modifier,
) {
    // 空链路:显示提示文案,不渲染卡片容器(避免空卡噪声)
    if (roots.isEmpty()) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            shape = MuseShapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            tonalElevation = 0.dp,
        ) {
            Text(
                text = stringResource(R.string.delegation_no_chain),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(MusePaddings.cardInner),
            )
        }
        return
    }

    val totalCount = remember(roots) { countNodes(roots) }

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
            // ── 标题行:图标 + 标题 + 节点总数 ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.iconPadding),
            ) {
                Icon(
                    imageVector = Icons.Default.AccountTree,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.delegation_chain_title),
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
                        text = stringResource(R.string.delegation_node_count, totalCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // ── 链路根节点列表 ──
            roots.forEach { node ->
                key(node.requestId) {
                    ChainNodeView(node = node, depth = 0)
                }
            }
        }
    }
}

/**
 * 单个链路节点渲染:状态圆点 → 目标徽章 → 任务摘要 → 耗时,
 * 失败节点额外展示错误消息预览;PAUSED 节点展示"等待确认";
 * 有 subNodes 时用 Box 缩进 + Canvas 竖线递归渲染。
 */
@Composable
private fun ChainNodeView(
    node: DelegationChainTracker.ChainNode,
    depth: Int,
) {
    var showDetail by remember { mutableStateOf(false) }
    val indent = (depth * 24).dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indent),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showDetail = true }
                .padding(vertical = 4.dp),
        ) {
            // 状态圆点(RUNNING 脉冲 / 其它状态实心圆)
            StatusDot(status = node.status)

            // 目标徽章(类型 + 名称)
            TargetBadge(targetType = node.targetType, targetName = node.targetName)

            // 任务摘要(前 80 字)
            Text(
                text = node.task.take(80),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            // 耗时
            Text(
                text = formatNodeDuration(node),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 失败节点:错误图标 + 错误预览(单行省略)
        if (node.status == DelegationNodeStatus.FAILED && !node.errorMessage.isNullOrBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 28.dp, top = 2.dp, bottom = 2.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = node.errorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 暂停节点:等待确认文字
        if (node.status == DelegationNodeStatus.PAUSED) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 28.dp, top = 2.dp, bottom = 2.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Pause,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = stringResource(R.string.delegation_status_paused),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // 子节点:Box 缩进 + Canvas 画竖线 + 递归渲染
        if (node.subNodes.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(top = 4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    val lineColor = MaterialTheme.colorScheme.outlineVariant
                    Canvas(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(2.dp),
                    ) {
                        drawLine(
                            color = lineColor,
                            start = Offset(size.width / 2f, 0f),
                            end = Offset(size.width / 2f, size.height),
                            strokeWidth = 2f,
                        )
                    }
                }
                Spacer(Modifier.width(MusePaddings.contentGap))
                Column(
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    modifier = Modifier.weight(1f),
                ) {
                    node.subNodes.forEach { sub ->
                        key(sub.requestId) {
                            ChainNodeView(node = sub, depth = depth + 1)
                        }
                    }
                }
            }
        }
    }

    // 详情弹窗(点击节点触发)
    if (showDetail) {
        NodeDetailDialog(node = node, onDismiss = { showDetail = false })
    }
}

/** 状态圆点:RUNNING 用脉冲动画,其余为实心圆。 */
@Composable
private fun StatusDot(status: DelegationNodeStatus) {
    val color = when (status) {
        DelegationNodeStatus.RUNNING -> MaterialTheme.colorScheme.primary
        DelegationNodeStatus.COMPLETED -> MaterialTheme.colorScheme.primary
        DelegationNodeStatus.FAILED -> MaterialTheme.colorScheme.error
        DelegationNodeStatus.TIMEOUT -> MaterialTheme.colorScheme.tertiary
        DelegationNodeStatus.CANCELLED -> MaterialTheme.colorScheme.outline
        DelegationNodeStatus.PAUSED -> MaterialTheme.colorScheme.secondary
    }

    if (status == DelegationNodeStatus.RUNNING) {
        // 脉冲动画:alpha 0.3f → 1f 来回
        val transition = rememberInfiniteTransition(label = "delegationPulse")
        val alpha by transition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(MuseAnimation.LOOP_SLOW_MS),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "delegationPulseAlpha",
        )
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color = color.copy(alpha = alpha)),
        )
    } else {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color = color),
        )
    }
}

/** 目标徽章:展示 targetType + targetName,类似文件树里的文件夹标签。 */
@Composable
private fun TargetBadge(targetType: String, targetName: String) {
    val bg = if (targetType == "team") {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val fg = if (targetType == "team") {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(percent = 50),
    ) {
        Text(
            text = targetName.ifBlank { targetType },
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 96.dp)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** 节点详情弹窗:展示完整任务 / 结果 / 错误 / 耗时。 */
@Composable
private fun NodeDetailDialog(
    node: DelegationChainTracker.ChainNode,
    onDismiss: () -> Unit,
) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.delegation_chain_title),
        onConfirm = onDismiss,
        dismissText = null,
        content = {
            // v1.0.7 修复崩溃:MuseDialog 的 content 区已自带 verticalScroll,嵌套会崩溃
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                DetailRow(
                    label = stringResource(R.string.delegation_target_label),
                    value = "${node.targetName} (${node.targetType})",
                )
                DetailRow(
                    label = stringResource(R.string.delegation_task_label),
                    value = node.task,
                )
                DetailRow(
                    label = stringResource(R.string.delegation_duration_label),
                    value = formatNodeDuration(node),
                )
                node.resultPreview?.let { preview ->
                    DetailRow(
                        label = stringResource(R.string.delegation_result_label),
                        value = preview,
                    )
                }
                node.errorMessage?.let { err ->
                    DetailRow(
                        label = stringResource(R.string.delegation_error_label),
                        value = err,
                        isError = true,
                    )
                }
            }
        },
    )
}

/** 详情弹窗内的"标签:值"行。 */
@Composable
private fun DetailRow(
    label: String,
    value: String,
    isError: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 统计节点总数(含子节点)。 */
private fun countNodes(roots: List<DelegationChainTracker.ChainNode>): Int {
    return roots.sumOf { 1 + countNodes(it.subNodes) }
}

/**
 * 格式化节点耗时。
 * - RUNNING → "执行中..."
 * - 已完成 → "1.2s" / "2m 30s" / "150ms"
 *
 * 作为 @Composable 才能调用 [stringResource] 拉取"执行中..."文案。
 */
@Composable
private fun formatNodeDuration(node: DelegationChainTracker.ChainNode): String {
    val runningText = stringResource(R.string.delegation_status_running)
    if (node.status == DelegationNodeStatus.RUNNING) {
        return runningText
    }
    val finished = node.finishedAt ?: return runningText
    val ms = finished - node.startedAt
    return when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "${ms / 1000.0}s"
        else -> {
            val min = ms / 60_000
            val sec = (ms % 60_000) / 1000
            "${min}m ${sec}s"
        }
    }
}
