package io.zer0.muse.ui.chat

import io.zer0.common.resultOf
import io.zer0.muse.asr.ASRController
import io.zer0.muse.asr.ASRState
import io.zer0.muse.asr.AsrConfig
import io.zer0.muse.asr.AsrProviderType
import io.zer0.muse.asr.DashScopeAsrController
import io.zer0.muse.asr.OpenAiRealtimeAsrController
import io.zer0.muse.asr.OpenAiWhisperAsrController
import io.zer0.muse.asr.StepAsrController
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.speech.TtsManager
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * v1.105 阶段 1: 从 ChatViewModel 抽离的音频(TTS / ASR)Coordinator。
 *
 * 职责:
 *  - toggleTts / stopTts: 文本转语音
 *  - 流式 ASR Controller 管理(getOrCreateAsrController / start / stop / cancel / dispose)
 *  - saveAsrConfig / shouldUseApiRecording
 *
 * 持有 [asrController] 和 [asrBaseText] 两个可变状态(原 ChatViewModel 字段迁移过来)。
 */
class ChatAudioCoordinator(
    private val accessor: ChatStateAccessor,
    private val ttsManager: TtsManager,
    private val settings: SettingsRepository,
) {

    /** v1.91: 流式 ASR Controller 实例(懒创建,Provider 切换时重建)。 */
    private var asrController: ASRController? = null

    /** v1.91: 录音前输入框文本快照(用于结果拼接与取消时恢复)。 */
    private var asrBaseText = ""

    /** Phase 8.7: 切换 TTS 朗读(同条消息停止,不同消息开始新朗读)。 */
    fun toggleTts(messageId: Uuid, content: String, reportError: (String) -> Unit) {
        val current = accessor.snapshot.speakingMessageId
        if (current == messageId) {
            ttsManager.stop()
        } else {
            val ok = ttsManager.speak(content, messageId.toString())
            if (!ok) {
                reportError("语音引擎未就绪或文本为空")
            }
        }
    }

    /** Phase 8.7: 停止当前朗读(切换会话/退出页面时调用)。 */
    fun stopTts() {
        ttsManager.stop()
    }

    /**
     * v1.91: 创建或复用 ASRController(根据当前 asrConfig)。
     * - SYSTEM / DASHSCOPE_FILE 返回 null(走旧路径)
     * - DASHSCOPE / STEP / OPENAI_WHISPER / OPENAI_REALTIME / AGNES 创建对应流式 Controller,并启动状态观察协程
     */
    private fun getOrCreateAsrController(): ASRController? {
        val cfg = accessor.snapshot.asrConfig
        if (cfg.provider == AsrProviderType.SYSTEM || cfg.provider == AsrProviderType.DASHSCOPE_FILE) return null
        if (cfg.apiKey.isBlank()) return null
        val existing = asrController
        if (existing != null) return existing
        val controller = when (cfg.provider) {
            AsrProviderType.DASHSCOPE -> DashScopeAsrController(cfg)
            AsrProviderType.STEP -> StepAsrController(cfg)
            // 通用 OpenAI Whisper 兼容端点(含 agnes 等中转站),分段批量非真流式
            AsrProviderType.OPENAI_WHISPER -> OpenAiWhisperAsrController(cfg)
            // Agnes 中转站(OpenAI 兼容),复用 Whisper 适配器,baseUrl 不同
            AsrProviderType.AGNES -> OpenAiWhisperAsrController(cfg)
            // OpenAI Realtime WebSocket 流式(服务端 VAD + 增量 transcription)
            AsrProviderType.OPENAI_REALTIME -> OpenAiRealtimeAsrController(cfg)
            // SYSTEM / DASHSCOPE_FILE 上面已 return null,此处不会到达
            AsrProviderType.SYSTEM, AsrProviderType.DASHSCOPE_FILE -> return null
        }
        asrController = controller
        accessor.coroutineScope.launch {
            controller.state.collect { state ->
                accessor.update { it.copy(asrState = state) }
            }
        }
        return controller
    }

    /** v1.91: 开始流式录音识别。 */
    fun startStreamingAsr() {
        val controller = getOrCreateAsrController() ?: return
        if (controller.state.value.isRecording) return
        asrBaseText = accessor.snapshot.input
        controller.start { transcript ->
            val spacer = if (asrBaseText.isBlank() || transcript.isBlank()) "" else " "
            accessor.update { it.copy(input = asrBaseText + spacer + transcript) }
        }
    }

    /**
     * 语音对话模式专用:开始流式录音,识别文本通过 [onTranscript] 回调给调用方,
     * 不写入输入框(语音对话模式不走 InputBar 文本流)。
     *
     * 与 [startStreamingAsr] 的区别:
     *  - [startStreamingAsr]:transcript 回填到 input 字段,用户可编辑后手动发送
     *  - [startVoiceConversationListening]:transcript 通过回调上报,由 ViewModel 自动发送
     *
     * @param onTranscript 实时识别文本回调(主线程,含中间与最终结果)
     */
    fun startVoiceConversationListening(onTranscript: (String) -> Unit) {
        val controller = getOrCreateAsrController() ?: return
        if (controller.state.value.isRecording) return
        controller.start(onTranscript)
    }

    /** 语音对话模式专用:停止录音,等待最后一段结果返回后切回 Idle。 */
    fun stopVoiceConversationListening() {
        asrController?.stop()
    }

    /** v1.91: 停止流式录音,等待最后结果。 */
    fun stopStreamingAsr() {
        asrController?.stop()
    }

    /** v1.91: 取消流式录音(恢复原始输入框文本)。 */
    fun cancelStreamingAsr() {
        asrController?.stop()
        accessor.update { it.copy(input = asrBaseText) }
    }

    /** v1.91: 释放 ASR Controller(会话切换/ViewModel 销毁时)。 */
    fun disposeAsr() {
        asrController?.dispose()
        asrController = null
        accessor.update { it.copy(asrState = ASRState()) }
    }

    /** Phase 9.3 (M2): 保存 ASR 配置(Settings 页编辑后调用)。 */
    fun saveAsrConfig(config: AsrConfig) {
        accessor.coroutineScope.launch {
            resultOf { settings.saveAsrConfig(config) }
        }
    }

    /**
     * Phase 9.3 (M2): 判断当前是否应走 API 录音路径(而非系统 Intent)。
     * - true: DashScope/Step/OpenAI Whisper/OpenAI Realtime/Agnes(均走流式 Controller),UI 用长按录音
     * - false: SYSTEM,UI 用点击触发 Intent
     */
    fun shouldUseApiRecording(): Boolean {
        val p = accessor.snapshot.asrConfig.provider
        return (p == AsrProviderType.DASHSCOPE ||
                p == AsrProviderType.STEP ||
                p == AsrProviderType.OPENAI_WHISPER ||
                p == AsrProviderType.OPENAI_REALTIME ||
                p == AsrProviderType.AGNES)
            && accessor.snapshot.asrConfig.isConfigured
    }
}
