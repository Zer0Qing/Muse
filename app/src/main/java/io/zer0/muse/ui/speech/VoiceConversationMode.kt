@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "TooManyFunctions")

package io.zer0.muse.ui.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.speech.tts.Voice
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.ai.core.MessageRole
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.asr.ASRState
import io.zer0.muse.asr.ASRStatus
import io.zer0.muse.asr.AsrConfig
import io.zer0.muse.asr.StreamingAsrCoordinator
import io.zer0.muse.data.MediaConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.SpeechProviderResolver
import io.zer0.muse.data.withResolvedEndpoint
import io.zer0.muse.ui.chat.ChatViewModel
import io.zer0.muse.ui.chat.adaptiveSpeakingWatchdogMs
import io.zer0.muse.ui.chat.mergeTranscript
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseSelectionSheet
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.safeFraction
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.bodyLargeStrong
import io.zer0.muse.ui.theme.bodyMediumStrong
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.util.launchSafely
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 语音对话模式状态机(ASR 按钮 + TTS 自动播放的循环模型)。
 *
 * 状态流转:
 *  - IDLE → LISTENING:用户点击主按钮,开始 ASR 录音
 *  - LISTENING → THINKING:用户再次点击主按钮(「这句话说完了」),取出整段识别文本一次性发送
 *  - THINKING → SPEAKING:AI 流式回复完成,自动调 TtsManager 朗读
 *  - SPEAKING → LISTENING:TTS 朗读完成,自动恢复录音(连续对话)
 *  - 任意状态 → IDLE:用户点击关闭按钮,或主按钮中断
 *
 * 设计要点:
 *  - 本段输入何时结束完全由用户决定:主按钮是 toggle(非按住),ASR 自行结束不提交
 *  - TTS 播放时必须暂停 ASR(避免扬声器声音被麦克风拾入形成回声)
 *  - 退出时立即释放 ASR/TTS 资源,避免后台继续录音或朗读
 *  - THINKING / SPEAKING 下点击主按钮中断当前状态,符合"一键可控"交互
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
 * 语音对话的 ASR 端口。把 Android 录音服务挡在协议外，控制器的状态机可在 JVM 单测中验证。
 */
internal interface VoiceConversationAsr {
    val state: StateFlow<ASRState>
    fun shouldUseApiRecording(): Boolean
    fun startListening(onTranscript: (String) -> Unit)
    fun stopListening()
    fun cancelListening()
}

/** 语音对话的 TTS 端口，允许测试注入不启动系统引擎的假实现。 */
internal interface VoiceConversationTts {
    val playbackState: StateFlow<PlaybackState>
    fun speak(text: String, utteranceId: String): Boolean
    fun stop()
}

/**
 * 语音对话循环控制器。
 *
 * 它只依赖 [VoiceConversationBridge] 暴露的生成状态与最新回复，因此普通聊天与 Agent
 * 主舞台可以共用同一套录音、提交、朗读、续听逻辑。
 */
