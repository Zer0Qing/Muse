package io.zer0.muse.ui.common.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import io.zer0.muse.ui.common.MuseFloatingActionItem
import io.zer0.muse.ui.common.MuseFloatingActionMenu
import io.zer0.muse.ui.common.form.MuseIconContainer
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseActionColors

/**
 * 聊天类页面顶栏的统一图标按钮：**裸图标**（无容器底色）+ 48dp 触控区 + 24dp 图标。
 *
 * 为什么不做成圆底胶囊：应用里其它页面的顶栏（`MuseTopBar`，全部设置页）一直是裸图标，
 * 聊天页原先的三颗灰色圆块既与全局语言不一致，又让返回/标题/更多看起来同样重、没有层级。
 * 顶栏是对齐到系统设置页的极简语言，不是 Telegram 式的悬浮岛。
 *
 * 裸图标压在滚动内容上时的可读性由调用方的顶栏渐变底保证（见 `ChatTopBarScrim`），
 * 不要在按钮自己身上加阴影或毛玻璃。
 */
@Composable
internal fun MuseTopBarIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** 展开态等需要强调时用主色；默认跟随次级实心容器（主题色的低饱和版本）。 */
    tint: Color = MuseActionColors.tonalContent,
    /**
     * [solid] 为 true 时渲染实心圆形底（主题主色容器）+ 反相图标，
     * 让全部可点按钮口径一致；列表行内联小操作用 false 保持裸图标，避免每行两个圆块。
     *
     * 容器取 tonal（primaryContainer）而非高饱和主色：顶栏是导航镀铬层，
     * 一屏里可能同时出现返回/菜单/更多三颗圆标，高饱和会盖过页面内容。
     */
    solid: Boolean = true,
) {
    MuseTactileButton(
        icon = icon,
        onClick = onClick,
        contentDescription = contentDescription,
        enabled = enabled,
        modifier = modifier,
        container = if (solid) MuseIconContainer.Tonal else MuseIconContainer.None,
        tint = tint,
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
                icon = Icons.Outlined.MoreVert,
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
@Composable
internal fun ChatTopBarScrim(modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        background,
                        background.copy(alpha = 0.86f),
                        Color.Transparent,
                    ),
                ),
            ),
    )
}
