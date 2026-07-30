package io.zer0.muse.ui.common.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * 通用设置项行 — 左侧图标 + 标题 + 副标题,右侧 trailing 内容。
 *
 * 对标 iOS SwiftUI `NavigationLink` / `Button` in Form 的视觉。
 *
 * @param icon 左侧图标(null 则不显示)
 * @param title 主标题
 * @param subtitle 副标题(null 则不显示,灰色小字)
 * @param onClick 点击回调(null 则不可点击)
 * @param enabled 是否启用点击(默认 true;false 时视觉不变但点击无效)
 * @param trailing 右侧 trailing 内容(默认空,可放箭头 / 数值 / 开关等)
 */
@Composable
fun SettingsItemRow(
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
) {
    val rowInteractionSource = remember { MutableInteractionSource() }
    val isRowPressed by rowInteractionSource.collectIsPressedAsState()
    // iOS 按压: 白/黑偏移 55%, 220ms easeOutCubic
    val colorScheme = MaterialTheme.colorScheme
    val isLight = colorScheme.surface.luminance() > 0.5f
    val pressColor = if (isLight) Color.Black.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.08f)
    val rowBgColor by animateColorAsState(
        targetValue = if (isRowPressed) pressColor else Color.Transparent,
        animationSpec = tween(MuseAnimation.NORMAL_MS, easing = MuseAnimation.EaseOutCubic),
        label = "settingsItemBg",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBgColor)
            .then(
                if (onClick != null && enabled) Modifier.clickable(
                    interactionSource = rowInteractionSource,
                    indication = null,
                    onClick = onClick,
                ) else Modifier,
            )
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        if (icon != null) {
            // 36dp 图标槽(圆角 8dp 背景色块)
            Surface(
                modifier = Modifier.size(36.dp),
                shape = MuseShapes.small,
                color = colorScheme.primaryContainer.copy(alpha = 0.15f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }
        if (trailing != null) {
            trailing()
        }
    }
}
