package io.zer0.muse.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.sp
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseHaptics

/**
 * Muse UI Kit — iOS 风格分段控件 [CapsuleTabBar]。
 *
 * 设计稿对齐(.capsule-tab):
 *  - 容器: surfaceVariant 50%透明背景, 20dp圆角, 2dp内边距, 32dp高度
 *  - 选中项: surface(白色)背景 + onSurface(深色)文字 + 1dp微阴影 + 16dp圆角
 *  - 未选中: 透明背景 + onSurfaceVariant(灰色)文字
 *  - 文字: 12sp, 选中 weight 600 / 未选中 weight 500
 *  - 无涟漪, color-fade 过渡
 *
 * 用法:
 * ```
 * CapsuleTabBar(
 *     tabs = listOf("任务", "Agent", "群聊"),
 *     selectedIndex = currentTab,
 *     onTabSelected = { currentTab = it },
 * )
 * ```
 */
@Composable
fun CapsuleTabBar(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current

    // 容器: surfaceVariant 50%透明, 20dp圆角, 2dp内边距, 32dp高度
    Row(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, label ->
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
                label = "capsule_tab_bg_$index",
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
                label = "capsule_tab_text_$index",
            )

            // 选中项: surface白色背景 + 1dp微阴影 + 16dp圆角
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = {
                            if (!isSelected) {
                                MuseHaptics.soft(haptic)
                                onTabSelected(index)
                            }
                        },
                    ),
                shape = RoundedCornerShape(16.dp),
                color = bgColor,
                shadowElevation = if (isSelected) 1.dp else 0.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = textColor,
                    )
                }
            }
        }
    }
}
