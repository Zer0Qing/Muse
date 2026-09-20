@file:Suppress("FunctionNaming", "MatchingDeclarationName")

package io.zer0.muse.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseElevation
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
 * 顶栏「更多」的浮动菜单。
 *
 * v1.0.93 重做：原来是「灰底面板 + 逐行细分割线 + 行尾打勾」的密集菜单，视觉上像一张
 * 系统设置表格。现在按主流对话 App（GPT 等）的语言重做：
 *  - 面板用最浅的一层底（surfaceContainerLowest，浅色下即纯白）+ 阴影分层，
 *    不再用 surfaceContainerHigh 的灰底，也不用分割线 —— 层级靠留白和圆角表达；
 *  - 每行左侧一颗圆形图标片（surfaceVariant 底 + 中性图标），标签只留文字；
 *  - 开关态不在行尾打勾，改为**图标本身用主题色点亮**（primary 图标 + primaryContainer 图标片），
 *    状态直接长在图标上，行尾不再多一个控件；
 *  - 整体收窄、行高压到 44dp，面板不再占掉半屏。
 *
 * 侧滑/淡入动画与「点完自动收起」的约定保持不变。
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
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shadowElevation = MuseElevation.high,
                tonalElevation = 0.dp,
                // 与屏幕边缘留出间距,避免卡片贴边
                modifier = Modifier.padding(end = 8.dp, top = 4.dp),
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(min = 176.dp, max = 232.dp)
                        .padding(vertical = 6.dp),
                ) {
                    items.forEach { item ->
                        key(item.key) { MenuRow(item = item) }
                    }
                }
            }
        }
    }
}

/**
 * 菜单行：圆形图标片 + 标签，整行可点。
 *
 * 开关态（[MuseFloatingActionItem.checked] 为 true）用主题色点亮图标表达，行尾不放勾。
 */
@Composable
private fun MenuRow(item: MuseFloatingActionItem) {
    val colors = MaterialTheme.colorScheme
    val isOn = item.checked == true
    val labelAlpha = if (item.enabled) 1f else MuseActionColors.disabledAlpha

    val iconTint = when {
        !item.enabled -> colors.onSurfaceVariant.copy(alpha = labelAlpha)
        item.tint != null -> item.tint ?: colors.onSurfaceVariant
        isOn -> colors.primary
        else -> colors.onSurfaceVariant
    }
    val chipColor = if (isOn && item.enabled) colors.primaryContainer else colors.surfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(enabled = item.enabled, onClick = item.onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(chipColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(17.dp),
            )
        }
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface.copy(alpha = labelAlpha),
            modifier = Modifier.weight(1f),
        )
    }
}
