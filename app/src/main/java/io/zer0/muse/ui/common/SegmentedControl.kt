package io.zer0.muse.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseCornerRadius

/**
 * iOS 风格分段选择器 — Kelivo 设计语言通用组件。
 *
 * 替代项目中散落的 private SegmentedControl / SegmentedOptionRow 实现,
 * 提供统一的公共 API。动画滑动指示器 + 字号 13sp + emphasis 字重。
 *
 * 用法:
 * ```
 * SegmentedControl(
 *     options = listOf("跟随系统", "亮色", "暗色"),
 *     selectedIndex = selectedMode,
 *     onSelectedChange = { newMode = it },
 * )
 * ```
 *
 * @param options 选项文本列表 (2-5 个)
 * @param selectedIndex 当前选中索引
 * @param onSelectedChange 选中变更回调
 * @param modifier 修饰符
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelectedChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val containerColor = colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val selectedBg = colorScheme.surface
    val unselectedText = colorScheme.onSurface.copy(alpha = 0.6f)
    val selectedText = colorScheme.onSurface

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(MuseCornerRadius.BUTTON.dp))
            .background(containerColor)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) selectedBg else containerColor,
                animationSpec = tween(MuseAnimation.FAST_NORMAL_MS, easing = MuseAnimation.EaseOutCubic),
                label = "seg_bg_$index",
            )
            val textColor by animateColorAsState(
                targetValue = if (isSelected) selectedText else unselectedText,
                animationSpec = tween(MuseAnimation.FAST_NORMAL_MS, easing = MuseAnimation.EaseOutCubic),
                label = "seg_text_$index",
            )
            val fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(30.dp)
                    .clip(RoundedCornerShape((MuseCornerRadius.BUTTON - 2).dp))
                    .background(bgColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab,
                        onClick = { onSelectedChange(index) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = fontWeight,
                    color = textColor,
                    maxLines = 1,
                )
            }
        }
    }
}
