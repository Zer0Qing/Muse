package io.zer0.muse.ui.common.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings

/**
 * 带开关的设置项行 — 左侧图标 + 标题 + 副标题,右侧 Switch。
 *
 * 对齐系统设置开关的视觉。
 * CMP-09: 全项目开关行唯一实现(原通用 `SwitchRow` 已删除并全部迁到此处);
 * 图标可空、副标题可空,两者的形态差异只由参数表达,不再有第二套组件。
 *
 * @param icon 左侧图标(null 则不显示)
 * @param title 主标题
 * @param subtitle 副标题(null 则不显示)
 * @param checked 开关状态
 * @param onCheckedChange 开关切换回调
 * @param contentPadding 行内容内边距。默认 [MusePaddings.cardInner]，与
 *   [io.zer0.muse.ui.common.surface.CardGroup] / [io.zer0.muse.ui.common.surface.MuseListItem] 内的行对齐；全宽表单(自身已加
 *   `MusePaddings.screen` 水平边距、与 `MuseTextField` 同列)传 `PaddingValues(0.dp)`，
 *   避免二次缩进。
 */
@Composable
fun SettingsSwitchRow(
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentPadding: PaddingValues = MusePaddings.cardInner,
    /** 2026-11-20(P3-13):整行禁用。为 false 时点击无效、开关不可拖动、文字降透明度。 */
    enabled: Boolean = true,
) {
    val enabledText = stringResource(R.string.common_state_enabled)
    val disabledText = stringResource(R.string.common_state_disabled)
    val rowInteractionSource = remember { MutableInteractionSource() }
    val isRowPressed by rowInteractionSource.collectIsPressedAsState()
    val colorScheme = MaterialTheme.colorScheme
    val pressColor = colorScheme.onSurface.copy(alpha = MuseAlpha.rowPressed)
    val rowBgColor by animateColorAsState(
        targetValue = if (isRowPressed) pressColor else Color.Transparent,
        animationSpec = MuseMotion.tween(MuseAnimation.NORMAL_MS, easing = MuseAnimation.EaseOutCubic),
        label = "settingsSwitchRowBg",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBgColor)
            .clickable(
                interactionSource = rowInteractionSource,
                indication = null,
                enabled = enabled,
            ) { onCheckedChange(!checked) }
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        // 图标直接画，不再套 36dp 彩色方块。
        // 设置页 82.5% 的行都有这个方块，一屏下来是一列同色圆角砖，
        // 视觉重量盖过了真正要看的标题与开关；图标本身已经足够承担扫视定位。
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colorScheme.onSurfaceVariant,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else MuseAlpha.disabledContent),
                // LAYOUT-01: 标题限行。仅 weight 取宽不够 —— 剩余宽度极小时仍会逐字换行撑高整行，
                // 由外层卡片裁掉(线上事故形态)。限 1 行 + 省略号是最后一道兜底。
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        MuseSwitch(
            checked = checked,
            // 禁用时传 null:MuseSwitch 用可空回调表达不可交互
            onCheckedChange = if (enabled) onCheckedChange else null,
            modifier = Modifier.semantics {
                stateDescription = if (checked) enabledText else disabledText
            },
        )
    }
}
