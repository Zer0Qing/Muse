package io.zer0.muse.ui.chat

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.asr.AsrConfig
import io.zer0.muse.asr.StreamingAsrCoordinator
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.transformer.MoodSkinParser
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.speech.TtsManager
import io.zer0.muse.ui.speech.TtsSynthResult
import io.zer0.muse.ui.speech.VoiceMessageStore
import io.zer0.muse.ui.speech.VoicePlaybackController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.uuid.Uuid

/**
 * 聊天页音频（TTS / ASR / 微信式语音条）协调器。
 *
 * ASR 生命周期与状态机委托给共享的 [StreamingAsrCoordinator]，确保单聊与 Agent 行为完全一致。
 */
class ChatAudioCoordinator(
    private val accessor: ChatStateAccessor,
    private val ttsManager: TtsManager,
    private val settings: SettingsRepository,
    private val context: Context,
    private val sessionRepository: SessionRepository? = null,
    private val voiceMessageStore: VoiceMessageStore? = null,
    private val voicePlaybackController: VoicePlaybackController? = null,
) {
    private val asrCoordinator = StreamingAsrCoordinator(
        context = context,
        coroutineScope = accessor.coroutineScope,
        configProvider = { accessor.snapshot.asrConfig },
        inputProvider = { accessor.snapshot.input },
        updateInput = { transform -> accessor.update { it.copy(input = transform(it.input)) } },
        onStateChange = { asrState -> accessor.update { it.copy(asrState = asrState) } },
    )

    /** 切换消息语音条（已合成则直接点按播放/暂停；未合成则转为微信式语音条，默认点按才播）。 */
    fun toggleTts(messageId: Uuid, content: String, reportError: (String) -> Unit) {
        val store = voiceMessageStore
        val playback = voicePlaybackController
        val existingUri = accessor.messagesSnapshot.firstOrNull { it.id == messageId }
            ?.voiceUri
            ?.takeIf { it.isNotBlank() }
        if (store != null && playback != null && existingUri != null) {
            val file = store.resolve(existingUri)
            if (file != null) {
                playback.toggle(existingUri, file)
                return
            }
        }

        if (store != null) {
            if (!ttsManager.isEngineAvailable()) {
                reportError(
                    context.getString(
                        if (ttsManager.initFailedState.value) {
                            R.string.speech_tts_init_failed
                        } else {
                            R.string.tts_not_ready
                        },
                    ),
                )
                return
            }
            val clean = TtsManager.stripMarkdown(MoodSkinParser.cleanForExport(content)).trim()
            if (clean.isBlank()) {
                reportError(context.getString(R.string.speech_tts_engine_not_ready))
                return
            }
            MuseToast.show(context.getString(R.string.chat_voice_converting_toast))
            val sessionId = effectiveChatSessionId(accessor.snapshot)
            accessor.coroutineScope.launch {
                synthesizeVoiceBarForMessage(
                    sessionId = sessionId,
                    messageId = messageId,
                    rawContent = clean,
                    notifyReady = true,
                    reportError = reportError,
                )
            }
            return
        }

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

    /** 把指定消息内容合成为微信式语音条并挂回消息与数据库；默认静默呈现（点按才播）。 */
    suspend fun synthesizeVoiceBarForMessage(
        sessionId: String?,
        messageId: Uuid,
        rawContent: String,
        notifyReady: Boolean,
        reportError: ((String) -> Unit)? = null,
    ) {
        val store = voiceMessageStore ?: return
        val clean = TtsManager.stripMarkdown(MoodSkinParser.cleanForExport(rawContent)).trim()
        if (clean.isBlank() || messageId in accessor.snapshot.synthesizingVoiceMessageIds) return

        accessor.update {
            it.copy(synthesizingVoiceMessageIds = it.synthesizingVoiceMessageIds + messageId)
        }
        val tempFile = File(context.cacheDir, "voice_bar_${Uuid.random()}.wav")
        try {
            val outcome = ttsManager.synthesizeTo(clean, tempFile, null)
            if (outcome is TtsSynthResult.Failure) {
                Logger.w(TAG, "语音条合成失败: ${outcome.reason}")
                reportError?.invoke(context.getString(R.string.chat_voice_synth_failed_toast))
            } else {
                val voiceUri = store.persist(tempFile, clean + VOICE_BAR_KEY_SUFFIX)
                if (voiceUri == null) {
                    reportError?.invoke(context.getString(R.string.chat_voice_synth_failed_toast))
                } else {
                    store.saveTranscript(voiceUri, clean)
                    val durationMs = withContext(Dispatchers.IO) { store.probeDurationMs(tempFile) }
                    accessor.updateMessages { list ->
                        list.map { msg ->
                            if (msg.id == messageId) {
                                msg.copy(voiceUri = voiceUri, voiceDurationMs = durationMs)
                            } else {
                                msg
                            }
                        }
                    }
                    sessionRepository?.attachMediaToMessage(
                        sessionId = sessionId,
                        messageId = messageId,
                        content = "",
                        voiceUri = voiceUri,
                        voiceDurationMs = durationMs,
                    )
                    if (notifyReady) {
                        MuseToast.show(context.getString(R.string.chat_voice_ready_toast))
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IOException) {
            Logger.w(TAG, "语音条生成异常: ${e.message}")
            reportError?.invoke(context.getString(R.string.chat_voice_synth_failed_toast))
        } catch (e: IllegalStateException) {
            Logger.w(TAG, "语音条生成异常: ${e.message}")
            reportError?.invoke(context.getString(R.string.chat_voice_synth_failed_toast))
        } finally {
            runCatching { tempFile.delete() }
            accessor.update {
                it.copy(synthesizingVoiceMessageIds = it.synthesizingVoiceMessageIds - messageId)
            }
        }
    }

    /** 停止当前朗读与语音条播放（切换会话/退出页面时调用）。 */
    fun stopTts() {
        ttsManager.stop()
        voicePlaybackController?.stop()
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

    private companion object {
        private const val TAG = "ChatAudioCoordinator"
        private const val VOICE_BAR_KEY_SUFFIX = "|voice-bar"
    }
}
