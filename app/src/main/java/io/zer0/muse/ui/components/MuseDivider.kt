package io.zer0.muse.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Muse UI Kit — 分隔线基元 [MuseDivider]。
 *
 * v1.0.26: 统一所有列表分隔线、卡片内分隔线、表单分组的实现,
 * 消除各页面裸用 [HorizontalDivider] 时 thickness/color/startIndent 取值不统一的问题。
 *
 * 规格:
 *  - thickness: 0.5dp(对齐 iOS Settings,极细但可见)
 *  - color: outlineVariant @ 70% alpha(v1.0.27:从 50% 提升,解决浅色背景分隔线几乎不可见的问题)
 *  - 默认 startIndent: 56dp(= 16dp 卡片内边距 + 24dp leading icon + 16dp icon-to-text gap)
 *    对齐 iOS Settings 的"从 leading 内容右侧开始分隔"风格
 */
@Composable
fun MuseDivider(
    modifier: Modifier = Modifier,
    startIndent: Dp = 56.dp,
    thickness: Dp = 0.5.dp,
    color: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
) {
    HorizontalDivider(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = startIndent),
        thickness = thickness,
        color = color,
    )
}
