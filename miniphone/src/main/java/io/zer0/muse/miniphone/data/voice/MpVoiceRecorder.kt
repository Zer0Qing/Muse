package io.zer0.muse.miniphone.data.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.zer0.common.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * 录音的对外契约。
 *
 * 真实现是 [MpVoiceRecorder];抽成接口是因为「按下麦克风」这条路径必须能在单测里走完,
 * 而单测不该真开麦克风,也不该为了一次状态迁移去申请权限。
 */
internal interface MpVoiceCapture {

    /** 开始采集;拿不到麦克风或起采失败返回 false(此时不留任何临时文件)。 */
    fun start(): Boolean

    /** 停止采集并交出整段 WAV;没有在录、或这段音频为空时返回 null。 */
    suspend fun stop(): ByteArray?

    /** 丢弃这次录音:停采并删掉临时文件。 */
    fun cancel()
}

/**
 * 聊天页语音入口的录音器。
 *
 * PCM16 / 16kHz / 单声道,采集线程边读边写临时文件 —— 六十秒是 1.9MB 左右,
 * 全放内存不算大,但录音期间内存会一直被占着,dump 出来也看不出这段音频长什么样。
 * 停止时把临时文件补上 WAV 头整段读出来,再删掉文件:交出的是字节,不是路径。
 *
 * 采集最长 [MAX_DURATION_MS]:到点就不再往里写,免得用户忘了端点把磁盘写满。
 * 到点之后仍需外面调 [stop] 才会拿到音频 —— 谁决定「录完了」由状态机说了算。
 *
 * @param directory 临时文件目录,通常传 `cacheDir`
 * @param factory 造采集器的口子;测试用它换掉真麦克风
 * @param ioDispatcher 采集与读写文件的线程
 * @param maxDurationMs 单次录音的时长上限
 */
