package io.zer0.muse.asr.local

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.asr.ASRController
import io.zer0.muse.asr.ASRState
import io.zer0.muse.asr.ASRStatus
import io.zer0.muse.asr.AsrAudioCapture
import io.zer0.muse.asr.AudioAmplitude.appendAmplitude
import io.zer0.muse.asr.shouldReportAsrCaptureFailure
import io.zer0.muse.util.GlobalCoroutineExceptionHandler
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地 SenseVoice 识别控制器（整段识别，非流式）。
 *
 * 与流式 Provider 的差异：录音期间只更新振幅波形，不出中间字；松手后才做
 * 前处理 + ONNX 推理，一次性出最终文本。状态映射沿用现有枚举：
 * Listening（录音）→ Stopping（推理）→ Idle（出字）。
 *
 * 模型加载是懒式的：第一次识别要等模型进内存（数秒），期间状态停在 Stopping。
 * 空闲超时释放由 [IdleWatcher] 配合 [SenseVoiceEngine.release] 完成 —— 调用方
 * dispose 本控制器不释放引擎，因为引擎是进程级共享的。
 */
internal class SenseVoiceController(
    private val appContext: Context,
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val withItn: Boolean = true,
) : ASRController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + GlobalCoroutineExceptionHandler)

    private val _state = MutableStateFlow(ASRState(isAvailable = true))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var audioRecord: android.media.AudioRecord? = null
    private var recordJob: Job? = null
    private val isDisposed = AtomicBoolean(false)
    private var onTranscriptChange: ((String) -> Unit)? = null

    // PCM 累积缓冲（录音线程写，推理线程读，bufferLock 串行化）
    private val pcmBuffer = java.io.ByteArrayOutputStream()
    private val bufferLock = Any()

    override fun start(onTranscriptChange: ((String) -> Unit)?) {
        if (isDisposed.get() || _state.value.isRecording) return
        if (!LocalModelStore.isReady(appContext)) {
            Logger.w(TAG, "本地语音模型未下载")
            _state.update {
                it.copy(
                    status = ASRStatus.Error,
                    errorMessage = appContext.getString(io.zer0.muse.R.string.asr_local_error_model_missing),
                    amplitudes = emptyList(),
                )
            }
            return
        }
        this.onTranscriptChange = onTranscriptChange
        synchronized(bufferLock) { pcmBuffer.reset() }
        _state.update {
            it.copy(status = ASRStatus.Connecting, transcript = "", errorMessage = null, amplitudes = emptyList())
        }
        startRecording()
    }

    private fun startRecording() {
        recordJob = scope.launch(Dispatchers.IO) {
            val capture = AsrAudioCapture.create(sampleRate, TAG)
            if (capture == null) {
                setError(appContext.getString(io.zer0.muse.R.string.asr_error_mic_init_failed))
                return@launch
            }
            val recorder = capture.recorder
            audioRecord = recorder
            try {
                recorder.startRecording()
                _state.update { it.copy(status = ASRStatus.Listening) }
                val buffer = ByteArray(capture.bufferSize)
                while (coroutineContext[Job]?.isActive == true) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val amplitude = io.zer0.muse.asr.AudioAmplitude.calculateRmsAmplitude(buffer, read)
                        _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(amplitude)) }
                        synchronized(bufferLock) { pcmBuffer.write(buffer, 0, read) }
                    } else if (read == 0) {
                        delay(10L)
                    } else {
                        Logger.w(TAG, "AudioRecord.read 错误: $read")
                        if (shouldReportAsrCaptureFailure(_state.value.status, coroutineContext[Job]?.isActive == true)) {
                            setError(appContext.getString(io.zer0.muse.R.string.asr_error_capture_failed, read))
                        }
                        break
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "录音失败: ${e.message}")
                if (shouldReportAsrCaptureFailure(_state.value.status, coroutineContext[Job]?.isActive == true)) {
                    setError(e.message ?: appContext.getString(io.zer0.muse.R.string.asr_error_capture_generic))
                }
            } finally {
                releaseRecorder()
            }
        }
    }

    override fun stop() {
        if (!_state.value.isRecording) return
        _state.update { it.copy(status = ASRStatus.Stopping) }
        recordJob?.cancel()
        releaseRecorder()
        scope.launch(Dispatchers.IO) {
            try {
                val pcm = synchronized(bufferLock) {
                    val bytes = pcmBuffer.toByteArray()
                    pcmBuffer.reset()
                    bytes
                }
                val text = transcribePcm(pcm)
                if (!text.isNullOrBlank()) {
                    _state.update { it.copy(transcript = text, errorMessage = null) }
                    onTranscriptChange?.invoke(text)
                } else if (_state.value.status == ASRStatus.Stopping) {
                    // 整段没出字（全静音或过短），按无结果收尾而不是报错
                    Logger.d(TAG, "本地识别无结果（音频过短或全静音）")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "本地识别失败: ${e.message}")
                if (_state.value.status == ASRStatus.Stopping) {
                    setError(e.message ?: appContext.getString(io.zer0.muse.R.string.asr_local_error_inference))
                }
            } finally {
                _state.update { it.copy(status = ASRStatus.Idle, amplitudes = emptyList()) }
            }
        }
    }

    /**
     * PCM → 文本。模型未加载时先加载（首次约数秒），失败抛异常给 [stop] 转 Error。
     */
    private suspend fun transcribePcm(pcm: ByteArray): String? = withContext(Dispatchers.Default) {
        if (pcm.size < MIN_PCM_BYTES) {
            Logger.d(TAG, "音频过短(${pcm.size} bytes)，跳过识别")
            return@withContext null
        }
        val shorts = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = ShortArray(shorts.remaining())
        shorts.get(samples)

        val modelDir = LocalModelStore.modelDir(appContext)
        SenseVoiceEngine.ensureLoaded(modelDir)
        val vocab = LocalModelStore.loadTokens(appContext)
        val ids = SenseVoiceEngine.recognize(samples, sampleRate, withItn)
        SenseVoiceTokens.decode(ids.toList(), vocab).trim().takeIf { it.isNotEmpty() }
    }

    override fun dispose() {
        if (!isDisposed.compareAndSet(false, true)) return
        recordJob?.cancel()
        releaseRecorder()
        scope.cancel()
        _state.update { it.copy(status = ASRStatus.Idle, amplitudes = emptyList()) }
    }

    private fun setError(message: String) {
        recordJob?.cancel()
        releaseRecorder()
        _state.update { it.copy(status = ASRStatus.Error, errorMessage = message) }
    }

    private fun releaseRecorder() {
        recordJob = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }

    companion object {
        private const val TAG = "SenseVoiceController"

        /** 16 kHz 固定采样率（模型训练采样率）。 */
        const val DEFAULT_SAMPLE_RATE = 16_000

        /** 最短音频：16 kHz 16-bit 单声道 200 ms ≈ 6400 bytes，过短识别必空。 */
        private const val MIN_PCM_BYTES = 6_400
    }
}

