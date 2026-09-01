package io.zer0.muse.ui.chat

import android.content.Context
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.asr.ASRController
import io.zer0.muse.asr.ASRState
import io.zer0.muse.asr.AsrConfig
import io.zer0.muse.asr.AsrClientFactory
import io.zer0.muse.asr.AsrProviderType
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.speech.TtsManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
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
    private val context: Context,
) {

    /** v1.91: 流式 ASR Controller 实例(懒创建,Provider 切换时重建)。 */
    private var asrController: ASRController? = null
    private var asrControllerConfig: AsrConfig? = null
    private var asrStateJob: Job? = null

    /** v1.91: 录音前输入框文本快照(用于结果拼接与取消时恢复)。 */
    private var asrBaseText = ""
    private var lastAsrTranscript = ""

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
        val supportsStreaming = cfg.provider != AsrProviderType.SYSTEM &&
            cfg.provider != AsrProviderType.DASHSCOPE_FILE
        if (!supportsStreaming || cfg.apiKey.isBlank()) {
            // Provider 切回系统识别或清空 API Key 时,旧 Controller 不能继续占用麦克风/网络。
            if (asrController != null || asrStateJob != null) {
                asrStateJob?.cancel()
                asrStateJob = null
                asrController?.dispose()
                asrController = null
                asrControllerConfig = null
                accessor.update { it.copy(asrState = ASRState()) }
            }
            if (cfg.apiKey.isBlank() && supportsStreaming) {
                accessor.update {
                    it.copy(
                        asrState = ASRState(
                            status = io.zer0.muse.asr.ASRStatus.Error,
                            isAvailable = false,
                            errorMessage = context.getString(R.string.asr_missing_api_key_error),
                        ),
                    )
                }
            }
            return null
        }
        val existing = asrController
        if (existing != null && asrControllerConfig == cfg) return existing
        asrStateJob?.cancel()
        existing?.dispose()
        val controller = AsrClientFactory.createController(cfg) ?: return null
        asrController = controller
        asrControllerConfig = cfg
        asrStateJob = accessor.coroutineScope.launch {
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
        lastAsrTranscript = ""
        controller.start { transcript ->
            accessor.update { state ->
                val current = state.input
                val base = if (lastAsrTranscript.isNotEmpty() && current.endsWith(lastAsrTranscript)) {
                    current.removeSuffix(lastAsrTranscript).trimEnd()
                } else {
                    current
                }
                val spacer = if (base.isBlank() || transcript.isBlank()) "" else " "
                lastAsrTranscript = transcript
                state.copy(input = base + spacer + transcript)
            }
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
        // 取消不能走 stop():stop 会 flush 最后一段音频,其异步回调可能在用户上滑取消后
        // 又把识别文字写回输入框。直接释放当前 Controller,下一次录音按配置重建。
        asrStateJob?.cancel()
        asrStateJob = null
        asrController?.dispose()
        asrController = null
        asrControllerConfig = null
        accessor.update { state ->
            val current = state.input
            val restored = if (lastAsrTranscript.isNotEmpty() && current.endsWith(lastAsrTranscript)) {
                current.removeSuffix(lastAsrTranscript).trimEnd()
            } else {
                // 用户在录音期间继续输入时，保留当前输入，不使用过期快照覆盖。
                current
            }
            lastAsrTranscript = ""
            state.copy(input = restored.ifBlank { asrBaseText }, asrState = ASRState())
        }
    }

    /** v1.91: 释放 ASR Controller(会话切换/ViewModel 销毁时)。 */
    fun disposeAsr() {
        asrStateJob?.cancel()
        asrStateJob = null
        asrController?.dispose()
        asrController = null
        asrControllerConfig = null
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
     * - true: 任意非 SYSTEM / 非文件转录 Provider,即使缺 key 也必须走 API 路径，
     *   由 getOrCreateAsrController 把“缺少 key”明确展示给用户，而不是悄悄降级到另一套系统识别。
     * - false: SYSTEM 或 DASHSCOPE_FILE(文件转录不属于输入栏实时录音)。
     */
    fun shouldUseApiRecording(): Boolean {
        val p = accessor.snapshot.asrConfig.provider
        return p != AsrProviderType.SYSTEM && p != AsrProviderType.DASHSCOPE_FILE
    }
}
