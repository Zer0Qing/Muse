@file:Suppress("FunctionNaming", "MatchingDeclarationName")

package io.zer0.muse.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import compose.icons.TablerIcons
import compose.icons.tablericons.Check
import io.zer0.muse.ui.common.surface.MuseDivider
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MuseShapes

/** 顶栏浮动菜单中的独立操作项。 */
internal data class MuseFloatingActionItem(
    val key: String,
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean = true,
    val tint: Color? = null,
    val checked: Boolean? = null,
    val onClick: () -> Unit,
)

/**
 * 顶栏「更多」的浮动菜单:一张标准菜单卡片 + 等高整行。
 *
 * 之前是「灰底大容器 + 每项一个宽度不一的白色胶囊」的双层嵌套,视觉上是一团胶囊汤;
 * 现在按设计规范走原生菜单:单层容器(surfaceContainerHigh + 细分割线)、所有行等高、
 * 宽度统一,勾选项在行尾显示勾,禁用行整体降透明度。整卡一次性淡入,不再逐项飞入。
 */
@Composable
internal fun MuseFloatingActionMenu(
    items: List<MuseFloatingActionItem>,
    onDismiss: () -> Unit,
    offset: IntOffset? = null,
    belowAnchorDp: Dp = 56.dp,
) {
    val density = LocalDensity.current
    val reducedMotion = MuseMotion.isReducedMotion()
    val duration = if (reducedMotion) 0 else MuseAnimation.FAST_NORMAL_MS
    val resolvedOffset = offset ?: with(density) {
        IntOffset(0, belowAnchorDp.roundToPx())
    }
    Popup(
        onDismissRequest = onDismiss,
        alignment = Alignment.TopEnd,
        offset = resolvedOffset,
    ) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(MuseMotion.tween(duration)) +
                scaleIn(
                    animationSpec = MuseMotion.tween(duration),
                    initialScale = 0.94f,
                    transformOrigin = TransformOrigin(1f, 0f),
                ),
        ) {
            Surface(
                shape = MuseShapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = MuseElevation.high,
                tonalElevation = 0.dp,
                // 与屏幕边缘留出间距,避免卡片贴边
                modifier = Modifier.padding(end = 8.dp, top = 4.dp),
            ) {
                Column(
                    // 统一最小宽度:短标签的项不再把卡片挤成窄条,所有行左右对齐
                    modifier = Modifier.widthIn(min = 232.dp, max = 320.dp),
                ) {
                    items.forEachIndexed { index, item ->
                        key(item.key) {
                            MenuRow(item = item)
                        }
                        if (index != items.lastIndex) {
                            MuseDivider(startIndent = 0.dp, thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
    }
}

/** 菜单行:等高、整行可点、行尾勾选;禁用时整体降透明度且不响应点击。 */
@Composable
private fun MenuRow(item: MuseFloatingActionItem) {
    val foreground = item.tint ?: if (item.enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 48dp 触控目标(MD3 菜单行高度),点按有涟漪
            .heightIn(min = MuseIconSizes.touchTarget)
            .clickable(enabled = item.enabled, onClick = item.onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(MuseIconSizes.iconMedium),
        )
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyMedium,
            color = foreground,
            modifier = Modifier.weight(1f),
        )
        item.checked?.let { checked ->
            Box(modifier = Modifier.size(MuseIconSizes.iconMedium)) {
                if (checked) {
                    Icon(
                        imageVector = TablerIcons.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                }
            }
        }
    }
}