internal class VoiceConversationController(
    private val bridge: VoiceConversationBridge,
    private val asr: VoiceConversationAsr?,
    private val tts: VoiceConversationTts?,
    private val unavailableMessage: String,
    private val retryHint: String,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val TAG = "VoiceConversation"
    }

    private val _state = MutableStateFlow(VoiceConversationState.IDLE)
    val state: StateFlow<VoiceConversationState> = _state.asStateFlow()

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    private val _aiReply = MutableStateFlow("")
    val aiReply: StateFlow<String> = _aiReply.asStateFlow()

    private val _hint = MutableStateFlow<String?>(null)
    val hint: StateFlow<String?> = _hint.asStateFlow()

    private val fallbackAsrState = MutableStateFlow(ASRState())
    val asrState: StateFlow<ASRState> = asr?.state ?: fallbackAsrState

    private val fallbackPlaybackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = tts?.playbackState ?: fallbackPlaybackState

    private var observeJob: Job? = null
    private var speakingWatchdog: Job? = null

    private var pendingCommit = false
    private var wasRecording = false
    private var wasGenerating = false
    private var wasSpeaking = false
    private var replyBeforeTurn = ""

    /** 进入语音对话模式:开始首轮 LISTENING 并启动状态机循环观察。 */
    fun startConversation(): Boolean {
        if (_state.value != VoiceConversationState.IDLE) return false
        val asrSource = asr
        if (asrSource == null || !asrSource.shouldUseApiRecording()) {
            _hint.value = unavailableMessage
            return false
        }
        observeJob?.cancel()
        speakingWatchdog?.cancel()
        pendingCommit = false
        wasRecording = false
        wasGenerating = false
        wasSpeaking = false
        _transcript.value = ""
        _aiReply.value = ""
        _hint.value = null
        replyBeforeTurn = bridge.latestAssistantReply.value
        _state.value = VoiceConversationState.LISTENING
        startListening()
        observeLoop()
        return true
    }

    /** 退出语音对话模式:停止 ASR/TTS,取消循环观察协程,状态归零。 */
    fun stopConversation() {
        observeJob?.cancel()
        observeJob = null
        speakingWatchdog?.cancel()
        speakingWatchdog = null
        pendingCommit = false
        asr?.cancelListening()
        tts?.stop()
        reset()
    }

    /** 中断当前语音对话状态(用户点击主按钮)。 */
    fun interruptConversation() {
        val current = _state.value
        if (current == VoiceConversationState.IDLE) return
        val shouldStopGeneration = current == VoiceConversationState.THINKING && bridge.isGenerating.value
        observeJob?.cancel()
        observeJob = null
        speakingWatchdog?.cancel()
        speakingWatchdog = null
        pendingCommit = false
        asr?.cancelListening()
        tts?.stop()
        if (shouldStopGeneration) bridge.stopGeneration()
        reset()
    }

    /**
     * 语音对话主按钮(UI 侧 toggle,非按住式):
     *  - IDLE:开始一轮对话
     *  - LISTENING 且正在录音:结束本段输入并提交
     *  - LISTENING 但未在录音:重新开始录音
     *  - THINKING / SPEAKING:中断当前回复
     */
    fun toggleConversation() {
        when (_state.value) {
            VoiceConversationState.IDLE -> startConversation()
            VoiceConversationState.LISTENING -> {
                if (asrState.value.isRecording) {
                    finishListening()
                } else {
                    startListening()
                }
            }
            VoiceConversationState.THINKING,
            VoiceConversationState.SPEAKING,
            -> interruptConversation()
        }
    }

    /** 显式提交入口:用户决定「这一句说完了」。 */
    fun finishListening() {
        if (_state.value != VoiceConversationState.LISTENING) return
        if (asrState.value.isRecording.not()) return
        pendingCommit = true
        _hint.value = null
        asr?.stopListening()
    }

    private fun startListening() {
        asr?.startListening { incoming ->
            if (_state.value == VoiceConversationState.LISTENING) {
                _transcript.value = mergeTranscript(_transcript.value, incoming)
            }
        }
    }

    private fun observeLoop() {
        observeJob?.cancel()
        observeJob = scope.launch {
            combine(
                asrState,
                bridge.isGenerating,
                bridge.latestAssistantReply,
                playbackState,
            ) { asrSnapshot, generating, reply, playback ->
                VoiceConversationSnapshot(asrSnapshot, generating, reply, playback)
            }.collect { snapshot ->
                when (_state.value) {
                    VoiceConversationState.IDLE -> Unit
                    VoiceConversationState.LISTENING -> handleListening(snapshot)
                    VoiceConversationState.THINKING -> handleThinking(snapshot)
                    VoiceConversationState.SPEAKING -> handleSpeaking(snapshot)
                }
            }
        }
    }

    private fun handleListening(snapshot: VoiceConversationSnapshot) {
        if (snapshot.asr.isRecording) {
            wasRecording = true
            if (_hint.value != null) _hint.value = null
            return
        }
        if (wasRecording) {
            wasRecording = false
            val userCommitted = pendingCommit
            pendingCommit = false
            val text = mergeTranscript(_transcript.value, snapshot.asr.transcript)
            if (userCommitted && snapshot.asr.status == ASRStatus.Idle && text.isNotEmpty()) {
                _transcript.value = ""
                _aiReply.value = ""
                replyBeforeTurn = bridge.latestAssistantReply.value
                _state.value = VoiceConversationState.THINKING
                runCatching { bridge.sendVoiceUtterance(text) }
                    .onFailure {
                        Logger.w(TAG, "发送语音转写失败: ${it.message}", it)
                        resumeListeningAfterInterruptedAsr()
                    }
            } else {
                resumeListeningAfterInterruptedAsr()
            }
            return
        }
        if (snapshot.asr.status == ASRStatus.Error && _transcript.value.isBlank()) {
            _hint.value = snapshot.asr.errorMessage ?: retryHint
        }
    }

    private fun handleThinking(snapshot: VoiceConversationSnapshot) {
        if (snapshot.generating) {
            wasGenerating = true
            return
        }
        if (!wasGenerating) return
        wasGenerating = false
        val reply = snapshot.reply.trim()
        if (reply.isBlank() || reply == replyBeforeTurn) {
            resumeListeningAfterInterruptedAsr()
            return
        }
        _aiReply.value = reply
        _state.value = VoiceConversationState.SPEAKING
        wasSpeaking = false
        val ttsSource = tts
        if (ttsSource == null || !ttsSource.speak(reply, "voice-turn-${System.currentTimeMillis()}")) {
            resumeListeningAfterInterruptedAsr()
            return
        }
        armSpeakingWatchdog(reply.length)
    }

    private fun handleSpeaking(snapshot: VoiceConversationSnapshot) {
        if (snapshot.playback.status == PlaybackStatus.Playing) {
            wasSpeaking = true
            return
        }
        if (wasSpeaking && (snapshot.playback.status == PlaybackStatus.Ended || snapshot.playback.status == PlaybackStatus.Idle)) {
            finishSpeaking()
        }
    }

    private fun resumeListeningAfterInterruptedAsr() {
        _aiReply.value = ""
        _hint.value = if (_transcript.value.isBlank()) retryHint else null
        _state.value = VoiceConversationState.LISTENING
        if (asrState.value.status != ASRStatus.Error) startListening()
    }

    private fun finishSpeaking() {
        speakingWatchdog?.cancel()
        speakingWatchdog = null
        wasSpeaking = false
        _aiReply.value = ""
        _state.value = VoiceConversationState.LISTENING
        if (asrState.value.status != ASRStatus.Error) startListening()
    }

    private fun armSpeakingWatchdog(textLength: Int) {
        speakingWatchdog?.cancel()
        speakingWatchdog = scope.launch {
            delay(adaptiveSpeakingWatchdogMs(textLength))
            if (_state.value != VoiceConversationState.SPEAKING) return@launch
            if (playbackState.value.status == PlaybackStatus.Playing) return@launch
            Logger.w(TAG, "TTS 长时间未报告播放状态,回落监听")
            tts?.stop()
            finishSpeaking()
        }
    }

    private fun reset() {
        _state.value = VoiceConversationState.IDLE
        _transcript.value = ""
        _aiReply.value = ""
        _hint.value = null
        wasRecording = false
        wasGenerating = false
        wasSpeaking = false
        replyBeforeTurn = ""
    }

    private data class VoiceConversationSnapshot(
        val asr: ASRState,
        val generating: Boolean,
        val reply: String,
        val playback: PlaybackState,
    )
}

