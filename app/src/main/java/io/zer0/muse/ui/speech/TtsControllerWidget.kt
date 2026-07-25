package io.zer0.muse.ui.speech

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import io.zer0.muse.ui.theme.MuseAnimation
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.ui.theme.MuseIconSizes
import org.koin.compose.koinInject

/**
 * v1.4: 悬浮 TTS 控制器(参考 rikkahub TTSController)。
 *
 * 用 Compose 浮窗实现(不依赖 FloatingWindow 第三方库),显示在屏幕底部右下角,
 * InputBar 上方。仅当 [TtsManager.playbackState] 不是 Idle 时显示。
 *
 * 布局(圆形主按钮 + 横向工具栏):
 *  - 圆形主按钮:双圆环进度(外圈音频进度 / 内圈分片进度) + 中心 Play/Pause 图标
 *  - 停止按钮
 *  - 展开后:速度按钮(0.8x/1.0x/1.2x/1.5x 循环)+ 快进 5s 按钮
 *  - 展开/收起按钮
 *
 * 用法:在 ChatScreen 的 Box 里加 `TtsControllerWidget(Modifier.align(BottomEnd))`。
 *
 * @param modifier 外部对齐 + padding(由调用方指定位置)
 */
@Composable
fun TtsControllerWidget(
    modifier: Modifier = Modifier,
    ttsManager: TtsManager = koinInject(),
) {
    val state by ttsManager.playbackState.collectAsStateWithLifecycle()
    val visible = state.status != PlaybackStatus.Idle

    // 速度档位循环索引(0.8x → 1.0x → 1.2x → 1.5x)
    val speeds = remember { listOf(0.8f, 1.0f, 1.2f, 1.5f) }
    // L-SP5 修复: speedIndex/expanded 改用 rememberSaveable,配置变更(旋转/后台回收)后状态不丢失
    var speedIndex by rememberSaveable { mutableIntStateOf(1) } // 默认 1.0x
    var expanded by rememberSaveable { mutableStateOf(false) }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(MuseAnimation.TACTILE_MS)) + scaleIn(tween(MuseAnimation.TACTILE_MS), initialScale = 0.7f),
        exit = fadeOut(tween(MuseAnimation.TACTILE_MS)) + scaleOut(tween(MuseAnimation.TACTILE_MS), targetScale = 0.7f),
        modifier = modifier,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = CircleShape,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // 圆形主按钮:双圆环进度 + 中心 Play/Pause
                CircleProgressButton(
                    isPlaying = state.status == PlaybackStatus.Playing,
                    audioProgress = if (state.durationMs > 0) {
                        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                    } else 0f,
                    chunkProgress = if (state.totalChunks > 0) {
                        (state.currentChunkIndex.toFloat() / state.totalChunks).coerceIn(0f, 1f)
                    } else 0f,
                    onClick = {
                        if (state.status == PlaybackStatus.Playing) {
                            ttsManager.pause()
                        } else {
                            ttsManager.resume()
                        }
                    },
                )

                // 停止按钮
                CircleIconButton(
                    contentDescription = stringResource(R.string.speech_stop_reading_cd),
                    onClick = { ttsManager.stop() },
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp),
                    )
                }

                // 展开后:分片导航 + 进退 5s + 倍速
                AnimatedVisibility(visible = expanded) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        // 上一片
                        CircleIconButton(
                            contentDescription = stringResource(R.string.speech_previous_chunk_cd),
                            onClick = { ttsManager.previousChunk() },
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        // 下一片
                        CircleIconButton(
                            contentDescription = stringResource(R.string.speech_next_chunk_cd),
                            onClick = { ttsManager.nextChunk() },
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        // 后退 5s
                        CircleIconButton(
                            contentDescription = stringResource(R.string.speech_seek_backward_5s_cd),
                            onClick = { ttsManager.seekBy(-5_000) },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Replay5,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        // 快进 5s
                        CircleIconButton(
                            contentDescription = stringResource(R.string.speech_seek_forward_5s_cd),
                            onClick = { ttsManager.seekBy(5_000) },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Forward5,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        // 速度按钮(点击循环 0.8x → 1.0x → 1.2x → 1.5x)
                        CircleIconButton(
                            contentDescription = stringResource(R.string.speech_playback_speed_cd, speeds[speedIndex].toString()),
                            onClick = {
                                speedIndex = (speedIndex + 1) % speeds.size
                                ttsManager.setSpeed(speeds[speedIndex])
                            },
                        ) {
                            Text(
                                text = "${speeds[speedIndex]}x",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }

                // 展开/收起
                CircleIconButton(
                    contentDescription = if (expanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
                    onClick = { expanded = !expanded },
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.AutoMirrored.Filled.KeyboardArrowLeft
                                       else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * 圆形主按钮 — 双圆环进度(外圈音频 + 内圈分片)+ 中心 Play/Pause 图标。
 *
 * - 外圈 [CircularProgressIndicator]:音频播放进度(positionMs / durationMs)
 * - 内圈 CircularProgressIndicator:分片进度(currentChunkIndex / totalChunks)
 * - 中心:Playing 显示 Pause(点击暂停);Paused/Ended 显示 PlayArrow(点击恢复)
 *
 * @param isPlaying 当前是否在播放
 * @param audioProgress 音频进度 0..1
 * @param chunkProgress 分片进度 0..1
 * @param onClick 点击回调(切换播放/暂停)
 */
@Composable
private fun CircleProgressButton(
    isPlaying: Boolean,
    audioProgress: Float,
    chunkProgress: Float,
    onClick: () -> Unit,
) {
    val animatedAudio by animateFloatAsState(targetValue = audioProgress, label = "audioProgress")
    val animatedChunk by animateFloatAsState(targetValue = chunkProgress, label = "chunkProgress")

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // 外圈:音频进度(尺寸 48dp,填满整个 Box)
        CircularProgressIndicator(
            progress = { animatedAudio },
            modifier = Modifier.size(48.dp),
            strokeWidth = 3.dp,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        // 内圈:分片进度(尺寸 38dp,留 5dp 间隙)
        CircularProgressIndicator(
            progress = { animatedChunk },
            modifier = Modifier.size(38.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.tertiary,
            trackColor = Color.Transparent,
        )
        // 中心图标(Play / Pause 切换)
        Icon(
            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (isPlaying) stringResource(R.string.speech_pause_cd) else stringResource(R.string.speech_resume_cd),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * 通用圆形小按钮(48dp clickable Box,中心放图标/文字)。
 *
 * 用 Box + clickable 替代 IconButton,避免 IconButton 默认 padding 撑大布局,让浮窗更紧凑。
 * 触摸目标对齐 MD3 红线(48dp = [MuseIconSizes.touchTarget]),保证浮窗内可点性。
 *
 * @param contentDescription 无障碍描述
 * @param onClick 点击回调
 * @param content 内容(Icon / Text)
 */
@Composable
private fun CircleIconButton(
    contentDescription: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(MuseIconSizes.touchTarget)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
