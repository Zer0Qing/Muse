package io.zer0.muse.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.speech.VoiceMessageStore
import io.zer0.muse.ui.speech.VoicePlaybackController
import io.zer0.muse.ui.speech.VoicePlaybackState
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.voiceBubbleColors
import org.koin.compose.koinInject
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/** 语音条节点标识（测试与无障碍定位用）。 */
internal const val VOICE_MESSAGE_BUBBLE_TAG = "voice-message-bubble"

/** 语音存档已失效时的静态节点标识。 */
internal const val VOICE_MESSAGE_EXPIRED_TAG = "voice-message-expired"

/** 播放/暂停图标标识（断言图标切换行为）。 */
internal const val VOICE_MESSAGE_PLAY_ICON_TAG = "voice-message-icon-play"
internal const val VOICE_MESSAGE_PAUSE_ICON_TAG = "voice-message-icon-pause"

private const val VOICE_WAVE_BAR_COUNT = 24
private val VOICE_MIN_BUBBLE_WIDTH = 160.dp
private val VOICE_MAX_BUBBLE_WIDTH = 264.dp
private val VOICE_DEFAULT_UNKNOWN_WIDTH = 172.dp
private val VOICE_BADGE_SIZE = 30.dp
private val VOICE_ICON_SIZE = 16.dp
private val VOICE_ARC_SIZE = 18.dp
private val VOICE_UNREAD_DOT_SIZE = 8.dp

/**
 * 语音条宽度随总时长做非线性平方根缩放，保证短语音不显局促、长语音有节奏区分且不撑满整屏。
 */
internal fun voiceBubbleWidth(durationMs: Long): Dp {
    if (durationMs <= 0L) return VOICE_DEFAULT_UNKNOWN_WIDTH
    val clamped = durationMs.coerceIn(1_000L, 60_000L)
    val ratio = sqrt(((clamped - 1_000L).toFloat() / 59_000f).coerceIn(0f, 1f))
    val span = (VOICE_MAX_BUBBLE_WIDTH - VOICE_MIN_BUBBLE_WIDTH).value
    return (VOICE_MIN_BUBBLE_WIDTH.value + span * ratio).dp
}

/**
 * 语音时长文案：毫秒 → "M:SS"（超过 1 小时 "H:MM:SS"）。
 *
 * 时长未知（<= 0）时返回 "--:--"。
 */
internal fun formatVoiceDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "--:--"
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/**
 * 对话语音消息条：非对称方向圆角胶囊 + 播放/暂停圆形徽章 + 3 弧呼吸声波 + 可拖动波形进度 +
 * 等宽时长 + 未听红点 + 倍速/听筒/转文字扩展区。
 *
 * 播放权托管于共享 [VoicePlaybackController]，同一时刻最多播放一条；[onPlaybackCompleted]
 * 用于在自然播完时触发下一条未听语音的连续续播。
 */