internal class MpVoiceRecorder(
    private val directory: File,
    private val factory: MpAudioRecordFactory = SystemMpAudioRecordFactory(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxDurationMs: Long = MAX_DURATION_MS,
) : MpVoiceCapture {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /** 正在进行的这一次录音;null 表示没在录。 */
    private var session: Session? = null

    init {
        // 上次进程被杀留下的临时文件在后台清掉:构造发生在组合里,不能在这里做文件 IO
        scope.launch { cleanupStaleRecordings(directory) }
    }

    override fun start(): Boolean {
        if (session != null) return false
        val record = openRecord() ?: return false
        val file = createTempFile()
        if (file == null || !beginCapture(record)) {
            record.releaseQuietly()
            file?.delete()
            return false
        }
        val current = Session(record = record, file = file, maxBytes = bytesOf(maxDurationMs))
        session = current
        current.job = scope.launch { pump(current) }
        return true
    }

    /** 拿一个可用的采集器;初始化抛异常或拿不到都当作这次录音没开始。 */
    private fun openRecord(): MpAudioRecord? = runCatching { factory.create(SAMPLE_RATE, CHANNELS, BITS_PER_SAMPLE) }
        .onFailure { Logger.w(TAG, "麦克风初始化失败: ${it.message}") }
        .getOrNull()

    /** 起采;采集器拒绝启动也算失败。 */
    private fun beginCapture(record: MpAudioRecord): Boolean = runCatching { record.start() }
        .onFailure { Logger.w(TAG, "起采失败: ${it.message}") }
        .isSuccess

    override suspend fun stop(): ByteArray? = withContext(ioDispatcher) {
        val current = session ?: return@withContext null
        session = null
        current.record.stopQuietly()
        current.job?.join()
        current.record.releaseQuietly()
        val wav = readWavOrNull(current.file)
        current.file.delete()
        wav
    }

    override fun cancel() {
        val current = session ?: return
        session = null
        current.record.stopQuietly()
        current.job?.cancel()
        // 等采集循环真的退出再删,免得删在一个还开着的流上
        scope.launch {
            runCatching { current.job?.join() }
            current.record.releaseQuietly()
            current.file.delete()
        }
    }

    /** 采集循环:读到多少写多少,写满上限就自己停手。 */
    private suspend fun pump(current: Session) {
        try {
            FileOutputStream(current.file).use { sink ->
                val buffer = ByteArray(BUFFER_BYTES)
                var written = 0L
                while (written < current.maxBytes && currentCoroutineContext().isActive) {
                    val read = current.record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        sink.write(buffer, 0, read)
                        written += read
                    } else if (read == 0) {
                        // 采集器暂时没数据:让出一下,不要空转烧 CPU
                        delay(READ_RETRY_MS)
                    } else {
                        Logger.w(TAG, "AudioRecord.read 返回 $read,本次录音提前结束")
                        break
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Logger.w(TAG, "录音写入失败: ${e.message}")
        } catch (e: IllegalStateException) {
            Logger.w(TAG, "采集器状态异常: ${e.message}")
        } finally {
            // 正常结束、写满上限、被取消都在这里停采:pump 退出后不该继续占着麦克风
            current.record.stopQuietly()
        }
    }

    private fun createTempFile(): File? = runCatching {
        directory.mkdirs()
        File(directory, TEMP_PREFIX + UUID.randomUUID() + TEMP_SUFFIX)
    }.onFailure { Logger.w(TAG, "临时文件创建失败: ${it.message}") }.getOrNull()

    /** 临时文件里只有裸 PCM,读出来补上 WAV 头;一个字节都没采到就当没有音频。 */
    private fun readWavOrNull(file: File): ByteArray? = runCatching {
        val pcm = file.readBytes()
        if (pcm.isEmpty()) return null
        val header = wavHeader(pcm.size, SAMPLE_RATE, CHANNELS, BITS_PER_SAMPLE)
        ByteArray(header.size + pcm.size).also { wav ->
            header.copyInto(wav)
            pcm.copyInto(wav, header.size)
        }
    }.onFailure { Logger.w(TAG, "读取录音失败: ${it.message}") }.getOrNull()

    /** 一次录音的全部现场:采集器、临时文件、写入上限。 */
    private class Session(
        val record: MpAudioRecord,
        val file: File,
        val maxBytes: Long,
    ) {
        var job: Job? = null
    }

    companion object {
        /** 16kHz:语音识别服务端最常用的采样率,再高只是白占带宽。 */
        const val SAMPLE_RATE: Int = 16_000

        const val CHANNELS: Int = 1

        const val BITS_PER_SAMPLE: Int = 16

        /** 一次最多录六十秒。 */
        const val MAX_DURATION_MS: Long = 60_000L

        /** 这段时间能采到的字节数:PCM 每秒 = 采样率 × 声道 × 位深 / 8。 */
        private fun bytesOf(durationMs: Long): Long = durationMs * SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE / 8) / 1_000L
    }
}

/**
 * 清掉残留的录音临时文件。
 *
 * 只动本模块前缀、且一小时以上没改过的文件:单次录音上限一分钟、正常结束就删文件,
 * 活过一小时的 `mp-voice-*` 只可能是进程被杀留下的。按时间卡一刀,
 * 即使清理恰好与另一个页面刚建好的采集器撞在一起,也不会删掉正在录的那个。
 *
 * @return 实际删掉了几个;目录不在、文件删不掉都只记日志,不向外抛
 */
internal fun cleanupStaleRecordings(directory: File, now: Long = System.currentTimeMillis()): Int = runCatching {
    (directory.listFiles() ?: emptyArray())
        .filter { it.isFile && it.isStaleRecording(now) }
        .count { it.deleteQuietly() }
}.onFailure { Logger.w(TAG, "清理残留录音失败: ${it.message}") }.getOrDefault(0)

private fun File.isStaleRecording(now: Long): Boolean = name.startsWith(TEMP_PREFIX) && now - lastModified() > STALE_RECORDING_MS

private fun File.deleteQuietly(): Boolean = runCatching { delete() }.onFailure { Logger.w(TAG, "删除残留录音失败: $name") }.getOrDefault(false)

private const val TAG = "MpVoiceRecorder"

/** 一小时内改过的录音文件一律不碰:那是活着的录音,不是残留。 */
private const val STALE_RECORDING_MS = 60L * 60L * 1_000L

private const val BUFFER_BYTES = 8_192
private const val READ_RETRY_MS = 10L

/** 临时文件名的落盘形式;清理逻辑靠这个前缀认自己人。 */
private const val TEMP_PREFIX = "mp-voice-"
private const val TEMP_SUFFIX = ".pcm"

/**
 * 标准 44 字节 WAV 头:RIFF 外壳 + 16 字节 fmt(PCM)+ data 块长度。
 *
 * @param dataLength 后面跟着的 PCM 字节数
 * @param sampleRate 采样率
 * @param channels 声道数
 * @param bitsPerSample 位深
 */
internal fun wavHeader(dataLength: Int, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
    require(dataLength >= 0) { "dataLength 不能为负: $dataLength" }
    val blockAlign = channels * bitsPerSample / 8
    val header = ByteArray(WAV_HEADER_SIZE)
    header.writeIntLe(OFFSET_RIFF_SIZE, WAV_HEADER_SIZE - 8 + dataLength) // RIFF 块长度 = 36 + dataLength
    header.writeAscii(OFFSET_RIFF, "RIFF")
    header.writeAscii(OFFSET_WAVE, "WAVE")
    header.writeAscii(OFFSET_FMT, "fmt ")
    header.writeIntLe(OFFSET_FMT_SIZE, FMT_CHUNK_SIZE)
    header.writeShortLe(OFFSET_AUDIO_FORMAT, PCM_FORMAT)
    header.writeShortLe(OFFSET_CHANNELS, channels)
    header.writeIntLe(OFFSET_SAMPLE_RATE, sampleRate)
    header.writeIntLe(OFFSET_BYTE_RATE, sampleRate * blockAlign)
    header.writeShortLe(OFFSET_BLOCK_ALIGN, blockAlign)
    header.writeShortLe(OFFSET_BITS_PER_SAMPLE, bitsPerSample)
    header.writeAscii(OFFSET_DATA, "data")
    header.writeIntLe(OFFSET_DATA_SIZE, dataLength)
    return header
}

private const val WAV_HEADER_SIZE = 44
private const val FMT_CHUNK_SIZE = 16
private const val PCM_FORMAT = 1

private const val OFFSET_RIFF = 0
private const val OFFSET_RIFF_SIZE = 4
private const val OFFSET_WAVE = 8
private const val OFFSET_FMT = 12
private const val OFFSET_FMT_SIZE = 16
private const val OFFSET_AUDIO_FORMAT = 20
private const val OFFSET_CHANNELS = 22
private const val OFFSET_SAMPLE_RATE = 24
private const val OFFSET_BYTE_RATE = 28
private const val OFFSET_BLOCK_ALIGN = 32
private const val OFFSET_BITS_PER_SAMPLE = 34
private const val OFFSET_DATA = 36
private const val OFFSET_DATA_SIZE = 40

/** 小端写入 4 字节:WAV 头里的长度与采样率都是小端。 */
private fun ByteArray.writeIntLe(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = (value shr 8 and 0xFF).toByte()
    this[offset + 2] = (value shr 16 and 0xFF).toByte()
    this[offset + 3] = (value shr 24 and 0xFF).toByte()
}

/** 小端写入 2 字节。 */
private fun ByteArray.writeShortLe(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = (value shr 8 and 0xFF).toByte()
}

private fun ByteArray.writeAscii(offset: Int, text: String) {
    text.encodeToByteArray().copyInto(this, offset)
}

/** 采集器的最小可用面:只留采集要用的四个动作,测试据此替换真麦克风。 */
internal interface MpAudioRecord {
    fun start()

    fun read(buffer: ByteArray, offset: Int, size: Int): Int

    fun stop()

    fun release()
}

/** 造 [MpAudioRecord] 的口子;拿不到可用采集器时返回 null。 */
internal fun interface MpAudioRecordFactory {
    fun create(sampleRate: Int, channels: Int, bitsPerSample: Int): MpAudioRecord?
}

/** 真麦克风:[AudioRecord] 按语音识别的源与单声道 PCM16 配好再交出去。 */
private class SystemMpAudioRecordFactory : MpAudioRecordFactory {

    @SuppressLint("MissingPermission")
    override fun create(sampleRate: Int, channels: Int, bitsPerSample: Int): MpAudioRecord? {
        if (bitsPerSample != 16) return null
        val channelConfig = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val minBytes = AudioRecord.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) return null
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBytes, MIN_RECORD_BUFFER_BYTES),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        return SystemMpAudioRecord(record)
    }

    private companion object {
        /** 读取缓冲区下限:8KB 在 16kHz/16bit 下是 250ms,再小就容易频繁唤醒线程。 */
        const val MIN_RECORD_BUFFER_BYTES = 8_192
    }
}

/** [MpAudioRecord] 的真实现:把 AudioRecord 的几个调用原样转发。 */
private class SystemMpAudioRecord(private val record: AudioRecord) : MpAudioRecord {

    override fun start() = record.startRecording()

    override fun read(buffer: ByteArray, offset: Int, size: Int): Int = record.read(buffer, offset, size)

    override fun stop() = record.stop()

    override fun release() = record.release()
}

private fun MpAudioRecord.stopQuietly() {
    runCatching { stop() }.onFailure { Logger.w("MpVoiceRecorder", "停采失败: ${it.message}") }
}

private fun MpAudioRecord.releaseQuietly() {
    runCatching { release() }.onFailure { Logger.w("MpVoiceRecorder", "释放采集器失败: ${it.message}") }
}
