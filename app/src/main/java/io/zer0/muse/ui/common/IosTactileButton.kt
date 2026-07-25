package io.zer0.muse.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes

/**
 * iOS 触觉图标按钮 — Kelivo 风格,无涟漪效果。
 *
 * 按下时图标颜色渐变 (200ms easeOutCubic),白色/黑色偏移 35%,
 * 替代 Material 标准 IconButton 的涟漪反馈,配合触觉 [MuseHaptics.light] 营造 iOS 质感。
 *
 * 用法:
 * ```
 * IosTactileButton(
 *     icon = Icons.Outlined.Settings,
 *     onClick = { ... },
 *     contentDescription = "设置",
 * )
 * ```
 *
 * @param icon 图标矢量
 * @param onClick 点击回调
 * @param modifier 修饰符
 * @param size 按钮尺寸 (默认 [MuseIconSizes.touchTarget] = 48dp)
 * @param iconSize 图标视觉尺寸 (默认 [MuseIconSizes.icon] = 24dp)
 * @param contentDescription 无障碍描述
 * @param tint 图标颜色 (默认 `onSurface`)
 * @param enabled 是否可用
 */
@Composable
fun IosTactileButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = MuseIconSizes.touchTarget,
    iconSize: Dp = MuseIconSizes.icon,
    contentDescription: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Kelivo 核心: 按下时颜色渐变偏移 35% (白方向 → 变浅, 黑方向 → 变深)
    val isLight = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val pressedColor = if (isLight) {
        tint.copy(
            red = (tint.red + (1f - tint.red) * 0.35f).coerceAtMost(1f),
            green = (tint.green + (1f - tint.green) * 0.35f).coerceAtMost(1f),
            blue = (tint.blue + (1f - tint.blue) * 0.35f).coerceAtMost(1f),
        )
    } else {
        tint.copy(
            red = (tint.red * 0.65f).coerceAtLeast(0f),
            green = (tint.green * 0.65f).coerceAtLeast(0f),
            blue = (tint.blue * 0.65f).coerceAtLeast(0f),
        )
    }

    val animatedTint by animateColorAsState(
        targetValue = if (isPressed) pressedColor else tint,
        animationSpec = tween(durationMillis = MuseAnimation.TACTILE_MS, easing = MuseAnimation.EaseOutCubic),
        label = "tactile_tint",
    )

    IconButton(
        onClick = onClick,
        modifier = modifier.size(size),
        enabled = enabled,
        interactionSource = interactionSource,
        colors = IconButtonDefaults.iconButtonColors(contentColor = animatedTint),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = animatedTint,
        )
    }
}
