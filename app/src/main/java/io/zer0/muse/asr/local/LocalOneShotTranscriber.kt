package io.zer0.muse.asr.local

import android.content.Context
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 小手机整段语音输入的本地引擎分支：WAV 字节 → 文本。
 *
 * 与流式 [SenseVoiceController] 共用同一个 [SenseVoiceEngine] 单例（同一份 ~350 MB
 * 的内存，不该开两份）。任何一步失败都返回 null —— 调用方按「没听清」处理，
 * 与云端引擎的行为一致。
 */
internal object LocalOneShotTranscriber {

    private const val TAG = "LocalOneShotTranscriber"

    fun transcribe(wavBytes: ByteArray): String? = runCatching {
        val context = appContextOrNull() ?: return@runCatching null
        kotlinx.coroutines.runBlocking { transcribeSuspend(context, wavBytes) }
    }.onFailure {
        if (it !is kotlinx.coroutines.CancellationException) {
            Logger.w(TAG, "本地整段转写失败: ${it.message}")
        }
    }.getOrNull()

    private suspend fun transcribeSuspend(context: Context, wavBytes: ByteArray): String? =
        withContext(Dispatchers.Default) {
            val parsed = parseWavPcm(wavBytes) ?: run {
                Logger.w(TAG, "WAV 解析失败（非 16-bit PCM），本地引擎跳过")
                return@withContext null
            }
            val (pcm, sampleRate) = parsed
            if (pcm.size < SenseVoiceController.DEFAULT_SAMPLE_RATE / 5) { // 200 ms
                return@withContext null
            }
            SenseVoiceEngine.ensureLoaded(LocalModelStore.modelDir(context))
            val vocab = LocalModelStore.loadTokens(context)
            val ids = SenseVoiceEngine.recognize(pcm, sampleRate, withItn = true)
            SenseVoiceTokens.decode(ids.toList(), vocab).trim().takeIf { it.isNotEmpty() }
        }

    private fun appContextOrNull(): Context? =
        LocalModelStoreBridge.contextOrNull()
}
