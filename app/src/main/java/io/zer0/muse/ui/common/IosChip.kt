package io.zer0.muse.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge

/**
 * iOS 风格选择胶囊组件 — 替代 Material3 FilterChip / AssistChip。
 *
 * 视觉特征:
 *  - 选中态:primary 色背景 + onPrimary 文本 + SemiBold 字重
 *  - 未选中:surfaceVariant 半透明(0.5 alpha)+ onSurfaceVariant 文本 + Medium 字重
 *  - 圆角 [MuseShapes.semiLarge],无 ripple(Surface onClick 默认无 ripple)
 *  - 可选 leadingIcon / trailingIcon(如关闭按钮 X)
 *  - 不可用状态:alpha 0.38 + 禁用点击(Surface enabled=false)
 *
 * 用于:标签筛选、类别切换、可关闭的标签、提示词模板选择等场景。
 */
@Composable
fun IosChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val bgColor = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = MuseShapes.semiLarge,
        color = bgColor,
        contentColor = contentColor,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.alpha(if (enabled) 1f else 0.38f),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            if (leadingIcon != null) leadingIcon()
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
            if (trailingIcon != null) trailingIcon()
        }
    }
}
