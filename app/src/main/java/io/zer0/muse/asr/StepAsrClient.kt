package io.zer0.muse.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * 阶跃星辰 Step-Audio 流式 ASR Controller。
 *
 * Step 的 OpenAI Whisper 兼容端点(POST {baseUrl}/v1/audio/transcriptions)不支持
 * WebSocket 流式,因此采用分段批量上传策略(参照 rikkahub MiMoASRController):
 *  - 录音期间 PCM 累积到缓冲区
 *  - 按时间阈值(默认 30s)或字节阈值(6MB)触发 flush
 *  - 每次 flush:PCM -> WAV -> HTTP POST -> 解析响应 -> 回调 onTranscriptChange
 *  - [stop] 时做最后一次 flush,等 flushJob 完成才切 Idle
 *  - 串行 flushJob 避免并发乱序
 *
 * 请求(multipart/form-data):
 *  - file: 音频文件(wav 格式,PCM 16-bit)
 *  - model: 模型名(默认 step-audio-r1.1)
 *  - language: 语种(可选)
 *
 * 响应(JSON):
 *  - { "text": "识别文本" }
 *
 * @param config ASR 配置(必须有 apiKey 和 model)
 * @param baseUrl API 基础 URL(默认 https://api.stepfun.com)
 */
class StepAsrController(
    private val config: AsrConfig,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : ASRController {

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

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(AsrConstants.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(AsrConstants.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    override fun start(onTranscriptChange: ((String) -> Unit)?) {
        if (_state.value.isRecording) return
        this.onTranscriptChange = onTranscriptChange
        totalTranscript.clear()
        synchronized(bufferLock) {
            pcmBuffer.reset()
            segmentStartElapsedMs = SystemClock.elapsedRealtime()
        }
        flushJob = null
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
        // 录音循环在 IO 协程内运行,内部用 isActive 检查取消状态
        recordJob = scope.launch(Dispatchers.IO) {
            // 检查权限:无 Context,通过 AudioRecord 初始化结果判断(RECORD_AUDIO 缺失时 state != INITIALIZED)
            val sampleRate = config.sampleRate
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) {
                setError("AudioRecord 缓冲区大小无效: $minBuf")
                return@launch
            }
            val bufSize = (minBuf * 2).coerceAtLeast(sampleRate / 10 * 2).coerceAtLeast(4096)
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize * 2,
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Logger.w(TAG, "AudioRecord 初始化失败(可能缺少 RECORD_AUDIO 权限)")
                recorder.release()
                // v1.98: 移除 setError 弹窗提示,静默处理(仅记日志)
                _state.update { it.copy(status = ASRStatus.Error) }
                return@launch
            }
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

                        // 2. 累积到 pcmBuffer(bufferLock 同步),同时判断是否达到分段阈值
                        val shouldFlush = synchronized(bufferLock) {
                            pcmBuffer.write(buffer, 0, read)
                            val elapsed = SystemClock.elapsedRealtime() - segmentStartElapsedMs
                            pcmBuffer.size() >= maxSegmentBytes || elapsed >= segmentDurationMs
                        }
                        // 3. 超阈值 -> 触发 flush(串行,不阻塞录音循环)
                        if (shouldFlush) {
                            triggerFlush()
                        }
                    } else if (read < 0) {
                        Logger.w(TAG, "AudioRecord.read 错误: $read")
                        setError("AudioRecord 读取错误: $read")
                        break
                    }
                }
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
                    Logger.w(TAG, "分段 flush 失败: ${throwable?.message ?: message}")
                }
        }
    }

    /**
     * 取出当前缓冲区里的 PCM,转 WAV 后 POST 到 Step /v1/audio/transcriptions。
     * 识别结果追加到 [totalTranscript] 并通过 onTranscriptChange 回调。
     */
    private suspend fun flushSegment() {
        // bufferLock 内:拷贝 PCM -> 重置 buffer -> 重置分段计时
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
     * 调用 Step OpenAI Whisper 兼容端点识别一段 WAV 音频。
     * 复用原 StepAsrClient 的 multipart 逻辑。
     */
    private suspend fun recognizeSegment(wavBytes: ByteArray): String? =
        withContext(Dispatchers.IO) {
            val wavBody = wavBytes.toRequestBody("audio/wav".toMediaType())
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio.wav", wavBody)
                .addFormDataPart("model", config.model)
                .apply {
                    config.language?.let { addFormDataPart("language", it) }
                }
                .build()

            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/v1/audio/transcriptions")
                .header("Authorization", "Bearer ${config.apiKey}")
                .post(multipart)
                .build()

            resultOf {
                client.newCall(request).execute().use { resp: Response ->
                    if (!resp.isSuccessful) {
                        Logger.w(TAG, "Step ASR HTTP ${resp.code}: ${resp.message}")
                        return@use null
                    }
                    val body = resp.body.string()
                    parseTranscriptionResponse(body)
                }
            }.onError { message, throwable ->
                Logger.w(TAG, "Step ASR 请求失败: ${throwable?.message ?: message}")
            }.getOrNull()
        }

    /** 解析 OpenAI 兼容 transcription 响应,提取 text 字段。 */
    private fun parseTranscriptionResponse(body: String): String? {
        return resultOf {
            val obj = Json.parseToJsonElement(body) as? JsonObject
            (obj?.get("text") as? JsonPrimitive)?.content
        }.getOrNull()
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
                setError(e.message ?: "Step ASR 最终 flush 失败")
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
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
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
        private const val TAG = "StepAsrController"
        private const val DEFAULT_BASE_URL = "https://api.stepfun.com"
        /** 分段时间阈值:30 秒触发一次 flush。 */
        private const val SEGMENT_DURATION_MS = 30_000L
        /** 分段字节阈值:6MB 触发 flush(提前量,避免单段过大)。 */
        private const val MAX_SEGMENT_BYTES = 6 * 1024 * 1024
        /** 最短段字节数:16kHz/16bit/mono 下 100ms = 3200 bytes,短于此值跳过避免 400。 */
        private const val MIN_SEGMENT_BYTES = 3200
    }
}

