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
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.pill

/**
 * v2.0.1: 设置页搜索栏。
 *
 * - 胶囊形、左侧放大镜、无语音输入；点击进入搜索态（由调用方处理）；
 * - [glass] 模式：只在胶囊形状内部做背景模糊（backdrop blur，轻模糊、可透视下方内容），
 *   用于吸顶悬浮态——由页面把内容层标记为 `Modifier.hazeSource(state)` 后传入 [hazeState]；
 *   API 31+ 为真实高斯模糊，低版本自动降级为半透明 scrim（Haze 内建行为）；
 * - 非玻璃模式：实色浅面胶囊（surface 白），用于随内容滚动的常态。
 *
 * 注：模糊范围仅限胶囊本体——胶囊之外不做任何遮罩。
 */
@Composable
fun MuseSearchBar(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, glass: Boolean = false, hazeState: HazeState? = null) {
    val shape = MaterialTheme.shapes.pill
    val surfaceColor = MaterialTheme.colorScheme.surface
    val base = modifier
        .fillMaxWidth()
        .height(MusePaddings.searchBarHeight)
        .clip(shape)
    val surfaceModifier = if (glass && hazeState != null) {
        base.hazeEffect(
            state = hazeState,
            style = HazeStyle(
                // 底色需要接近实色：合计 0.59 时下方滚动文字仍有四成透出，
                // 吸顶状态下会看到「玻璃里糊着一层字」。提高到 0.86 / 0.12 后
                // 保留模糊质感，但不再透出可辨认的文字。
                backgroundColor = surfaceColor.copy(alpha = 0.86f),
                tint = HazeTint(surfaceColor.copy(alpha = 0.12f)),
                blurRadius = 16.dp,
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
