package io.zer0.muse.ui.chat

import android.content.Context
import io.zer0.ai.core.MessageRole
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.data.artifact.ArtifactRepository
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.ChatErrorType
import io.zer0.muse.ui.speech.TtsManager
import io.zer0.muse.ui.speech.VoiceConversationState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** P2-33: SPEAKING 看门狗基础超时(毫秒)— 起播/合成缓冲。 */
internal const val SPEAKING_WATCHDOG_BASE_MS = 8_000L

/** P2-33: 每个字符允许的最长朗读时间(毫秒),用于按文本长度自适应超时。 */
internal const val SPEAKING_WATCHDOG_MS_PER_CHAR = 120L

/** P2-33: SPEAKING 看门狗超时下限(毫秒)。 */
internal const val SPEAKING_WATCHDOG_MIN_MS = 15_000L

/** P2-33: SPEAKING 看门狗超时上限(毫秒)。 */
internal const val SPEAKING_WATCHDOG_MAX_MS = 120_000L

/** P2-33: 检测到 TTS 仍在播报时的最大续期次数(续期后仍无结束回调 → 强制回落)。 */
internal const val SPEAKING_WATCHDOG_MAX_EXTENSIONS = 1

/**
 * P2-33: SPEAKING 看门狗超时 — 按文本长度自适应。
 *
 * 朗读时长与文本长度近似线性([SPEAKING_WATCHDOG_MS_PER_CHAR] 为每字保守上限),
 * 叠加起播缓冲后收敛到 [SPEAKING_WATCHDOG_MIN_MS] ~ [SPEAKING_WATCHDOG_MAX_MS]:
 * 短句快速兜底,长文不误杀。
 */
internal fun adaptiveSpeakingWatchdogMs(textLength: Int): Long =
    (SPEAKING_WATCHDOG_BASE_MS + textLength.coerceAtLeast(0).toLong() * SPEAKING_WATCHDOG_MS_PER_CHAR)
        .coerceIn(SPEAKING_WATCHDOG_MIN_MS, SPEAKING_WATCHDOG_MAX_MS)

/**
 * v1.x: 从 ChatViewModel 抽离的媒体/产物 Controller。
 *
 * 职责:产物(artifact)卡片 + 语音对话模式(录音 → 识别 → 思考 → 播报循环)。
 * 语音循环经回调(send/stop/updateInput/addError)与生成侧解耦,不反向依赖 ChatViewModel。
 */
