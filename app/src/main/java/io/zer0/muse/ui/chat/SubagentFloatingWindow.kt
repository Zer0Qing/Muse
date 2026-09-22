package io.zer0.muse.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.subagent.SubagentThreadStore
import io.zer0.muse.tools.DeferredResultStore
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlin.math.roundToInt

/**
 * v1.0.92: 子代理悬浮小窗。
 *
 * 后台子任务(subagent_task / 可续接子会话)运行时,在消息区右侧贴边浮现一枚小胶囊
 * (「N 个任务运行中」),点击展开任务面板:实时状态 + 取消入口;全部结束自动淡出收起。
 *
 * 设计约束(「与主对话不打架」):
 *  - 数据只读:仅消费 ChatViewModel 已有的 [activeThreads]/[pendingTasks] 快照,不写回任何状态;
 *  - 布局独立:透明全屏容器不挂任何手势,不阻塞消息滚动与输入;只有胶囊/面板自身可交互;
 *  - 操作有界:唯一干预是取消任务([onCancelTask],复用既有 cancelSubagentTask 链路)。
 *
 * 胶囊可上下拖动,位置按归一化比例记忆(rememberSaveable,跨配置变更保留)。
 */
@Composable
internal fun SubagentFloatingWindow(
    activeThreads: List<SubagentThreadStore.ThreadEntry>,
    pendingTasks: List<DeferredResultStore.DeferredTask>,
    onCancelTask: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pending = remember(pendingTasks) {
        pendingTasks.filter { it.status == DeferredResultStore.TaskStatus.PENDING }
    }
    val taskThreadIds = remember(pending) { pending.mapNotNull { it.threadId }.toSet() }
    val orphanThreads = remember(activeThreads, taskThreadIds) {
        activeThreads.filter { it.threadId !in taskThreadIds }
    }
    val activeCount = maxOf(pending.size, activeThreads.size)
    val visible = activeCount > 0

    var expanded by rememberSaveable { mutableStateOf(false) }
    // 无任务时强制收起面板,避免残留展开态在下次出现时突兀弹开
    LaunchedEffect(visible) { if (!visible) expanded = false }

    // 胶囊垂直位置:归一化 0..1(fraction=0.5 即垂直居中)
    var containerHeight by remember { mutableIntStateOf(0) }
    var fraction by rememberSaveable { mutableStateOf(0.38f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerHeight = it.height },
    ) {
        // ── 贴边小胶囊(展开面板时隐藏,避免半遮挡) ──
        AnimatedVisibility(
            visible = visible && !expanded,
            enter = fadeIn() + slideInHorizontally { it / 2 },
            exit = fadeOut() + slideOutHorizontally { it / 2 },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            val travel = (containerHeight - with(LocalDensity.current) { 44.dp.toPx() }).coerceAtLeast(1f)
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(
                    topStartPercent = 50,
                    topEndPercent = 4,
                    bottomEndPercent = 4,
                    bottomStartPercent = 50,
                ),
                tonalElevation = 2.dp,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .offset { IntOffset(0, ((fraction - 0.5f) * travel).roundToInt()) }
                    .pointerInput(travel) {
                        detectVerticalDragGestures { _, dragAmount ->
                            fraction = (fraction + dragAmount / travel).coerceIn(0.06f, 0.94f)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { expanded = !expanded }
                    },
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(MaterialTheme.colorScheme.onPrimary, CircleShape),
                    )
                    Text(
                        text = stringResource(R.string.subagent_task_floating_count, activeCount),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // ── 展开的任务面板 ──
        AnimatedVisibility(
            visible = visible && expanded,
            enter = fadeIn() + slideInHorizontally { it / 3 },
            exit = fadeOut() + slideOutHorizontally { it / 3 },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 14.dp),
        ) {
            Surface(
                shape = MuseShapes.medium,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier
                        .width(280.dp)
                        .padding(MusePaddings.cardInner),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    // 头部:标题 + 计数 + 收起
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.iconPadding),
                    ) {
                        Text(
                            text = stringResource(R.string.subagent_task_list_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.subagent_task_list_count, activeCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(MuseIconSizes.iconSmall)
                                .clickable { expanded = false },
                        )
                    }
                    // 条目区(超长可滚动)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    ) {
                        pending.forEach { task ->
                            SubagentWindowRow(
                                title = task.label?.takeIf { it.isNotBlank() } ?: task.taskSummary,
                                statusText = stringResource(R.string.subagent_task_running),
                                onCancel = { onCancelTask(task.taskId) },
                            )
                        }
                        orphanThreads.forEach { thread ->
                            SubagentWindowRow(
                                title = stringResource(R.string.tool_label_subagent_task),
                                statusText = stringResource(R.string.subagent_task_running),
                                onCancel = null,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 单条子任务行:状态点 + 标题/状态 + 可选取消入口。 */
@Composable
private fun SubagentWindowRow(
    title: String,
    statusText: String,
    onCancel: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.iconPadding),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.ifBlank { statusText },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onCancel != null) {
            Text(
                text = stringResource(R.string.subagent_task_cancel),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}
