package io.zer0.muse.ui.settings

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import io.zer0.muse.ui.theme.MoodSkinColors
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseMotion

/**
 * B6-02: pelle-d-umore 全屏情绪皮肤 overlay。
 *
 * 渲染在消息列表背后，按当前最后一条助手消息的 moodSkin 切换氛围色。
 * rage/rage2/desire/vuoto/moonlight 各有独立渐变，并带轻微呼吸光晕。
 */
@Composable
fun MoodSkinOverlay(skin: String?) {
    if (skin == null || skin == "off") return

    val colors = MoodSkinColors.overlays[skin] ?: MoodSkinColors.defaultOverlay
    val gradientBrush = remember(colors) { Brush.verticalGradient(colors) }

    val animatedAlpha: State<Float>? = if (MuseMotion.isReducedMotion()) {
        null
    } else {
        val transition = rememberInfiniteTransition(label = "moodSkin")
        transition.animateFloat(
            initialValue = 0.65f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = MuseMotion.tween(MuseAnimation.LOOP_EXTRA_SLOW_MS * 2),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "moodSkinAlpha",
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(brush = gradientBrush)
                val alphaVal = animatedAlpha?.value ?: 0.82f
                drawRect(color = Color.White.copy(alpha = (1f - alphaVal) * 0.08f))
            },
    )
}
