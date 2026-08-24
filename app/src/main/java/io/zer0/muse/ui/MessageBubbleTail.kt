@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod", "TooManyFunctions", "ReturnCount", "TooGenericExceptionCaught", "SwallowedException", "MaxLineLength", "ComplexCondition", "UseCheckOrError", "UnusedPrivateProperty")

package io.zer0.muse.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import compose.icons.TablerIcons
import compose.icons.tablericons.Download
import compose.icons.tablericons.PlayerPlay
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import io.zer0.common.Logger
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.theme.tiny
import androidx.compose.material.icons.filled.Pause
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.ui.common.form.MuseSlider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 生成的图片卡片 — 圆角 + 点击预览。
 *
 * v1.0.80:
 *  - 放大展示面:ContentScale.Fit 完整显示,不再 Crop 截断 1:1 图,高度上限放宽到 420dp
 *  - 移除外侧下载按钮:下载入口收进大图预览页(右下角),避免卡片上按钮遮挡画面
 */
@Composable
internal fun GeneratedImageCard(
    imageUri: String,
    onPreview: () -> Unit,
    onSave: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.tightGap)
            .clip(MuseShapes.medium)
            .clickable(onClick = onPreview),
    ) {
        SmartImage(
            model = imageUri,
            contentDescription = stringResource(R.string.chat_generated_image_cd),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 420.dp)
                .clip(MuseShapes.medium),
        )
    }
}

/**
 * iOS 风格 ActionSheet 行项 — 全宽 Row(图标 + 文字),点击触发回调。
 */
@Composable
internal fun ActionMenuItem(
    icon: ImageVector,
    text: String,
    contentDescription: String,
    onClick: () -> Unit,
    // v1.48: 可选 tint,用于"删除消息"等危险操作标红
    tint: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
) {
    val iconTint = if (tint == androidx.compose.ui.graphics.Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint
    val textTint = if (tint == androidx.compose.ui.graphics.Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint
    Surface(
        onClick = onClick,
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.iconPadding, vertical = MusePaddings.inputPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.iconPadding),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = iconTint,
                // M-MB3: 图标尺寸用 MuseIconSizes.iconMedium 令牌替代硬编码 22dp
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = textTint,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 任务 2A: iOS 风格 shimmer 骨架屏 + 脉冲点加载动画。
 * 三个圆点依次缩放/淡入淡出,下方显示状态文字。
 */
@Composable
internal fun LoadingDots(text: String = stringResource(R.string.chat_loading_thinking)) {
    Column(
        modifier = Modifier.padding(MusePaddings.cardInner),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // v1.79 (L-B15): 三个圆点共享一个 InfiniteTransition,减少动画开销
            val infiniteTransition = rememberInfiniteTransition(label = "dots")
            repeat(3) { index ->
                val scale by infiniteTransition.animateFloat(
                    initialValue = 0.6f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600, delayMillis = index * 120, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "dot$index",
                )
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.4f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600, delayMillis = index * 120, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "dotAlpha$index",
                )
                Box(
                    modifier = Modifier
                        .size(MusePaddings.contentGap)
                        .scale(scale)
                        .alpha(alpha)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Spacer(Modifier.height(MusePaddings.contentGap))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

/**
 * v0.48: shimmer 骨架屏占位 — 空流式 assistant 消息气泡渲染占位条,
 * 替代旧 LoadingDots 的"思考中"文字,营造"AI 正在写"的呼吸感。
 *
 * 实现:一个 fillMaxWidth(0.6f) / height 14dp 圆角矩形,
 * 用 [Brush.linearGradient] 配合 [animateFloat] + [infiniteRepeatable]
 * 做从左到右的扫光动画(1200ms 一个周期,LinearEasing)。
 * 颜色:surfaceVariant(0.4) → primary(0.2) → surfaceVariant(0.4)。
 */
@Composable
internal fun ShimmerPlaceholder(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerTranslate",
    )
    val brush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        start = Offset(translateAnim * -300f, 0f),
        end = Offset(translateAnim * 300f, 0f),
    )
    Box(
        modifier = modifier
            .fillMaxWidth(0.6f)
            .height(14.dp)
            .clip(MuseShapes.tiny)
            .background(brush),
    )
}

/**
 * v1.33: 智能图片渲染器
 * 绕过 Coil 的 data URI 解析(部分设备上 DataUriFetcher 静默失败)
 * data URI → 直接用 BitmapFactory 解码;普通 URL/Uri → 仍走 Coil AsyncImage
 */
@Composable
fun SmartImage(
    model: Any?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val dataUriPrefix = "data:image/"
    if (model is String && model.startsWith(dataUriPrefix)) {
        // data URI:提取 base64 部分,IO 线程解码
        val base64Part = remember(model) {
            val commaIndex = model.indexOf(',')
            if (commaIndex > 0) model.substring(commaIndex + 1) else null
        }
        if (base64Part != null) {
            val bitmapState by produceState<android.graphics.Bitmap?>(initialValue = null, base64Part) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        // B-02: 部分上游按 76 字符换行输出 base64,NO_WRAP 解码遇到换行直接失败
                        // (显示灰块);解码前剥离换行符,兼容两种格式。
                        val normalized = base64Part.replace("\n", "").replace("\r", "")
                        val bytes = android.util.Base64.decode(normalized, android.util.Base64.NO_WRAP)
                        // v1.79 (H-B1): 先探测尺寸再降采样,避免大图解码 OOM
                        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        val targetSize = 1024
                        var sampleSize = 1
                        while (options.outWidth / sampleSize > targetSize || options.outHeight / sampleSize > targetSize) {
                            sampleSize *= 2
                        }
                        val decodeOptions = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                    }.onFailure { Logger.w("MessageBubble", "base64 image decode failed: ${it.message}", it) }.getOrNull()
                }
                // v1.79 (M-B11): produceState 退出时显式回收 Bitmap,避免内存泄漏
                awaitDispose {
                    value?.recycle()
                }
            }
            val current = bitmapState
            if (current != null) {
                Image(
                    bitmap = current.asImageBitmap(),
                    contentDescription = contentDescription,
                    modifier = modifier,
                    contentScale = contentScale,
                )
            } else {
                // 解码中/失败:灰色占位
                Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant))
            }
        } else {
            Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant))
        }
    } else {
        // 普通 URL/Uri:走 Coil AsyncImage
        // v1.0.74 fix: 加载失败无占位会整块空白,补 placeholder/error 灰块
        val imageModel = remember(model) {
            if (model is String && model.startsWith("/")) File(model) else model
        }
        AsyncImage(
            model = imageModel,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
            placeholder = androidx.compose.ui.graphics.painter.ColorPainter(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            ),
            error = androidx.compose.ui.graphics.painter.ColorPainter(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ),
        )
    }
}

