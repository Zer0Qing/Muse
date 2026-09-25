package io.zer0.muse.ui.common.form

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.pill

/**
 * v2.0.1: 设置页搜索栏（ColorOS 17 结构借鉴）。
 *
 * - 胶囊形、左侧放大镜、无语音输入；点击进入搜索态（由调用方处理）；
 * - [glass] 模式：只在胶囊形状内部做背景模糊（backdrop blur，轻模糊、可透视下方内容），
 *   用于吸顶悬浮态——由页面把内容层标记为 `Modifier.hazeSource(state)` 后传入 [hazeState]；
 *   API 31+ 为真实高斯模糊，低版本自动降级为半透明 scrim（Haze 内建行为）；
 * - 非玻璃模式：实色浅面胶囊（surface 白），用于随内容滚动的常态。
 *
 * 注：模糊范围仅限胶囊本体——胶囊之外不做任何遮罩（ColorOS 同款语义）。
 */
@Composable
fun MuseSearchBar(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glass: Boolean = false,
    hazeState: HazeState? = null,
) {
    val shape = MaterialTheme.shapes.pill
    val surfaceColor = MaterialTheme.colorScheme.surface
    val base = modifier
        .fillMaxWidth()
        .height(48.dp)
        .clip(shape)
    val surfaceModifier = if (glass && hazeState != null) {
        base.hazeEffect(
            state = hazeState,
            style = HazeStyle(
                // v2.0.1 调实：原 tint 0.38 透明感过强，改为基础底 0.30 + 着色 0.42（合计≈0.59）。
                backgroundColor = surfaceColor.copy(alpha = 0.30f),
                tint = HazeTint(surfaceColor.copy(alpha = 0.42f)),
                blurRadius = 12.dp,
                noiseFactor = 0f,
            ),
        )
    } else {
        base.background(surfaceColor)
    }
    Box(
        modifier = surfaceModifier.clickable(onClick = onClick),
        // v2.0.1 fix: 内容必须显式垂直居中——Box 默认 TopStart，Row 仅 fillMaxWidth 时
        // 图标与文字会贴到胶囊顶部（搜索栏文字位置上偏的缺陷）。
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = MuseIcons.search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
