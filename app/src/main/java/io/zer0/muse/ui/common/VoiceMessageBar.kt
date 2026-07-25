package io.zer0.muse.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import io.zer0.muse.ui.theme.MuseAnimation
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
import androidx.compose.material.icons.filled.Mic
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
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.CoralWhisper
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

    // 录音动画: 脉冲效果
    val infiniteTransition = rememberInfiniteTransition(label = "recording")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(MuseAnimation.LOOP_SLOW_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_alpha",
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MuseShapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                repeat(8) { i ->
                    val barHeight by infiniteTransition.animateFloat(
                        initialValue = 4f + i * 2f,
                        targetValue = 12f + i * 3f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(300 + i * 50, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "bar_$i",
                    )
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
            Spacer(Modifier.width(8.dp))
            // 停止按钮
            IconButton(
                onClick = onStop,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "Stop recording",
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
