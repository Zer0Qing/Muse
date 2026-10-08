package io.zer0.muse.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * v2.6: 可复用「玻璃岛」容器 —— 顶栏三岛 / 输入岛 / 更多菜单 / 按钮统一走这里，
 * 保证玻璃质感一致。
 *
 * 引擎与降级由 [glassSurfaceModifier] 统一决策（FROST=backdrop / WATER=liquid /
 * 低版本=假玻璃），本容器不再直接依赖具体引擎，也不区分 haze/backdrop 两套源。
 *
 * 玻璃未启用时回退为 [solidColor] 实色底，调用方无需分支。
 *
 * @param shape 岛形（胶囊/圆角/圆形）
 * @param solidColor 关闭玻璃时的实色底（也是玻璃 tint 的基调色）
 * @param contentPadding 岛内容内边距
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun GlassIsland(
    config: LiquidGlassConfig,
    shape: Shape,
    solidColor: Color,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val useGlass = config.enabled
    val base =
        if (useGlass) {
            glassSurfaceModifier(
                shape = shape,
                surfaceColor = solidColor,
                config = config,
                backdrop = LocalLayerBackdrop.current,
                waterState = LocalWaterGlassState.current,
            )
        } else {
            Modifier.clip(shape).then(Modifier.background(solidColor))
        }
    Box(modifier = modifier.then(base).padding(contentPadding), content = content)
}
