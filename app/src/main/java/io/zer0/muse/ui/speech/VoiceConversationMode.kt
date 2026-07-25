package io.zer0.muse.ui.speech

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import io.zer0.muse.ui.theme.MuseAnimation
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.ui.ChatViewModel
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge

/**
 * 语音对话模式状态机(参考 rikkahub AsrButton + TTSAutoPlay 的循环模型)。
 *
 * 状态流转:
 *  - IDLE → LISTENING:用户点击主按钮,开始 ASR 录音
 *  - LISTENING → THINKING:ASR 录音结束(用户再次点击或松开),取出识别文本自动发送
 *  - THINKING → SPEAKING:AI 流式回复完成,自动调 TtsManager 朗读
 *  - SPEAKING → LISTENING:TTS 朗读完成,自动恢复录音(连续对话)
 *  - 任意状态 → IDLE:用户点击关闭按钮,或主按钮中断
 *
 * 设计要点:
 *  - TTS 播放时必须暂停 ASR(避免扬声器声音被麦克风拾入形成回声)
 *  - 退出时立即释放 ASR/TTS 资源,避免后台继续录音或朗读
 *  - 任何状态下点击主按钮都中断当前状态回到 IDLE,符合"一键可控"交互
 */
enum class VoiceConversationState {
    /** 空闲,等待用户点击主按钮开始对话。 */
    IDLE,

    /** ASR 录音中,实时显示转写文本与波形。 */
    LISTENING,

    /** 等待 AI 回复,显示加载动画。 */
    THINKING,

    /** TTS 朗读中,显示 AI 回复文本与播放进度。 */
    SPEAKING,
}

/**
 * 完整语音对话模式(全屏覆盖式)。
 *
 * 调用方(ChatScreen)用 [AnimatedVisibility] 包裹本 Composable,在用户点击语音对话入口时显示。
 * 关闭时通过 [onClose] 回调通知调用方隐藏。
 *
 * @param onClose 用户点击关闭按钮(X)时回调,调用方应隐藏本页面并调 [ChatViewModel.stopVoiceConversation]
 * @param viewModel 聊天 ViewModel,提供状态流和控制入口
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceConversationMode(
    onClose: () -> Unit,
    viewModel: ChatViewModel,
) {
    val context = LocalContext.current
    val conversationState by viewModel.voiceConversationState.collectAsStateWithLifecycle()
    val transcript by viewModel.voiceConversationTranscript.collectAsStateWithLifecycle()
    val aiReply by viewModel.voiceConversationAiReply.collectAsStateWithLifecycle()
    val chatState by viewModel.state.collectAsStateWithLifecycle()
    val asrState = chatState.asrState
    val playbackState by viewModel.ttsPlaybackStateFlow.collectAsStateWithLifecycle()

    // 切换语音 Bottom Sheet 状态
    var showVoicePicker by remember { mutableStateOf(false) }
    val voiceSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // RECORD_AUDIO 权限申请:进入页面时若未授权则申请,授权后自动开启首轮 LISTENING
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.startVoiceConversation()
        } else {
            viewModel.reportError(context.getString(R.string.chat_err_mic_permission))
            onClose()
        }
    }

    // 进入页面时检查权限:已授权直接开始,未授权申请权限
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.startVoiceConversation()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // 用户点击关闭:先停 ViewModel 再回调
    val handleClose: () -> Unit = {
        viewModel.stopVoiceConversation()
        onClose()
    }

    // 用户点击主按钮:任意状态中断回 IDLE(若已在 IDLE 则启动新一轮 LISTENING)
    val handleMainButtonClick: () -> Unit = {
        when (conversationState) {
            VoiceConversationState.IDLE -> viewModel.startVoiceConversation()
            else -> viewModel.interruptVoiceConversation()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.92f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // ── 顶部:关闭按钮 ───────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(
                    onClick = handleClose,
                    modifier = Modifier.size(MuseIconSizes.touchTarget),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.voice_conversation_close_cd),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(MuseIconSizes.icon),
                    )
                }
            }

            // ── 中部:主按钮 + 状态文本 ─────────────────────────────────
            VoiceConversationMainButton(
                state = conversationState,
                onClick = handleMainButtonClick,
                modifier = Modifier.padding(vertical = MusePaddings.sectionGap),
            )

            // ── 中下:状态提示文本 + 实时转写/AI 回复 ───────────────────
            VoiceConversationStatusPanel(
                state = conversationState,
                transcript = transcript,
                aiReply = aiReply,
                amplitudes = asrState.amplitudes,
                playbackPositionMs = playbackState.positionMs,
                playbackDurationMs = playbackState.durationMs,
                playbackChunkIndex = playbackState.currentChunkIndex,
                playbackTotalChunks = playbackState.totalChunks,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = MusePaddings.screen),
            )

            // ── 底部:切换语音按钮 ─────────────────────────────────────
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                shape = MuseShapes.pill,
                modifier = Modifier
                    .padding(bottom = MusePaddings.sectionGap)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { showVoicePicker = true },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    Icon(
                        imageVector = Icons.Default.RecordVoiceOver,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                    Text(
                        text = stringResource(R.string.voice_conversation_change_voice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }

    // 切换语音 Bottom Sheet
    if (showVoicePicker) {
        ModalBottomSheet(
            onDismissRequest = { showVoicePicker = false },
            sheetState = voiceSheetState,
        ) {
            VoicePickerContent(
                voices = viewModel.getAvailableTtsVoices(),
                currentVoiceName = viewModel.currentTtsVoiceName(),
                onSelect = { name ->
                    viewModel.setTtsVoice(name)
                    showVoicePicker = false
                },
            )
        }
    }
}

/**
 * 语音对话主按钮(大圆形,根据状态切换图标与动画)。
 *
 * - IDLE:麦克风图标,静态
 * - LISTENING:麦克风图标 + 脉冲缩放动画
 * - THINKING:圆形进度条
 * - SPEAKING:扬声器图标 + 波形动画
 */
