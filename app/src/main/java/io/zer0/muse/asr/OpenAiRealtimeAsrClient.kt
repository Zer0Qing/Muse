package io.zer0.muse.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import io.zer0.muse.asr.AudioAmplitude.appendAmplitude
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * OpenAI Realtime 流式 ASR Controller(借鉴 rikkahub OpenAIRealtimeASRController 与
 * 本项目 [DashScopeAsrController] 架构)。
 *
 * 走 wss://api.openai.com/v1/realtime?intent=transcription,真流式 + 服务端 VAD。
 *
 * 协议(参照 OpenAI Realtime API transcription 模式):
 *  1. 建立 WebSocket(wss://api.openai.com/v1/realtime?intent=transcription)
 *     - Header: Authorization: Bearer {apiKey}
 *     - Header: OpenAI-Beta: realtime=v1
 *  2. 发送 session.update 事件,配置:
 *     - input_audio_format: pcm16
 *     - input_audio_transcription.model: whisper-1(或 gpt-4o-transcribe 等)
 *     - input_audio_transcription.prompt: 热词(可选,提升专有名词识别率)
 *     - input_audio_transcription.language: 语种(可选)
 *     - turn_detection: server_vad(ServerVadThreshold 默认 0.5,turn_detection_offset=0)
 *     - input_audio_noise_reduction_types: 噪声抑制(可选,如 ["near_field", "far_field"])
 *     - sample_rate: 16000(配置项)
 *  3. 启动 AudioRecord(VOICE_COMMUNICATION 源,PCM 16-bit mono)循环采集
 *  4. 每帧:计算 RMS 振幅 → 更新 state.amplitudes;Base64 编码 → 发 input_audio_buffer.append 事件
 *  5. 接收 conversation.item.input_audio_transcription.delta 增量拼接
 *  6. 接收 conversation.item.input_audio_transcription.completed 入 completedTranscripts
 *  7. stop():发 input_audio_buffer.commit,等服务端 VAD 自然收尾(或超时强制切 Idle)
 *  8. dispose():取消协程,关 WebSocket,释放 AudioRecord
 *
 * 状态流转:Idle → Connecting → Listening → Stopping → Idle(或 Error)
 *
 * 注:服务端 VAD 已足够智能,本地 VAD 默认不启用([AsrConfig.vadEnabled] 默认 false);
 * 若用户启用本地 VAD,则静音超过 [AsrConfig.vadSilenceDurationMs] 时主动触发 stop。
 *
 * @param config ASR 配置(必须有 apiKey)
 * @param sharedClient 可注入共享 OkHttpClient
 */
class OpenAiRealtimeAsrController(
    private val config: AsrConfig,
    sharedClient: OkHttpClient? = null,
) : ASRController {

    private val json = Json { ignoreUnknownKeys = true }
    private val ownsClient: Boolean = sharedClient == null
    private val client: OkHttpClient = sharedClient ?: OkHttpClient.Builder()
        .connectTimeout(AsrConstants.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(AsrConstants.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ASRState(isAvailable = config.apiKey.isNotBlank()))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var webSocket: WebSocket? = null
    private var session: RealtimeSession? = null
    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null
    private var onTranscriptChange: ((String) -> Unit)? = null

    // 累积的最终结果(completed 事件的文本)+ 当前中间结果(delta 文本)
    private val completedTranscripts = StringBuilder()
    @Volatile private var currentDeltaText: String = ""

    // 可选本地 VAD(用户在 config 中显式启用)
    private var vadDetector: VadDetector? = null

    override fun start(onTranscriptChange: ((String) -> Unit)?) {
        if (_state.value.status == ASRStatus.Listening) {
            Logger.w(TAG, "已在录音,忽略重复 start")
            return
        }
        if (config.apiKey.isBlank()) {
            Logger.w(TAG, "OpenAI Realtime ASR 未配置 apiKey")
            _state.update { it.copy(status = ASRStatus.Error, errorMessage = "未配置 apiKey") }
            return
        }
        this.onTranscriptChange = onTranscriptChange
        completedTranscripts.setLength(0)
        currentDeltaText = ""
        vadDetector = if (config.vadEnabled) {
            VadDetector(
                threshold = config.vadThreshold,
                silenceDurationMs = config.vadSilenceDurationMs,
            )
        } else null
        _state.update {
            it.copy(status = ASRStatus.Connecting, transcript = "", errorMessage = null, amplitudes = emptyList())
        }
        scope.launch { connectAndRecord() }
    }

    /** 建立 WebSocket、发 session.update、启动录音线程。 */
    private suspend fun connectAndRecord() {
        val streamSession = RealtimeSession(json) { event ->
            // onMessage 在 OkHttp 线程触发,派发到 Main scope 保证 handleEvent 在主线程执行
            scope.launch { handleEvent(event) }
        }
        streamSession.onError = { msg ->
            _state.update { it.copy(status = ASRStatus.Error, errorMessage = msg) }
        }
        session = streamSession

        // endpoint 优先取 config.baseUrl,空时用内置默认值
        // baseUrl 可能是 https:// 或 wss://,需要转换为 wss:// 并加 intent=transcription
        val endpoint = buildRealtimeEndpoint(config.baseUrl)
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("OpenAI-Beta", "realtime=v1")
            .build()

        val ws = client.newWebSocket(request, streamSession)
        webSocket = ws

        try {
            // 发送 session.update 配置(不等响应,Realtime API 不返回 session.created 之外的事件,
            // session.updated 可能延迟,直接进入 Listening 即可)
            val sessionUpdate = buildSessionUpdateMessage()
            if (!ws.send(sessionUpdate)) {
                Logger.w(TAG, "发送 session.update 失败")
                _state.update { it.copy(status = ASRStatus.Error, errorMessage = "发送 session.update 失败") }
                cleanupConnection()
                return
            }

            // 短暂等待 session.created(可选,5s 超时不阻塞)
            streamSession.waitForEvent("session.created", TIMEOUT_EVENT_MS)
            // 即使没收到 session.created 也进入 Listening(部分中转站不回此事件)
            _state.update { it.copy(status = ASRStatus.Listening) }
            startRecordingInternal()
        } catch (e: Exception) {
            Logger.w(TAG, "connectAndRecord 异常: ${e.message}")
            _state.update { it.copy(status = ASRStatus.Error, errorMessage = e.message ?: "连接异常") }
            cleanupConnection()
        }
    }

    /** 启动 AudioRecord 录音线程(循环采集 + base64 上传)。 */
    @SuppressLint("MissingPermission")
    private fun startRecordingInternal() {
        val sampleRate = config.sampleRate
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuf <= 0) {
            Logger.w(TAG, "AudioRecord 缓冲区大小无效: $minBuf")
            _state.update { it.copy(status = ASRStatus.Error, errorMessage = "AudioRecord 缓冲区无效") }
            return
        }
        val bufSize = minBuf * 2
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            channelConfig,
            audioFormat,
            bufSize,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Logger.w(TAG, "AudioRecord 初始化失败(可能缺少 RECORD_AUDIO 权限)")
            record.release()
            _state.update { it.copy(status = ASRStatus.Error) }
            return
        }
        audioRecord = record
        record.startRecording()

        // 录音线程:循环读 PCM → 计算振幅 → base64 编码 → 发 input_audio_buffer.append
        recordJob = scope.launch(Dispatchers.IO) {
            val chunk = ByteArray(AUDIO_CHUNK_BYTES)
            while (isActive && _state.value.status == ASRStatus.Listening) {
                val read = record.read(chunk, 0, chunk.size)
                if (read > 0) {
                    // 1. 计算 RMS 振幅 → 更新 state.amplitudes
                    val amp = AudioAmplitude.calculateRmsAmplitude(chunk, read)
                    _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(amp)) }

                    // 2. 本地 VAD(若启用):静音超阈值主动触发 stop
                    if (vadDetector?.processFrame(chunk, read, amp) == true) {
                        Logger.d(TAG, "本地 VAD 触发,主动停止")
                        scope.launch { stop() }
                        break
                    }

                    // 3. 背压检查:队列超限丢帧,避免内存堆积
                    val ws = webSocket
                    if (ws != null && ws.queueSize() < MAX_WEBSOCKET_QUEUE_BYTES) {
                        // Base64 编码 PCM(Realtime API 要求 base64)
                        val audioBase64 = android.util.Base64.encodeToString(
                            chunk.copyOf(read),
                            android.util.Base64.NO_WRAP,
                        )
                        val appendMsg = buildInputAudioBufferAppendMessage(audioBase64)
                        if (!ws.send(appendMsg)) {
                            Logger.w(TAG, "发送音频块失败")
                            break
                        }
                    } else {
                        Logger.d(TAG, "背压超限,丢帧 queueSize=${ws?.queueSize()}")
                    }
                } else if (read < 0) {
                    Logger.w(TAG, "AudioRecord.read 错误: $read")
                    break
                }
            }
        }
    }

    /** 处理 WebSocket 事件(在 Main 线程执行)。 */
    private fun handleEvent(event: JsonObject) {
        val eventType = event.optString("type")
        when (eventType) {
            "conversation.item.input_audio_transcription.delta" -> {
                // 增量文本:累加到 currentDeltaText
                val delta = event.optString("delta")
                if (delta.isNotEmpty()) {
                    currentDeltaText += delta
                    val full = buildTranscript()
                    _state.update { it.copy(transcript = full) }
                    onTranscriptChange?.invoke(full)
                }
            }
            "conversation.item.input_audio_transcription.completed" -> {
                // 句子完成:把累积的 delta(或 completed 事件的 text)入 completedTranscripts,清空 delta
                val text = event.optString("text").ifBlank { currentDeltaText }
                if (text.isNotEmpty()) {
                    completedTranscripts.append(text)
                }
                currentDeltaText = ""
                val full = buildTranscript()
                _state.update { it.copy(transcript = full) }
                onTranscriptChange?.invoke(full)
            }
            "error" -> {
                val errObj = event.optObject("error")
                val msg = errObj?.optString("message") ?: event.toString()
                Logger.w(TAG, "Realtime ASR 错误: $msg")
                _state.update { it.copy(status = ASRStatus.Error, errorMessage = msg) }
            }
            "session.created", "session.updated" -> {
                // 已在 connectAndRecord 处理或不需处理,忽略
            }
            else -> { /* 忽略未知事件(input_audio_buffer.speech_started / speech_stopped / committed 等) */ }
        }
    }

    /** 拼接 completedTranscripts + currentDeltaText,空格分隔。 */
    private fun buildTranscript(): String {
        val parts = mutableListOf<String>()
        if (completedTranscripts.isNotEmpty()) {
            val completed = completedTranscripts.toString().trim()
            if (completed.isNotEmpty()) parts.add(completed)
        }
        if (currentDeltaText.isNotBlank()) parts.add(currentDeltaText.trim())
        return parts.joinToString(" ").trim()
    }

    override fun stop() {
        if (!_state.value.isRecording) return
        _state.update { it.copy(status = ASRStatus.Stopping) }
        // 先停 AudioRecord(解除 read 阻塞),再取消录音协程,最后释放
        try { audioRecord?.stop() } catch (_: Throwable) { /* 已停止或未初始化 */ }
        recordJob?.cancel()
        audioRecord?.release()
        audioRecord = null

        // 发 input_audio_buffer.commit,触发服务端 VAD 收尾(回 transcription.completed)
        val ws = webSocket
        if (ws != null) {
            ws.send(buildInputAudioBufferCommitMessage())
        }

        // 超时保护:5 秒未收到收尾,强制切 Idle
        scope.launch {
            delay(STOP_TIMEOUT_MS)
            if (_state.value.status == ASRStatus.Stopping) {
                Logger.w(TAG, "stop() 等待收尾超时,强制切 Idle")
                val full = buildTranscript()
                _state.update { it.copy(status = ASRStatus.Idle, transcript = full) }
                cleanupConnection()
            }
        }
    }

    override fun dispose() {
        recordJob?.cancel()
        try { audioRecord?.stop() } catch (_: Throwable) { /* 已停止或未初始化 */ }
        audioRecord?.release()
        audioRecord = null
        webSocket?.close(1000, "disposed")
        webSocket = null
        session = null
        scope.cancel()
        _state.update { it.copy(status = ASRStatus.Idle, amplitudes = emptyList()) }
        if (ownsClient) {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    /** 关闭 WebSocket 并清理会话引用。 */
    private fun cleanupConnection() {
        webSocket?.close(1000, "session ended")
        webSocket = null
        session = null
    }

    /**
     * 构造 Realtime WebSocket endpoint。
     * - 输入为空:用内置 wss://api.openai.com/v1/realtime?intent=transcription
     * - 输入 wss://...:直接拼 intent=transcription
     * - 输入 https://...:替换为 wss:// 并拼 intent=transcription
     */
    private fun buildRealtimeEndpoint(baseUrl: String?): String {
        val raw = baseUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_REALTIME_ENDPOINT
        val withScheme = when {
            raw.startsWith("wss://") || raw.startsWith("ws://") -> raw
            raw.startsWith("https://") -> "wss://" + raw.removePrefix("https://")
            raw.startsWith("http://") -> "ws://" + raw.removePrefix("http://")
            else -> "wss://$raw"
        }
        // 拼接 intent=transcription(若已有 query 用 &,否则用 ?)
        return if (withScheme.contains("?")) {
            "$withScheme&intent=transcription"
        } else {
            "$withScheme?intent=transcription"
        }
    }

    /** 构造 session.update 事件 JSON(配置模型/采样率/VAD/噪声抑制/热词)。 */
    private fun buildSessionUpdateMessage(): String {
        val modelName = config.model.ifBlank { config.defaultModel() }
        val session = buildJsonObject {
            put("input_audio_format", "pcm16")
            put("input_audio_transcription", buildJsonObject {
                put("model", modelName)
                // 热词通过 prompt 传入(Realtime API 支持)
                if (config.hotwords.isNotEmpty()) {
                    val prompt = config.hotwords.joinToString(", ")
                    if (prompt.isNotBlank()) put("prompt", prompt)
                }
                config.language?.takeIf { it.isNotBlank() }?.let { put("language", it) }
            })
            // 服务端 VAD(ServerVadThreshold 默认 0.5)
            put("turn_detection", buildJsonObject {
                put("type", "server_vad")
                put("threshold", 0.5)
                put("prefix_padding_ms", 300)
                put("silence_duration_ms", 500)
            })
        }
        val msg = buildJsonObject {
            put("type", "session.update")
            put("session", session)
        }
        return json.encodeToString(JsonObject.serializer(), msg)
    }

    /** 构造 input_audio_buffer.append 事件 JSON(音频 base64 编码后发送)。 */
    private fun buildInputAudioBufferAppendMessage(audioBase64: String): String {
        val msg = buildJsonObject {
            put("type", "input_audio_buffer.append")
            put("audio", audioBase64)
        }
        return json.encodeToString(JsonObject.serializer(), msg)
    }

    /** 构造 input_audio_buffer.commit 事件 JSON(触发服务端 VAD 收尾转录)。 */
    private fun buildInputAudioBufferCommitMessage(): String {
        val msg = buildJsonObject {
            put("type", "input_audio_buffer.commit")
        }
        return json.encodeToString(JsonObject.serializer(), msg)
    }

    /** JsonObject 扩展工具(容错取值)。 */
    private fun JsonObject.optString(key: String): String =
        (this[key] as? JsonPrimitive)?.content ?: ""

    private fun JsonObject.optObject(key: String): JsonObject? =
        this[key] as? JsonObject

    companion object {
        private const val TAG = "OpenAiRealtimeAsrController"
        private const val DEFAULT_REALTIME_ENDPOINT = "wss://api.openai.com/v1/realtime"
        private const val TIMEOUT_EVENT_MS = 5_000L
        /** 音频分块大小:100ms @ 16kHz 16-bit mono = 16000 * 2 * 0.1 = 3200 bytes。 */
        private const val AUDIO_CHUNK_BYTES = 3200
        /** WebSocket 发送队列背压上限(字节),超限丢帧避免内存堆积。 */
        private const val MAX_WEBSOCKET_QUEUE_BYTES = 100_000L
        /** stop() 等待收尾的超时,超时强制切 Idle。 */
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * OpenAI Realtime WebSocket 会话,用 Channel 桥接回调到协程(持续监听模式)。
 * - onMessage(text) → 解析 JSON → 入 events Channel(供 waitForEvent 取 session.created)
 * - 同时通过 onEvent 回调持续派发所有事件(供 Controller 处理 transcription.delta / completed / error)
 * - waitForEvent(type) 从 Channel 取指定类型事件
 */
private class RealtimeSession(
    private val json: Json,
    private val onEvent: (JsonObject) -> Unit,
) : WebSocketListener() {

    private val events = kotlinx.coroutines.channels.Channel<JsonObject>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    @Volatile private var closed = false
    /** WebSocket 失败回调(由 Controller 设置,切 Error 状态)。 */
    var onError: ((String) -> Unit)? = null

    override fun onOpen(webSocket: WebSocket, response: Response) {
        Logger.d(TAG, "WebSocket 连接已建立")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        resultOf {
            val obj = json.parseToJsonElement(text) as JsonObject
            // 入 channel 供 waitForEvent 取(主要给 session.created)
            events.trySend(obj)
            // 持续派发给 Controller 处理(transcription.delta / completed / error)
            onEvent(obj)
        }.onError { message, throwable ->
            Logger.w(TAG, "解析服务端消息失败: ${throwable?.message ?: message}")
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
        // Realtime transcription 模式不返回二进制消息,忽略
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        closeChannel()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Logger.w(TAG, "WebSocket 失败: ${t.message}")
        onError?.invoke(t.message ?: "WebSocket 连接失败")
        closeChannel()
    }

    /** 挂起等待指定事件类型,timeoutMs 超时返回 null。 */
    suspend fun waitForEvent(type: String?, timeoutMs: Long): JsonObject? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            val event = withTimeoutOrNull(remaining) { events.receiveCatching().getOrNull() } ?: return null
            if (type == null) return event
            val eventType = event["type"]?.let { (it as? JsonPrimitive)?.content }
            if (eventType == type) return event
            // error 事件特殊处理
            if (eventType == "error") {
                val errMsg = (event["error"] as? JsonObject)?.let {
                    (it["message"] as? JsonPrimitive)?.content
                } ?: event.toString()
                Logger.w(TAG, "等待 $type 时收到 error: $errMsg")
                return null
            }
            Logger.d(TAG, "等待 $type 时丢弃非目标事件: $eventType")
        }
        return null
    }

    private fun closeChannel() {
        events.close()
    }

    companion object {
        private const val TAG = "RealtimeSession"
    }
}