/** 普通聊天 ViewModel 到语音桥的适配器。 */
private class ChatViewModelVoiceConversationBridge(
    private val viewModel: ChatViewModel,
    stateScope: CoroutineScope,
) : VoiceConversationBridge {

    override val assistantName: StateFlow<String> = viewModel.state
        .map { it.currentAssistant?.name.orEmpty() }
        .stateIn(stateScope, SharingStarted.Eagerly, viewModel.state.value.currentAssistant?.name.orEmpty())

    override val isGenerating: StateFlow<Boolean> = viewModel.state
        .map { it.isStreaming }
        .stateIn(stateScope, SharingStarted.Eagerly, viewModel.state.value.isStreaming)

    override val latestAssistantReply: StateFlow<String> = viewModel.messages
        .map { messages -> messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.content.orEmpty() }
        .stateIn(stateScope, SharingStarted.Eagerly, viewModel.messages.value.lastOrNull { it.role == MessageRole.ASSISTANT }?.content.orEmpty())

    override fun sendVoiceUtterance(text: String) {
        viewModel.updateInput(text)
        viewModel.send()
    }

    override fun stopGeneration() {
        viewModel.stop()
    }
}

/** 真实录音链路到语音 ASR 端口的适配器。 */
private class AndroidVoiceConversationAsr(
    context: Context,
    scope: CoroutineScope,
    configProvider: () -> AsrConfig,
) : VoiceConversationAsr {
    private val coordinator = StreamingAsrCoordinator(
        context = context,
        coroutineScope = scope,
        configProvider = configProvider,
        inputProvider = { "" },
        updateInput = { _ -> },
    )

    override val state: StateFlow<ASRState> = coordinator.state

    override fun shouldUseApiRecording(): Boolean = coordinator.shouldUseApiRecording()

    override fun startListening(onTranscript: (String) -> Unit) {
        coordinator.startVoiceConversationListening(onTranscript)
    }

    override fun stopListening() {
        coordinator.stopVoiceConversationListening()
    }

    override fun cancelListening() {
        coordinator.cancelVoiceConversationListening()
    }
}

