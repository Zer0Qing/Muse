package io.zer0.muse.asr

import android.annotation.SuppressLint
import android.media.AudioRecord
import android.os.SystemClock
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
import kotlinx.coroutines.withContext
import io.zer0.muse.asr.AudioAmplitude.appendAmplitude
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 通用 OpenAI Whisper 兼容 ASR Controller(与本项目 [StepAsrController] 架构一致)。
 *
 * 适配任何提供 `POST {baseUrl}/audio/transcriptions` 端点的服务商:
 *  - OpenAI 官方(https://api.openai.com/v1)
 *  - Groq / DeepInfra / Together 等兼容平台
 *  - Agnes 等中转站(用户在 [AsrConfig.baseUrl] 自定义)
 *
 * 真流式 WebSocket 由 OpenAI 官方提供(见 [OpenAiRealtimeAsrController]),
 * 多数中转站只支持 multipart 文件上传,因此本 Controller 采用与 [StepAsrController] 相同的
 * 分段批量策略:
 *  - 录音期间 PCM 累积到缓冲区
 *  - 按时间阈值(默认 30s)或字节阈值(6MB)触发 flush
 *  - 每次 flush:PCM -> WAV -> HTTP multipart POST -> 解析响应 -> 回调 onTranscriptChange
 *  - [stop] 时做最后一次 flush,等 flushJob 完成才切 Idle
 *  - 串行 flushJob 避免并发乱序
 *  - 可选 [AsrConfig.vadEnabled] 启用本地 VAD,静音超过阈值自动触发 flush(模拟句子分割)
 *
 * 请求(multipart/form-data):
 *  - file: 音频文件(wav 格式,PCM 16-bit)
 *  - model: 模型名(默认 whisper-1)
 *  - language: 语种(可选,ISO-639-1 如 zh/en/ja)
 *  - prompt: 热词/上下文提示(可选,Whisper 用此提升专有名词识别率)
 *  - response_format: text(默认,直接返回纯文本)或 json(含 text 字段)
 *
 * 响应(JSON):
 *  - { "text": "识别文本" }
 * 或 response_format=text 时直接返回纯文本。
 *
 * @param config ASR 配置(必须有 apiKey;baseUrl 空时用默认 https://api.openai.com/v1)
 * @param sharedClient 可注入共享 OkHttpClient
 */
class OpenAiWhisperAsrController(
    private val config: AsrConfig,
    sharedClient: OkHttpClient? = null,
) : ASRController {

    private val json = Json { ignoreUnknownKeys = true }
    private val ownsClient: Boolean = sharedClient == null
    private val client: OkHttpClient = sharedClient ?: OkHttpClient.Builder()
        .connectTimeout(AsrConstants.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(AsrConstants.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ASRState(isAvailable = config.apiKey.isNotBlank()))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null
    private var flushJob: Job? = null
    private var onTranscriptChange: ((String) -> Unit)? = null

    // PCM 缓冲区(bufferLock 同步拷贝与重置)
    private val pcmBuffer = ByteArrayOutputStream()
    private val bufferLock = Any()
    private var totalTranscript = StringBuilder()

    // 分段阈值:30 秒或 6MB 先到先触发
    private val segmentDurationMs = SEGMENT_DURATION_MS
    private val maxSegmentBytes = MAX_SEGMENT_BYTES
    private var segmentStartElapsedMs = 0L

    // 可选本地 VAD(静音超过阈值自动 flush,模拟句子分割)
    private var vadDetector: VadDetector? = null

    override fun start(onTranscriptChange: ((String) -> Unit)?) {
        if (_state.value.isRecording) return
        if (config.apiKey.isBlank()) {
            Logger.w(TAG, "OpenAI Whisper ASR 未配置 apiKey")
            _state.update { it.copy(status = ASRStatus.Error, errorMessage = "未配置 apiKey") }
            return
        }
        this.onTranscriptChange = onTranscriptChange
        totalTranscript.clear()
        synchronized(bufferLock) {
            pcmBuffer.reset()
            segmentStartElapsedMs = SystemClock.elapsedRealtime()
        }
        flushJob = null
        // VAD 初始化(仅在 config.vadEnabled 时启用)
        vadDetector = if (config.vadEnabled) {
            VadDetector(
                threshold = config.vadThreshold,
                silenceDurationMs = config.vadSilenceDurationMs,
            )
        } else null
        _state.update {
            it.copy(
                status = ASRStatus.Connecting,
                transcript = "",
                errorMessage = null,
                amplitudes = emptyList(),
            )
        }
        startRecording()
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        recordJob = scope.launch(Dispatchers.IO) {
            val capture = AsrAudioCapture.create(config.sampleRate, TAG)
            if (capture == null) {
                setError("麦克风初始化失败,请检查权限或录音设备")
                return@launch
            }
            val recorder = capture.recorder
            val bufSize = capture.bufferSize
            audioRecord = recorder

            try {
                recorder.startRecording()
                _state.update { it.copy(status = ASRStatus.Listening) }
                val buffer = ByteArray(bufSize)
                while (coroutineContext[Job]?.isActive == true) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        // 1. 计算振幅 -> 更新 state(归一化 0-1f)
                        val amplitude = AudioAmplitude.calculateRmsAmplitude(buffer, read)
                        _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(amplitude)) }

                        // 2. 喂给 VAD(若启用),静音超过阈值时强制 flush(模拟句子分割)
                        val vadTriggered = vadDetector?.processFrame(buffer, read, amplitude) == true

                        // 3. 累积到 pcmBuffer(bufferLock 同步),判断分段阈值
                        val shouldFlush = synchronized(bufferLock) {
                            pcmBuffer.write(buffer, 0, read)
                            val elapsed = SystemClock.elapsedRealtime() - segmentStartElapsedMs
                            // VAD 触发:仅当缓冲区有足够数据时才 flush,避免过短段
                            val vadFlush = vadTriggered && pcmBuffer.size() >= MIN_VAD_FLUSH_BYTES
                            pcmBuffer.size() >= maxSegmentBytes || elapsed >= segmentDurationMs || vadFlush
                        }
                        // 4. 超阈值 -> 触发 flush(串行,不阻塞录音循环)
                        if (shouldFlush) {
                            triggerFlush()
                        }
                    } else if (read == 0) {
                        delay(10L)
                    } else if (read < 0) {
                        Logger.w(TAG, "AudioRecord.read 错误: $read")
                        setError("AudioRecord 读取错误: $read")
                        break
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "录音失败: ${e.message}")
                setError(e.message ?: "录音失败")
            } finally {
                releaseRecorder()
            }
        }
    }

    /**
     * 触发一次 flush(串行):同一时刻只允许一个 flushJob 运行,避免后发先至导致结果乱序。
     * PCM 拷贝与 buffer 重置在 [bufferLock] 内完成,HTTP 请求在锁外执行。
     */
    private fun triggerFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch(Dispatchers.IO) {
            resultOf { flushSegment() }
                .onError { message, throwable ->
                    val error = throwable?.message ?: message
                    Logger.w(TAG, "分段 flush 失败: $error")
                    setError("语音识别分段失败: $error")
                }
        }
    }

    /**
     * 取出当前缓冲区里的 PCM,转 WAV 后 POST 到 {baseUrl}/audio/transcriptions。
     * 识别结果追加到 [totalTranscript] 并通过 onTranscriptChange 回调。
     */
    private suspend fun flushSegment() {
        val pcmCopy = synchronized(bufferLock) {
            if (pcmBuffer.size() == 0) return
            val bytes = pcmBuffer.toByteArray()
            pcmBuffer.reset()
            segmentStartElapsedMs = SystemClock.elapsedRealtime()
            bytes
        }

        // 最短段过滤:PCM < 3200 bytes(100ms @ 16kHz 16bit mono)跳过,避免 400 错误
        if (pcmCopy.size < MIN_SEGMENT_BYTES) {
            Logger.d(TAG, "跳过 flush:PCM 过短(${pcmCopy.size} bytes)")
            return
        }

        val wavBytes = PcmWavConverter.toWav(pcmCopy, config.sampleRate, channels = 1, bitsPerSample = 16)
        val text = recognizeSegment(wavBytes)
        if (!text.isNullOrBlank()) {
            totalTranscript.append(text).append(" ")
            val transcript = totalTranscript.toString().trim()
            _state.update { it.copy(transcript = transcript, errorMessage = null) }
            onTranscriptChange?.invoke(transcript)
        }
    }

    /**
     * 调用 OpenAI Whisper 兼容端点识别一段 WAV 音频。
     *
     * multipart form 字段:
     *  - file: wav 音频
     *  - model: 模型名(默认 whisper-1)
     *  - language: 语种(可选)
     *  - prompt: 热词拼接(可选,Whisper 用此提升专有名词识别率)
     *  - response_format: json(便于解析)
     */
    private suspend fun recognizeSegment(wavBytes: ByteArray): String? =
        withContext(Dispatchers.IO) {
            val wavBody = wavBytes.toRequestBody("audio/wav".toMediaType())
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio.wav", wavBody)
                .addFormDataPart("model", config.model.ifBlank { config.defaultModel() })
                .addFormDataPart("response_format", "json")
                .apply {
                    config.language?.takeIf { it.isNotBlank() }?.let { addFormDataPart("language", it) }
                    if (config.hotwords.isNotEmpty()) {
                        val prompt = config.hotwords.joinToString(", ")
                        if (prompt.isNotBlank()) addFormDataPart("prompt", prompt)
                    }
                }
                .build()
            val base = config.baseUrl.ifBlank { config.defaultBaseUrl().ifBlank { DEFAULT_BASE_URL } }
            val request = Request.Builder()
                .url(transcriptionEndpoint(base))
                .header("Authorization", "Bearer ${config.apiKey}")
                .post(multipart)
                .build()
            var lastError: String? = null
            for (attempt in 0 until AsrConstants.MAX_HTTP_ATTEMPTS) {
                try {
                    val result = client.newCall(request).execute().use { resp: Response ->
                        if (!resp.isSuccessful) {
                            lastError = "识别服务 HTTP ${resp.code}: ${resp.message}"
                            Logger.w(TAG, "Whisper ASR HTTP ${resp.code}: ${resp.message}")
                            if (resp.code !in 500..599 && resp.code != 429) {
                                return@use null
                            }
                            null
                        } else {
                            parseTranscriptionResponse(resp.body.string())
                        }
                    }
                    if (!result.isNullOrBlank()) return@withContext result
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: java.io.IOException) {
                    lastError = e.message ?: "网络连接失败"
                    Logger.w(TAG, "Whisper ASR 请求失败(attempt=${attempt + 1}): ${e.message}")
                }
                if (attempt + 1 < AsrConstants.MAX_HTTP_ATTEMPTS) {
                    delay(AsrConstants.HTTP_RETRY_BACKOFF_MS * (attempt + 1))
                }
            }
            lastError?.let { setError("语音识别失败: $it") }
            null
        }

    private fun transcriptionEndpoint(baseUrl: String): String {
        val base = baseUrl.trimEnd('/')
        return if (base.endsWith("/audio/transcriptions")) {
            base
        } else if (base.endsWith("/v1")) {
            "$base/audio/transcriptions"
        } else {
            "$base/v1/audio/transcriptions"
        }
    }

    /**
     * 解析 transcription 响应,提取 text 字段。
     * - response_format=json: { "text": "..." }
     * - response_format=text: 直接是纯文本(回退兼容)
     */
    private fun parseTranscriptionResponse(body: String): String? {
        // 先尝试 JSON 解析
        val fromJson = resultOf {
            val obj = Json.parseToJsonElement(body) as? JsonObject
            (obj?.get("text") as? JsonPrimitive)?.content
        }.getOrNull()
        if (!fromJson.isNullOrBlank()) return fromJson
        // 回退:响应可能直接是纯文本(response_format=text)
        return body.takeIf { it.isNotBlank() }
    }

    override fun stop() {
        if (!_state.value.isRecording) return
        _state.update { it.copy(status = ASRStatus.Stopping) }
        recordJob?.cancel()
        releaseRecorder()
        // 最后一次 flush:等当前 flushJob 完成后直接调用 flushSegment(绕过 triggerFlush 的并发检查)
        scope.launch(Dispatchers.IO) {
            try {
                flushJob?.join()
                flushSegment()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "最终 flush 失败: ${e.message}")
                setError(e.message ?: "Whisper ASR 最终 flush 失败")
            } finally {
                _state.update { it.copy(status = ASRStatus.Idle, amplitudes = emptyList()) }
            }
        }
    }

    override fun dispose() {
        recordJob?.cancel()
        flushJob?.cancel()
        releaseRecorder()
        scope.cancel()
        if (ownsClient) {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
        _state.update { it.copy(status = ASRStatus.Idle, amplitudes = emptyList()) }
    }

    private fun setError(message: String) {
        _state.update { it.copy(status = ASRStatus.Error, errorMessage = message) }
    }

    private fun releaseRecorder() {
        recordJob = null
        resultOf { audioRecord?.stop() }
        resultOf { audioRecord?.release() }
        audioRecord = null
    }

    companion object {
        private const val TAG = "OpenAiWhisperAsrController"
        private const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        /** 分段时间阈值:30 秒触发一次 flush。 */
        private const val SEGMENT_DURATION_MS = 30_000L
        /** 分段字节阈值:6MB 触发 flush(提前量,避免单段过大)。 */
        private const val MAX_SEGMENT_BYTES = 6 * 1024 * 1024
        /** 最短段字节数:16kHz/16bit/mono 下 100ms = 3200 bytes,短于此值跳过避免 400。 */
        private const val MIN_SEGMENT_BYTES = 3200
        /** VAD 触发 flush 的最短段字节数:避免过短段(500ms = 16000 bytes)。 */
        private const val MIN_VAD_FLUSH_BYTES = 16000
    }
}
