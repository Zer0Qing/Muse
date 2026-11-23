package io.zer0.muse.ui.common.form

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion

/**
 * 图标按钮统一件 — 全应用所有「只有图标、没有文字」的按钮都用它。
 *
 * 触摸区域恒为 [size]（默认 [MuseIconSizes.touchTarget] = 48dp），
 * 视觉圆形由 [container] 与 [visualSize] 决定：
 *  - [MuseIconContainer.None]  无底色，行内图标（视觉 = 触摸区）
 *  - [MuseIconContainer.Tonal] 浅色圆底，顶栏图标（视觉 = 36dp）
 *  - [MuseIconContainer.Solid] 主题色圆底，主操作
 *  - [MuseIconContainer.Neutral] 中性底，未选中/次要
 *
 * 按压反馈:图标颜色渐变 (200ms easeOutCubic) 偏移 35%，
 * 有底色时叠加 0.92x 缩放；不出现 Material 涟漪。
 *
 * 用法:
 * ```
 * MuseTactileButton(
 *     icon = MuseIcons.sliders,
 *     onClick = { ... },
 *     contentDescription = "设置",
 *     container = MuseIconContainer.Tonal,
 * )
 * ```
 *
 * @param icon 图标矢量
 * @param onClick 点击回调
 * @param modifier 修饰符
 * @param size 触摸区域直径 (默认 [MuseIconSizes.touchTarget] = 48dp)
 * @param iconSize 图标视觉尺寸 (默认 [MuseIconSizes.icon] = 24dp)
 * @param contentDescription 无障碍描述（图标按钮必须有）
 * @param tint 无底色时的图标颜色 (默认 `onSurface`；有底色时由 [container] 决定)
 * @param enabled 是否可用
 * @param container 底色变体
 * @param visualSize 圆形底色直径；默认无底色取 [size]，有底色取 [MuseIconSizes.topBarSolid] = 36dp
 */
@Composable
fun MuseTactileButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = MuseIconSizes.touchTarget,
    iconSize: Dp = MuseIconSizes.icon,
    contentDescription: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    container: MuseIconContainer = MuseIconContainer.None,
    visualSize: Dp? = null,
    compact: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val (rawContainer, rawContent) = when (container) {
        MuseIconContainer.None -> Color.Transparent to tint
        MuseIconContainer.Tonal -> MuseActionColors.tonalContainer to MuseActionColors.tonalContent
        MuseIconContainer.Solid -> MuseActionColors.container to MuseActionColors.content
        MuseIconContainer.Neutral -> MuseActionColors.neutralContainer to MuseActionColors.neutralContent
    }

    val pressBase = if (container == MuseIconContainer.None) {
        MaterialTheme.colorScheme.surface
    } else {
        rawContainer
    }
    val isLight = pressBase.luminance() > 0.5f
    val pressedColor = if (isLight) lighten(rawContent, 0.35f) else darken(rawContent, 0.65f)

    val targetTint = when {
        !enabled -> rawContent.copy(alpha = MuseActionColors.disabledAlpha)
        isPressed -> pressedColor
        else -> rawContent
    }
    val animatedTint by animateColorAsState(
        targetValue = targetTint,
        animationSpec = MuseMotion.tween(
            durationMillis = MuseAnimation.TACTILE_MS,
            easing = MuseAnimation.EaseOutCubic,
        ),
        label = "tactile_tint",
    )

    val hasContainer = container != MuseIconContainer.None
    val resolvedSize = if (compact && size == MuseIconSizes.touchTarget) {
        MuseIconSizes.controlTouch
    } else {
        size
    }
    val resolvedIconSize = if (compact && iconSize == MuseIconSizes.icon) {
        MuseIconSizes.iconSmall
    } else {
        iconSize
    }
    val effectiveVisual = visualSize ?: if (hasContainer) {
        if (compact) MuseIconSizes.iconVideo else MuseIconSizes.topBarSolid
    } else {
        resolvedSize
    }

    val boxColor = if (!enabled && rawContainer != Color.Transparent) {
        rawContainer.copy(alpha = MuseActionColors.disabledAlpha)
    } else {
        rawContainer
    }

    Box(
        modifier = modifier
            .size(resolvedSize)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(effectiveVisual)
                .clip(CircleShape)
                .background(boxColor)
                .background(
                    color = if (isPressed && enabled) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = MuseAlpha.pressedOverlay)
                    } else {
                        Color.Transparent
                    },
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(resolvedIconSize),
                tint = animatedTint,
            )
        }
    }
}

/** 图标按钮的底色变体。 */
enum class MuseIconContainer {
    /** 无底色，行内图标。 */
    None,

    /** 浅色圆底（顶栏图标按钮）。 */
    Tonal,

    /** 主题色圆底（主操作）。 */
    Solid,

    /** 中性底（次要/未选中）。 */
    Neutral,
}

private fun lighten(color: Color, ratio: Float): Color = color.copy(
    red = (color.red + (1f - color.red) * ratio).coerceAtMost(1f),
    green = (color.green + (1f - color.green) * ratio).coerceAtMost(1f),
    blue = (color.blue + (1f - color.blue) * ratio).coerceAtMost(1f),
)

private fun darken(color: Color, factor: Float): Color = color.copy(
    red = (color.red * factor).coerceAtLeast(0f),
    green = (color.green * factor).coerceAtLeast(0f),
    blue = (color.blue * factor).coerceAtLeast(0f),
)