/** 真实 TTS 管理器到语音 TTS 端口的适配器。 */
private class TtsVoiceConversationTts(
    private val manager: TtsManager,
) : VoiceConversationTts {
    override val playbackState: StateFlow<PlaybackState> = manager.playbackState

    override fun speak(text: String, utteranceId: String): Boolean = manager.speak(text, utteranceId)

    override fun stop() {
        manager.stop()
    }
}

/**
 * 兼容旧调用的完整语音对话模式。内部转换为通用桥接，页面逻辑只保留一份。
 */
@Composable
fun VoiceConversationMode(onClose: () -> Unit, viewModel: ChatViewModel) {
    val scope = rememberCoroutineScope()
    val bridge = remember(viewModel, scope) { ChatViewModelVoiceConversationBridge(viewModel, scope) }
    VoiceConversationMode(onClose = onClose, bridge = bridge)
}

/**
 * 完整语音对话模式(全屏覆盖式)。
 *
 * @param onClose 用户点击关闭按钮(X)时回调
 * @param bridge 会话桥接:负责接收识别文本、暴露生成状态与最新回复
 */
@Composable
fun VoiceConversationMode(onClose: () -> Unit, bridge: VoiceConversationBridge) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepository: SettingsRepository = koinInject()
    val ttsManager: TtsManager = koinInject()

    val asrConfig by settingsRepository.asrConfigFlow.collectAsStateWithLifecycle(initialValue = AsrConfig())
    val providers by settingsRepository.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val mediaConfig by settingsRepository.mediaConfigFlow.collectAsStateWithLifecycle(initialValue = MediaConfig())
    val resolvedAsrConfig = remember(asrConfig, providers) {
        asrConfig.withResolvedEndpoint(SpeechProviderResolver.resolveAsr(asrConfig, providers))
    }
    val configState = rememberUpdatedState(resolvedAsrConfig)
    val asr = remember(context, scope) {
        AndroidVoiceConversationAsr(context, scope) { configState.value }
    }
    val tts = remember(ttsManager) { TtsVoiceConversationTts(ttsManager) }
    val unavailableMessage = stringResource(R.string.err_chat_voice_no_asr)
    val retryHint = stringResource(R.string.voice_conversation_retry_hint)
    val controller = remember(bridge, asr, tts, unavailableMessage, retryHint) {
        VoiceConversationController(
            bridge = bridge,
            asr = asr,
            tts = tts,
            unavailableMessage = unavailableMessage,
            retryHint = retryHint,
            scope = scope,
        )
    }

    val conversationState by controller.state.collectAsStateWithLifecycle()
    val transcript by controller.transcript.collectAsStateWithLifecycle()
    val aiReply by controller.aiReply.collectAsStateWithLifecycle()
    val hint by controller.hint.collectAsStateWithLifecycle()
    val asrState by controller.asrState.collectAsStateWithLifecycle()
    val playbackState by controller.playbackState.collectAsStateWithLifecycle()
    val assistantName by bridge.assistantName.collectAsStateWithLifecycle()
    val fallbackAssistantName = stringResource(R.string.agent_screen_title)
    val voiceModeHint = stringResource(R.string.agent_hub_voice_mode_hint)

    val isRecording = conversationState == VoiceConversationState.LISTENING && asrState.isRecording

    var showVoicePicker by remember { mutableStateOf(false) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            controller.startConversation()
        } else {
            MuseToast.show(context.getString(R.string.chat_err_mic_permission))
            onClose()
        }
    }

    LaunchedEffect(controller) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            controller.startConversation()
        } else {
            micPermissionLauncher.launchSafely(Manifest.permission.RECORD_AUDIO, context)
        }
    }

    val handleClose: () -> Unit = {
        controller.stopConversation()
        onClose()
    }

    BackHandler(onBack = handleClose)
    DisposableEffect(controller) {
        onDispose { controller.stopConversation() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = MuseAlpha.titleMuted)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = assistantName.ifBlank { fallbackAssistantName },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = voiceModeHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = MuseAlpha.disabledContent),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                MuseTactileButton(
                    icon = MuseIcons.x,
                    onClick = handleClose,
                    contentDescription = stringResource(R.string.voice_conversation_close_cd),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    size = MuseIconSizes.touchTarget,
                    iconSize = MuseIconSizes.icon,
                )
            }

            VoiceConversationMainButton(
                state = conversationState,
                isRecording = isRecording,
                onClick = controller::toggleConversation,
                modifier = Modifier.padding(vertical = MusePaddings.sectionGap),
            )

            VoiceConversationStatusPanel(
                state = conversationState,
                transcript = transcript,
                aiReply = aiReply,
                hint = hint,
                amplitudes = asrState.amplitudes,
                playback = playbackState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = MusePaddings.screen),
            )

            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = MuseAlpha.statusTint),
                shape = MuseShapes.pill,
                modifier = Modifier
                    .padding(bottom = MusePaddings.sectionGap)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { showVoicePicker = true },
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = MuseSpacing.xl,
                        vertical = MuseSpacing.md,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    Icon(
                        imageVector = MuseIcons.microphone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                    Text(
                        text = stringResource(R.string.voice_conversation_change_voice),
                        style = MaterialTheme.typography.bodyMediumStrong,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }

    if (showVoicePicker) {
        MuseSelectionSheet(
            title = stringResource(R.string.voice_conversation_pick_voice_title),
            onDismissRequest = { showVoicePicker = false },
        ) {
            VoicePickerContent(
                voices = ttsManager.getAvailableVoices(),
                currentVoiceName = mediaConfig.ttsVoiceName,
                onSelect = { name ->
                    val newConfig = mediaConfig.copy(ttsVoiceName = name)
                    ttsManager.applyConfig(newConfig)
                    scope.launch {
                        runCatching { settingsRepository.saveMediaConfig(newConfig) }
                            .onFailure { Logger.w("VoiceConversation", "保存语音选择失败: ${it.message}", it) }
                    }
                    showVoicePicker = false
                },
            )
        }
    }
}

