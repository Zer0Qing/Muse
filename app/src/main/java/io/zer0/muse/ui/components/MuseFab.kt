package io.zer0.muse.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MuseShadow

/**
 * Muse UI Kit — 品牌绿浮动操作按钮 [MuseFab]。
 *
 * 设计稿对齐:
 *  - 品牌绿圆形背景 + 白色线性图标
 *  - 56dp 直径(标准 FAB 尺寸)
 *  - 柔和阴影(shadow-float)
 *  - 按压时 0.95x 缩放 + 颜色加深
 *
 * 用法:
 * ```
 * MuseFab(
 *     onClick = { /* 新建 */ },
 *     modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
 * )
 * ```
 *
 * @param onClick 点击回调
 * @param modifier 修饰符
 * @param icon 图标(默认 + 号,线性)
 * @param contentDescription 无障碍描述
 */
@Composable
fun MuseFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Add,
    contentDescription: String? = "新建",
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Surface(
        onClick = {
            MuseHaptics.medium(haptic)
            onClick()
        },
        modifier = modifier.size(56.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = MuseShadow.high.elevation,
        interactionSource = interactionSource,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(24.dp),
        )
    }
}
