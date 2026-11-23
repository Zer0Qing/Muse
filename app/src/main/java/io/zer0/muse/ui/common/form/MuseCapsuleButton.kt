package io.zer0.muse.ui.common.form

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseButtonSize
import io.zer0.muse.ui.theme.MuseButtonSizes
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.pill

/**
 * 胶囊形按钮 — 替代 Material3 [Button]/[OutlinedButton]/[TextButton]。
 *
 * 尺寸分三档（[MuseButtonSize]）：
 *  - [MuseButtonSize.Large]：46dp 视觉高，用于整页主 CTA / 引导主按钮；
 *  - [MuseButtonSize.Medium]（默认）：38dp 视觉高，常规表单与面板按钮；
 *  - [MuseButtonSize.Compact]：32dp 视觉高，卡片内联操作与次要小按钮。
 *
 * 所有档位统一使用 [MuseShapes.pill] 全胶囊圆角与专属按钮内边距令牌，不再借用列表间距。
 *
 * @param text 按钮文字
 * @param onClick 点击回调
 * @param modifier 修饰符
 * @param enabled 是否可点击
 * @param variant 按钮样式变体 [IosCapsuleButtonVariant.Primary]/[Secondary]/[Text]
 * @param fillWidth 是否填满可用宽度(默认 true)
 * @param leadingIcon 可选的前置图标
 * @param trailingIcon 可选的后置图标（选中勾选、状态指示等）
 * @param loading 进行中:显示小转圈并禁止点击
 * @param destructive 危险操作(删除/清除):容器走主题错误色，覆盖 [variant] 的底色
 * @param size 尺寸档位（默认 [MuseButtonSize.Medium]）
 */
@Composable
fun MuseCapsuleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: IosCapsuleButtonVariant = IosCapsuleButtonVariant.Primary,
    fillWidth: Boolean = true,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    loading: Boolean = false,
    destructive: Boolean = false,
    size: MuseButtonSize = MuseButtonSize.Medium,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val (backgroundColor, contentColor) = when {
        destructive -> MuseActionColors.dangerContainer to MuseActionColors.dangerContent
        variant == IosCapsuleButtonVariant.Primary -> MuseActionColors.container to MuseActionColors.content
        variant == IosCapsuleButtonVariant.Secondary -> MuseActionColors.tonalContainer to MuseActionColors.tonalContent
        else -> Color.Transparent to MaterialTheme.colorScheme.primary
    }
    val transparentContainer = variant == IosCapsuleButtonVariant.Text && !destructive

    val clickable = enabled && !loading
    val containerAlpha = if (clickable || loading) 1f else MuseActionColors.disabledAlpha
    val contentAlpha = if (clickable || loading) 1f else MuseAlpha.disabledContent

    val minHeight = when (size) {
        MuseButtonSize.Large -> MuseButtonSizes.largeHeight
        MuseButtonSize.Medium -> MuseButtonSizes.mediumHeight
        MuseButtonSize.Compact -> MuseButtonSizes.compactHeight
    }
    val padH = when {
        transparentContainer && size == MuseButtonSize.Compact -> MuseSpacing.sm
        transparentContainer -> MuseSpacing.md
        size == MuseButtonSize.Large -> MuseButtonSizes.largePaddingH
        size == MuseButtonSize.Medium -> MuseButtonSizes.mediumPaddingH
        else -> MuseButtonSizes.compactPaddingH
    }
    val padV = when (size) {
        MuseButtonSize.Large -> MuseButtonSizes.largePaddingV
        MuseButtonSize.Medium -> MuseButtonSizes.mediumPaddingV
        MuseButtonSize.Compact -> MuseButtonSizes.compactPaddingV
    }
    val iconSize = when (size) {
        MuseButtonSize.Large -> MuseIconSizes.iconSmall
        MuseButtonSize.Medium -> MuseIconSizes.iconSmallTiny
        MuseButtonSize.Compact -> MuseIconSizes.iconTiny
    }
    val iconGap = if (size == MuseButtonSize.Compact) MuseButtonSizes.compactIconGap else MuseButtonSizes.iconGap
    val textStyle = when (size) {
        MuseButtonSize.Compact -> MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
        MuseButtonSize.Large,
        MuseButtonSize.Medium,
        -> MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    }

    val boxModifier = if (fillWidth) {
        modifier.fillMaxWidth()
    } else {
        modifier
    }

    Box(
        modifier = boxModifier
            .heightIn(min = minHeight)
            .clip(MuseShapes.pill)
            .background(
                color = if (transparentContainer) {
                    Color.Transparent
                } else {
                    backgroundColor.copy(alpha = containerAlpha)
                },
                shape = MuseShapes.pill,
            )
            .background(
                color = if (isPressed && clickable) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = MuseAlpha.pressedOverlay)
                } else {
                    Color.Transparent
                },
                shape = MuseShapes.pill,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                enabled = clickable,
                onClick = onClick,
            )
            .padding(horizontal = padH, vertical = padV),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (loading) {
                MuseSpinner(
                    size = iconSize,
                    color = contentColor.copy(alpha = contentAlpha),
                )
                Spacer(Modifier.width(iconGap))
            } else if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = contentAlpha),
                    modifier = Modifier.size(iconSize),
                )
                Spacer(Modifier.width(iconGap))
            }
            Text(
                text = text,
                style = textStyle,
                color = contentColor.copy(alpha = contentAlpha),
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            if (trailingIcon != null) {
                Spacer(Modifier.width(iconGap))
                Icon(
                    imageVector = trailingIcon,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = contentAlpha),
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}

enum class IosCapsuleButtonVariant {
    Primary,
    Secondary,
    Text,
}
