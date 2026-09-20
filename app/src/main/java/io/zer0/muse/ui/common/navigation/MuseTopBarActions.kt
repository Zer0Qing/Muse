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
    /** 展开态等需要强调时用主色；默认跟随实心内容色。 */
    tint: Color = MuseActionColors.content,
    /**
     * UI-FIX A：[solid] 为 true 时渲染实心圆形底（浅色近黑 / 深色近白）+ 反相图标，
     * 让全部可点按钮口径一致；列表行内联小操作用 false 保持裸图标，避免每行两个黑圆。
     */
    solid: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
    ) {
        if (solid) {
            Box(
                modifier = Modifier
                    .size(MuseIconSizes.topBarSolid)
                    .clip(CircleShape)
                    .background(if (enabled) MuseActionColors.container else MuseActionColors.neutralContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = if (enabled) tint else MuseActionColors.mutedContent,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            }
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(MuseIconSizes.icon),
            )
        }
    }
}

/**
 * 顶栏右侧统一的「更多」入口：一颗裸图标 + 无遮罩浮动菜单。
 *
 * 页面把自己的操作作为 [items] 传进来即可，菜单外观、动画与「点完自动收起」全部一致。
 * 菜单展开时按钮会显示淡底 + 主色图标，让浮层与来源按钮的归属关系看得出来。
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
                tint = if (highlighted) MaterialTheme.colorScheme.primary else MuseActionColors.content,
            )
        }
        if (expanded) {
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
