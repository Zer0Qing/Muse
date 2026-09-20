package io.zer0.muse.asr

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioRecord
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
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
import java.util.concurrent.atomic.AtomicBoolean

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
 * @param appContext 应用 Context(用于把用户可见错误文案解析为本地化字符串)
 */
class DashScopeAsrController(
    private val config: AsrConfig,
    // 可注入共享 OkHttpClient,避免每次新建独立 client
    sharedClient: OkHttpClient? = null,
    // CONS-05: 注入 Application Context,用于把用户可见错误文案解析为本地化字符串
    private val appContext: Context,
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

    // ── Phase 3: 断线重连相关字段(对齐 OpenAiRealtimeAsrController 的既有实现) ──
    /** 已触发的重连次数(重连成功后清零)。 */
    private var reconnectAttempt = 0
    /** 重连期间缓冲的 PCM 音频帧(按入队顺序补发)。 */
    private val audioBuffer = ArrayDeque<ByteArray>()
    /** [audioBuffer] 锁:录音线程入队与重连补发互斥。 */
    private val audioBufferLock = Any()
    /** [audioBuffer] 当前累计字节数,用于上限判断。 */
    @Volatile private var audioBufferBytes = 0
    /** 当前调度的退避重连协程;stop/dispose/start 时取消。 */
    private var reconnectJob: Job? = null
    /** dispose 标志,防止 dispose 后仍在重连。 */
    private val isDisposed = AtomicBoolean(false)

    override fun start(onTranscriptChange: ((String) -> Unit)?) {
        // 仅在 Listening 态拒绝重复 start;Connecting/Stopping 过渡态允许新 start 接续
        if (_state.value.status == ASRStatus.Listening) {
            Logger.w(TAG, "已在录音,忽略重复 start")
            return
        }
        if (config.apiKey.isBlank()) {
            Logger.w(TAG, "DashScope ASR 未配置 apiKey")
            _state.update { it.copy(status = ASRStatus.Error, errorMessage = appContext.getString(R.string.asr_error_no_apikey)) } // CONS-05: 硬编码中文迁移到字符串资源
            return
        }
        // Phase 3: 重连中重新 start → 取消重连并释放录音资源,走全新连接(避免两套录音叠加)
        if (_state.value.status == ASRStatus.Reconnecting) {
            cancelReconnect()
            releaseAudioRecord()
        }
        this.onTranscriptChange = onTranscriptChange
        completedTranscripts.setLength(0)
        partialTranscripts.clear()
        reconnectAttempt = 0
        clearAudioBuffer()
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

    /** 建立 WebSocket、发 run-task、等 task-started、启动录音线程(首次连接路径)。 */
    private suspend fun connectAndRecord() {
        if (establishConnection(isInitial = true)) {
            startRecordingInternal()
        }
    }

    /**
     * Phase 3: 建立一次 WebSocket 会话(发 run-task + 等 task-started)。
     *
     * @param isInitial true=首次连接(失败切 Error,不重连);false=断线重连
     *   (失败由 [attemptReconnect] 继续退避)
     * @return true=已收到 task-started,连接可用
     */
    private suspend fun establishConnection(isInitial: Boolean): Boolean {
        val taskId = java.util.UUID.randomUUID().toString()
        currentTaskId = taskId

        // 创建会话:onMessage 解析后既入 channel(供 waitForEvent 取 task-started),
        // 又派发到 onEvent(后续持续监听 result-generated / task-finished / task-failed)
        val streamSession = DashScopeSession(taskId, json) { event ->
            // onMessage 在 OkHttp 线程触发,派发到 Main scope 保证 handleEvent 在主线程执行
            scope.launch { handleEvent(event) }
        }
        // Phase 3: 断线回调 — 同样派发到 Main scope,与状态更新串行
        streamSession.onDisconnected = { reason ->
            scope.launch { onWebSocketDisconnected(reason) }
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

        return try {
            // H-ASR1: 直接发送 run-task,再等 task-started
            val runTask = buildRunTaskMessage(taskId, config.sampleRate)
            if (!ws.send(runTask)) {
                Logger.w(TAG, "发送 run-task 失败")
                cleanupConnection()
                if (isInitial) {
                    _state.update { it.copy(status = ASRStatus.Error, errorMessage = appContext.getString(R.string.asr_error_send_run_task_failed)) } // CONS-05: 硬编码中文迁移到字符串资源
                }
                return false
            }

            // 等 task-started(run-task 后服务端确认)
            val started = streamSession.waitForEvent("task-started", TIMEOUT_EVENT_MS)
            if (started == null) {
                Logger.w(TAG, "run-task 后未收到 task-started")
                cleanupConnection()
                if (isInitial) {
                    _state.update { it.copy(status = ASRStatus.Error, errorMessage = appContext.getString(R.string.asr_error_no_task_started)) } // CONS-05: 硬编码中文迁移到字符串资源
                }
                return false
            }

            if (isInitial) {
                // P2-13: 握手期间用户可能已 stop/dispose — 状态不再处于 Connecting 时放弃启动录音,
                // 否则会把用户的停止意图覆盖成 Listening 并继续录音
                if (_state.value.status != ASRStatus.Connecting) {
                    Logger.d(TAG, "握手完成但状态已变化(${_state.value.status}),放弃启动录音")
                    cleanupConnection()
                    return false
                }
                // 已收到 task-started,切 Listening 并启动录音
                _state.update { it.copy(status = ASRStatus.Listening) }
            } else {
                if (_state.value.status != ASRStatus.Connecting) {
                    Logger.d(TAG, "重连完成但状态已变化(${_state.value.status}),放弃恢复")
                    return false
                }
                // 重连成功:重置退避计数,状态切回 Listening,补发缓冲音频
                onReconnected()
            }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "establishConnection 异常: ${e.message}")
            cleanupConnection()
            if (isInitial) {
                _state.update { it.copy(status = ASRStatus.Error, errorMessage = e.message ?: appContext.getString(R.string.asr_error_connection_exception)) } // CONS-05: 硬编码中文迁移到字符串资源
            }
            false
        }
    }

    /** 启动 AudioRecord 录音线程(循环采集 + 上传)。 */
    @SuppressLint("MissingPermission")
    private fun startRecordingInternal() {
        val capture = AsrAudioCapture.create(config.sampleRate, TAG)
        if (capture == null) {
            _state.update { it.copy(status = ASRStatus.Error, errorMessage = appContext.getString(R.string.asr_error_mic_init_failed)) } // CONS-05: 硬编码中文迁移到字符串资源
            return
        }
        val record = capture.recorder
        audioRecord = record
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            Logger.w(TAG, "AudioRecord 启动失败: ${e.message}", e)
            record.release()
            audioRecord = null
            _state.update { it.copy(status = ASRStatus.Error, errorMessage = appContext.getString(R.string.asr_error_recording_start_failed)) } // CONS-05: 硬编码中文迁移到字符串资源
            return
        }

        // 录音线程:循环读 PCM → 计算振幅 → 发 WebSocket(带背压检查)
        // Phase 3: Reconnecting 期间继续录音,音频帧先入缓冲,重连成功后补发
        recordJob = scope.launch(Dispatchers.IO) {
            val chunk = ByteArray(AUDIO_CHUNK_BYTES)
            while (isActive && _state.value.isRecording) {
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

                    // 3. Listening 期间正常发送;其余过渡态(Reconnecting / 重连建立中)先缓冲,
                    //    避免在 run-task/task-started 握手完成前把 PCM 发到新 WebSocket
                    if (_state.value.status == ASRStatus.Listening) {
                        sendAudioFrame(chunk, read)
                    } else {
                        bufferAudioFrame(chunk, read)
                    }
                } else if (read == 0) {
                    delay(10L)
                } else if (read < 0) {
                    Logger.w(TAG, "AudioRecord.read 错误: $read")
                    break
                }
            }
        }
    }

    /** Phase 3: Listening 状态下发送一帧 PCM(背压超限丢帧,避免内存堆积)。 */
    private fun sendAudioFrame(chunk: ByteArray, read: Int) {
        val ws = webSocket
        if (ws != null && ws.queueSize() < MAX_QUEUE_BYTES) {
            // 用 okio.Buffer 构造 ByteString(二进制 PCM 直传,DashScope 协议支持)
            val byteString = okio.Buffer().apply { write(chunk, 0, read) }.readByteString()
            if (!ws.send(byteString)) {
                Logger.w(TAG, "发送音频块失败,触发重连")
                scope.launch { onWebSocketDisconnected("发送音频块失败") }
            }
        } else {
            Logger.d(TAG, "背压超限,丢帧 queueSize=${ws?.queueSize()}")
        }
    }

    /** Phase 3: Reconnecting 状态下把一帧 PCM 入队缓冲(超上限丢帧避免 OOM)。 */
    private fun bufferAudioFrame(chunk: ByteArray, read: Int) {
        val frame = chunk.copyOf(read)
        synchronized(audioBufferLock) {
            if (audioBufferBytes + frame.size <= MAX_AUDIO_BUFFER_BYTES) {
                audioBuffer.addLast(frame)
                audioBufferBytes += frame.size
            } else {
                Logger.w(TAG, "重连期间音频缓冲已满(${audioBufferBytes} 字节),丢帧")
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

    // ── Phase 3: 断线重连(指数退避,对齐 OpenAiRealtimeAsrController 的实现) ──

    /**
     * WebSocket 断开回调(由 [DashScopeSession.onDisconnected] 派发到 Main scope)。
     *
     * 决策见 [resolveAsrDisconnectAction]:
     *  - dispose / 终态 / 用户停止中 / 首次连接中 → 忽略;
     *  - Listening(或重连中) → 指数退避重连;
     *  - 重试耗尽 → 切 Error 并释放录音资源,附用户可见降级文案。
     */
    private fun onWebSocketDisconnected(reason: String) {
        when (
            resolveAsrDisconnectAction(
                status = _state.value.status,
                isDisposed = isDisposed.get(),
                reconnectAttempt = reconnectAttempt,
            )
        ) {
            AsrDisconnectAction.IGNORE -> {
                Logger.d(TAG, "忽略 WebSocket 断开(状态=${_state.value.status}): $reason")
            }
            AsrDisconnectAction.GIVE_UP -> {
                giveUpReconnect(reason)
            }
            AsrDisconnectAction.RECONNECT -> {
                Logger.w(TAG, "WebSocket 断开,触发重连: $reason (attempt=$reconnectAttempt)")
                scheduleReconnect(reason)
            }
        }
    }

    /**
     * 调度下一次指数退避重连:500ms → 1s → 2s([DashScopeReconnectPolicy])。
     *
     * 退避期间状态为 [ASRStatus.Reconnecting],录音线程继续采集并缓冲音频帧。
     */
    private fun scheduleReconnect(reason: String) {
        if (isDisposed.get()) return
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            giveUpReconnect(reason)
            return
        }
        // 已有重连任务在调度/执行(如 onFailure + onClosing 双回调、连续发送失败)时忽略重复调度
        if (reconnectJob?.isActive == true) {
            Logger.d(TAG, "重连已在进行中,忽略重复调度: $reason")
            return
        }
        val attemptIndex = reconnectAttempt
        val attemptNumber = attemptIndex + 1
        val delayMs = DashScopeReconnectPolicy.delayForAttempt(attemptIndex)
        Logger.w(TAG, "调度第 $attemptNumber/$MAX_RECONNECT_ATTEMPTS 次重连,${delayMs}ms 后执行($reason)")
        _state.update {
            it.copy(
                status = ASRStatus.Reconnecting,
                errorMessage = appContext.getString(R.string.asr_reconnecting_message, attemptNumber, MAX_RECONNECT_ATTEMPTS), // CONS-05: 硬编码中文迁移到字符串资源
            )
        }
        reconnectJob = scope.launch {
            awaitReconnectBackoff(attemptIndex)
            attemptReconnect()
        }
        reconnectAttempt++
    }

    /** 执行一次重连尝试(仅当仍处于 Reconnecting 时;失败继续退避,耗尽则降级)。 */
    private suspend fun attemptReconnect() {
        if (isDisposed.get()) return
        if (_state.value.status != ASRStatus.Reconnecting) {
            Logger.d(TAG, "退避期间状态变化(${_state.value.status}),取消重连")
            return
        }
        // 临时切 Connecting 表示正在建立连接(退避已完成);该状态下断开由 establishConnection 自行处理
        _state.update { it.copy(status = ASRStatus.Connecting) }
        if (!establishConnection(isInitial = false)) {
            Logger.w(TAG, "重连失败(attempt=$reconnectAttempt),继续退避")
            scheduleReconnect("重连尝试失败")
        }
    }

    /** 重连成功:重置退避计数,状态切回 Listening 并补发最近一段缓冲音频。 */
    private fun onReconnected() {
        // 竞态保护:重连握手期间用户可能已 stop/dispose,状态不再是 Connecting 时放弃恢复
        if (_state.value.status != ASRStatus.Connecting) {
            Logger.d(TAG, "重连成功但状态已变化(${_state.value.status}),放弃恢复")
            cleanupConnection()
            clearAudioBuffer()
            return
        }
        Logger.i(TAG, "WebSocket 重连成功,补发缓冲音频(${audioBuffer.size} 帧,${audioBufferBytes} 字节)")
        reconnectAttempt = 0
        _state.update { it.copy(status = ASRStatus.Listening, errorMessage = null) }
        flushAudioBuffer()
    }

    /**
     * 重连耗尽:切 Error 状态(附用户可见降级文案),关闭连接并释放麦克风。
     *
     * 已识别的文本保留在 state.transcript 中,用户可复制后再重试。
     */
    private fun giveUpReconnect(reason: String) {
        Logger.w(TAG, "重连 $MAX_RECONNECT_ATTEMPTS 次仍未恢复,降级为 Error: $reason")
        _state.update {
            it.copy(status = ASRStatus.Error, errorMessage = appContext.getString(R.string.asr_reconnect_exhausted)) // CONS-05: 硬编码中文迁移到字符串资源
        }
        cleanupConnection()
        releaseAudioRecord()
    }

    /**
     * 重连成功后补发最近一小段缓冲 PCM 并清空缓冲。
     *
     * 对齐 OpenAiRealtimeAsrController.B-28:只补发最近 [MAX_REPLAY_BYTES](≈2 秒),
     * 更早的帧直接丢弃,避免断线前的整段音频被新会话当作新语音重复识别。
     */
    private fun flushAudioBuffer() {
        val ws = webSocket ?: run {
            Logger.w(TAG, "flushAudioBuffer: webSocket 为空,丢弃缓冲")
            clearAudioBuffer()
            return
        }
        synchronized(audioBufferLock) {
            while (audioBufferBytes > MAX_REPLAY_BYTES && audioBuffer.isNotEmpty()) {
                audioBufferBytes -= audioBuffer.removeFirst().size
            }
            while (audioBuffer.isNotEmpty()) {
                val chunk = audioBuffer.removeFirst()
                audioBufferBytes -= chunk.size
                val byteString = okio.Buffer().apply { write(chunk) }.readByteString()
                if (!ws.send(byteString)) {
                    Logger.w(TAG, "补发音频帧失败,剩余 ${audioBuffer.size} 帧丢弃")
                    break
                }
            }
            audioBuffer.clear()
            audioBufferBytes = 0
        }
    }

    /** 清空重连音频缓冲(start/stop/dispose 时调用)。 */
    private fun clearAudioBuffer() {
        synchronized(audioBufferLock) {
            audioBuffer.clear()
            audioBufferBytes = 0
        }
    }

    /** 取消正在调度/执行的重连(不修改状态),用户主动停止时调用。 */
    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    /** 释放 AudioRecord 硬件资源(取消录音协程 + stop + release)。 */
    private fun releaseAudioRecord() {
        recordJob?.cancel()
        recordJob = null
        try { audioRecord?.stop() } catch (_: Throwable) { /* 已停止或未初始化 */ }
        audioRecord?.release()
        audioRecord = null
    }

    override fun stop() {
        if (!_state.value.isRecording) return
        // Phase 3: 用户主动停止,取消进行中的重连并清空重连缓冲
        cancelReconnect()
        clearAudioBuffer()
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
        isDisposed.set(true)
        // Phase 3: 取消重连并清空缓冲,防止 dispose 后仍重连
        cancelReconnect()
        clearAudioBuffer()
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
                put("model", config.model.ifBlank { config.defaultModel() })
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
        /** Phase 3: 最大重连尝试次数(与 Step/Whisper 的 AsrConstants.RECONNECT_MAX_ATTEMPTS 对齐)。 */
        private const val MAX_RECONNECT_ATTEMPTS = DashScopeReconnectPolicy.MAX_ATTEMPTS
        /** Phase 3: 重连期间 PCM 缓冲上限(5MB,对齐 OpenAiRealtimeAsrController,避免 OOM)。 */
        private const val MAX_AUDIO_BUFFER_BYTES = 5 * 1024 * 1024
        /**
         * Phase 3: 重连成功后最多补发的音频字节数。
         * 2 秒 @16kHz/16bit/mono ≈ 64KB(与 OpenAiRealtimeAsrController.MAX_REPLAY_BYTES 一致)。
         */
        private const val MAX_REPLAY_BYTES = 2 * 16_000 * 2
    }
}

/**
 * DashScope WebSocket 会话,用 Channel 桥接回调到协程(持续监听模式)。
 * - onMessage(text) → 解析 JSON → 入 events Channel(供 waitForEvent 取 task-started)
 * - 同时通过 onEvent 回调持续派发所有事件(供 Controller 处理 result-generated / task-finished)
 * - waitForEvent(type) 从 Channel 取指定类型事件,null 类型取任意
 *
 * Phase 3: 新增 [onDisconnected] 回调 — WebSocket 失败/服务端关闭时通知 Controller
 * 触发指数退避重连(本地 close() 触发的 onClosed 不通知,避免自触发重连)。
 */
internal class DashScopeSession(
    private val taskId: String,
    private val json: Json,
    private val onEvent: (JsonObject) -> Unit,
) : WebSocketListener() {

    private val events = kotlinx.coroutines.channels.Channel<JsonObject>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    @Volatile private var closed = false
    /**
     * Phase 3: 断线回调(由 Controller 设置)。
     * - [onFailure]: 连接失败/异常断开;
     * - [onClosing]: 服务端主动关闭连接(本地 close() 不会触发)。
     * 由 Controller 决定是重连还是降级为 Error。
     */
    var onDisconnected: ((String) -> Unit)? = null

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
        // Phase 3: 服务端主动关闭(本地 close() 不触发 onClosing) → 通知 Controller 决策重连
        Logger.w(TAG, "服务端关闭连接: code=$code reason=$reason")
        onDisconnected?.invoke("服务端关闭连接: code=$code reason=$reason")
        webSocket.close(code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        // 本地 close() 也会走到这里,不在此触发重连(否则 cleanupConnection 会自触发)
        closeChannel()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Logger.w(TAG, "WebSocket 失败: ${t.message}")
        // Phase 3: 断线重连 — 由 Controller 按状态与退避次数决定重连/降级(F-34 TODO 落地)
        onDisconnected?.invoke(t.message ?: "WebSocket 连接失败")
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

/**
 * Phase 3: DashScope 断线重连策略。
 *
 * 尝试上限与分批量客户端(Step/Whisper 的 AsrConstants.RECONNECT_MAX_ATTEMPTS)对齐,
 * 退避曲线对齐 [OpenAiRealtimeAsrController] 的既有实现(指数翻倍 + 封顶):
 * 500ms → 1s → 2s,单次上限 8s,最多 3 次。
 */
internal object DashScopeReconnectPolicy {
    /** 最大重连尝试次数(与 Step/Whisper 的 3 次对齐)。 */
    const val MAX_ATTEMPTS = 3
    /** 首次重连退避(毫秒)。 */
    const val BASE_DELAY_MS = 500L
    /** 单次退避上限(毫秒)。 */
    const val MAX_DELAY_MS = 8_000L

    /**
     * 重连耗尽后的用户可见降级文案:说明已保留内容,并给出明确的手动重试方式。
     * CONS-05: 生产路径(giveUpReconnect)已迁移到字符串资源 asr_reconnect_exhausted;
     * 此常量仅保留给 DashScopeAsrReconnectTest 的文案断言使用,不再进入用户可见路径。
     */
    const val EXHAUSTED_MESSAGE = "语音连接中断,自动重连 3 次仍未恢复。已识别的内容已保留," +
        "请检查网络后重新开始录音。"

    /** 第 attempt 次(0-based)重连前等待的退避时长:500ms → 1s → 2s(上限 8s)。 */
    fun delayForAttempt(attempt: Int): Long {
        val safeAttempt = attempt.coerceIn(0, 16)
        return minOf(MAX_DELAY_MS, BASE_DELAY_MS shl safeAttempt)
    }
}

/** Phase 3: WebSocket 断开后控制器应执行的动作。 */
internal enum class AsrDisconnectAction {
    /** 忽略(已 dispose / 终态 / 用户停止中 / 首次连接由 establishConnection 自行处理)。 */
    IGNORE,

    /** 进入指数退避重连。 */
    RECONNECT,

    /** 重试耗尽,降级为 Error 并释放录音资源。 */
    GIVE_UP,
}

/**
 * Phase 3: 根据当前状态判定断开处理动作(纯函数,便于单测)。
 *
 * - dispose 后、Idle/Error 终态:忽略;
 * - Stopping(用户主动停止中):忽略,由 stop() 超时逻辑收尾;
 * - Connecting(首次连接/重连建立中):忽略,由 establishConnection 自行处理失败;
 * - 其余(Listening/Reconnecting):重连;达到 [maxAttempts] 则降级。
 */
internal fun resolveAsrDisconnectAction(
    status: ASRStatus,
    isDisposed: Boolean,
    reconnectAttempt: Int,
    maxAttempts: Int = DashScopeReconnectPolicy.MAX_ATTEMPTS,
): AsrDisconnectAction = when {
    isDisposed -> AsrDisconnectAction.IGNORE
    status == ASRStatus.Idle || status == ASRStatus.Error -> AsrDisconnectAction.IGNORE
    status == ASRStatus.Stopping -> AsrDisconnectAction.IGNORE
    status == ASRStatus.Connecting -> AsrDisconnectAction.IGNORE
    reconnectAttempt >= maxAttempts -> AsrDisconnectAction.GIVE_UP
    else -> AsrDisconnectAction.RECONNECT
}

/**
 * Phase 3: 断线重连的退避等待。
 *
 * 单独抽出便于单测验证取消语义:delay 抛出的 CancellationException 必须原样向上传播
 * (不 catch、不吞),否则 stop()/dispose() 取消重连后协程仍会继续执行。
 */
internal suspend fun awaitReconnectBackoff(attempt: Int) {
    delay(DashScopeReconnectPolicy.delayForAttempt(attempt))
}
