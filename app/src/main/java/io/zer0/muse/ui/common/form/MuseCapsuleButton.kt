package io.zer0.muse.ui.common.form

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.huge

/**
 * iOS 风格胶囊按钮 — 替代 Material3 [Button]/[OutlinedButton]/[TextButton]。
 *
 * 视觉:全宽或 hug 内容、48dp 最小高度、24dp 圆角([MuseShapes.huge])、
 * 按压时轻微缩放(0.97x)并无涟漪。主按钮用品牌色/黑色背景 + 白字，
 * 次按钮用 surfaceVariant + onSurface，文字按钮透明背景 + primary 色。
 *
 * @param text 按钮文字
 * @param onClick 点击回调
 * @param modifier 修饰符
 * @param enabled 是否可点击
 * @param variant 按钮样式变体 [IosCapsuleButtonVariant.Primary]/[Secondary]/[Text]
 * @param fillWidth 是否填满可用宽度(默认 true)
 */
@Composable
fun MuseCapsuleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: IosCapsuleButtonVariant = IosCapsuleButtonVariant.Primary,
    fillWidth: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = MuseMotion.tween(MuseAnimation.FAST_MS),
        label = "capsuleBtnScale",
    )

    val (backgroundColor, contentColor) = when (variant) {
        IosCapsuleButtonVariant.Primary -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        IosCapsuleButtonVariant.Secondary -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurface
        IosCapsuleButtonVariant.Text -> Color.Transparent to MaterialTheme.colorScheme.primary
    }

    val alpha = if (enabled) 1f else 0.5f

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
                color = if (variant == IosCapsuleButtonVariant.Text) {
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
                enabled = enabled,
                onClick = onClick,
            )
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .padding(horizontal = MusePaddings.messageGap, vertical = MusePaddings.itemGap),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            color = contentColor.copy(alpha = alpha),
            textAlign = TextAlign.Center,
        )
    }
}

enum class IosCapsuleButtonVariant {
    Primary,
    Secondary,
    Text,
}
