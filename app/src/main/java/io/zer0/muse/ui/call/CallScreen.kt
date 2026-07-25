package io.zer0.muse.ui.call

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.zer0.muse.ui.theme.CoralWhisper
import io.zer0.muse.ui.theme.LavenderDream
import io.zer0.muse.ui.theme.MuseShapes

/**
 * Phase 5 5A: 实时通话模式 — 全屏通话 UI,持续 ASR → AI → TTS 循环。
 *
 * 功能:
 * - 大头像 + 波形动画 + 静音/挂断按钮
 * - VAD: 1.5 秒静音自动结束当前段
 * - 打断: 用户说话 → 停止当前 TTS
 *
 * @param assistantName 助手名称
 * @param assistantEmoji 助手头像 emoji
 * @param callState 通话状态
 * @param isMuted 是否静音
 * @param onToggleMute 切换静音
 * @param onHangUp 挂断
 */
@Composable
fun CallScreen(
    assistantName: String,
    assistantEmoji: String = "\uD83E\uDD16",
    callState: CallState = CallState.CONNECTING,
    isMuted: Boolean = false,
    onToggleMute: () -> Unit = {},
    onHangUp: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "call")

    // 波形动画
    val waveScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "wave_scale",
    )

    // 呼吸动画(头像光晕)
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow_alpha",
    )

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // 顶部状态
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 48.dp),
            ) {
                Text(
                    text = callState.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            // 中间: 头像 + 波形
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // 头像光晕
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(140.dp),
                ) {
                    // 光晕圈
                    Box(
                        modifier = Modifier
                            .size(140.dp)
                            .graphicsLayer { alpha = glowAlpha }
                            .background(LavenderDream.copy(alpha = 0.2f), CircleShape),
                    )
                    // 头像
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(100.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                CircleShape,
                            ),
                    ) {
                        Text(
                            text = assistantEmoji,
                            fontSize = 48.sp,
                        )
                    }
                }

                // 助手名称
                Text(
                    text = assistantName,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                // 波形条
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.height(40.dp),
                ) {
                    repeat(12) { i ->
                        val barHeight by infiniteTransition.animateFloat(
                            initialValue = 8f + i * 2f,
                            targetValue = 24f + i * 3f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(400 + i * 30, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse,
                            ),
                            label = "bar_$i",
                        )
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(
                                    if (callState == CallState.SPEAKING || callState == CallState.LISTENING) barHeight.dp
                                    else 8.dp,
                                )
                                .background(
                                    if (callState == CallState.SPEAKING) LavenderDream
                                    else if (callState == CallState.LISTENING) CoralWhisper
                                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                    MuseShapes.small,
                                ),
                        )
                    }
                }
            }

            // 底部: 控制按钮
            Row(
                modifier = Modifier.padding(bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 静音按钮
                IconButton(
                    onClick = onToggleMute,
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape,
                        ),
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (isMuted) "Unmute" else "Mute",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 挂断按钮
                IconButton(
                    onClick = onHangUp,
                    modifier = Modifier
                        .size(72.dp)
                        .background(CoralWhisper, CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Hang up",
                        tint = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }
    }
}

/** 通话状态。 */
enum class CallState(val label: String) {
    CONNECTING("Connecting..."),
    LISTENING("Listening"),
    SPEAKING("Speaking"),
    THINKING("Thinking..."),
    ENDED("Call ended"),
}
