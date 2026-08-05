package io.zer0.muse.asr

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.asr.AudioAmplitude.appendAmplitude
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 阿里云 DashScope Paraformer 流式语音识别 Controller。
 *
 * 与旧版 [AsrClient] 一次性 recognize 不同,本 Controller 实现 [ASRController] 接口,
 * 边录边传边回调,支持中间结果实时显示。
 *
 * 协议(沿用 DashScope 原生 Paraformer,不变):
 *  1. 建立 WebSocket(wss://dashscope.aliyuncs.com/api-ws/v1/inference)
 *  2. 发送 run-task,接收 task-started
 *  3. 启动 AudioRecord(VOICE_COMMUNICATION)循环采集 PCM
 *  4. 每帧:计算 RMS 振幅 → 更新 state.amplitudes;Base64/二进制发送 → WebSocket(带背压检查)
 *  5. onMessage 解析 result-generated:sentence_end=true 累积,sentence_end=false 按 sentence_id 替换中间结果
 *  6. stop():发 finish-task,等 task-finished(5s 超时强制切 Idle)
 *  7. dispose():取消协程,关 WebSocket,释放 AudioRecord
 *
 * 状态流转:Idle → Connecting → Listening → Stopping → Idle(或 Error)
 *
 * @param config ASR 配置(必须有 apiKey 和 model)
 * @param sharedClient 可注入共享 OkHttpClient(由调用方管理生命周期)
 */
class DashScopeAsrController(
    private val config: AsrConfig,
    // 可注入共享 OkHttpClient,避免每次新建独立 client
    sharedClient: OkHttpClient? = null,
) : ASRController {

    private val json = Json { ignoreUnknownKeys = true }
    // 优先复用注入的共享 client;未注入时自建(ownsClient=true,dispose 时才 shutdown)
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
    private var session: DashScopeSession? = null
    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null
    private var onTranscriptChange: ((String) -> Unit)? = null
    @Volatile private var currentTaskId: String? = null

    // 累积的最终结果(sentence_end=true 的文本)+ 当前中间结果(sentence_id → text)
    private val completedTranscripts = StringBuilder()
    private val partialTranscripts = ConcurrentHashMap<String, String>()

    // 可选本地 VAD(用户在 config 中显式启用;DashScope 服务端已自带 VAD,通常不需要本地)
    private var vadDetector: VadDetector? = null

    override fun start(onTranscriptChange: ((String) -> Unit)?) {
        // 仅在 Listening 态拒绝重复 start;Connecting/Stopping 过渡态允许新 start 接续
        if (_state.value.status == ASRStatus.Listening) {
            Logger.w(TAG, "已在录音,忽略重复 start")
            return
        }
        if (config.apiKey.isBlank()) {
            Logger.w(TAG, "DashScope ASR 未配置 apiKey")
            _state.update { it.copy(status = ASRStatus.Error, errorMessage = "未配置 apiKey") }
            return
        }
        this.onTranscriptChange = onTranscriptChange
        completedTranscripts.setLength(0)
        partialTranscripts.clear()
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

    /** 建立 WebSocket、发 run-task、等 task-started、启动录音线程。 */
    private suspend fun connectAndRecord() {
        val taskId = java.util.UUID.randomUUID().toString()
        currentTaskId = taskId

        // 创建会话:onMessage 解析后既入 channel(供 waitForEvent 取 task-started),
        // 又派发到 onEvent(后续持续监听 result-generated / task-finished / task-failed)
        val streamSession = DashScopeSession(taskId, json) { event ->
            // onMessage 在 OkHttp 线程触发,派发到 Main scope 保证 handleEvent 在主线程执行
            scope.launch { handleEvent(event) }
        }
        streamSession.onError = { msg ->
            _state.update { it.copy(status = ASRStatus.Error, errorMessage = msg) }
        }
        session = streamSession

        // L-ASR3: 端点优先取 config.asrEndpoint,空时回退到内置默认值
        val endpoint = config.asrEndpoint.ifBlank { ENDPOINT }
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("X-DashScope-DataInspection", "enable")
            .build()

        val ws = client.newWebSocket(request, streamSession)
        webSocket = ws

        try {
            // H-ASR1: 直接发送 run-task,再等 task-started
            val runTask = buildRunTaskMessage(taskId, config.sampleRate)
            if (!ws.send(runTask)) {
                Logger.w(TAG, "发送 run-task 失败")
                _state.update { it.copy(status = ASRStatus.Error, errorMessage = "发送 run-task 失败") }
                cleanupConnection()
                return
            }

            // 等 task-started(run-task 后服务端确认)
            val started = streamSession.waitForEvent("task-started", TIMEOUT_EVENT_MS)
            if (started == null) {
                Logger.w(TAG, "run-task 后未收到 task-started")
                _state.update { it.copy(status = ASRStatus.Error, errorMessage = "未收到 task-started") }
                cleanupConnection()
                return
            }

            // 已收到 task-started,切 Listening 并启动录音
            _state.update { it.copy(status = ASRStatus.Listening) }
            startRecordingInternal()
        } catch (e: Exception) {
            Logger.w(TAG, "connectAndRecord 异常: ${e.message}")
            _state.update { it.copy(status = ASRStatus.Error, errorMessage = e.message ?: "连接异常") }
            cleanupConnection()
        }
    }

    /** 启动 AudioRecord 录音线程(循环采集 + 上传)。 */
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
            // v1.98: 移除 errorMessage 弹窗提示,静默处理(仅记日志)
            _state.update { it.copy(status = ASRStatus.Error) }
            return
        }
        audioRecord = record
        record.startRecording()

        // 录音线程:循环读 PCM → 计算振幅 → 发 WebSocket(带背压检查)
        recordJob = scope.launch(Dispatchers.IO) {
            val chunk = ByteArray(AUDIO_CHUNK_BYTES)
            while (isActive && _state.value.status == ASRStatus.Listening) {
                val read = record.read(chunk, 0, chunk.size)
                if (read > 0) {
                    // 1. 计算 RMS 振幅 → 更新 state.amplitudes(归一化 0-1f)
                    val amp = AudioAmplitude.calculateRmsAmplitude(chunk, read)
                    _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(amp)) }

                    // 2. 本地 VAD(若启用):静音超阈值主动触发 stop
                    //    DashScope 服务端已自带 VAD(sentence_end 自动断句),本地 VAD 仅用于自动停止录音
                    if (vadDetector?.processFrame(chunk, read, amp) == true) {
                        Logger.d(TAG, "本地 VAD 触发,主动停止")
                        scope.launch { stop() }
                        break
                    }

                    // 3. 背压检查:队列超限丢帧,避免内存堆积
                    val ws = webSocket
                    if (ws != null && ws.queueSize() < MAX_QUEUE_BYTES) {
                        // 用 okio.Buffer 构造 ByteString(二进制 PCM 直传,DashScope 协议支持)
                        val byteString = okio.Buffer().apply { write(chunk, 0, read) }.readByteString()
                        if (!ws.send(byteString)) {
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
        val eventType = event.optString("event")
        when (eventType) {
            "result-generated" -> {
                val sentence = event.optObject("payload")?.optObject("output")?.optObject("sentence")
                val text = sentence?.optString("text") ?: ""
                val isFinal = sentence?.optBool("sentence_end") ?: false
                val sentenceId = sentence?.optString("sentence_id") ?: "_partial"
                if (text.isNotEmpty()) {
                    if (isFinal) {
                        // 句子结束:累积到 completedTranscripts,清除对应的中间结果
                        completedTranscripts.append(text)
                        partialTranscripts.remove(sentenceId)
                    } else {
                        // 中间结果:按 sentence_id 替换(同一句的中间结果会反复更新)
                        partialTranscripts[sentenceId] = text
                    }
                    // 拼接 completed + partial,回调主线程
                    val full = buildTranscript()
                    _state.update { it.copy(transcript = full) }
                    onTranscriptChange?.invoke(full)
                }
            }
            "task-finished" -> {
                Logger.d(TAG, "收到 task-finished,识别完成")
                val full = buildTranscript()
                _state.update { it.copy(status = ASRStatus.Idle, transcript = full) }
                onTranscriptChange?.invoke(full)
                cleanupConnection()
            }
            "task-failed" -> {
                val errMsg = (event["header"] as? JsonObject)?.let {
                    (it["error_message"] as? JsonPrimitive)?.content
                } ?: event.toString()
                Logger.w(TAG, "DashScope ASR 任务失败: $errMsg")
                _state.update { it.copy(status = ASRStatus.Error, errorMessage = errMsg) }
            }
            "task-started" -> {
                // 已在 connectAndRecord 的 waitForEvent 处理,此处忽略
            }
            else -> { /* 忽略未知事件 */ }
        }
    }

    /** 拼接 completedTranscripts + partialTranscripts.values,空格分隔。 */
    private fun buildTranscript(): String {
        val parts = mutableListOf<String>()
        if (completedTranscripts.isNotEmpty()) {
            val completed = completedTranscripts.toString().trim()
            if (completed.isNotEmpty()) parts.add(completed)
        }
        partialTranscripts.values.forEach { if (it.isNotBlank()) parts.add(it) }
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

        // 发 finish-task,等 task-finished(由 handleEvent 处理切回 Idle)
        val ws = webSocket
        val tid = currentTaskId
        if (ws != null && tid != null) {
            ws.send(buildFinishTaskMessage(tid))
        }

        // 超时保护:5 秒未收到 task-finished,强制切 Idle
        scope.launch {
            delay(STOP_TIMEOUT_MS)
            if (_state.value.status == ASRStatus.Stopping) {
                Logger.w(TAG, "stop() 等待 task-finished 超时,强制切 Idle")
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
        // 仅当自建 client 时才 shutdown(共享 client 由调用方管理生命周期)
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
     * 构造 run-task 指令 JSON。
     *
     * 支持的模型(config.model):
     *  - paraformer-realtime-v2(默认,实时流式 Paraformer v2)
     *  - paraformer-realtime-v3(v3,识别准确度提升,支持方言增强)
     *  - sensevoice-v1(SenseVoice,支持情绪识别 + ASR,多语种强)
     *
     * 热词参数:DashScope Paraformer 支持通过 `vocabulary_id`(预创建词表 ID)传入热词,
     * 当前 [AsrConfig.hotwords] 是 inline 列表,通过 `hotwords` 参数传入(DashScope 部分
     * 模型版本支持 inline 热词 map,格式 {word: weight});如服务端不接受 inline 形式,
     * 用户应改用 DashScope 控制台预创建词表并填入 [AsrConfig.hotwords] 第一个元素
     * 作为 vocabulary_id(此处会自动检测:单元素且不以非字母数字结尾时视为 vocabulary_id)。
     */
    private fun buildRunTaskMessage(taskId: String, sampleRate: Int): String {
        val parameters = buildJsonObject {
            put("format", "pcm")
            put("sample_rate", sampleRate)
            put("punctuation_prediction_enabled", config.enablePunctuation)
            put("inverse_text_normalization_enabled", config.enableInverseTextNormalization)
            config.language?.let { put("language_hints", JsonArray(listOf(JsonPrimitive(it)))) }
            // 热词:inline 列表转为 hotwords map(word -> 默认权重 1)
            if (config.hotwords.isNotEmpty()) {
                val hotwordsMap = buildJsonObject {
                    config.hotwords.forEach { word ->
                        if (word.isNotBlank()) put(word.trim(), JsonPrimitive(1))
                    }
                }
                put("hotwords", hotwordsMap)
            }
        }
        val msg = buildJsonObject {
            put("header", buildJsonObject {
                put("action", "run-task")
                put("task_id", taskId)
                put("streaming", "duplex")
            })
            put("payload", buildJsonObject {
                put("task_group", "audio")
                put("task", "asr")
                put("function", "recognition")
                put("model", config.model)
                put("input", buildJsonObject {})
                put("parameters", parameters)
            })
        }
        return json.encodeToString(JsonObject.serializer(), msg)
    }

    /** 构造 finish-task 指令 JSON。 */
    private fun buildFinishTaskMessage(taskId: String): String {
        val msg = buildJsonObject {
            put("header", buildJsonObject {
                put("action", "finish-task")
                put("task_id", taskId)
                put("streaming", "duplex")
            })
            put("payload", buildJsonObject {
                put("input", buildJsonObject {})
            })
        }
        return json.encodeToString(JsonObject.serializer(), msg)
    }

    /** JsonObject 扩展工具(容错取值)。 */
    private fun JsonObject.optString(key: String): String =
        (this[key] as? JsonPrimitive)?.content ?: ""

    private fun JsonObject.optInt(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun JsonObject.optBool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    private fun JsonObject.optObject(key: String): JsonObject? =
        this[key] as? JsonObject

    companion object {
        private const val TAG = "DashScopeAsrController"
        private const val ENDPOINT = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
        private const val TIMEOUT_EVENT_MS = 15_000L
        /** 音频分块大小:100ms @ 16kHz 16-bit mono = 16000 * 2 * 0.1 = 3200 bytes。 */
        private const val AUDIO_CHUNK_BYTES = 3200
        /** WebSocket 发送队列背压上限(字节),超限丢帧避免内存堆积。 */
        private const val MAX_QUEUE_BYTES = 100_000L
        /** stop() 等待 task-finished 的超时,超时强制切 Idle。 */
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * DashScope WebSocket 会话,用 Channel 桥接回调到协程(持续监听模式)。
 * - onMessage(text) → 解析 JSON → 入 events Channel(供 waitForEvent 取 task-started)
 * - 同时通过 onEvent 回调持续派发所有事件(供 Controller 处理 result-generated / task-finished)
 * - waitForEvent(type) 从 Channel 取指定类型事件,null 类型取任意
 */
private class DashScopeSession(
    private val taskId: String,
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
        // L-ASR1: 用 resultOf 替代 runCatching,避免吞 CancellationException
        resultOf {
            val obj = json.parseToJsonElement(text) as JsonObject
            val header = obj["header"] as? JsonObject ?: return@resultOf
            // 合并 header.event 到顶层便于取值
            val merged = JsonObject(obj + ("event" to (header["event"] ?: JsonPrimitive(""))))
            // 入 channel 供 waitForEvent 取(主要给 task-started)
            events.trySend(merged)
            // 持续派发给 Controller 处理(result-generated / task-finished / task-failed)
            onEvent(merged)
        }.onError { message, throwable ->
            Logger.w(TAG, "解析服务端消息失败: ${throwable?.message ?: message}")
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
        // DashScope ASR 不返回二进制消息,忽略
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

    /** 挂起等待指定事件类型,timeoutMs 超时返回 null。type=null 取任意事件。 */
    suspend fun waitForEvent(type: String?, timeoutMs: Long): JsonObject? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            val event = withTimeoutOrNull(remaining) { events.receiveCatching().getOrNull() } ?: return null
            if (type == null) return event
            val eventType = event["event"]?.let { (it as? JsonPrimitive)?.content }
            if (eventType == type) return event
            // L-ASR2: 对 task-failed 等错误事件特殊处理,其他非目标事件至少 Logger.d
            if (eventType == "task-failed") {
                val errMsg = (event["header"] as? JsonObject)?.let {
                    (it["error_message"] as? JsonPrimitive)?.content
                } ?: event.toString()
                Logger.w(TAG, "等待 $type 时收到 task-failed: $errMsg")
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
        private const val TAG = "DashScopeSession"
    }
}
