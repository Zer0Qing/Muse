package io.zer0.muse.ui.memory

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.statusColors
import kotlin.math.roundToInt

/**
 * 记忆树 Phase 0 视觉原型。
 *
 * 这里只展示受控的假数据，不接 Room、不生成关系、不改变记忆业务状态。
 * 视觉约束：主题岛 + 纸片节点 + 克制关系线，拒绝真实星空和持续粒子动画。
 */
@Composable
fun MemoryGraphPreview(
    modifier: Modifier = Modifier,
) {
    val previewMuse = stringResource(R.string.memory_graph_preview_node_muse)
    val previewAndroid = stringResource(R.string.memory_graph_preview_node_android)
    val previewLocalAi = stringResource(R.string.memory_graph_preview_node_local_ai)
    val previewPhoto = stringResource(R.string.memory_graph_preview_node_photo)
    val previewCamera = stringResource(R.string.memory_graph_preview_node_camera)
    val previewGoal = stringResource(R.string.memory_graph_preview_node_goal)
    val previewWriting = stringResource(R.string.memory_graph_preview_node_writing)
    val nodes = remember(previewMuse, previewAndroid, previewLocalAi, previewPhoto, previewCamera, previewGoal, previewWriting) {
        listOf(
            PreviewNode(previewMuse, 0.50f, 0.30f, NodeTone.Primary),
            PreviewNode(previewAndroid, 0.28f, 0.47f, NodeTone.Secondary),
            PreviewNode(previewLocalAi, 0.70f, 0.47f, NodeTone.Tertiary),
            PreviewNode(previewPhoto, 0.22f, 0.72f, NodeTone.Info),
            PreviewNode(previewCamera, 0.48f, 0.68f, NodeTone.Info),
            PreviewNode(previewGoal, 0.77f, 0.72f, NodeTone.Success),
            PreviewNode(previewWriting, 0.66f, 0.86f, NodeTone.Warning),
        )
    }
    val edges = remember {
        listOf(
            0 to 1, 0 to 2, 1 to 4, 2 to 5, 3 to 4, 4 to 5, 5 to 6,
        )
    }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val colors = MaterialTheme.colorScheme
    val statusColors = MaterialTheme.statusColors

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surfaceVariant.copy(alpha = 0.34f))
            .pointerInput(Unit) {
                detectTransformGestures { _, panChange, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.82f, 2.2f)
                    pan += panChange
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val transform = { point: PreviewNode ->
                Offset(point.x * w * scale + pan.x + w * (1f - scale) / 2f, point.y * h * scale + pan.y + h * (1f - scale) / 2f)
            }
            // 视觉原型刻意不绘制主题岛背景:用户反馈其存在感过强,
            // 让留白、节点和关系线成为主角。未来真实聚类仍可复用数据层,
            // 但不预设为带底色的大块区域。
            edges.forEach { (a, b) ->
                val start = transform(nodes[a])
                val end = transform(nodes[b])
                val focused = selectedIndex == null || selectedIndex == a || selectedIndex == b
                drawLine(
                    color = colors.outline.copy(alpha = if (focused) 0.34f else 0.08f),
                    start = start,
                    end = end,
                    strokeWidth = if (selectedIndex == a || selectedIndex == b) 2.2.dp.toPx() else 1.dp.toPx(),
                )
            }
        }

        Text(
            text = stringResource(R.string.memory_graph_preview_title),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant.copy(alpha = 0.72f),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(MusePaddings.cardInner),
        )
        Text(
            text = stringResource(R.string.memory_graph_preview_hint),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant.copy(alpha = 0.58f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(MusePaddings.cardInner),
        )

        nodes.forEachIndexed { index, node ->
            val tone = node.tone.color(colors, statusColors)
            val isSelected = selectedIndex == index
            Surface(
                modifier = Modifier
                    .offset {
                        val x = node.x * maxWidth.toPx() * scale + pan.x + maxWidth.toPx() * (1f - scale) / 2f
                        val y = node.y * maxHeight.toPx() * scale + pan.y + maxHeight.toPx() * (1f - scale) / 2f
                        IntOffset(
                            (x - 55.dp.toPx()).roundToInt(),
                            (y - 20.dp.toPx()).roundToInt(),
                        )
                    }
                    .widthIn(min = 72.dp, max = 132.dp)
                    .clip(RoundedCornerShape(if (isSelected) 18.dp else 14.dp))
                    .clickable {
                        selectedIndex = if (selectedIndex == index) null else index
                    },
                color = if (isSelected) tone.copy(alpha = 0.24f) else colors.surface.copy(alpha = 0.92f),
                shadowElevation = if (isSelected) 4.dp else 1.dp,
                shape = RoundedCornerShape(if (isSelected) 18.dp else 14.dp),
            ) {
                Text(
                    text = node.title,
                    style = if (isSelected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        if (selectedIndex != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(MusePaddings.cardInner),
                color = colors.surface.copy(alpha = 0.94f),
                shape = RoundedCornerShape(18.dp),
                shadowElevation = 3.dp,
            ) {
                Text(
                    text = stringResource(R.string.memory_graph_preview_selected, nodes[selectedIndex!!].title),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

private data class PreviewNode(
    val title: String,
    val x: Float,
    val y: Float,
    val tone: NodeTone,
)

private enum class NodeTone {
    Primary,
    Secondary,
    Tertiary,
    Info,
    Success,
    Warning;

    fun color(
        scheme: androidx.compose.material3.ColorScheme,
        statusColors: io.zer0.muse.ui.theme.MuseStatusColors,
    ): Color = when (this) {
        Primary -> scheme.primary
        Secondary -> scheme.secondary
        Tertiary -> scheme.tertiary
        Info -> scheme.primary.copy(alpha = 0.82f)
        Success -> statusColors.success
        Warning -> statusColors.warning
    }
}
