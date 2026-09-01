package io.zer0.muse.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.zer0.common.Logger

/**
 * 统一创建麦克风录音器。
 *
 * 不同 ROM 对 VOICE_COMMUNICATION 音源支持不一致；按“语音识别 -> 通话 -> 默认麦克风”
 * 顺序尝试，避免某个设备只拒绝单一音源就让整个 ASR 链路不可用。
 */
internal object AsrAudioCapture {
    data class Session(
        val recorder: AudioRecord,
        val bufferSize: Int,
    )

    @SuppressLint("MissingPermission")
    fun create(sampleRate: Int, tag: String): Session? {
        if (sampleRate !in 8_000..48_000) {
            Logger.w(tag, "不支持的 ASR 采样率: $sampleRate")
            return null
        }
        val minBuffer = try {
            AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        } catch (e: IllegalArgumentException) {
            Logger.w(tag, "获取 AudioRecord 缓冲区失败: ${e.message}", e)
            return null
        }
        if (minBuffer <= 0) {
            Logger.w(tag, "AudioRecord 缓冲区大小无效: $minBuffer")
            return null
        }
        val bufferSize = (minBuffer * 2)
            .coerceAtLeast(sampleRate / 10 * 2)
            .coerceAtLeast(4096)
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.MIC,
        ).distinct()
        for (source in sources) {
            val recorder = try {
                AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize * 2,
                )
            } catch (e: SecurityException) {
                Logger.w(tag, "AudioRecord 音源 $source 被拒绝: ${e.message}")
                null
            } catch (e: IllegalArgumentException) {
                Logger.w(tag, "AudioRecord 音源 $source 参数无效: ${e.message}")
                null
            }
            if (recorder != null) {
                if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                    return Session(recorder, bufferSize)
                }
                recorder.release()
            }
        }
        Logger.w(tag, "所有 AudioRecord 音源均初始化失败")
        return null
    }
}