@Composable
private fun VoiceConversationMainButton(
    state: VoiceConversationState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val error = MaterialTheme.colorScheme.error

    // 脉冲动画(LISTENING 时主按钮放大缩小,模拟呼吸感)
    val pulseScale by animateFloatAsState(
        targetValue = if (state == VoiceConversationState.LISTENING) 1.1f else 1f,
        animationSpec = if (state == VoiceConversationState.LISTENING) {
            infiniteRepeatable(
                animation = tween(MuseAnimation.LOOP_SLOW_MS, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            )
        } else {
            tween(MuseAnimation.TACTILE_MS)
        },
        label = "voiceMainPulse",
    )

    // 主按钮背景色:LISTENING 用 error(红,录音中提示),SPEAKING 用 primary,其他用 primary
    val buttonColor = when (state) {
        VoiceConversationState.LISTENING -> error
        else -> primary
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(160.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(buttonColor)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .semantics { contentDescription = stateAccessibilityLabel(state) },
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                VoiceConversationState.IDLE -> Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = onPrimary,
                    modifier = Modifier.size(72.dp),
                )
                VoiceConversationState.LISTENING -> Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = onPrimary,
                    modifier = Modifier.size(72.dp),
                )
                VoiceConversationState.THINKING -> CircularProgressIndicator(
                    color = onPrimary,
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(72.dp),
                )
                VoiceConversationState.SPEAKING -> Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = onPrimary,
                    modifier = Modifier.size(72.dp),
                )
            }
        }
        Spacer(Modifier.height(MusePaddings.contentGap))
        Text(
            text = stateStatusLabel(state),
            style = MaterialTheme.typography.titleMedium,
            color = onPrimary,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 状态面板:IDLE 显示提示、LISTENING 显示转写、THINKING 显示加载、SPEAKING 显示 AI 回复与进度。
 */
@Composable
private fun VoiceConversationStatusPanel(
    state: VoiceConversationState,
    transcript: String,
    aiReply: String,
    amplitudes: List<Float>,
    playbackPositionMs: Long,
    playbackDurationMs: Long,
    playbackChunkIndex: Int,
    playbackTotalChunks: Int,
    modifier: Modifier = Modifier,
) {
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val onPrimaryMedium = onPrimary.copy(alpha = 0.7f)

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            VoiceConversationState.IDLE -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.voice_conversation_idle_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = onPrimaryMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(MusePaddings.contentGap))
                    Text(
                        text = stringResource(R.string.voice_conversation_idle_tap_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = onPrimaryMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            VoiceConversationState.LISTENING -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.voice_conversation_listening),
                        style = MaterialTheme.typography.labelLarge,
                        color = onPrimaryMedium,
                    )
                    Spacer(Modifier.height(MusePaddings.contentGap))
                    // 波形动画(归一化振幅 → 竖条高度)
                    if (amplitudes.isNotEmpty()) {
                        ListeningWaveform(amplitudes = amplitudes)
                        Spacer(Modifier.height(MusePaddings.contentGap))
                    }
                    if (transcript.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.15f),
                            shape = MuseShapes.semiLarge,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = transcript,
                                style = MaterialTheme.typography.bodyLarge,
                                color = onPrimary,
                                modifier = Modifier.padding(MusePaddings.cardInner),
                                textAlign = TextAlign.Center,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            VoiceConversationState.THINKING -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = onPrimary,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.height(MusePaddings.contentGap))
                    Text(
                        text = stringResource(R.string.voice_conversation_thinking),
                        style = MaterialTheme.typography.bodyLarge,
                        color = onPrimaryMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            VoiceConversationState.SPEAKING -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.voice_conversation_speaking),
                        style = MaterialTheme.typography.labelLarge,
                        color = onPrimaryMedium,
                    )
                    Spacer(Modifier.height(MusePaddings.contentGap))
                    if (aiReply.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.15f),
                            shape = MuseShapes.semiLarge,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = aiReply,
                                style = MaterialTheme.typography.bodyMedium,
                                color = onPrimary,
                                modifier = Modifier.padding(MusePaddings.cardInner),
                                textAlign = TextAlign.Start,
                                maxLines = 12,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // 播放进度(分片进度 + 片内时间)
                    if (playbackTotalChunks > 0) {
                        Spacer(Modifier.height(MusePaddings.contentGap))
                        val progress = if (playbackDurationMs > 0) {
                            (playbackPositionMs.toFloat() / playbackDurationMs).coerceIn(0f, 1f)
                        } else 0f
                        val progressLabel = if (playbackTotalChunks > 1) {
                            "${playbackChunkIndex + 1}/$playbackTotalChunks"
                        } else {
                            "${(progress * 100).toInt()}%"
                        }
                        Text(
                            text = progressLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = onPrimaryMedium,
                        )
                    }
                }
            }
        }
    }
}