@Composable
internal fun VoiceMessageBubble(
    voiceUri: String,
    durationMs: Long,
    isFromUser: Boolean,
    modifier: Modifier = Modifier,
    controller: VoicePlaybackController = koinInject(),
    store: VoiceMessageStore = koinInject(),
    transcript: String? = null,
    showTranscriptToggle: Boolean = true,
    onPlaybackCompleted: (() -> Unit)? = null,
) {
    val resolved by produceState<File?>(
        initialValue = store.getCachedResolved(voiceUri),
        key1 = voiceUri,
        key2 = store,
    ) {
        if (!store.isResolveCached(voiceUri)) {
            value = store.resolveAsync(voiceUri)
        }
    }
    val width = voiceBubbleWidth(durationMs)
    val resolvedFile = resolved
    if (resolvedFile == null) {
        VoiceExpiredBubble(isFromUser = isFromUser, modifier = modifier.width(width))
        return
    }

    val ownsPlayback by remember(voiceUri, controller) {
        derivedStateOf { controller.state.uri == voiceUri }
    }
    val isPlaying by remember(voiceUri, controller) {
        derivedStateOf { controller.state.uri == voiceUri && controller.state.isPlaying }
    }
    val isUnplayed by remember(voiceUri, isFromUser, controller) {
        derivedStateOf { !isFromUser && voiceUri !in controller.state.playedUris }
    }
    val activePlayback = if (ownsPlayback) controller.state else null
    val positionMs = activePlayback?.positionMs ?: 0L
    val progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val timeText = formatVoiceDuration(if (isPlaying) positionMs else durationMs)
    val toggleLabel = stringResource(if (isPlaying) R.string.chat_voice_pause_cd else R.string.chat_voice_play_cd)
    val effectiveTranscript by produceState<String?>(
        initialValue = store.getCachedTranscript(voiceUri) ?: transcript?.takeIf { it.isNotBlank() },
        key1 = voiceUri,
        key2 = transcript,
        key3 = store,
    ) {
        if (store.getCachedTranscript(voiceUri) == null) {
            value = store.readTranscriptAsync(voiceUri) ?: transcript?.takeIf { it.isNotBlank() }
        }
    }

    var showTranscript by rememberSaveable(voiceUri) { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, voiceUri) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller.stopIfActive(voiceUri)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val colors = MaterialTheme.voiceBubbleColors
    val bgColor = if (isFromUser) colors.userBg else colors.assistantBg
    val borderColor = if (isFromUser) colors.userBorder else colors.assistantBorder
    val accentColor = if (isFromUser) colors.userAccent else colors.assistantAccent
    val waveActive = if (isFromUser) colors.userWaveActive else colors.assistantWaveActive
    val waveInactive = if (isFromUser) colors.userWaveInactive else colors.assistantWaveInactive
    val timeColor = if (isFromUser) colors.userSubtext else colors.assistantSubtext
    val bubbleShape = voiceBarShape(isFromUser)
    val unreadCd = stringResource(R.string.chat_voice_unread_cd)
    val hasControlsStrip = ownsPlayback || (showTranscriptToggle && !effectiveTranscript.isNullOrBlank())

    Column(
        modifier = modifier,
        horizontalAlignment = if (isFromUser) Alignment.End else Alignment.Start,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = bubbleShape,
                color = bgColor,
                border = BorderStroke(1.dp, borderColor),
                modifier = Modifier
                    .width(width)
                    .heightIn(min = MuseIconSizes.touchTarget)
                    .clip(bubbleShape)
                    .clickable(role = Role.Button) {
                        controller.toggle(voiceUri, resolvedFile, onPlaybackCompleted)
                    }
                    .semantics { contentDescription = toggleLabel }
                    .testTag(VOICE_MESSAGE_BUBBLE_TAG),
            ) {
                VoiceBubbleBarRow(
                    voiceUri = voiceUri,
                    durationMs = durationMs,
                    isFromUser = isFromUser,
                    isPlaying = isPlaying,
                    progress = progress,
                    timeText = timeText,
                    accentColor = accentColor,
                    waveActive = waveActive,
                    waveInactive = waveInactive,
                    timeColor = timeColor,
                    onSeekFraction = { fraction ->
                        controller.seek(
                            uri = voiceUri,
                            file = resolvedFile,
                            positionMs = (durationMs * fraction).toLong(),
                            onCompleted = onPlaybackCompleted,
                        )
                    },
                )
            }
            if (isUnplayed) {
                Box(
                    modifier = Modifier
                        .size(VOICE_UNREAD_DOT_SIZE)
                        .clip(CircleShape)
                        .background(colors.unreadDot)
                        .semantics { contentDescription = unreadCd },
                )
            }
        }

        if (hasControlsStrip) {
            VoiceControlStrip(
                isFromUser = isFromUser,
                ownsPlayback = ownsPlayback,
                playback = activePlayback,
                file = resolvedFile,
                hasTranscript = showTranscriptToggle && !effectiveTranscript.isNullOrBlank(),
                transcriptVisible = showTranscript,
                controller = controller,
                onToggleTranscript = { showTranscript = !showTranscript },
            )
        }

        AnimatedVisibility(
            visible = showTranscript && !effectiveTranscript.isNullOrBlank(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            VoiceTranscriptCard(
                transcript = effectiveTranscript.orEmpty(),
                isFromUser = isFromUser,
            )
        }
    }
}

@Composable
private fun VoiceBubbleBarRow(
    voiceUri: String,
    durationMs: Long,
    isFromUser: Boolean,
    isPlaying: Boolean,
    progress: Float,
    timeText: String,
    accentColor: Color,
    waveActive: Color,
    waveInactive: Color,
    timeColor: Color,
    onSeekFraction: (Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!isFromUser) {
            VoicePlayBadge(isPlaying = isPlaying, accentColor = accentColor)
            VoiceSoundWaveArcs(
                isPlaying = isPlaying,
                pointLeft = false,
                activeColor = accentColor,
                inactiveColor = waveInactive,
            )
        } else {
            VoiceDurationLabel(text = timeText, color = timeColor)
        }

        VoiceWaveform(
            progress = progress,
            seed = voiceUri.hashCode(),
            activeColor = waveActive,
            inactiveColor = waveInactive,
            onSeek = if (durationMs > 0L) onSeekFraction else null,
            modifier = Modifier.weight(1f),
        )

        if (!isFromUser) {
            VoiceDurationLabel(text = timeText, color = timeColor)
        } else {
            VoiceSoundWaveArcs(
                isPlaying = isPlaying,
                pointLeft = true,
                activeColor = accentColor,
                inactiveColor = waveInactive,
            )
            VoicePlayBadge(isPlaying = isPlaying, accentColor = accentColor)
        }
    }
}

