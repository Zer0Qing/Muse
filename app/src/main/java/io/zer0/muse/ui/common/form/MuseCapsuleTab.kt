package io.zer0.muse.ui.common.form

import androidx.compose.animation.core.animateFloatAsState
import io.zer0.muse.ui.theme.MuseAnimation
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseMotion
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
 * v1.137 B3: 真正实现滑动/点按动效分离。
 *  - 用户拖拽([isDragging] = true):指示器颜色随手指连续插值(lerp),实时跟踪 pager 偏移,
 *    无 tween,手指移到哪指示器跟到哪。
 *  - 点击切换([isDragging] = false):用 200ms tween 平滑过渡,有"弹"的质感。
 *  - 调用方通过 [isDragging] 区分:HomeScreen 在 onSelect 时设 clickAnimating=true,
 *    isScrollInProgress 结束时清除,从而区分用户拖拽和 animateScrollToPage。
 *
 * @param tabs Tab 标签文本列表
 * @param selectedIndex 当前选中索引
 * @param onSelect 选中回调(参数为新索引)
 * @param modifier Modifier
 * @param pageOffset pager 偏移分数(连续跟踪手指滑动);null 时回退到 tween 动画(默认)
 * @param isDragging 用户是否正在拖拽 pager(仅拖拽时用连续跟踪;点击动画/静止时用 tween)
 */
@Composable
fun MuseCapsuleTab(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    pageOffset: Float? = null,
    isDragging: Boolean = false,
) {
    val useContinuous = pageOffset != null && isDragging
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
            // v1.0.74 fix (前端审计 3.7): 高度 32dp → 48dp,段内触摸目标达 MD3 红线
            .height(48.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(MusePaddings.tinyGap),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, label ->
                val isSelected = selectedIndex == index
                // B3: 拖拽时直接根据 fractionalIndex 计算"选中度"(0~1),无延迟;
                // 点击/静止时用 animateFloatAsState 平滑过渡,有 tween 质感。
                val directFraction = (1f - abs(fractionalIndex - index)).coerceIn(0f, 1f)
                val animatedFraction by animateFloatAsState(
                    targetValue = if (isSelected) 1f else 0f,
                    animationSpec = MuseMotion.tween(MuseAnimation.TACTILE_MS),
                    label = "capsule_tab_fraction",
                )
                val fraction = if (useContinuous) directFraction else animatedFraction

                val bgColor = lerp(unselectedBg, selectedBg, fraction)
                val textColor = lerp(unselectedText, selectedText, fraction)
                // v1.0.75 fix (CI hardcoded-cjk): 选中态文案走资源(Composable 上下文取值,semantics lambda 内复用)
                val selectedSuffix = stringResource(R.string.settings_selected_suffix)
                val interactionSource = remember(index) { MutableInteractionSource() }

                Surface(
                    shape = MuseShapes.semiLarge,
                    color = bgColor,
                    shadowElevation = if (isSelected) MuseElevation.low else MuseElevation.none,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        // v1.0.74 fix (前端审计 3.7): 用 selected 语义替代拼进 contentDescription,
                         // TalkBack 能读"已选中"关系而非字符串拼接。
                         .semantics {
                             selected = isSelected
                             // v1.0.75 fix (CI hardcoded-cjk): 选中态文案走资源(Composable 外取值)
                             contentDescription = if (selectedIndex == index) "$label$selectedSuffix" else label
                         }
                         // 胶囊已经有自绘选中态，不能再叠加 Material 默认 ripple；
                         // 默认 bounded ripple 在深色遮罩下会表现为白色横条。
                         .clickable(
                             interactionSource = interactionSource,
                             indication = null,
                             role = Role.Tab,
                             onClick = { onSelect(index) },
                         ),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge.copy(
                             fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            ),
                            color = textColor,
                        )
                    }
                }
            }
        }
    }
}
