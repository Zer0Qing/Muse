package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * P1-3b 拆域：TTS 工具实现。
 * 由 TtsToolsRegistrar 注册到 ToolRegistry。
 */
class TtsToolsImpl(private val context: Context) {

    suspend fun speak(args: Map<String, String>): String {
        val text = args["text"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_url_missing)
        val language = args["language"]
        val rate = args["rate"]?.toFloatOrNull()?.coerceIn(0.25f, 4.0f) ?: 1.0f
        return resultOf {
            val result = speakWithTts(text, language, rate)
            if (result) {
                context.getString(R.string.tool_speak_text_success, text.take(50))
            } else {
                context.getString(R.string.tool_speak_text_failed)
            }
        }.onError { msg, _ -> Logger.w("TtsTools", "TTS 失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_speak_text_failed)
    }

    private suspend fun speakWithTts(text: String, language: String?, rate: Float): Boolean {
        return suspendCancellableCoroutine { cont ->
            var tts: android.speech.tts.TextToSpeech? = null
            // P2-16: 统一释放入口 — 每个实例在使用完(朗读完成/失败/取消)后必须 shutdown,
            // 否则每次工具调用泄漏一个 TextToSpeech(绑定引擎进程与资源)。
            val release: () -> Unit = {
                val engine = tts
                tts = null
                engine?.runCatching { shutdown() }
            }
            tts = android.speech.tts.TextToSpeech(context) { status ->
                if (status != android.speech.tts.TextToSpeech.SUCCESS) {
                    release()
                    if (cont.isActive) cont.resume(false) { _, _, _ -> }
                    return@TextToSpeech
                }
                try {
                    tts?.setSpeechRate(rate)
                    if (!language.isNullOrBlank()) {
                        val locale = java.util.Locale.forLanguageTag(language)
                        val langResult = tts?.setLanguage(locale)
                        if (langResult == android.speech.tts.TextToSpeech.LANG_MISSING_DATA ||
                            langResult == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED
                        ) {
                            release()
                            if (cont.isActive) cont.resume(false) { _, _, _ -> }
                            return@TextToSpeech
                        }
                    }
                    val utteranceId = "tts_tool_${System.currentTimeMillis()}_${text.hashCode()}"
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        val params = android.os.Bundle()
                        tts?.setOnUtteranceProgressListener(
                            object : android.speech.tts.UtteranceProgressListener() {
                                override fun onStart(uttId: String?) {}
                                @Deprecated("Deprecated in Java")
                                override fun onError(uttId: String?) {
                                    release()
                                }

                                override fun onError(uttId: String?, errorCode: Int) {
                                    if (uttId == utteranceId) release()
                                }

                                override fun onDone(uttId: String?) {
                                    if (uttId == utteranceId) release()
                                }
                            },
                        )
                        tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, params, utteranceId)
                    } else {
                        @Suppress("DEPRECATION")
                        tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null)
                        // pre-L 无法通过 utterance 回调感知完成,延迟释放兜底
                        android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(release, 3_000L)
                    }
                    cont.resume(true) { _, _, _ -> }
                } catch (e: Exception) {
                    Logger.w("TtsTools", "TTS 朗读异常: ${e.message}")
                    release()
                    if (cont.isActive) cont.resume(false) { _, _, _ -> }
                }
            }
            cont.invokeOnCancellation {
                release()
            }
        }
    }
}