@Composable
private fun VoicePlayBadge(isPlaying: Boolean, accentColor: Color) {
    Box(
        modifier = Modifier
            .size(VOICE_BADGE_SIZE)
            .clip(CircleShape)
            .background(accentColor.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isPlaying) MuseIcons.pause else MuseIcons.play,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier
                .size(VOICE_ICON_SIZE)
                .testTag(if (isPlaying) VOICE_MESSAGE_PAUSE_ICON_TAG else VOICE_MESSAGE_PLAY_ICON_TAG),
        )
    }
}

@Composable
private fun VoiceDurationLabel(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
        color = color,
        maxLines = 1,
        softWrap = false,
    )
}

@Composable
private fun VoiceSoundWaveArcs(isPlaying: Boolean, pointLeft: Boolean, activeColor: Color, inactiveColor: Color) {
    val phaseState: State<Float>? = if (isPlaying) {
        val transition = rememberInfiniteTransition(label = "voiceSoundWave")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 3f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "voiceSoundWavePhase",
        )
    } else {
        null
    }

    Canvas(modifier = Modifier.size(VOICE_ARC_SIZE)) {
        val activeArcCount = if (phaseState != null) (phaseState.value.toInt() % 3) + 1 else 3
        val strokeWidth = 1.9.dp.toPx()
        val originX = if (pointLeft) size.width * 0.85f else size.width * 0.15f
        val originY = size.height / 2f
        val startAngle = if (pointLeft) 138f else -42f
        val sweepAngle = 84f
        val radii = listOf(size.minDimension * 0.22f, size.minDimension * 0.44f, size.minDimension * 0.68f)

        drawCircle(
            color = if (activeArcCount >= 1) activeColor else inactiveColor,
            radius = strokeWidth * 0.6f,
            center = Offset(originX, originY),
        )
        radii.forEachIndexed { idx, radius ->
            val color = if (idx < activeArcCount) activeColor else inactiveColor
            drawArc(
                color = color,
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(originX - radius, originY - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun VoiceControlStrip(
    isFromUser: Boolean,
    ownsPlayback: Boolean,
    playback: VoicePlaybackState?,
    file: File,
    hasTranscript: Boolean,
    transcriptVisible: Boolean,
    controller: VoicePlaybackController,
    onToggleTranscript: () -> Unit,
) {
    val colors = MaterialTheme.voiceBubbleColors
    val earpieceMode = playback?.earpieceMode == true
    val speed = playback?.speed ?: 1f
    val speedCd = stringResource(R.string.chat_voice_speed_cd)
    val earpieceCd = stringResource(
        if (earpieceMode) R.string.chat_voice_speaker_cd else R.string.chat_voice_earpiece_cd,
    )
    val earpieceLabel = stringResource(
        if (earpieceMode) R.string.chat_voice_earpiece_label else R.string.chat_voice_speaker_label,
    )
    val transcriptLabel = stringResource(
        if (transcriptVisible) R.string.chat_voice_transcript_hide else R.string.chat_voice_transcript_show,
    )
    val speedLabel = String.format(Locale.US, "%.1fx", speed)

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp, if (isFromUser) Alignment.End else Alignment.Start),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ownsPlayback) {
            VoiceMiniActionPill(
                label = speedLabel,
                contentDescription = speedCd,
                onClick = { controller.cycleSpeed() },
                colors = colors,
            )
            VoiceMiniActionPill(
                label = earpieceLabel,
                contentDescription = earpieceCd,
                onClick = { controller.toggleEarpiece(file) },
                icon = if (earpieceMode) MuseIcons.phone else MuseIcons.volume,
                colors = colors,
            )
        }
        if (hasTranscript) {
            VoiceMiniActionPill(
                label = transcriptLabel,
                contentDescription = transcriptLabel,
                onClick = onToggleTranscript,
                icon = if (transcriptVisible) MuseIcons.chevronUp else MuseIcons.fileText,
                colors = colors,
            )
        }
    }
}

@Composable
private fun VoiceMiniActionPill(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    colors: io.zer0.muse.ui.theme.MuseVoiceBubbleColors,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = MuseIconSizes.touchTarget)
            .clip(MuseShapes.small)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(colors.controlBg)
                .border(1.dp, colors.controlBorder, CircleShape)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.assistantSubtext,
                    modifier = Modifier.size(12.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = colors.assistantSubtext,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun VoiceTranscriptCard(transcript: String, isFromUser: Boolean) {
    val colors = MaterialTheme.voiceBubbleColors
    Surface(
        shape = MuseShapes.medium,
        color = colors.transcriptBg,
        border = BorderStroke(1.dp, colors.transcriptBorder),
        modifier = Modifier
            .padding(top = 2.dp)
            .width(VOICE_MAX_BUBBLE_WIDTH),
    ) {
        SelectionContainer {
            Text(
                text = transcript,
                style = MaterialTheme.typography.bodySmall,
                color = if (isFromUser) colors.userText else colors.assistantText,
                modifier = Modifier.padding(MusePaddings.cardInner),
            )
        }
    }
}

/** 正在后台合成语音条时的占位指示胶囊。 */
@Composable
internal fun VoiceSynthesizingBubble(isFromUser: Boolean = false, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.voiceBubbleColors
    val shape = voiceBarShape(isFromUser)
    val label = stringResource(R.string.chat_voice_synthesizing)
    Surface(
        shape = shape,
        color = colors.synthesizingBg,
        border = BorderStroke(1.dp, colors.assistantBorder),
        modifier = modifier.heightIn(min = MuseIconSizes.touchTarget),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VoiceSoundWaveArcs(
                isPlaying = true,
                pointLeft = isFromUser,
                activeColor = colors.assistantAccent,
                inactiveColor = colors.assistantWaveInactive,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = colors.assistantSubtext,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** 存档已失效：只留一条静态说明，不可点、不建播放器。 */
@Composable
private fun VoiceExpiredBubble(isFromUser: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.voiceBubbleColors
    val shape = voiceBarShape(isFromUser)
    Surface(
        shape = shape,
        color = if (isFromUser) colors.userBg else colors.assistantBg,
        border = BorderStroke(1.dp, if (isFromUser) colors.userBorder else colors.assistantBorder),
        modifier = modifier
            .heightIn(min = MuseIconSizes.touchTarget)
            .testTag(VOICE_MESSAGE_EXPIRED_TAG),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = MuseIcons.play,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(VOICE_ICON_SIZE),
            )
            Text(
                text = stringResource(R.string.chat_voice_expired),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

private fun voiceBarShape(isFromUser: Boolean): RoundedCornerShape = if (isFromUser) {
    RoundedCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
} else {
    RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
}

/**
 * 窄波形条：每根柱高度由 [seed] 确定性生成，保证同一条语音重排后柱高不跳；
 * 随 [progress] 从左往右由浅灰扫成主色；支持横向拖动按比例跳转。
 */
@Composable
private fun VoiceWaveform(
    progress: Float,
    seed: Int,
    activeColor: Color,
    inactiveColor: Color,
    onSeek: ((Float) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val peaks = remember(seed) { wavePeaks(seed, VOICE_WAVE_BAR_COUNT) }
    val clamped = progress.coerceIn(0f, 1f)
    val dragMod = if (onSeek != null) {
        modifier.pointerInput(onSeek) {
            detectHorizontalDragGestures { change, _ ->
                change.consume()
                val w = size.width.coerceAtLeast(1)
                onSeek((change.position.x / w.toFloat()).coerceIn(0f, 1f))
            }
        }
    } else {
        modifier
    }

    Canvas(modifier = dragMod.height(22.dp)) {
        val count = peaks.size
        if (count == 0 || size.width <= 0f) return@Canvas
        val step = size.width / count
        val barWidth = (step * 0.56f).coerceIn(2f, 5f)
        val radius = barWidth / 2f
        val maxH = size.height * 0.9f
        val minH = maxH * 0.25f

        for (i in 0 until count) {
            val fraction = (i + 0.5f) / count
            val barH = minH + (maxH - minH) * peaks[i]
            val top = (size.height - barH) / 2f
            val left = i * step + (step - barWidth) / 2f
            drawRoundRect(
                color = if (fraction <= clamped) activeColor else inactiveColor,
                topLeft = Offset(left, top),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(radius, radius),
            )
        }
    }
}

/** 轻量线性同余确定性高度表（区间 0f..1f）。 */
private fun wavePeaks(seed: Int, count: Int): FloatArray {
    var s = seed.toLong() * 0x5DEECE66DL + 0xBL
    return FloatArray(count) {
        s = (s * 0x5DEECE66DL + 0xBL) and 0xFFFFFFFFFFFFL
        val raw = abs((s ushr 16).toInt() % 100) / 100f
        0.22f + raw * 0.78f
    }
}