/**
 * WAV 字节 → PCM short 数组（小工具，供 [OneShotAsrTranscriber] 本地分支复用）。
 *
 * 只解析 16-bit PCM WAV；其他格式返回 null（调用方按"不支持"处理）。
 */
internal fun parseWavPcm(wavBytes: ByteArray): Pair<ShortArray, Int>? {
    if (wavBytes.size < 44) return null
    val buf = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)
    if (buf.int != 0x46464952) return null // "RIFF"
    buf.int // riff size
    if (buf.int != 0x45564157) return null // "WAVE"
    var sampleRate = 0
    var channels = 0
    var bitsPerSample = 0
    var data: ByteArray? = null
    while (buf.remaining() >= 8) {
        val chunkId = buf.int
        val chunkSize = buf.int
        when (chunkId) {
            0x20746D66 -> { // "fmt "
                if (chunkSize < 16) return null
                buf.getShort() // audio format
                channels = buf.short.toInt()
                sampleRate = buf.int
                buf.int // byte rate
                buf.getShort() // block align
                bitsPerSample = buf.short.toInt()
                val skip = chunkSize - 16
                if (skip > 0) buf.position(buf.position() + skip)
            }
            0x61746164 -> { // "data"
                val size = minOf(chunkSize, buf.remaining())
                data = ByteArray(size)
                buf.get(data)
            }
            else -> {
                val skip = chunkSize + (chunkSize and 1) // RIFF chunk 对齐到 2 字节
                if (skip > buf.remaining()) return null
                buf.position(buf.position() + skip)
            }
        }
    }
    if (sampleRate <= 0 || channels <= 0 || bitsPerSample != 16 || data == null) return null
    val shortBuf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    val interleaved = ShortArray(shortBuf.remaining())
    shortBuf.get(interleaved)
    // 多声道混到单声道（取平均）
    val mono = if (channels == 1) {
        interleaved
    } else {
        ShortArray(interleaved.size / channels) { i ->
            var sum = 0
            for (c in 0 until channels) sum += interleaved[i * channels + c]
            (sum / channels).toShort()
        }
    }
    return mono to sampleRate
}
