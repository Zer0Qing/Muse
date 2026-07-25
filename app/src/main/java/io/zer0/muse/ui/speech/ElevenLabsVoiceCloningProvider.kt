package io.zer0.muse.ui.speech

import android.util.Base64
import io.zer0.common.Logger
import io.zer0.common.Result
import io.zer0.common.resultOf
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * P2-9: ElevenLabs Voice Cloning Provider 实现。
 *
 * 端点( baseUrl = https://api.elevenlabs.io/v1 ):
 *  - POST   /voices/add          — multipart 上传样本音频 + name,返回 voice_id
 *  - GET    /voices              — 列出所有 voice(含克隆 + 预设)
 *  - DELETE /voices/{voice_id}   — 删除指定 voice
 *
 * 认证:HTTP 头 `xi-api-key: {apiKey}`。
 *
 * [apiKey] 由 UI 层在每次操作前设置(从 MediaConfig.ttsApiKey 透传),
 * 与 [CloudTtsService] 的 ElevenLabs TTS 路径共用同一 API Key。
 *
 * 超时:复用注入的 OkHttpClient,但通过 [newBuilder] 强制覆盖为 30s 三项超时,
 * 满足"API 调用必须有超时(30 秒)"约束(克隆上传不应沿用 chat client 5min 读超时)。
 */
class ElevenLabsVoiceCloningProvider(
    private val client: OkHttpClient,
) : VoiceCloningProvider {

    companion object {
        private const val TAG = "ElevenLabsVoiceClone"
        /** ElevenLabs Voice Cloning API 基址。 */
        private const val BASE_URL = "https://api.elevenlabs.io/v1"
        /** 克隆语音的 mime 类型(ElevenLabs 接受 mp3/wav/m4a 等)。 */
        private const val SAMPLE_MIME = "audio/mpeg"
        /** 上传样本音频时的文件名(ElevenLabs 仅依赖二进制内容,文件名占位即可)。 */
        private const val SAMPLE_FILENAME = "sample.mp3"
        /** 强制 30 秒超时(满足任务约束)。 */
        private const val TIMEOUT_SECONDS = 30L
    }

    /** ElevenLabs provider 唯一标识。 */
    override val providerId: String = "elevenlabs"

    /**
     * ElevenLabs API Key — 由 UI 在调用前设置(从 MediaConfig.ttsApiKey 透传)。
     * 用 @Volatile 保证跨线程可见性(协程在 IO 调度器上执行)。
     */
    @Volatile
    var apiKey: String = ""

    /** 带有 30s 超时的 client(每次调用按需创建,继承基础 client 的代理 / 拦截器配置)。 */
    private val timedClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 创建克隆语音。
     *
     * 请求:multipart/form-data
     *  - `name`  text 字段
     *  - `files` audio/mpeg 二进制(由 base64 解码)
     *
     * 响应:`{"voice_id": "..."}` — 返回新建 voice id。
     */
    override suspend fun cloneVoice(name: String, sampleAudioBase64: String): Result<String> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext Result.Error("ElevenLabs apiKey is empty")
            }
            resultOf {
                val audioBytes = Base64.decode(sampleAudioBase64, Base64.DEFAULT)
                if (audioBytes.isEmpty()) {
                    throw IOException("Sample audio is empty after base64 decode")
                }

                val multipart = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("name", name)
                    .addFormDataPart(
                        "files",
                        SAMPLE_FILENAME,
                        audioBytes.toRequestBody(SAMPLE_MIME.toMediaType()),
                    )
                    .build()

                val req = Request.Builder()
                    .url("$BASE_URL/voices/add")
                    .header("xi-api-key", apiKey)
                    .header("Accept", "application/json")
                    .post(multipart)
                    .build()

                timedClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val body = resp.body.string()
                        throw IOException("ElevenLabs cloneVoice failed: HTTP ${resp.code}, body=$body")
                    }
                    val root = Json { ignoreUnknownKeys = true }
                        .parseToJsonElement(resp.body.string()) as? JsonObject
                        ?: throw IOException("ElevenLabs cloneVoice response is not JSON")
                    val voiceId = root["voice_id"]?.jsonPrimitive?.contentOrNull
                        ?: throw IOException("ElevenLabs cloneVoice response missing voice_id")
                    Logger.i(TAG, "Cloned voice: name=$name, voiceId=$voiceId")
                    voiceId
                }
            }
        }

    /**
     * 列出所有 voice,过滤 category == "cloned" 的项(ElevenLabs 同时返回预设 voice)。
     *
     * 响应:`{"voices": [{"voice_id": "...", "name": "...", "category": "...", "created_at": "..."}]}`
     * `created_at` 为 ISO 8601 字符串(如 "2024-01-15T12:34:56.789Z"),解析失败时回退 0L。
     */
    override suspend fun listClonedVoices(): Result<List<ClonedVoice>> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext Result.Error("ElevenLabs apiKey is empty")
            }
            resultOf {
                val req = Request.Builder()
                    .url("$BASE_URL/voices")
                    .header("xi-api-key", apiKey)
                    .header("Accept", "application/json")
                    .get()
                    .build()

                timedClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val body = resp.body.string()
                        throw IOException("ElevenLabs listVoices failed: HTTP ${resp.code}, body=$body")
                    }
                    val root = Json { ignoreUnknownKeys = true }
                        .parseToJsonElement(resp.body.string()) as? JsonObject
                        ?: throw IOException("ElevenLabs listVoices response is not JSON")
                    val arr = root["voices"] as? kotlinx.serialization.json.JsonArray
                        ?: throw IOException("ElevenLabs listVoices response missing voices[]")
                    arr.mapNotNull { item ->
                        val obj = item as? JsonObject ?: return@mapNotNull null
                        val voiceId = obj["voice_id"]?.jsonPrimitive?.contentOrNull
                            ?: return@mapNotNull null
                        val voiceName = obj["name"]?.jsonPrimitive?.contentOrNull ?: voiceId
                        val category = obj["category"]?.jsonPrimitive?.contentOrNull ?: "cloned"
                        val createdAtStr = obj["created_at"]?.jsonPrimitive?.contentOrNull
                        val createdAt = parseIso8601ToMillis(createdAtStr)
                        ClonedVoice(
                            voiceId = voiceId,
                            name = voiceName,
                            createdAt = createdAt,
                            category = category,
                        )
                    }.filter { it.category == "cloned" }
                }
            }
        }

    /**
     * 删除指定 voice。
     *
     * DELETE /voices/{voice_id}
     * 成功返回 204 No Content(无响应体)。
     */
    override suspend fun deleteVoice(voiceId: String): Result<Unit> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext Result.Error("ElevenLabs apiKey is empty")
            }
            resultOf {
                val req = Request.Builder()
                    .url("$BASE_URL/voices/$voiceId")
                    .header("xi-api-key", apiKey)
                    .header("Accept", "application/json")
                    .delete()
                    .build()

                timedClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val body = runCatching { resp.body.string() }.getOrNull()
                        throw IOException("ElevenLabs deleteVoice failed: HTTP ${resp.code}, body=$body")
                    }
                    Logger.i(TAG, "Deleted voice: voiceId=$voiceId")
                    Unit
                }
            }
        }

    /**
     * 解析 ISO 8601 时间字符串为毫秒时间戳。
     *
     * ElevenLabs 返回如 "2024-01-15T12:34:56.789Z" 的时间,失败时返回 0L(不阻塞列表渲染)。
     */
    private fun parseIso8601ToMillis(input: String?): Long {
        if (input.isNullOrBlank()) return 0L
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
        )
        for (pattern in formats) {
            val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            try {
                return sdf.parse(input)?.time ?: continue
            } catch (_: Exception) {
                // 尝试下一个格式
            }
        }
        return 0L
    }
}