/**
 * LISTENING 状态下的波形动画(把最近振幅历史渲染成竖条)。
 *
 * 振幅已是 0-1f 归一化值(由 ASR 上报),直接映射为竖条高度。
 * 用主色调半透明渲染,与录音中主按钮的红色形成视觉呼应。
 */
@Composable
private fun ListeningWaveform(amplitudes: List<Float>) {
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    Row(
        modifier = Modifier.height(28.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        amplitudes.forEach { amp ->
            val fraction = amp.coerceIn(0.05f, 1f)
            val animatedHeight by animateFloatAsState(
                targetValue = fraction,
                animationSpec = tween(MuseAnimation.FAST_MS),
                label = "voiceWave",
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(animatedHeight)
                    .clip(CircleShape)
                    .background(onPrimary.copy(alpha = 0.8f)),
            )
        }
    }
}

/**
 * 切换语音 Bottom Sheet 内容:列出系统 TTS 可用声音,点击即切换。
 */
@Composable
private fun VoicePickerContent(
    voices: List<android.speech.tts.Voice>,
    currentVoiceName: String,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen)
            .navigationBarsPadding()
            .padding(bottom = MusePaddings.sectionGap),
    ) {
        Text(
            text = stringResource(R.string.voice_conversation_pick_voice_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = MusePaddings.contentGap),
        )
        if (voices.isEmpty()) {
            Text(
                text = stringResource(R.string.voice_conversation_no_voices),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = MusePaddings.sectionGap),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                items(voices, key = { it.name }) { voice ->
                    val isSelected = voice.name == currentVoiceName
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(voice.name) }
                            .padding(vertical = MusePaddings.contentGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = voice.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(
                                text = voice.locale?.displayName ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(MuseIconSizes.iconSmall),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 主按钮无障碍标签。 */
private fun stateAccessibilityLabel(state: VoiceConversationState): String = when (state) {
    VoiceConversationState.IDLE -> "点击开始对话"
    VoiceConversationState.LISTENING -> "正在录音,点击中断"
    VoiceConversationState.THINKING -> "AI 思考中,点击中断"
    VoiceConversationState.SPEAKING -> "正在朗读,点击中断"
}

/** 主按钮下方状态文本。 */
@Composable
private fun stateStatusLabel(state: VoiceConversationState): String = when (state) {
    VoiceConversationState.IDLE -> stringResource(R.string.voice_conversation_state_idle)
    VoiceConversationState.LISTENING -> stringResource(R.string.voice_conversation_state_listening)
    VoiceConversationState.THINKING -> stringResource(R.string.voice_conversation_state_thinking)
    VoiceConversationState.SPEAKING -> stringResource(R.string.voice_conversation_state_speaking)
}
