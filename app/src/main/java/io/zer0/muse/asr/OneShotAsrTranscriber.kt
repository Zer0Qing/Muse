package io.zer0.muse.asr

import io.zer0.ai.core.ProxyProvider
import io.zer0.common.Logger
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.SpeechProviderResolver
import io.zer0.muse.data.withResolvedEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 把一段完整录音转成文字的一次性转写器。
 *
 * 录音不在这一层:调用方采完 PCM、封好 WAV 再把字节交过来,这里只管「一段音频进,一句话出」。
 * 连接参数一律取自用户的语音识别设置,并走 [SpeechProviderResolver] 解析 —— 开了
 * 「跟随供应商配置」的用户,录音与流式识别用的是同一套地址与凭据。
 *
 * 与流式控制器相反,这里没有增量、没有分段、没有重试:一次请求对应一句话,
 * 任何一步失败都当作「没听清」返回 null,不向调用方抛异常。
 */
class OneShotAsrTranscriber(
    private val settings: SettingsRepository,
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * 把 [wavBytes] 整段转成文字。
     *
     * @return 去掉首尾空白的文本;引擎不支持、未配置识别服务、请求失败、响应里没有可用文本都返回 null。
     */
    suspend fun transcribe(wavBytes: ByteArray): String? {
        if (wavBytes.isEmpty()) return null
        val config = effectiveConfig() ?: return null
        if (!config.supportsFileTranscription()) {
            Logger.w(TAG, "当前识别引擎(${config.provider})不提供整段音频转写端点")
            return null
        }
        val base = config.baseUrl.ifBlank { config.defaultBaseUrl().ifBlank { DEFAULT_BASE_URL } }
        return requestTranscription(
            url = oneShotTranscriptionUrl(base),
            apiKey = config.apiKey,
            model = config.model.ifBlank { config.defaultModel() },
            wavBytes = wavBytes,
        )
    }

    /**
     * 当前设置能不能把整段音频转成文字。
     *
     * 与 [transcribe] 共用同一份解析结果与同一个判据 —— 说支持就必须真的会发请求，
     * 不能说支持却在 [transcribe] 里当场返回 null。
     */
    suspend fun supportsTranscription(): Boolean = effectiveConfig()?.supportsFileTranscription() == true

    /** 读一次设置,把「跟随供应商」解析进配置;没配 apiKey 就没有可用的识别服务。 */
    private suspend fun effectiveConfig(): AsrConfig? {
        val config = settings.asrConfigFlow.first()
        val providers = settings.providersFlow.first()
        val resolved = SpeechProviderResolver.resolveAsr(config, providers)
        if (resolved.isProviderFallback) {
            Logger.w(TAG, "跟随供应商配置不可用(${resolved.fallbackReason}),录音转写改用手填配置")
        }
        val effective = config.withResolvedEndpoint(resolved)
        if (effective.apiKey.isBlank()) {
            Logger.w(TAG, "语音识别未配置 apiKey,跳过录音转写")
            return null
        }
        return effective
    }

    /**
     * POST `{baseUrl}/audio/transcriptions`(multipart/form-data)。
     *
     * 表单只有三项:音频文件、模型、`response_format=json`;不带 language,
     * 语种交给服务端自己判断,免得用户没选语种时被按中文强转一段外文。
     */
    private suspend fun requestTranscription(url: String, apiKey: String, model: String, wavBytes: ByteArray): String? =
        withContext(Dispatchers.IO) {
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", FILE_NAME, wavBytes.toRequestBody(WAV_MEDIA_TYPE))
                .addFormDataPart("model", model)
                .addFormDataPart("response_format", "json")
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .post(multipart)
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Logger.w(TAG, "录音转写失败: HTTP ${response.code} ${response.message}")
                        return@withContext null
                    }
                    val text = parseOneShotTranscription(response.body?.string().orEmpty())
                    if (text == null) Logger.w(TAG, "录音转写响应里没有可用文本(HTTP ${response.code})")
                    text
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Logger.w(TAG, "录音转写请求异常: ${e.message}")
                null
            }
        }

    private companion object {
        private const val TAG = "OneShotAsrTranscriber"

        /** 与既有 Whisper 客户端同一个兜底地址:用户既没手填也没跟随供应商时用官方端点。 */
        private const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

        private const val FILE_NAME = "voice.wav"

        private val WAV_MEDIA_TYPE = "audio/wav".toMediaType()
    }
}

/**
 * 解析转写响应里的 `text` 字段。
 *
 * 只认 JSON 里的字符串 text:字段缺失、不是字符串、trim 后为空、响应根本不是 JSON,
 * 一律返回 null。这里不做「响应是纯文本就整段拿走」的兜底 —— `response_format=json`
 * 下那多半是一段报错页面,当成识别结果填进输入框比什么都不填更糟。
 */
internal fun parseOneShotTranscription(body: String): String? = runCatching {
    val obj = Json.parseToJsonElement(body) as? JsonObject
    val text = (obj?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content
    text?.trim()?.takeIf { it.isNotEmpty() }
}.getOrNull()

/**
 * 只有真能接 `POST {baseUrl}/audio/transcriptions` 的引擎才认整段音频。
 *
 * 其余引擎各走各的路:系统 Intent 没有 HTTP 端点、DashScope 是 wss 实时与异步文件转录、
 * Step 把音频塞进 chat 请求、Realtime 是 wss 流式 —— 拿着它们的 apiKey 去拼
 * `/audio/transcriptions` 只会得到一个必败的请求。所以支持与否看引擎类型,不看 apiKey 真不真。
 *
 * 还需 apiKey 非空且 baseUrl 是 http(s):这两个条件缺一个,[OneShotAsrTranscriber.transcribe]
 * 也会在发请求前就返回 null。
 */
internal fun AsrConfig.supportsFileTranscription(): Boolean {
    if (provider != AsrProviderType.OPENAI_WHISPER && provider != AsrProviderType.AGNES) return false
    if (apiKey.isBlank()) return false
    val base = baseUrl.ifBlank { defaultBaseUrl() }
    return base.startsWith("https://") || base.startsWith("http://")
}

/**
 * 拼出转写端点。
 *
 * 与既有 Whisper 客户端同一套规则:配到 `/v1` 就不重复拼版本段,
 * 用户把完整端点粘进设置里也照样能用。
 */
internal fun oneShotTranscriptionUrl(baseUrl: String): String {
    val base = baseUrl.trimEnd('/')
    return when {
        base.endsWith("/audio/transcriptions") -> base
        base.endsWith("/v1") -> "$base/audio/transcriptions"
        else -> "$base/v1/audio/transcriptions"
    }
}

/** 转写用的 HTTP 客户端:超时与既有 ASR 客户端取同一组常量,并继承宿主的代理设置。 */
private fun defaultClient(): OkHttpClient = ProxyProvider.applyTo(OkHttpClient.Builder())
    .connectTimeout(AsrConstants.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
    .readTimeout(AsrConstants.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
    .build()
