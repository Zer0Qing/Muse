package io.zer0.muse.ui.common.surface

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
 *  - thickness: 0.5dp（对齐 iOS Settings / ColorOS：1px 发丝线）
 *  - color: onSurface @ 13% alpha（v2.0.1：改为随文字墨色派生——白卡上约 #E1E1E1，
 *    对齐 ColorOS 17 实测线色 #E0E0E0；深色模式自动变深灰，跨主题自适应。
 *    历史值 outlineVariant@70% 经"偏白归一化"后被拉得太淡，浅色下几乎不可见。）
 *  - 默认 startIndent: 56dp(= 16dp 卡片内边距 + 24dp leading icon + 16dp icon-to-text gap)
 *    对齐 iOS Settings 的"从 leading 内容右侧开始分隔"风格
 */
@Composable
fun MuseDivider(
    modifier: Modifier = Modifier,
    startIndent: Dp = 56.dp,
    thickness: Dp = 0.5.dp,
    color: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f),
) {
    HorizontalDivider(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = startIndent),
        thickness = thickness,
        color = color,
    )
}