/**
 * 审计修复 (S-02): 助手消息视频生成结果卡片。
 *
 * generate_video 成功后 videoFileUri 写入消息并落库,此处渲染:
 * 深色占位 + 播放图标,点击用 ACTION_VIEW 调起系统播放器;
 * 审查修复 (2.0 C-01): ACTION_VIEW 对 data: URI 视频基本不可用(多数系统播放器
 * 不识别 data: scheme),点击时先把 data URI 解码落盘到 cacheDir 再播放文件,
 * http(s) URL 仍直接调起系统播放器;无应用可处理时 Toast 提示。
 */
@Composable
internal fun AssistantVideoCard(videoUri: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(MuseShapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable {
                runCatching {
                    // C-01: data URI → 解码落盘 cacheDir 后播放文件;http(s) 直放
                    val playable = if (videoUri.startsWith("data:")) {
                        val commaIndex = videoUri.indexOf(',')
                        val base64 = if (commaIndex > 0) videoUri.substring(commaIndex + 1) else videoUri
                        val normalized = base64.replace("\n", "").replace("\r", "")
                        val bytes = android.util.Base64.decode(normalized, android.util.Base64.NO_WRAP)
                        val file = java.io.File(context.cacheDir, "muse_video_${videoUri.hashCode()}.mp4")
                        file.writeBytes(bytes)
                        file.toURI().toString()
                    } else {
                        videoUri
                    }
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(playable), "video/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }.onFailure { e ->
                    MuseToast.show(context.getString(R.string.chat_video_open_failed, e.message ?: ""))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.VideoLibrary,
            contentDescription = stringResource(R.string.chat_generated_video_cd),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(MuseIconSizes.iconEmpty),
        )
        // 中央播放图标(scrim 半透明背景提升对比度,与用户视频附件一致)
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = TablerIcons.PlayerPlay,
                contentDescription = stringResource(R.string.chat_video_play_cd),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(MuseIconSizes.iconLarge),
            )
        }
    }
}

/**
 * 阶段 4: 流式光标 — 末尾 AI 流式消息文本后追加的闪烁竖线。
 *
 * 设计: 2.5dp 宽 / 18dp 高竖条,通过 [rememberInfiniteTransition] + [animateFloat]
 *       在 1.0 ↔ 0.2 间用 FastOutSlowInEasing 往返(530ms 周期),呈现"打字机呼吸感"。
 * 颜色: 取自 MaterialTheme.colorScheme.primary(月桂绿 #2D8C5F),
 *       与品牌色保持一致,符合"深夜台灯"配色铁律(<5% 品牌色点缀)。
 *
 * v1.0.3 改进:
 *  - 周期从 1s 缩短到 530ms,看起来更"活跃",与更快的内容流入节奏匹配
 *  - alpha 范围从 0~1 改为 0.2~1,避免完全消失,视觉更连贯
 *  - 缓动从 LinearEasing 改为 FastOutSlowInEasing,呼吸感更自然
 *  - 宽度从 2dp 加到 2.5dp,高度从 16dp 加到 18dp,略微更醒目
 */
@Composable
internal fun StreamingCursor(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "streaming_cursor")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 530, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursor_alpha",
    )
    Box(
        modifier = modifier
            .size(width = 2.5.dp, height = 18.dp)
            .background(color = color.copy(alpha = alpha)),
    )
}