/**
 * 语音对话主按钮(大圆形,根据模式状态 + 实际录音状态切换图标与动画)。
 */
@Composable
private fun VoiceConversationMainButton(
    state: VoiceConversationState,
    isRecording: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val error = MaterialTheme.colorScheme.error
    val mainButtonSize = MuseSpacing.xxxl * 5f
    val mainIconSize = MuseIconSizes.iconEmpty + MuseSpacing.sm

    val pulseScale = if (state == VoiceConversationState.LISTENING && isRecording && !MuseMotion.isReducedMotion()) {
        val transition = rememberInfiniteTransition(label = "voiceMainPulse")
        val animatedScale by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.1f,
            animationSpec = infiniteRepeatable(
                animation = MuseMotion.tween(MuseAnimation.LOOP_SLOW_MS, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "voiceMainPulseScale",
        )
        animatedScale
    } else {
        1f
    }

    val mainButtonInteraction = remember { MutableInteractionSource() }
    val isMainPressed by mainButtonInteraction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isMainPressed) 0.96f else 1f,
        animationSpec = MuseMotion.tween(MuseAnimation.FAST_MS),
        label = "voiceMainPress",
    )

    val buttonColor = when {
        state == VoiceConversationState.LISTENING && isRecording -> error
        else -> primary
    }

    val stateCd = stateAccessibilityLabel(state, isRecording)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(mainButtonSize)
                .scale(pulseScale * pressScale)
                .clip(CircleShape)
                .background(buttonColor)
                .clickable(
                    interactionSource = mainButtonInteraction,
                    indication = null,
                    onClick = onClick,
                )
                .semantics { contentDescription = stateCd },
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                VoiceConversationState.IDLE -> Icon(
                    imageVector = MuseIcons.microphone,
                    contentDescription = null,
                    tint = onPrimary,
                    modifier = Modifier.size(mainIconSize),
                )
                VoiceConversationState.LISTENING -> Icon(
                    imageVector = if (isRecording) MuseIcons.stop else MuseIcons.microphone,
                    contentDescription = null,
                    tint = onPrimary,
                    modifier = Modifier.size(mainIconSize),
                )
                VoiceConversationState.THINKING -> MuseSpinner(
                    size = mainIconSize,
                    strokeWidth = MuseSpacing.xs,
                    color = onPrimary,
                )
                VoiceConversationState.SPEAKING -> Icon(
                    imageVector = MuseIcons.volume,
                    contentDescription = null,
                    tint = onPrimary,
                    modifier = Modifier.size(mainIconSize),
                )
            }
        }
        Spacer(Modifier.height(MusePaddings.contentGap))
        Text(
            text = stateStatusLabel(state, isRecording),
            style = MaterialTheme.typography.titleMedium,
            color = onPrimary,
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
    hint: String?,
    amplitudes: List<Float>,
    playback: PlaybackState,
    modifier: Modifier = Modifier,
) {
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val onPrimaryMedium = onPrimary.copy(alpha = MuseAlpha.disabledContent)

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            VoiceConversationState.IDLE -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!hint.isNullOrBlank()) {
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = onPrimaryMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(MusePaddings.contentGap))
                    }
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
                    if (!hint.isNullOrBlank()) {
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodyLarge,
                            color = onPrimary,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(MusePaddings.contentGap))
                    }
                    Text(
                        text = stringResource(R.string.voice_conversation_listening),
                        style = MaterialTheme.typography.labelLarge,
                        color = onPrimaryMedium,
                    )
                    Spacer(Modifier.height(MusePaddings.contentGap))
                    Text(
                        text = stringResource(R.string.voice_conversation_listening_finish_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = onPrimaryMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(MusePaddings.contentGap))
                    if (amplitudes.isNotEmpty()) {
                        ListeningWaveform(amplitudes = amplitudes)
                        Spacer(Modifier.height(MusePaddings.contentGap))
                    }
                    if (transcript.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = MuseAlpha.statusTint),
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
                    MuseSpinner(
                        size = MuseIconSizes.stopButton,
                        strokeWidth = MuseIconSizes.progressStroke,
                        color = onPrimary,
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
                            color = MaterialTheme.colorScheme.surface.copy(alpha = MuseAlpha.statusTint),
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
                    if (playback.totalChunks > 0) {
                        Spacer(Modifier.height(MusePaddings.contentGap))
                        val progress = if (playback.durationMs > 0) {
                            (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        val progressLabel = if (playback.totalChunks > 1) {
                            "${playback.currentChunkIndex + 1}/${playback.totalChunks}"
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

/** LISTENING 状态下的波形动画(把最近振幅历史渲染成竖条)。 */
@Composable
private fun ListeningWaveform(amplitudes: List<Float>) {
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    Row(
        modifier = Modifier.height(MuseSpacing.xxl + MuseSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        amplitudes.forEach { amp ->
            val fraction = amp.safeFraction(min = 0.05f, fallback = 0.05f)
            val animatedHeight by animateFloatAsState(
                targetValue = fraction,
                animationSpec = MuseMotion.tween(MuseAnimation.FAST_MS),
                label = "voiceWave",
            )
            Box(
                modifier = Modifier
                    .width(MuseIconSizes.waveformBarWidth)
                    .fillMaxHeight(animatedHeight)
                    .clip(CircleShape)
                    .background(onPrimary.copy(alpha = MuseAlpha.titleMuted)),
            )
        }
    }
}

/** 切换语音 Bottom Sheet 内容:列出系统 TTS 可用声音,点击即切换。 */
@Composable
private fun VoicePickerContent(
    voices: List<Voice>,
    currentVoiceName: String,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = MusePaddings.sectionGap),
    ) {
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
                    .heightIn(max = MusePaddings.maxToolSheetListHeight),
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
                                style = if (isSelected) {
                                    MaterialTheme.typography.bodyLargeStrong
                                } else {
                                    MaterialTheme.typography.bodyLarge
                                },
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            Text(
                                text = voice.locale?.displayName.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = MuseIcons.check,
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

/** 主按钮无障碍标签(随录音状态变:录音中 = 结束并发送,未录音 = 开始录音)。 */
@Composable
private fun stateAccessibilityLabel(state: VoiceConversationState, isRecording: Boolean): String = when (state) {
    VoiceConversationState.IDLE -> stringResource(R.string.voice_conversation_cd_idle)
    VoiceConversationState.LISTENING -> if (isRecording) {
        stringResource(R.string.voice_conversation_cd_listening)
    } else {
        stringResource(R.string.voice_conversation_cd_listening_waiting)
    }
    VoiceConversationState.THINKING -> stringResource(R.string.voice_conversation_cd_thinking)
    VoiceConversationState.SPEAKING -> stringResource(R.string.voice_conversation_cd_speaking)
}

/** 主按钮下方状态文本。 */
@Composable
private fun stateStatusLabel(state: VoiceConversationState, isRecording: Boolean): String = when (state) {
    VoiceConversationState.IDLE -> stringResource(R.string.voice_conversation_state_idle)
    VoiceConversationState.LISTENING -> if (isRecording) {
        stringResource(R.string.voice_conversation_state_listening)
    } else {
        stringResource(R.string.voice_conversation_state_listening_idle)
    }
    VoiceConversationState.THINKING -> stringResource(R.string.voice_conversation_state_thinking)
    VoiceConversationState.SPEAKING -> stringResource(R.string.voice_conversation_state_speaking)
}
