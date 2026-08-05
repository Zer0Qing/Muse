package io.zer0.muse.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import io.zer0.muse.ui.theme.MoodSkinColors

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

    val transition = rememberInfiniteTransition(label = "moodSkin")
    val alpha by transition.animateFloat(
        initialValue = 0.65f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "moodSkinAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(colors),
            )
            .background(color = Color.White.copy(alpha = (1f - alpha) * 0.08f)),
    )
}
