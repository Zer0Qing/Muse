package io.zer0.muse.ui.common.form

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import io.zer0.muse.ui.theme.MuseAnimation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge
import kotlin.math.abs

/**
 * iOS 风格胶囊 Tab 选择器。
 *
 * 设计稿中首页(任务/Agent/群聊)、搜索页(Sessions/Messages/Settings)、
 * 助手详情(Basic/Prompt/Extensions/Memory/Advanced)均使用此组件。
 *
 * 样式:surfaceVariant 凹槽容器(20dp 圆角),选中项白色凸起(16dp 圆角) + 阴影。
 *
 * v1.136 T9: 动效分离修复。
 *  - 滑动([pageOffset] != null):指示器颜色随手指连续插值(lerp),实时跟踪 pager 偏移,
 *    不再套 200ms tween,消除"滑动也像点击"的离散淡入感。
 *  - 点击([pageOffset] == null,默认):保留 200ms tween 平滑过渡(用于无 pager 的场景)。
 *  - 滑动结束后 [pageOffset] 归 0、selectedIndex 翻转为目标页,lerp 自然落在终态,
 *    无需额外的 settle 动画,无闪屏。
 *
 * 用法(绑定 pager,启用连续跟踪):
 * ```
 * MuseCapsuleTab(
 *     tabs = listOf("Tasks", "Agent", "Group"),
 *     selectedIndex = pagerState.currentPage,
 *     onSelect = { scope.launch { pagerState.animateScrollToPage(it) } },
 *     pageOffset = pagerState.currentPageOffsetFraction,
 * )
 * ```
 *
 * 用法(无 pager,使用 tween 动画):
 * ```
 * MuseCapsuleTab(
 *     tabs = listOf("Basic", "Prompt"),
 *     selectedIndex = idx,
 *     onSelect = { idx = it },
 * )
 * ```
 *
 * @param tabs Tab 标签文本列表
 * @param selectedIndex 当前选中索引
 * @param onSelect 选中回调(参数为新索引)
 * @param modifier Modifier
 * @param pageOffset pager 偏移分数(连续跟踪手指滑动);null 时回退到 tween 动画(默认)
 */
@Composable
fun MuseCapsuleTab(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    pageOffset: Float? = null,
) {
    val useContinuous = pageOffset != null
    val fractionalIndex = pageOffset?.let { selectedIndex + it } ?: selectedIndex.toFloat()

    val selectedBg = MaterialTheme.colorScheme.surface
    val unselectedBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0f)
    val selectedText = MaterialTheme.colorScheme.onSurface
    val unselectedText = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier
            .widthIn(min = (tabs.size * 60).dp)
            .height(32.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(MusePaddings.tinyGap),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, label ->
                val selected = selectedIndex == index
                // v1.136 T9: 连续模式下直接根据 fractionalIndex 计算"选中度"(0~1);
                // 离散模式下用 animateFloatAsState 平滑过渡(替代原 animateColorAsState)。
                val directFraction = (1f - abs(fractionalIndex - index)).coerceIn(0f, 1f)
                val animatedFraction by animateFloatAsState(
                    targetValue = if (selected) 1f else 0f,
                    animationSpec = tween(MuseAnimation.TACTILE_MS),
                    label = "capsule_tab_fraction",
                )
                val fraction = if (useContinuous) directFraction else animatedFraction

                val bgColor = lerp(unselectedBg, selectedBg, fraction)
                val textColor = lerp(unselectedText, selectedText, fraction)

                Surface(
                    shape = MuseShapes.semiLarge,
                    color = bgColor,
                    shadowElevation = if (selected) MuseElevation.low else MuseElevation.none,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics {
                            contentDescription = if (selected) "$label (selected)" else label
                        },
                    onClick = { onSelect(index) },
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            ),
                            color = textColor,
                        )
                    }
                }
            }
        }
    }
}
