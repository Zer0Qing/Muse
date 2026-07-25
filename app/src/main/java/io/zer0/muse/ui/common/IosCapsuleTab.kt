package io.zer0.muse.ui.common

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge

/**
 * iOS 风格胶囊 Tab 选择器。
 *
 * 设计稿中首页(任务/Agent/群聊)、搜索页(Sessions/Messages/Settings)、
 * 助手详情(Basic/Prompt/Extensions/Memory/Advanced)均使用此组件。
 *
 * 样式:surfaceVariant 凹槽容器(20dp 圆角),选中项白色凸起(16dp 圆角) + 阴影。
 *
 * 用法:
 * ```
 * IosCapsuleTab(
 *     tabs = listOf("Tasks", "Agent", "Group"),
 *     selectedIndex = pagerState.currentPage,
 *     onSelect = { scope.launch { pagerState.animateScrollToPage(it) } },
 * )
 * ```
 *
 * @param tabs Tab 标签文本列表
 * @param selectedIndex 当前选中索引
 * @param onSelect 选中回调(参数为新索引)
 * @param modifier Modifier
 */
@Composable
fun IosCapsuleTab(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                .padding(2.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, label ->
                val selected = selectedIndex == index
                val bgColor by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.surface
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0f),
                    animationSpec = tween(MuseAnimation.TACTILE_MS),
                    label = "capsule_tab_bg",
                )
                val textColor by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(MuseAnimation.TACTILE_MS),
                    label = "capsule_tab_text",
                )
                Surface(
                    shape = MuseShapes.semiLarge,
                    color = bgColor,
                    shadowElevation = if (selected) 1.dp else 0.dp,
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
