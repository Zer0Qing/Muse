package io.zer0.muse.ui.common.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.common.MuseFloatingActionItem
import io.zer0.muse.ui.common.MuseFloatingActionMenu
import io.zer0.muse.ui.common.form.MuseIconContainer
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings

/**
 * 聊天类页面顶栏的统一图标按钮：**裸图标**（无容器底色）+ 48dp 触控区 + 24dp 图标。
 *
 * 为什么不做成圆底胶囊：应用里其它页面的顶栏（`MuseTopBar`，全部设置页）一直是裸图标，
 * 聊天页原先的三颗灰色圆块既与全局语言不一致，又让返回/标题/更多看起来同样重、没有层级。
 * 顶栏是对齐到系统设置页的极简语言，不做悬浮岛。
 *
 * 裸图标压在滚动内容上时的可读性由调用方的顶栏渐变底保证（见 `ChatTopBarScrim`），
 * 不要在按钮自己身上加阴影或毛玻璃。
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun MuseTopBarIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** 展开态等需要强调时用主色；默认(Color.Unspecified)跟随当前内容色。 */
    tint: Color = Color.Unspecified,
    /**
     * [solid] 为 true 时渲染实心圆形底（主题主色容器）+ 反相图标，
     * 让全部可点按钮口径一致；列表行内联小操作用 false 保持裸图标，避免每行两个圆块。
     *
     * 容器取 tonal（primaryContainer）而非高饱和主色：顶栏是导航镀铬层，
     * 一屏里可能同时出现返回/菜单/更多三颗圆标，高饱和会盖过页面内容。
     */
    solid: Boolean = true,
    /** v2.6: 玻璃岛模式 —— true 时按钮壳改用玻璃(backdrop/liquid 真玻璃或降级假玻璃),实色容器退居基调色。 */
    glassActive: Boolean = false,
    glassConfig: io.zer0.muse.ui.theme.LiquidGlassConfig = io.zer0.muse.ui.theme.LiquidGlassConfig(),
) {
    val useGlass = glassActive && glassConfig.enabled && solid
    // v2.6: 未显式指定 tint 时跟随当前内容色(LocalContentColor),
    // 使玻璃岛内的图标自动适配背景明暗(GlassIsland 已注入对比色)。
    val resolvedTint = if (tint == Color.Unspecified) androidx.compose.material3.LocalContentColor.current else tint
    if (useGlass) {
        // 玻璃模式:外壳交给 GlassIsland(圆形岛),图标居中,不画实色容器。
        io.zer0.muse.ui.theme.GlassIsland(
            config = glassConfig,
            shape = androidx.compose.foundation.shape.CircleShape,
            solidColor = MuseActionColors.tonalContainer,
            modifier = modifier.size(MuseIconSizes.touchTarget),
        ) {
            MuseTactileButton(
                icon = icon,
                onClick = onClick,
                contentDescription = contentDescription,
                enabled = enabled,
                container = MuseIconContainer.None,
                tint = resolvedTint,
                iconSize = MuseIconSizes.iconMedium,
                visualSize = MuseIconSizes.topBarSolid,
            )
        }
        return
    }
    MuseTactileButton(
        icon = icon,
        onClick = onClick,
        contentDescription = contentDescription,
        enabled = enabled,
        modifier = modifier,
        container = if (solid) MuseIconContainer.Tonal else MuseIconContainer.None,
        tint = if (tint == Color.Unspecified) MuseActionColors.tonalContent else tint,
        iconSize = if (solid) MuseIconSizes.iconMedium else MuseIconSizes.icon,
        visualSize = MuseIconSizes.topBarSolid,
    )
}

/**
 * 顶栏右侧统一的「更多」入口：一颗裸图标 + 右上角浮层菜单（页面右上角三点位置）。
 *
 * 页面把自己的操作作为 [items] 传进来即可，菜单外观、动画与「点完自动收起」全部一致。
 * 展开时按钮显示主题色图标，让浮层与来源按钮的归属关系看得出来。
 */
@Composable
internal fun MuseTopBarMenu(
    items: List<MuseFloatingActionItem>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val highlighted = expanded && enabled
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(MuseIconSizes.touchTarget)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            MuseTopBarIconButton(
                icon = MuseIcons.moreVertical,
                contentDescription = contentDescription,
                onClick = { expanded = true },
                enabled = enabled,
                tint = if (highlighted) MaterialTheme.colorScheme.primary else MuseActionColors.tonalContent,
            )
        }
        if (expanded) {
            // v1.0.90: 保留顶栏右上角的浮层菜单（曾短暂改成底部面板，按反馈改回）。
            MuseFloatingActionMenu(
                items = items.map { item ->
                    item.copy(
                        onClick = {
                            expanded = false
                            item.onClick()
                        },
                    )
                },
                onDismiss = { expanded = false },
            )
        }
    }
}

/**
 * 聊天页顶栏的渐变底：内容会从顶栏下面滚过，裸图标需要一个极浅的从上到下淡出来保证可读性。
 *
 * 只用背景色到透明的三段渐变，不加阴影、不做毛玻璃——符合「层次靠背景色差」的规范。
 */
@Suppress("FunctionNaming")
@Composable
internal fun ChatTopBarScrim(
    modifier: Modifier = Modifier,
    glassActive: Boolean = false,
    glassConfig: io.zer0.muse.ui.theme.LiquidGlassConfig = io.zer0.muse.ui.theme.LiquidGlassConfig(),
) {
    val background = MaterialTheme.colorScheme.background
    // v2.5.0: 独立玻璃大岛 —— 顶栏不再用整条横接渐变,改为居中的胶囊岛
    // (对齐群聊顶栏的大岛语言),岛内含返回键/标题/更多键。玻璃关闭时回退旧 scrim。
    if (glassActive && glassConfig.enabled) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = MusePaddings.screen, vertical = 4.dp)
                .then(
                    io.zer0.muse.ui.theme.glassSurfaceModifier(
                        shape = CircleShape,
                        surfaceColor = background,
                        config = glassConfig,
                        backdrop = io.zer0.muse.ui.theme.LocalLayerBackdrop.current,
                        waterState = io.zer0.muse.ui.theme.LocalWaterGlassState.current,
                    ),
                ),
        )
    } else {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        // v2.0.1: 顶部实区拉长、过渡更快 — 滚过的消息残影不再"顶得满"（用户反馈顶部挤）；
                        // 尾部仍保留渐出，避免硬边界。
                        colorStops = arrayOf(
                            0f to background,
                            0.62f to background,
                            0.85f to background.copy(alpha = 0.82f),
                            1f to Color.Transparent,
                        ),
                    ),
                ),
        )
    }
}
