package io.zer0.muse.asr

import android.content.Context
import io.zer0.muse.R
import io.zer0.muse.asr.local.LocalModelStoreBridge
import io.zer0.muse.ui.speech.SpeechInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 与具体对话页面解耦的流式 ASR 会话协调器（单聊与 Agent 共用）。
 *
 * 统一托管本地离线引擎（SenseVoice）与云端流式识别（DashScope / Step / Whisper / Realtime / Agnes）：
 * - 按当前 [AsrConfig] 懒创建并在配置变化时重建 [ASRController]；
 * - 维护单调递增的 [asrGeneration] 令牌，阻止已释放/已取消控制器的迟到回调污染输入框；
 * - 松手自动发送时等待 `Stopping → Idle/Error`（上限 20s，覆盖本地 ONNX 首次加载与推理耗时）。
 */
class StreamingAsrCoordinator(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val configProvider: () -> AsrConfig,
    private val inputProvider: () -> String,
    private val updateInput: ((String) -> String) -> Unit,
    private val onStateChange: (ASRState) -> Unit = {},
    private val controllerFactory: (AsrConfig, Context) -> ASRController? = { cfg, ctx ->
        AsrClientFactory.createController(cfg, ctx)
    },
    private val localModelReadyProvider: () -> Boolean = { LocalModelStoreBridge.isReady() },
    private val systemRecognizerAvailableProvider: () -> Boolean = { SpeechInput.isAvailable(context) },
) {
    private val _state = MutableStateFlow(ASRState())
    val state: StateFlow<ASRState> = _state.asStateFlow()

    private var asrController: ASRController? = null
    private var asrControllerConfig: AsrConfig? = null
    private var asrStateJob: Job? = null
    private var asrGeneration: Long = 0L
    private var asrBaseText = ""
    private var lastAsrTranscript = ""

    private fun publishState(newState: ASRState) {
        _state.value = newState
        onStateChange(newState)
    }

    /**
     * 判断当前是否应走应用内流式/本地录音路径（而非系统 RecognizerIntent）。
     *
     * 包含本地 SenseVoice 与全部云端流式识别引擎；仅 `SYSTEM` 与离线文件转录 `DASHSCOPE_FILE` 返回 false。
     */
    fun shouldUseApiRecording(): Boolean {
        val p = configProvider().provider
        return p != AsrProviderType.SYSTEM && p != AsrProviderType.DASHSCOPE_FILE
    }

    private fun getOrCreateController(): ASRController? {
        val cfg = configProvider()
        val supportsStreaming = cfg.provider != AsrProviderType.SYSTEM &&
            cfg.provider != AsrProviderType.DASHSCOPE_FILE
        val configured = isAsrInputAvailable(
            provider = cfg.provider,
            apiKey = cfg.apiKey,
            localModelReady = localModelReadyProvider(),
            systemRecognizerAvailable = systemRecognizerAvailableProvider(),
        )
        if (!supportsStreaming || !configured) {
            if (asrController != null || asrStateJob != null) {
                asrGeneration++
                asrStateJob?.cancel()
                asrStateJob = null
                asrController?.dispose()
                asrController = null
                asrControllerConfig = null
                publishState(ASRState())
            }
            if (!configured && supportsStreaming) {
                val message = if (cfg.provider == AsrProviderType.LOCAL_SENSEVOICE) {
                    context.getString(R.string.asr_local_error_model_missing)
                } else {
                    context.getString(R.string.asr_missing_api_key_error)
                }
                publishState(
                    ASRState(
                        status = ASRStatus.Error,
                        isAvailable = false,
                        errorMessage = message,
                    ),
                )
            }
            return null
        }
        val existing = asrController
        if (existing != null && asrControllerConfig == cfg) return existing
        asrGeneration++
        asrStateJob?.cancel()
        existing?.dispose()
        val controller = controllerFactory(cfg, context) ?: return null
        asrController = controller
        asrControllerConfig = cfg
        asrStateJob = coroutineScope.launch {
            controller.state.collect { controllerState ->
                if (controller === asrController) {
                    publishState(controllerState)
                }
            }
        }
        return controller
    }

    /** 开始流式/本地录音，将实时识别文本增量拼接进输入框。 */
    fun startStreamingAsr(): Boolean {
        val controller = getOrCreateController() ?: return false
        if (controller.state.value.isRecording) return false
        asrBaseText = inputProvider()
        lastAsrTranscript = ""
        val generation = ++asrGeneration
        controller.start { transcript ->
            if (generation != asrGeneration || controller !== asrController) return@start
            updateInput { current ->
                val base = if (lastAsrTranscript.isNotEmpty() && current.endsWith(lastAsrTranscript)) {
                    current.removeSuffix(lastAsrTranscript).trimEnd()
                } else {
                    current
                }
                val spacer = if (base.isBlank() || transcript.isBlank()) "" else " "
                lastAsrTranscript = transcript
                base + spacer + transcript
            }
        }
        return true
    }

    /** 语音对话模式专用：不写输入框，识别文本直接通过回调交给调用方。 */
    fun startVoiceConversationListening(onTranscript: (String) -> Unit) {
        val controller = getOrCreateController() ?: return
        if (controller.state.value.isRecording) return
        lastAsrTranscript = ""
        val generation = ++asrGeneration
        controller.start { transcript ->
            if (generation != asrGeneration || controller !== asrController) return@start
            onTranscript(transcript)
        }
    }

    fun stopVoiceConversationListening() {
        asrController?.stop()
    }

    fun cancelVoiceConversationListening() {
        dispose()
    }

    /** 停止录音，待最终文本落定（含本地 ONNX 推理完成）后触发 [onAutoSend]。 */
    fun stopStreamingAsrAndSend(onAutoSend: () -> Unit) {
        val controller = asrController ?: return
        if (!controller.state.value.isRecording) return
        val generation = asrGeneration
        controller.stop()
        coroutineScope.launch {
            withTimeoutOrNull(AUTO_SEND_WAIT_MS) {
                controller.state.first {
                    it.status == ASRStatus.Idle || it.status == ASRStatus.Error
                }
            }
            if (generation != asrGeneration) return@launch
            if (controller.state.value.status == ASRStatus.Error) return@launch
            onAutoSend()
        }
    }

    fun stopStreamingAsr() {
        asrController?.stop()
    }

    /** 取消录音并恢复录音前的输入框文本，立即释放控制器防止迟到回调回写。 */
    fun cancelStreamingAsr() {
        asrGeneration++
        asrStateJob?.cancel()
        asrStateJob = null
        asrController?.dispose()
        asrController = null
        asrControllerConfig = null
        updateInput { current ->
            val restored = if (lastAsrTranscript.isNotEmpty() && current.endsWith(lastAsrTranscript)) {
                current.removeSuffix(lastAsrTranscript).trimEnd()
            } else {
                current
            }
            lastAsrTranscript = ""
            restored.ifBlank { asrBaseText }
        }
        publishState(ASRState())
    }

    /** 页面退出、退后台或 ViewModel 销毁时释放麦克风与识别引擎。 */
    fun dispose() {
        asrGeneration++
        asrStateJob?.cancel()
        asrStateJob = null
        asrController?.dispose()
        asrController = null
        asrControllerConfig = null
        lastAsrTranscript = ""
        publishState(ASRState())
    }

    companion object {
        /** 松手后等待识别结论的上限，覆盖本地模型首次加载与推理耗时。 */
        const val AUTO_SEND_WAIT_MS = 20_000L
    }
}
