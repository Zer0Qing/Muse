package io.zer0.muse.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import io.zer0.muse.ui.theme.MuseIconSizes

/**
 * iOS 风格设置图标 — 黑白线条风格。
 *
 * 直接渲染 24dp 线条图标，颜色跟随 onSurface（自动适配亮/暗色模式）。
 *
 * @param icon 线条图标（推荐使用 Icons.Outlined.*）
 * @param modifier 修饰符
 */
@Composable
fun IosSettingsIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.size(MuseIconSizes.icon),
    )
}