@Suppress("LongParameterList", "TooManyFunctions")
internal class ChatMediaController(
    private val accessor: ChatStateAccessor,
    private val artifactRepository: ArtifactRepository,
    private val voiceState: ChatVoiceState,
    private val stateStore: ChatStateStore,
    private val audioCoordinator: ChatAudioCoordinator,
    private val ttsManager: TtsManager,
    private val appContext: Context,
    private val onError: (ChatErrorType, String, Boolean) -> Unit,
    private val onSend: () -> Unit,
    private val onStop: () -> Unit,
    private val onUpdateInput: (String) -> Unit,
    private val settings: SettingsRepository,
    /**
     * P2-33: SPEAKING 看门狗超时提供者(毫秒,入参为待朗读文本长度)。
     * 默认按文本长度自适应([adaptiveSpeakingWatchdogMs]);单测注入短超时以确定性覆盖兜底路径。
     */
    private val speakingWatchdogTimeoutMs: (Int) -> Long = ::adaptiveSpeakingWatchdogMs,
) {

    private companion object {
        const val TAG = "ChatMediaController"

        /** 进入 SPEAKING 时的看门狗回落原因(写日志,便于事后定位引擎/回调问题)。 */
        const val REASON_NO_CALLBACK = "超时未收到任何 TTS 状态回调"
        const val REASON_STILL_SPEAKING = "TTS 长时间未报告结束(结束回调可能丢失)"
    }

    /** v1.43: 选中产物卡片,打开 ArtifactViewerDialog。 */
    fun selectArtifact(artifact: ArtifactEntity) {
        accessor.update { it.copy(selectedArtifact = artifact) }
    }

    /** v1.43: 关闭产物卡片查看弹窗。 */
    fun dismissArtifactViewer() {
        accessor.update { it.copy(selectedArtifact = null) }
    }

    /** v1.43: 观察某条消息关联的产物卡片列表。 */
    fun observeArtifactsByMessage(messageId: String): Flow<List<ArtifactEntity>> {
        return artifactRepository.observeByMessage(messageId)
    }

    /** 查询系统 TTS 可用声音列表(切换语音 Bottom Sheet 用)。 */
    fun getAvailableTtsVoices(): List<android.speech.tts.Voice> = ttsManager.getAvailableVoices()

    /** 当前生效的 TTS 声音名称(用于切换语音 Sheet 标记选中项)。 */
    fun currentTtsVoiceName(): String = accessor.snapshot.mediaConfig.ttsVoiceName

    /** 切换 TTS 声音:立即应用到 TtsManager,并持久化到 Settings(下次启动仍生效)。 */
    fun setTtsVoice(voiceName: String) {
        val currentConfig = accessor.snapshot.mediaConfig
        val newConfig = currentConfig.copy(ttsVoiceName = voiceName)
        ttsManager.applyConfig(newConfig)
        accessor.coroutineScope.launch {
            resultOf { settings.saveMediaConfig(newConfig) }
        }
    }

    /** 进入语音对话模式:开始首轮 LISTENING 并启动状态机循环观察。 */
    fun startVoiceConversation() {
        if (voiceState.state.value != VoiceConversationState.IDLE) return
        if (!audioCoordinator.shouldUseApiRecording()) {
            onError(ChatErrorType.UNKNOWN, appContext.getString(R.string.err_chat_voice_no_asr), true)
            return
        }
        // 取消旧循环协程,重启确保状态干净
        voiceState.job?.cancel()
        voiceState.state.value = VoiceConversationState.LISTENING
        startListeningForVoiceConversation()
        observeVoiceConversationLoop()
    }

    /** 退出语音对话模式:停止 ASR/TTS,取消循环观察协程,状态归零。 */
    fun stopVoiceConversation() {
        voiceState.job?.cancel()
        voiceState.job = null
        audioCoordinator.stopVoiceConversationListening()
        ttsManager.stop()
        voiceState.state.value = VoiceConversationState.IDLE
        voiceState.transcript.value = ""
        voiceState.aiReply.value = ""
    }

    /** 中断当前语音对话状态(用户点击主按钮)。 */
    fun interruptVoiceConversation() {
        val current = voiceState.state.value
        if (current == VoiceConversationState.IDLE) return
        audioCoordinator.stopVoiceConversationListening()
        ttsManager.stop()
        // THINKING 状态下 AI 仍在生成,需停止生成避免后续 isStreaming 回调误触发 TTS
        if (current == VoiceConversationState.THINKING && accessor.snapshot.isStreaming) {
            onStop()
        }
        voiceState.state.value = VoiceConversationState.IDLE
        voiceState.transcript.value = ""
        voiceState.aiReply.value = ""
    }

    /** 启动一轮 ASR 录音,识别文本通过回调写入 transcript。 */
    private fun startListeningForVoiceConversation() {
        voiceState.transcript.value = ""
        audioCoordinator.startVoiceConversationListening { transcript ->
            voiceState.transcript.value = transcript
        }
    }

    /** 启动状态机循环观察协程:监听 ASR/流式/TTS 状态切换,自动驱动状态机循环。 */
    private fun observeVoiceConversationLoop() {
        voiceState.job = accessor.coroutineScope.launch {
            var wasRecording = false
            var wasStreaming = false
            var wasSpeaking = false
            // P2-33: SPEAKING 看门狗 — 进入 SPEAKING 后启动,超时未观察到 TTS 状态回调则回落,
            // 避免 speak() 返回 true 但 isSpeaking 回调丢失/被 StateFlow 合并时永久卡在 SPEAKING。
            var speakingWatchdog: Job? = null
            var speakingEpoch = 0
            stateStore.state.collect { state ->
                // 1. LISTENING → THINKING:ASR 录音结束,取 transcript 自动发送
                if (voiceState.state.value == VoiceConversationState.LISTENING) {
                    if (state.asrState.isRecording) {
                        wasRecording = true
                    } else if (wasRecording) {
                        wasRecording = false
                        val text = voiceState.transcript.value.trim()
                        if (text.isNotEmpty()) {
                            voiceState.state.value = VoiceConversationState.THINKING
                            onUpdateInput(text)
                            onSend()
                        } else {
                            // 未识别到内容,回 IDLE 等待用户再次点击
                            voiceState.state.value = VoiceConversationState.IDLE
                        }
                    }
                }
                // 2. THINKING → SPEAKING:AI 流式回复完成,自动朗读
                if (voiceState.state.value == VoiceConversationState.THINKING) {
                    if (state.isStreaming) {
                        wasStreaming = true
                    } else if (wasStreaming) {
                        wasStreaming = false
                        val lastAssistant = stateStore.messages.value.lastOrNull { it.role == MessageRole.ASSISTANT }
                        val content = lastAssistant?.content?.takeIf { it.isNotBlank() }
                        if (content != null) {
                            voiceState.aiReply.value = content
                            voiceState.state.value = VoiceConversationState.SPEAKING
                            // P2-33: 新一轮播报从干净状态开始 — 上一轮被中断/看门狗回落时
                            // wasSpeaking 可能残留 true,不重置会让新一轮 SPEAKING 立刻被误判为"已结束"
                            wasSpeaking = false
                            // TTS 播放时 ASR 已停止(本循环不会在 SPEAKING 状态启动 ASR),避免回声
                            val speakStarted = ttsManager.speak(content, lastAssistant.id.toString())
                            if (!speakStarted) {
                                // P2-14: speak() 返回失败(引擎未就绪/无引擎)时 isSpeaking 永不为
                                // true,步骤 3 永不触发,SPEAKING 会死循环;失败直接回到 LISTENING 续听。
                                voiceState.aiReply.value = ""
                                voiceState.state.value = VoiceConversationState.LISTENING
                                startListeningForVoiceConversation()
                            } else {
                                // P2-33: 只有真正开始播报才武装看门狗;epoch 保证过期看门狗不会误伤新一轮 SPEAKING
                                speakingEpoch++
                                val epoch = speakingEpoch
                                speakingWatchdog?.cancel()
                                speakingWatchdog = launch {
                                    armSpeakingWatchdog(
                                        epoch = epoch,
                                        textLength = content.length,
                                        isCurrent = { epoch == speakingEpoch },
                                    )
                                }
                            }
                        } else {
                            voiceState.state.value = VoiceConversationState.IDLE
                        }
                    }
                }
                // 3. SPEAKING → LISTENING:TTS 朗读完成,恢复录音(连续对话)
                if (voiceState.state.value == VoiceConversationState.SPEAKING) {
                    if (state.isSpeaking) {
                        wasSpeaking = true
                    } else if (wasSpeaking) {
                        wasSpeaking = false
                        speakingWatchdog?.cancel()
                        speakingWatchdog = null
                        voiceState.aiReply.value = ""
                        voiceState.state.value = VoiceConversationState.LISTENING
                        startListeningForVoiceConversation()
                    }
                }
            }
        }
    }

    /**
     * P2-33: 看门狗主体 — 等待[自适应超时][adaptiveSpeakingWatchdogMs]。
     *
     * 超时后:
     *  - 状态已不是本轮的 SPEAKING([isCurrent] 为 false / 已被中断)→ 静默退出;
     *  - TTS 仍在播报(引擎或 UI 状态仍报 speaking)且未超过 [SPEAKING_WATCHDOG_MAX_EXTENSIONS] 次续期
     *    → 续期一次,避免长文本被误杀;
     *  - 否则回落 LISTENING 并记录原因(未收到任何回调 / 长时间未报告结束),同时停止 TTS 避免残留播放。
     */
    private suspend fun armSpeakingWatchdog(
        epoch: Int,
        textLength: Int,
        isCurrent: () -> Boolean,
    ) {
        val timeoutMs = speakingWatchdogTimeoutMs(textLength)
        var extensions = 0
        while (true) {
            delay(timeoutMs)
            if (!isCurrent() || voiceState.state.value != VoiceConversationState.SPEAKING) return
            val stillSpeaking = ttsManager.isSpeaking() || stateStore.state.value.isSpeaking
            if (stillSpeaking && extensions < SPEAKING_WATCHDOG_MAX_EXTENSIONS) {
                extensions++
                Logger.w(
                    TAG,
                    "SPEAKING 看门狗超时(${timeoutMs}ms)但 TTS 仍在播报,续期 $extensions 次" +
                        " | textLength=$textLength | epoch=$epoch",
                )
                continue
            }
            val reason = if (stillSpeaking) REASON_STILL_SPEAKING else REASON_NO_CALLBACK
            Logger.w(
                TAG,
                "SPEAKING 看门狗触发,回落 LISTENING | reason=$reason | timeout=${timeoutMs}ms" +
                    " | textLength=$textLength | epoch=$epoch",
            )
            runCatching { ttsManager.stop() }
            voiceState.aiReply.value = ""
            voiceState.state.value = VoiceConversationState.LISTENING
            startListeningForVoiceConversation()
            return
        }
    }
}
