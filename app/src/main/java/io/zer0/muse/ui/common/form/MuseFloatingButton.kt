package io.zer0.muse.ui.common.form

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MuseButtonSizes
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpringTier
import io.zer0.muse.ui.theme.pill

/**
 * 浮动按钮 — 替代 Material3 [androidx.compose.material3.FloatingActionButton]。
 *
 * 视觉差异:
 *  - 无 ripple 涟漪(用 pressed scale + 颜色渐变替代)
 *  - 圆形 [MuseShapes.huge] 容器,primary 色背景
 *  - 按下时 0.92 缩放(弹簧曲线),模拟按压反馈
 *  - 56dp 默认尺寸符合 Material FAB 规范
 *
 * 用法:
 * ```
 * MuseFloatingButton(
 *     icon = MuseIcons.plus,
 *     onClick = { importLauncher.launch(...) },
 *     contentDescription = "导入插件",
 * )
 * ```
 *
 * @param icon 图标矢量
 * @param onClick 点击回调
 * @param modifier Modifier
 * @param contentDescription 无障碍描述(强烈建议提供)
 * @param containerColor 容器颜色(默认 primary)
 * @param contentColor 图标颜色(默认 onPrimary)
 * @param size 按钮尺寸(默认 56dp,符合 Material FAB 默认尺寸)
 * @param iconSize 图标视觉尺寸(默认 [MuseIconSizes.icon])
 */
@Composable
fun MuseFloatingButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    containerColor: Color = MuseActionColors.container,
    contentColor: Color = MuseActionColors.content,
    size: Dp = MuseIconSizes.fab,
    iconSize: Dp = MuseIconSizes.icon,
    compact: Boolean = false,
) {
    val effectiveSize = if (compact && size == MuseIconSizes.fab) MuseButtonSizes.fabCompact else size
    val effectiveIconSize = if (compact && iconSize == MuseIconSizes.icon) MuseIconSizes.iconMedium else iconSize
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = MuseMotion.spring(MuseSpringTier.RESPONSIVE),
        label = "ios_fab_scale",
    )

    Surface(
        shape = MuseShapes.pill,
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = MuseElevation.modal,
        tonalElevation = MuseElevation.none,
        modifier = modifier
            .size(effectiveSize)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .semantics {
                role = Role.Button
                if (contentDescription != null) this.contentDescription = contentDescription
            },
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(effectiveIconSize),
            )
        }
    }
}
