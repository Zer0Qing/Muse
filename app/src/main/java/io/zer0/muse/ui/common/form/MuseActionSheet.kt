package io.zer0.muse.ui.common.form

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.common.MuseFloatingActionItem
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * 底部弹出的操作菜单（自下而上）。
 *
 * 顶栏「更多」原来是从右上角弹出的浮层卡片：贴边、锚点依赖顶栏测量，且与页面里其它
 * 「长按 → 底部菜单」的操作语言不统一。现在统一收敛成底部面板，和会话长按菜单同一种交互。
 *
 * 行样式与顶栏浮动菜单（[io.zer0.muse.ui.common.MuseFloatingActionMenu]）保持同一套语言：
 * 圆形图标片 + 标签，开关态由图标点亮表达，行尾不放勾。
 *
 * 面板内部由 [MuseBottomSheet] 负责唯一滚动与屏幕边距，内容不要再叠横向 padding。
 */
@Composable
internal fun MuseActionSheet(
    items: List<MuseFloatingActionItem>,
    onDismiss: () -> Unit,
) {
    MuseBottomSheet(
        onDismissRequest = onDismiss,
        bottomContentSpacing = MusePaddings.contentGap,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap)) {
            items.forEach { item ->
                key(item.key) { MuseActionSheetRow(item = item, onDismiss = onDismiss) }
            }
        }
    }
}

@Composable
private fun MuseActionSheetRow(
    item: MuseFloatingActionItem,
    onDismiss: () -> Unit,
) {
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
            // 底部面板是常驻面板而不是瞬态浮层，保持完整触控高度。
            .heightIn(min = MuseIconSizes.touchTarget)
            .clip(MuseShapes.large)
            .clickable(enabled = item.enabled) {
                onDismiss()
                item.onClick()
            }
            .padding(horizontal = MusePaddings.contentGap, vertical = MusePaddings.contentGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(chipColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
        }
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurface.copy(alpha = labelAlpha),
            modifier = Modifier.weight(1f),
        )
    }
}
