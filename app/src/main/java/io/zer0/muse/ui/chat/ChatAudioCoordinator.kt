package io.zer0.muse.ui.chat

import android.content.Context
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.asr.AsrConfig
import io.zer0.muse.asr.StreamingAsrCoordinator
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.speech.TtsManager
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * 聊天页音频（TTS / ASR）协调器。
 *
 * ASR 生命周期与状态机委托给共享的 [StreamingAsrCoordinator]，确保单聊与 Agent 行为完全一致。
 */
class ChatAudioCoordinator(
    private val accessor: ChatStateAccessor,
    private val ttsManager: TtsManager,
    private val settings: SettingsRepository,
    private val context: Context,
) {
    private val asrCoordinator = StreamingAsrCoordinator(
        context = context,
        coroutineScope = accessor.coroutineScope,
        configProvider = { accessor.snapshot.asrConfig },
        inputProvider = { accessor.snapshot.input },
        updateInput = { transform -> accessor.update { it.copy(input = transform(it.input)) } },
        onStateChange = { asrState -> accessor.update { it.copy(asrState = asrState) } },
    )

    /** 切换 TTS 朗读（同条消息停止，不同消息开始新朗读）。 */
    fun toggleTts(messageId: Uuid, content: String, reportError: (String) -> Unit) {
        val current = accessor.snapshot.speakingMessageId
        if (current == messageId) {
            ttsManager.stop()
        } else {
            val utteranceId = messageId.toString()
            accessor.coroutineScope.launch {
                if (content.isBlank()) {
                    reportError(context.getString(R.string.speech_tts_engine_not_ready))
                    return@launch
                }
                ttsManager.speakStream(content, utteranceId)
                ttsManager.flushStream()
            }
        }
    }

    /** 停止当前朗读（切换会话/退出页面时调用）。 */
    fun stopTts() {
        ttsManager.stop()
    }

    fun startStreamingAsr(): Boolean = asrCoordinator.startStreamingAsr()

    fun startVoiceConversationListening(onTranscript: (String) -> Unit) {
        asrCoordinator.startVoiceConversationListening(onTranscript)
    }

    fun stopVoiceConversationListening() {
        asrCoordinator.stopVoiceConversationListening()
    }

    fun cancelVoiceConversationListening() {
        asrCoordinator.cancelVoiceConversationListening()
    }

    fun stopStreamingAsrAndSend(onAutoSend: () -> Unit) {
        asrCoordinator.stopStreamingAsrAndSend(onAutoSend)
    }

    fun stopStreamingAsr() {
        asrCoordinator.stopStreamingAsr()
    }

    fun cancelStreamingAsr() {
        asrCoordinator.cancelStreamingAsr()
    }

    fun disposeAsr() {
        asrCoordinator.dispose()
    }

    fun saveAsrConfig(config: AsrConfig) {
        accessor.coroutineScope.launch {
            resultOf { settings.saveAsrConfig(config) }
        }
    }

    fun shouldUseApiRecording(): Boolean = asrCoordinator.shouldUseApiRecording()
}