/**
 * AI 流式/思考状态指示器 — 绿色脉动圆点 + "正在思考…"文案。
 * 使用 MuseShapes.pill 绿色小点 + alpha 呼吸动画,符合 iOS/MANUS 风格。
 */
@Composable
internal fun ThinkingIndicator() {
    val transition = rememberInfiniteTransition(label = "thinking_dot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "thinking_alpha",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(MuseShapes.pill)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
        )
        Text(
            text = stringResource(R.string.chat_thinking),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
    }
}

/**
 * 功能2: TTS 语音消息播放器。显示在 AI 消息气泡下方,当前消息正在 TTS 朗读时出现。
 *
 * 包含波形条动画 + 播放/暂停按钮 + 进度条 + 倍速选择。
 */
@Composable
internal fun TtsAudioPlayer(
    modifier: Modifier = Modifier,
) {
    val ttsManager: io.zer0.muse.ui.speech.TtsManager = org.koin.compose.koinInject()
    val state by ttsManager.playbackState.collectAsStateWithLifecycle()
    val isPlaying = state.status == io.zer0.muse.ui.speech.PlaybackStatus.Playing
    val isPaused = state.status == io.zer0.muse.ui.speech.PlaybackStatus.Paused
    val progress = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f

    var speedIndex by rememberSaveable { mutableIntStateOf(1) }
    val speeds = remember { listOf(0.8f, 1.0f, 1.2f, 1.5f) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = MuseShapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.bubbleInner),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                // 播放/暂停按钮
                IconButton(
                    onClick = {
                        if (isPlaying) ttsManager.pause()
                        else ttsManager.resume()
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else TablerIcons.PlayerPlay,
                        contentDescription = if (isPlaying) stringResource(R.string.speech_pause_cd) else stringResource(R.string.speech_resume_cd),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                // 波形条动画(4 条竖条,随播放状态弹跳)
                WaveformBars(isActive = isPlaying)
                Spacer(Modifier.weight(1f))
                // 倍速选择
                Text(
                    text = "${speeds[speedIndex]}x",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            speedIndex = (speedIndex + 1) % speeds.size
                            ttsManager.setSpeed(speeds[speedIndex])
                        }
                        .clip(MuseShapes.tiny)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                        .padding(MusePaddings.chipInnerLoose),
                )
            }
            Spacer(Modifier.height(MusePaddings.tinyGap))
            // 进度条
            MuseSlider(
                value = progress,
                onValueChange = { v ->
                    val targetMs = (v * state.durationMs).toLong()
                    val delta = (targetMs - state.positionMs).toInt()
                    ttsManager.seekBy(delta)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 波形条动画 — 4 条竖条,播放时逐条错开弹跳,暂停时静止。
 */
@Composable
internal fun WaveformBars(
    isActive: Boolean,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val voicePlayingCd = stringResource(R.string.chat_voice_playing_cd)
    val voiceReadyCd = stringResource(R.string.chat_voice_ready_cd)
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        // v1.0.52: 无障碍 — TalkBack 可播报波形状态
        modifier = Modifier.semantics {
            contentDescription = if (isActive) voicePlayingCd else voiceReadyCd
        },
    ) {
        val heights = listOf(MusePaddings.itemGap, 18.dp, 14.dp, 20.dp)
        heights.forEachIndexed { index, maxHeight ->
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 600
                        0.4f at 0
                        1f at (150 + index * 50)
                        0.4f at 600
                    },
                    repeatMode = RepeatMode.Restart,
                ),
                label = "bar$index",
            )
            val currentHeight = if (isActive) maxHeight * scale else maxHeight * 0.4f
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(currentHeight)
                    .clip(MuseShapes.tiny)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isActive) 0.8f else 0.3f)),
            )
        }
    }
}

/**
 * v0.48: 消息时间戳格式化 — 受 chatPrefs.use24Hour 控制时制,
 * 默认 24 小时制显示 "HH:mm",12 小时制显示 "h:mm a"。
 */
// v1.79 (L-B12): SimpleDateFormat 提为文件级缓存,避免每条消息独立创建
private val sdf24Hour by lazy {
    java.text.SimpleDateFormat(MuseDateFormats.TIME_SHORT, java.util.Locale.getDefault())
}
private val sdf12Hour by lazy {
    java.text.SimpleDateFormat(MuseDateFormats.TIME_12H, java.util.Locale.getDefault())
}

// L-MB3: 移除多余的 @Composable 注解(函数不使用任何 Composable API)
internal fun formatMessageTime(timestamp: Long, use24Hour: Boolean = true): String {
    val sdf = if (use24Hour) sdf24Hour else sdf12Hour
    return sdf.format(java.util.Date(timestamp))
}

/**
 * 视频时长格式化:毫秒 → "M:SS"(超过 1 小时则 "H:MM:SS")。
 * 仅用于消息气泡右下角时长标签,与 InputBar 中同名函数语义一致。
 */
internal object MessageBubbleFormatters {
    fun formatVideoDuration(durationMs: Long): String {
        if (durationMs <= 0L) return "0:00"
        val totalSec = durationMs / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}

