@file:Suppress("FunctionNaming", "LongParameterList")

package io.zer0.muse.ui.common.form

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * 底部菜单 / 动作面板的统一下拉行（v2.x 统一化）。
 *
 * 提炼自聊天列表长按菜单（原 private ActionSheetRow），用于 MuseDialog /
 * 底部菜单中的可点击动作行：全宽 + 圆角浅底 + 可选图标 + 左对齐文本。
 *
 * 同族使用方（都应保持本组件样式，不再各自实现）：
 *  - 聊天列表长按菜单
 *  - MCP 服务器「更多」菜单
 *  - 主题 / 记忆空间 / 小手机设置 等设置行的「更多」菜单
 *
 * @param text 动作文案
 * @param modifier 修饰符
 * @param icon 可选图标（null 时仅文本）
 * @param contentColor 前景色（危险动作传 colorScheme.error）
 * @param enabled 是否可点（false 时降透明度且不响应点击）
 * @param onClick 点击回调（放末位，支持尾随 lambda）
 */
@Composable
fun MuseActionSheetRow(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val effectiveColor = if (enabled) contentColor else contentColor.copy(alpha = 0.38f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MuseIconSizes.touchTarget)
            .clip(MuseShapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = effectiveColor,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = effectiveColor,
            // LAYOUT-01: 取剩余宽度 + 限行。动作行是 Row(图标 + 文案)，文案若不取宽，在放大字号
            // (系统"显示大小")或窄窗口下会被图标挤成一列、逐字换行 —— 与 MCP 服务器行同源。
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
