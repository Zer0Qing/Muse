package io.zer0.muse.ui.common

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.huge

/**
 * iOS 风格浮动按钮 — 替代 Material3 [androidx.compose.material3.FloatingActionButton]。
 *
 * 视觉差异:
 *  - 无 ripple 涟漪(用 pressed scale + 颜色渐变替代)
 *  - 圆形 [MuseShapes.huge] 容器,primary 色背景
 *  - 按下时 0.92 缩放(弹簧曲线),模拟 iOS 按压反馈
 *  - 56dp 默认尺寸符合 Material FAB 规范
 *
 * 用法:
 * ```
 * IosFloatingButton(
 *     icon = Icons.Filled.Add,
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
fun IosFloatingButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    size: Dp = 56.dp,
    iconSize: Dp = MuseIconSizes.icon,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // 按下时 0.92 缩放(弹簧曲线),模拟 iOS 按压反馈
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "ios_fab_scale",
    )

    Surface(
        shape = MuseShapes.huge,
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = if (isPressed) 4.dp else 8.dp,
        tonalElevation = 0.dp,
        interactionSource = interactionSource,
        onClick = onClick,
        modifier = modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
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
                contentDescription = null,  // 已在 semantics 提供
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
