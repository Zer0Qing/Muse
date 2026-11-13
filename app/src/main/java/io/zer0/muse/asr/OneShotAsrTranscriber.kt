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
        val config = effectiveConfig()
        val gate = asrGate(config, hasAudio = wavBytes.isNotEmpty())
        if (gate == AsrGate.UNSUPPORTED_ENGINE) {
            Logger.w(TAG, "当前识别引擎(${config?.provider})不提供整段音频转写端点")
        }
        if (gate != AsrGate.GO) return null
        // 走到这里 gate == GO，等价于 config 非空；用局部非空名把类型收窄回来，
        // 而不是到处写 !! —— 判据只有 asrGate 一处，这里只是配合它的结论。
        val ready = checkNotNull(config)
        // 本地引擎不走 HTTP：WAV 解回 PCM，喂给 SenseVoice 推理（与流式控制器同一引擎单例）
        if (ready.provider == AsrProviderType.LOCAL_SENSEVOICE) {
            return io.zer0.muse.asr.local.LocalOneShotTranscriber.transcribe(wavBytes)
        }
        val base = ready.baseUrl.ifBlank { ready.defaultBaseUrl().ifBlank { DEFAULT_BASE_URL } }
        return requestTranscription(
            url = oneShotTranscriptionUrl(base),
            apiKey = ready.apiKey,
            model = ready.model.ifBlank { ready.defaultModel() },
            wavBytes = wavBytes,
        )
    }

    /**
     * 当前设置能不能把整段音频转成文字。
     *
     * 与 [transcribe] 共用 [asrGate] 这一个判定函数：说支持就必须真的会发请求，
     * 不能说支持却在 [transcribe] 里当场返回 null。判据只有一份，所以两边不可能分家；
     * 各自的矩阵由 `OneShotAsrTranscriberTest` 钉住。
     */
    suspend fun supportsTranscription(): Boolean = asrGate(effectiveConfig(), hasAudio = true) == AsrGate.GO

    /** 读一次设置,把「跟随供应商」解析进配置;没配 apiKey 就没有可用的识别服务。 */
    private suspend fun effectiveConfig(): AsrConfig? {
        val config = settings.asrConfigFlow.first()
        // 本地引擎不走网络,也没有 apiKey:可用性由模型文件决定,不能在这里被 apiKey 判空拦掉。
        if (config.provider == AsrProviderType.LOCAL_SENSEVOICE) {
            return config.takeIf { io.zer0.muse.asr.local.LocalModelStoreBridge.isReady() }
        }
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

/** 请求前的判定结果。 */
internal enum class AsrGate {
    /** 可以发请求。 */
    GO,

    /** 一段音频都没有 —— 不该发请求，也不该因此说"不支持录音"。 */
    EMPTY_AUDIO,

    /** 用户还没配识别服务。 */
    NOT_CONFIGURED,

    /** 配了，但这个引擎没有整段音频转写端点。 */
    UNSUPPORTED_ENGINE,
}

/**
 * 「这一趟到底该不该发请求」的唯一判据（P3）。
 *
 * 抽出来的理由不是复用代码，是**消掉一个可以分家的缝**：
 * [OneShotAsrTranscriber.transcribe] 与 [OneShotAsrTranscriber.supportsTranscription]
 * 此前各写一遍条件。小手机据此把麦克风置灰或点亮 —— 一旦两边条件漂移，
 * 用户看到的就是"能按，按完永远转不出字"，而这在真机上极难被注意到。
 *
 * `hasAudio` 只允许 [transcribe] 传 `false`：界面问"支不支持"时不该因为
 * 手上暂时没有音频就答不支持。
 *
 * 判定顺序也是内容的一部分：**先问有没有音频，再问有没有配置**。
 * 空音频时把原因说成"你没配识别服务"，用户会去设置页白找一圈。
 */
internal fun asrGate(config: AsrConfig?, hasAudio: Boolean): AsrGate = when {
    !hasAudio -> AsrGate.EMPTY_AUDIO
    config == null -> AsrGate.NOT_CONFIGURED
    !config.supportsFileTranscription() -> AsrGate.UNSUPPORTED_ENGINE
    else -> AsrGate.GO
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
    // 本地引擎没有 HTTP 端点，但"整段 WAV 进、文本出"本来就是它的原生工作方式：
    // 可用性只看模型文件在不在，与 apiKey 无关。
    if (provider == AsrProviderType.LOCAL_SENSEVOICE) return io.zer0.muse.asr.local.LocalModelStoreBridge.isReady()
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
