package io.zer0.muse.ui.common.media

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.theme.CoralWhisper
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * Phase 3 3B: 语音消息录制条 — WeChat 风格长按录音 + 波形动画。
 *
 * @param isRecording 是否正在录音
 * @param durationSeconds 已录音时长(秒)
 * @param onCancel 取消录音
 * @param onStop 停止录音并发送
 */
@Composable
fun VoiceMessageBar(
    isRecording: Boolean,
    durationSeconds: Int = 0,
    onCancel: () -> Unit = {},
    onStop: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (!isRecording) return

    val reducedMotion = MuseMotion.isReducedMotion()
    // reduced-motion 下仍保留录音状态指示,但不启动无限动画。
    val recordingTransition = if (reducedMotion) {
        null
    } else {
        rememberInfiniteTransition(label = "recording")
    }
    val pulseAlpha = if (recordingTransition == null) {
        1f
    } else {
        val animatedAlpha by recordingTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(
                animation = MuseMotion.tween(MuseAnimation.LOOP_SLOW_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse_alpha",
        )
        animatedAlpha
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MuseShapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
        ) {
            // 录音脉冲指示器
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(
                        CoralWhisper.copy(alpha = pulseAlpha),
                        CircleShape,
                    ),
            )
            // 录音时长
            Text(
                text = formatDuration(durationSeconds),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            // 波形占位 (简单条形动画)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
            ) {
                repeat(8) { i ->
                    val barHeight = if (recordingTransition == null) {
                        8f + i * 1.5f
                    } else {
                        val animatedHeight by recordingTransition.animateFloat(
                            initialValue = 4f + i * 2f,
                            targetValue = 12f + i * 3f,
                            animationSpec = infiniteRepeatable(
                                animation = MuseMotion.tween(
                                    MuseAnimation.FAST_NORMAL_MS + i * 50,
                                    easing = LinearEasing,
                                ),
                                repeatMode = RepeatMode.Reverse,
                            ),
                            label = "bar_$i",
                        )
                        animatedHeight
                    }
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(barHeight.dp)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                MuseShapes.small,
                            ),
                    )
                }
            }
            Spacer(Modifier.width(MusePaddings.contentGap))
            // 停止按钮
            IconButton(
                onClick = onStop,
                modifier = Modifier.size(MuseIconSizes.touchTarget),
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = stringResource(R.string.voice_stop_recording), // 前端修复 (i18n-6)
                    tint = CoralWhisper,
                )
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
}
