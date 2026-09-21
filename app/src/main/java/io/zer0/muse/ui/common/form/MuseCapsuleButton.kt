package io.zer0.muse.ui.common.form

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.huge

/**
 * iOS 风格胶囊按钮 — 替代 Material3 [Button]/[OutlinedButton]/[TextButton]。
 *
 * 视觉:全宽或 hug 内容、48dp 最小高度、24dp 圆角([MuseShapes.huge])、
 * 按压时轻微缩放(0.97x)并无涟漪。主按钮用品牌色/黑色背景 + 白字，
 * 次按钮用主题容器色 + 其反相色，文字按钮透明背景 + primary 色。
 *
 * 业务页面需要「图标 + 文字」「删除类红按钮」「进行中」三种形态时，
 * 一律用本组件的 [leadingIcon] / [destructive] / [loading]，不要退回原生按钮。
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
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = MuseMotion.tween(MuseAnimation.FAST_MS),
        label = "capsuleBtnScale",
    )

    // 主题色口径：主按钮走高饱和主题主色；次要按钮走低饱和主题容器色；
    // destructive 覆盖前两者，走主题错误色（删除/清除类操作）。
    val (backgroundColor, contentColor) = when {
        destructive -> MuseActionColors.dangerContainer to MuseActionColors.dangerContent
        variant == IosCapsuleButtonVariant.Primary -> MuseActionColors.container to MuseActionColors.content
        variant == IosCapsuleButtonVariant.Secondary -> MuseActionColors.tonalContainer to MuseActionColors.tonalContent
        else -> Color.Transparent to MaterialTheme.colorScheme.primary
    }
    val transparentContainer = variant == IosCapsuleButtonVariant.Text && !destructive

    val clickable = enabled && !loading
    // 进行中不是禁用：仍然显示为可用的高饱和外观，只是不接受点击。
    val alpha = if (clickable || loading) 1f else MuseActionColors.disabledAlpha

    val boxModifier = if (fillWidth) {
        modifier.fillMaxWidth()
    } else {
        modifier
    }

    Box(
        modifier = boxModifier
            .heightIn(min = MuseIconSizes.touchTarget)
            .clip(MuseShapes.huge)
            .background(
                color = if (transparentContainer) {
                    Color.Transparent
                } else {
                    backgroundColor.copy(alpha = alpha)
                },
                shape = MuseShapes.huge,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                enabled = clickable,
                onClick = onClick,
            )
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .padding(horizontal = MusePaddings.messageGap, vertical = MusePaddings.itemGap),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (loading) {
                MuseSpinner(
                    size = MuseIconSizes.iconSmall,
                    color = contentColor.copy(alpha = alpha),
                )
                Spacer(Modifier.width(MusePaddings.contentGap))
            } else if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = alpha),
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
                Spacer(Modifier.width(MusePaddings.contentGap))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = contentColor.copy(alpha = alpha),
                textAlign = TextAlign.Center,
                // 胶囊按钮永远是单行：窄屏 / 大字号下宁可省略号，也不要把文字挤成竖排。
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            if (trailingIcon != null) {
                Spacer(Modifier.width(MusePaddings.contentGap))
                Icon(
                    imageVector = trailingIcon,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = alpha),
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
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
