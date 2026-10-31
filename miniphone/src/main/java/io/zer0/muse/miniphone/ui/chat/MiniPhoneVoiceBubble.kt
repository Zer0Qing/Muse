package io.zer0.muse.miniphone.ui.chat

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import org.koin.compose.koinInject
import java.io.File

/**
 * 语音消息条:胶囊 + 播放键 + 波形 + 时长。
 *
 * 布局是固定的三段:左侧播放/暂停,中间一排竖条,右侧总时长(播放中换成已播时长)。
 * 播放权不归气泡自己 —— 点按只是向 [MiniPhoneVoicePlaybackController] 要,所以同一时刻
 * 只有一条在播,换个会话或退到后台也不会留下半截声音。
 *
 * 时长未知(<= 0)时右侧写 `--:--` 而不是 `0:00`:后者会被读成「零秒语音」。
 * 音频文件已被清掉时退化成一条静态说明,不弹播放器也不崩。
 *
 * @param message 语音消息;用 [ChatMessageUi.mediaUri] 定位音频,用 [ChatMessageUi.id] 做测试标签
 * @param controller 模块内的单例播放控制器
 * @param onLongPress 长按弹操作菜单
 */
@Composable
internal fun ChatVoiceBubble(
    message: ChatMessageUi,
    controller: MiniPhoneVoicePlaybackController = koinInject(),
    onLongPress: () -> Unit = {},
) {
    val uri = message.mediaUri.orEmpty()
    val durationMs = message.voiceDurationMs
    val file = remember(uri) { localAudioFileOf(uri) }
    val width = voiceBubbleWidth(durationMs)
    if (file == null) {
        VoiceUnavailableBubble(message = message, width = width, onLongPress = onLongPress)
        return
    }

    val mine = message.isMine
    val playback = controller.state
    val owns = playback.uri == uri
    val playing = owns && playback.isPlaying
    val positionMs = if (owns) playback.positionMs else 0L
    val progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val timeText = formatVoiceDuration(if (playing) positionMs else durationMs)

    // 离开页面(ON_STOP)或气泡被销毁(切会话、换页)时停掉正在播的这一条
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, uri) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller.stopIfActive(uri)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.stopIfActive(uri)
        }
    }

    Row(
        modifier = Modifier
            .testTag(MiniPhoneChatTags.voice(message.id))
            .width(width)
            .heightIn(min = VoiceRowHeight)
            .clip(CircleShape)
            .background(if (mine) MiniPhoneTheme.colors.bubbleSelf else MiniPhoneTheme.colors.bubbleOther)
            .then(
                if (mine) {
                    Modifier
                } else {
                    Modifier.border(MiniPhoneTheme.dimens.hairline, MiniPhoneTheme.colors.bubbleOtherBorder, CircleShape)
                },
            )
            .combinedClickable(
                role = Role.Button,
                onClick = { controller.toggle(uri, file) },
                onLongClick = onLongPress,
            )
            .semantics { contentDescription = if (playing) VOICE_PAUSE_LABEL else VOICE_PLAY_LABEL }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (playing) MiniPhoneIcons.Pause else MiniPhoneIcons.Play,
            contentDescription = null,
            tint = if (mine) MiniPhoneTheme.colors.onAccent else MiniPhoneTheme.colors.accent,
            modifier = Modifier
                .size(VoiceIconSize)
                .testTag(if (playing) MiniPhoneChatTags.VOICE_PAUSE_ICON else MiniPhoneChatTags.VOICE_PLAY_ICON),
        )
        VoiceWaveform(
            progress = progress,
            seed = uri.hashCode(),
            activeColor = if (mine) MiniPhoneTheme.colors.onAccent else MiniPhoneTheme.colors.accent,
            inactiveColor = if (mine) {
                MiniPhoneTheme.colors.onAccent.copy(alpha = INACTIVE_BAR_ALPHA)
            } else {
                MiniPhoneTheme.colors.accent.copy(alpha = INACTIVE_BAR_ALPHA)
            },
            modifier = Modifier.weight(1f),
        )
        Text(
            text = timeText,
            style = MiniPhoneTheme.typography.timestamp.copy(
                color = if (mine) MiniPhoneTheme.colors.bubbleSelfText else MiniPhoneTheme.colors.bubbleOtherText,
            ),
        )
    }
}

/** 音频已经不在:同一个胶囊形状,只留图标与一句说明 —— 播放不了,但仍可长按删除。 */
@Composable
private fun VoiceUnavailableBubble(message: ChatMessageUi, width: Dp, onLongPress: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val mine = message.isMine
    Row(
        modifier = Modifier
            .testTag(MiniPhoneChatTags.voiceUnavailable(message.id))
            .width(width)
            .heightIn(min = VoiceRowHeight)
            .clip(CircleShape)
            .background(if (mine) colors.bubbleSelf else colors.bubbleOther)
            .then(
                if (mine) {
                    Modifier
                } else {
                    Modifier.border(MiniPhoneTheme.dimens.hairline, colors.bubbleOtherBorder, CircleShape)
                },
            )
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = MiniPhoneIcons.Play,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(VoiceIconSize),
        )
        Text(
            text = VOICE_UNAVAILABLE_TEXT,
            style = MiniPhoneTheme.typography.timestamp.copy(color = colors.textSecondary),
        )
    }
}

