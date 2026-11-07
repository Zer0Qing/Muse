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
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings

/**
 * Muse UI Kit — 分隔线基元 [MuseDivider]。
 *
 * v1.0.26: 统一所有列表分隔线、卡片内分隔线、表单分组的实现,
 * 消除各页面裸用 [HorizontalDivider] 时 thickness/color/startIndent 取值不统一的问题。
 *
 * 规格:
 *  - thickness: 0.5dp（对齐系统设置：1px 发丝线）
 *  - color: onSurface @ 13% alpha（v2.0.1：改为随文字墨色派生——白卡上约 #E1E1E1，
 *    对齐实测线色 #E0E0E0；深色模式自动变深灰，跨主题自适应。
 *    历史值 outlineVariant@70% 经"偏白归一化"后被拉得太淡，浅色下几乎不可见。）
 *  - startIndent: 跟随内容左边线,由 [museDividerStartIndent] 按是否有 leading 计算;
 *    默认取"有 leading"档(56dp = 16dp 行内边距 + 24dp 图标 + 16dp 图标-文字间距),
 *    与 MuseListItem 的 leading 布局同源。无图标卡由调用方传 hasLeading=false 落到 16dp 内容线。
 */

/**
 * 分隔线起点缩进(纯函数,便于单测)——跟随内容左边线,不是固定值。
 *
 * 无 leading: 对齐行内文字起点([MusePaddings.screen])。
 * 有 leading: 对齐图标右侧的文字列 = 行内边距 + 图标 + 图标-文字间距,
 * 三项与 [MuseListItem] 的 leading 布局逐一对应,避免假定缩进漂移。
 */
fun museDividerStartIndent(hasLeading: Boolean): Dp =
    if (hasLeading) {
        MusePaddings.screen + MuseIconSizes.icon + MusePaddings.screen
    } else {
        MusePaddings.screen
    }

@Composable
fun MuseDivider(
    modifier: Modifier = Modifier,
    startIndent: Dp = museDividerStartIndent(hasLeading = true),
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
