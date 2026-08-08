package io.zer0.muse.ui.common.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseShadow

/**
 * v1.0.72: Muse 岛规范组件 — 所有悬浮式 UI 组件(引用块/提示条/工具胶囊等)统一用此样式。
 *
 * 岛 = 圆角形状 + 半透明背景(非全宽遮罩) + 微阴影,独立悬浮在页面内容之上。
 *
 * 规范(强制项):
 *  - 背景用 [MaterialTheme.colorScheme.surfaceVariant] 半透明(默认 0.45),
 *    禁止整块全宽实色背景(那会被视为\"遮罩\")\n  - 圆角默认 16dp(岛感;小胶囊用 CircleShape 自行指定)\n  - 带微阴影,视觉上与背景分离\n  - 不要横贯全宽的背景色块\n *
 * 使用方式:
 * ```kotlin\n * MuseIsland(modifier = Modifier.fillMaxWidth()) { ... }\n * ```\n *\n * @param modifier 外层修饰符(由调用方决定宽度/边距)\n * @param shape 形状(默认 16dp 圆角;圆形胶囊可传 CircleShape)\n * @param backgroundAlpha 背景透明度(0.45 = 半透明岛;1.0 = 实色胶囊)\n * @param content 岛内容\n */
@Composable
fun MuseIsland(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    backgroundAlpha: Float = 0.45f,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = backgroundAlpha),
        shape = shape,
        tonalElevation = MuseElevation.low,
        shadowElevation = MuseShadow.low.elevation,
        modifier = modifier,
    ) {
        Box(modifier = Modifier, content = content)
    }
}