/**
 * PCM -> WAV 封装工具。
 *
 * WAV 文件结构(44 字节头 + PCM 数据):
 *  - RIFF chunk descriptor(12 字节): "RIFF" + 文件大小 + "WAVE"
 *  - fmt sub-chunk(24 字节): "fmt " + 子块大小 + 音频格式 + 声道 + 采样率 + 字节率 + 块对齐 + 位深度
 *  - data sub-chunk(8 字节): "data" + 数据大小
 *  - PCM 数据
 */
internal object PcmWavConverter {
    fun toWav(pcm: ByteArray, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcm.size
        val chunkSize = 36 + dataSize

        val out = ByteArrayOutputStream(44 + dataSize)
        val dos = java.io.DataOutputStream(out)

        // RIFF 头
        dos.writeBytes("RIFF")
        dos.writeIntLittleEndian(chunkSize)
        dos.writeBytes("WAVE")

        // fmt 子块
        dos.writeBytes("fmt ")
        dos.writeIntLittleEndian(16)        // PCM 格式子块大小
        dos.writeShortLittleEndian(1)       // 音频格式 = 1 (PCM)
        dos.writeShortLittleEndian(channels)
        dos.writeIntLittleEndian(sampleRate)
        dos.writeIntLittleEndian(byteRate)
        dos.writeShortLittleEndian(blockAlign)
        dos.writeShortLittleEndian(bitsPerSample)

        // data 子块
        dos.writeBytes("data")
        dos.writeIntLittleEndian(dataSize)
        dos.write(pcm)

        return out.toByteArray()
    }

    private fun java.io.DataOutputStream.writeIntLittleEndian(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
        write((v shr 16) and 0xFF)
        write((v shr 24) and 0xFF)
    }

    private fun java.io.DataOutputStream.writeShortLittleEndian(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
    }
}