/**
 * 一排定宽圆头竖条,波峰高度由 [seed] 决定 —— 同一条语音每次绘制都长一样,重组不会跳。
 *
 * 已播的那一段换强调色:语音条里没有时间刻度,进度只能靠这一截颜色读出来。
 */
@Composable
private fun VoiceWaveform(progress: Float, seed: Int, activeColor: Color, inactiveColor: Color, modifier: Modifier = Modifier) {
    val peaks = remember(seed) { wavePeaks(seed, VOICE_WAVE_BAR_COUNT) }
    Canvas(modifier = modifier.height(VoiceWaveHeight)) {
        val gap = WaveBarGap.toPx()
        val barWidth = ((size.width - gap * (peaks.size - 1)) / peaks.size).coerceAtLeast(1f)
        val playedEdge = size.width * progress
        peaks.forEachIndexed { index, peak ->
            val barHeight = (size.height * peak).coerceAtLeast(MinBarHeight.toPx())
            val left = index * (barWidth + gap)
            drawRoundRect(
                color = if (left + barWidth <= playedEdge) activeColor else inactiveColor,
                topLeft = Offset(left, (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

/** 由种子摊出稳定的波峰比例(0.25–1.0):线性同余取低位,纯算术、无随机源。 */
private fun wavePeaks(seed: Int, count: Int): List<Float> {
    var state = seed.toLong() * 2654435761L + 0x9E3779B9L
    return List(count) {
        state = (state * 1103515245L + 12345L) and 0x7FFFFFFFL
        val norm = ((state ushr 16) and 0x7FFF).toFloat() / 0x7FFF.toFloat()
        0.25f + norm * 0.75f
    }
}

/**
 * 语音条宽度:按时长分三档,不做匀速映射。
 *
 * 语音时长分布极不均(大多 3–10 秒),匀速映射会让绝大多数语音挤在最窄的一端看不出差别,
 * 超长语音又会一路撑到占满整行。分档后短、中、长各有稳定宽度,最长一档也明显窄于气泡可用宽度。
 */
internal fun voiceBubbleWidth(durationMs: Long): Dp = when {
    durationMs <= SHORT_VOICE_MS -> ShortVoiceWidth
    durationMs <= MEDIUM_VOICE_MS -> MediumVoiceWidth
    else -> LongVoiceWidth
}

/**
 * 时长文案:毫秒 → `M:SS`(超过一小时 `H:MM:SS`)。
 *
 * 时长未知(<= 0)给 `--:--`:它不是零秒语音,是「没探测出来」。
 */
internal fun formatVoiceDuration(durationMs: Long): String {
    if (durationMs <= 0L) return UNKNOWN_DURATION
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/**
 * 语音引用 → 本地文件。
 *
 * 宿主存的是 `file://` 加绝对路径;`content://` 之类的引用本模块播不了(播放器只接文件),
 * 文件已不在也返回 null,由气泡退化成静态说明。
 *
 * 路径里带反斜杠或空格时 [Uri] 解不出 path(反斜杠不是分隔符,前一段会被当成 authority),
 * 这时直接除掉前缀当路径用 —— 判断依据是解出来的 path 必顶以 `/` 开头。
 */
private fun localAudioFileOf(uri: String): File? {
    val raw = uri.trim()
    if (raw.isEmpty()) return null
    val parsed = Uri.parse(raw)
    val path = when (parsed.scheme) {
        null -> raw
        ContentResolver.SCHEME_FILE -> parsed.path?.takeIf { it.startsWith("/") } ?: raw.removePrefix(FILE_PREFIX)
        else -> null
    } ?: return null
    return File(path).takeIf { it.isFile }
}

private val VoiceRowHeight = 40.dp
private val VoiceIconSize = 18.dp
private val VoiceWaveHeight = 18.dp
private val WaveBarGap = 2.dp
private val MinBarHeight = 1.5.dp
private val ShortVoiceWidth = 140.dp
private val MediumVoiceWidth = 184.dp
private val LongVoiceWidth = 228.dp

/** 波形柱数:18 根刚好铺满最窄一档,再密就看不出波形了。 */
private const val VOICE_WAVE_BAR_COUNT: Int = 18

private const val INACTIVE_BAR_ALPHA: Float = 0.28f

/** 分档边界:15 秒以内算短、1 分钟以内算中。 */
private const val SHORT_VOICE_MS: Long = 15_000L
private const val MEDIUM_VOICE_MS: Long = 60_000L

internal const val VOICE_UNAVAILABLE_TEXT: String = "语音无法播放"
private const val UNKNOWN_DURATION: String = "--:--"
private const val VOICE_PLAY_LABEL: String = "播放语音"
private const val VOICE_PAUSE_LABEL: String = "暂停语音"
private const val FILE_PREFIX: String = "file://"
