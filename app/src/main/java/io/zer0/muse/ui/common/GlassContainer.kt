package io.zer0.muse.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseCornerRadius

/**
 * 毛玻璃容器 — Kelivo GlassInputBar 风格。
 *
 * 为输入栏或其他组件提供毛玻璃视觉效果:
 *  - 半透明 surface 色背景
 *  - outline@20% alpha 边框 (1px)
 *  - 圆角 20dp
 *
 * 注: Compose 原生 BackdropFilter 需配合 [androidx.compose.ui.graphics.RenderEffect]
 * 在 Android 12+ 才可用, 此处使用兼容方案 (半透明 + 微模糊模拟)。
 *
 * 用法:
 * ```
 * GlassContainer {
 *     // 输入栏内容
 * }
 * ```
 *
 * @param modifier 修饰符
 * @param cornerRadius 圆角半径 (默认 20dp)
 * @param blurRadius 模糊半径 (默认 0dp, 仅在 Android 12+ 生效)
 * @param content 容器内容
 */
@Composable
fun GlassContainer(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = MuseCornerRadius.CARD.dp, // 20dp
    blurRadius: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val glassBg = colorScheme.surface.copy(alpha = 0.85f)
    val borderColor = colorScheme.outline.copy(alpha = 0.20f)
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .clip(shape)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .then(
                if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier
            ),
    ) {
        // 半透明背景层
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBehind {
                    drawRect(color = glassBg)
                }
        )
        content()
    }
}
