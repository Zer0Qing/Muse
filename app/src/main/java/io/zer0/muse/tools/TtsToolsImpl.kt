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
            tts = android.speech.tts.TextToSpeech(context) { status ->
                if (status != android.speech.tts.TextToSpeech.SUCCESS) {
                    tts?.shutdown()
                    cont.resume(false) { _, _, _ -> }
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
                            tts?.shutdown()
                            cont.resume(false) { _, _, _ -> }
                            return@TextToSpeech
                        }
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        val params = android.os.Bundle()
                        tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, params, null)
                    } else {
                        @Suppress("DEPRECATION")
                        tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null)
                    }
                    cont.resume(true) { _, _, _ -> }
                } catch (e: Exception) {
                    Logger.w("TtsTools", "TTS 朗读异常: ${e.message}")
                    cont.resume(false) { _, _, _ -> }
                }
            }
            cont.invokeOnCancellation {
                tts.shutdown()
            }
        }
    }
}
