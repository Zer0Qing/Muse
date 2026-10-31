package io.zer0.muse.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.speech.VoiceMessageStore
import io.zer0.muse.ui.speech.VoicePlaybackController
import org.koin.compose.koinInject

/** 语音条节点标识（测试与无障碍定位用）。 */
internal const val VOICE_MESSAGE_BUBBLE_TAG = "voice-message-bubble"

/** 语音存档已失效时的静态节点标识。 */
internal const val VOICE_MESSAGE_EXPIRED_TAG = "voice-message-expired"

/** 播放/暂停图标标识（断言图标真的切过）。 */
internal const val VOICE_MESSAGE_PLAY_ICON_TAG = "voice-message-icon-play"
internal const val VOICE_MESSAGE_PAUSE_ICON_TAG = "voice-message-icon-pause"

/** 播放按钮的图标尺寸。 */
private val VOICE_ICON_SIZE = 20.dp

/** 语音条高度(胶囊高度,触控目标由外层列表间距兜底)。 */
private val VOICE_ROW_HEIGHT = 40.dp

/** 波形柱数量：再密就看不出"波形"了，再疏填充的推进感就没了。 */
private const val VOICE_WAVE_BAR_COUNT = 26

/**
 * 语音条宽度分档（不随秒数匀速增长）。
 *
 * 三档而不是连续映射：语音时长分布极不均（大部分 3–10 秒），匀速映射会让绝大多数
 * 语音挤在最窄的一端看不出差别，超长语音又会一路撑到占满整行。分档后短、中、长
 * 各有稳定的视觉宽度，且最长一档也明显短于气泡可用宽度。
 */
internal fun voiceBubbleWidth(durationMs: Long): Dp = when {
    durationMs <= 15_000L -> 132.dp
    durationMs <= 60_000L -> 176.dp
    else -> 220.dp
}

/**
 * 语音时长文案：毫秒 → "M:SS"（超过 1 小时 "H:MM:SS"）。
 *
 * 时长未知（<= 0）时给 "--:--" 而不是 "0:00" —— 后者会被读成"零秒语音"。
 * 纯数字 + 冒号，不做本地化。
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
 * 语音消息条。
 *
 * 助手消息与用户消息共用：圆角胶囊 + 左侧播放/暂停 + 中间窄波形 + 右侧时长。
 * 播放权交给共享的 [VoicePlaybackController]，气泡自身不持有播放器 —— 同一时刻
 * 只会有一条语音在播，换条点按会先停掉上一条。
 *
 * 时长未知时右侧显示 "--:--"；播放中显示已播时长，空闲/暂停时显示总时长
 * （播放中要知道"听到哪儿了"，空闲时要知道"这条多长"）。
 *
 * [store] 解析失败的引用（文件被清掉）降级为静态"语音已失效"，不抛异常也不可点。
 *
 * @param voiceUri [VoiceMessageStore] 的存档引用
 * @param durationMs 语音总时长（毫秒），0 表示未知
 * @param isFromUser 用户侧气泡配色与助手侧不同
 */
@Composable
internal fun VoiceMessageBubble(
    voiceUri: String,
    durationMs: Long,
    isFromUser: Boolean,
    modifier: Modifier = Modifier,
    controller: VoicePlaybackController = koinInject(),
    store: VoiceMessageStore = koinInject(),
) {
    val resolved = remember(voiceUri, store) { store.resolve(voiceUri) }
    val width = voiceBubbleWidth(durationMs)
    if (resolved == null) {
        VoiceExpiredBubble(isFromUser = isFromUser, modifier = modifier.width(width))
        return
    }

    val playback = controller.state
    val ownsPlayback = playback.uri == voiceUri
    val isPlaying = ownsPlayback && playback.isPlaying
    val positionMs = if (ownsPlayback) playback.positionMs else 0L
    val progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val timeText = formatVoiceDuration(if (isPlaying) positionMs else durationMs)
    val toggleLabel = stringResource(if (isPlaying) R.string.chat_voice_pause_cd else R.string.chat_voice_play_cd)

    // 切后台/离开页面时释放播放器：气泡是被动方，只在自己正占着播放权时叫停。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, voiceUri) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller.stopIfActive(voiceUri)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Row(
        modifier =
        modifier
            .width(width)
            .heightIn(min = VOICE_ROW_HEIGHT)
            .clip(CircleShape)
            .background(capsuleColor(isFromUser))
            .clickable(role = Role.Button, onClick = { controller.toggle(voiceUri, resolved) })
            .semantics { contentDescription = toggleLabel }
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(VOICE_MESSAGE_BUBBLE_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (isPlaying) MuseIcons.pause else MuseIcons.play,
            contentDescription = null,
            tint = accentColor(isFromUser),
            modifier = Modifier
                .size(VOICE_ICON_SIZE)
                .testTag(if (isPlaying) VOICE_MESSAGE_PAUSE_ICON_TAG else VOICE_MESSAGE_PLAY_ICON_TAG),
        )
        VoiceWaveform(
            progress = progress,
            seed = voiceUri.hashCode(),
            activeColor = accentColor(isFromUser),
            inactiveColor = inactiveWaveColor(isFromUser),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = timeText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 存档已失效：只留一条静态说明，不可点、不建播放器。 */
@Composable
private fun VoiceExpiredBubble(
    isFromUser: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
        modifier
            .heightIn(min = VOICE_ROW_HEIGHT)
            .clip(CircleShape)
            .background(capsuleColor(isFromUser))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(VOICE_MESSAGE_EXPIRED_TAG),
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
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * 窄波形：一排定宽圆头竖条，波峰高度由 [seed] 决定（同一条语音每次绘制一致，
 * 不会随重组跳动），已播部分换强调色。
 */
@Composable
private fun VoiceWaveform(
    progress: Float,
    seed: Int,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
) {
    val peaks = remember(seed) { wavePeaks(seed, VOICE_WAVE_BAR_COUNT) }
    Canvas(modifier = modifier.height(20.dp)) {
        val gap = 2.dp.toPx()
        val barWidth = ((size.width - gap * (peaks.size - 1)) / peaks.size).coerceAtLeast(1f)
        val playedEdge = size.width * progress
        peaks.forEachIndexed { index, peak ->
            val barHeight = (size.height * peak).coerceAtLeast(1.5.dp.toPx())
            val left = index * (barWidth + gap)
            val played = left + barWidth <= playedEdge
            drawRoundRect(
                color = if (played) activeColor else inactiveColor,
                topLeft = Offset(left, (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

/** 由种子生成稳定的波峰高度比例（0.25–1.0）。 */
private fun wavePeaks(seed: Int, count: Int): List<Float> {
    var state = seed.toLong() * 2654435761L + 0x9E3779B9L
    return List(count) {
        state = (state * 1103515245L + 12345L) and 0x7FFFFFFFL
        val norm = ((state ushr 16) and 0x7FFF).toFloat() / 0x7FFF.toFloat()
        0.25f + norm * 0.75f
    }
}

@Composable
private fun capsuleColor(isFromUser: Boolean): Color =
    if (isFromUser) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }

@Composable
private fun accentColor(isFromUser: Boolean): Color =
    if (isFromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface

@Composable
private fun inactiveWaveColor(isFromUser: Boolean): Color =
    if (isFromUser) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)
    }
