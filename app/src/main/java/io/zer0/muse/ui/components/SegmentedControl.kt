package io.zer0.muse.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseHaptics

/**
 * Muse UI Kit — iOS 分段控制器 [SegmentedControl]。
 *
 * 设计稿对齐:
 *  - 胶囊形容器(surfaceVariant 背景)
 *  - 选中项: 白色背景 + 黑色文字 + 微阴影
 *  - 未选中: 透明背景 + 灰色文字
 *  - 无涟漪,color-fade 过渡
 *
 * 用法:
 * ```
 * SegmentedControl(
 *     items = listOf("会话", "消息内容"),
 *     selectedIndex = selectedTab,
 *     onSelectedChange = { selectedTab = it },
 * )
 * ```
 *
 * @param items 选项文字列表
 * @param selectedIndex 当前选中索引
 * @param onSelectedChange 选中变更回调
 * @param modifier 修饰符
 */
@Composable
fun SegmentedControl(
    items: List<String>,
    selectedIndex: Int,
    onSelectedChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            val interactionSource = remember { MutableInteractionSource() }

            val bgColor by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.surface
                } else {
                    Color.Transparent
                },
                animationSpec = tween(
                    durationMillis = MuseAnimation.TACTILE_MS,
                    easing = MuseAnimation.EaseOutCubic,
                ),
                label = "segment_bg_$index",
            )

            val textColor by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(
                    durationMillis = MuseAnimation.TACTILE_MS,
                    easing = MuseAnimation.EaseOutCubic,
                ),
                label = "segment_text_$index",
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(30.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(bgColor)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = {
                            if (!isSelected) {
                                MuseHaptics.soft(haptic)
                                onSelectedChange(index)
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = textColor,
                )
            }
        }
    }
}
