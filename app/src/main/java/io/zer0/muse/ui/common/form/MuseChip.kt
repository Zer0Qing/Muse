package io.zer0.muse.ui.common.form

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseChipSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge

/**
 * 选择胶囊组件 — 替代 Material3 FilterChip / AssistChip。
 *
 * 视觉高度由 [MuseChipSizes.visualHeight]（32dp）控制，告别此前 48dp 实心砖观感，
 * 同时保留选中语义（role=Checkbox + selected）与无涟漪弹性反馈。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MuseChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) MuseChipSizes.pressScale else 1f,
        animationSpec = MuseMotion.tween(MuseAnimation.FAST_MS),
        label = "chipPress",
    )
    val bgColor by animateColorAsState(
        targetValue = if (selected) MuseActionColors.container else MuseActionColors.neutralContainer,
        animationSpec = MuseMotion.tween(MuseAnimation.TACTILE_MS),
        label = "chipBg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MuseActionColors.content else MuseActionColors.neutralContent,
        animationSpec = MuseMotion.tween(MuseAnimation.TACTILE_MS),
        label = "chipContent",
    )
    Surface(
        shape = MuseShapes.semiLarge,
        color = bgColor,
        contentColor = contentColor,
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .alpha(if (enabled) 1f else MuseAlpha.disabled)
            .heightIn(min = MuseChipSizes.visualHeight)
            .semantics {
                this.selected = selected
                role = Role.Checkbox
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                horizontal = MuseChipSizes.paddingH,
                vertical = MuseChipSizes.paddingV,
            ),
        ) {
            if (leadingIcon != null) leadingIcon()
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
            if (trailingIcon != null) trailingIcon()
        }
    }
}
