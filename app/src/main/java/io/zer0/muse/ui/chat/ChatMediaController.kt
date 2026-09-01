package io.zer0.muse.ui.chat

import android.content.Context
import io.zer0.ai.core.MessageRole
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.data.artifact.ArtifactRepository
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.ChatErrorType
import io.zer0.muse.ui.speech.TtsManager
import io.zer0.muse.ui.speech.VoiceConversationState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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
) {

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
                            // TTS 播放时 ASR 已停止(本循环不会在 SPEAKING 状态启动 ASR),避免回声
                            ttsManager.speak(content, lastAssistant.id.toString())
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
                        voiceState.aiReply.value = ""
                        voiceState.state.value = VoiceConversationState.LISTENING
                        startListeningForVoiceConversation()
                    }
                }
            }
        }
    }
}
