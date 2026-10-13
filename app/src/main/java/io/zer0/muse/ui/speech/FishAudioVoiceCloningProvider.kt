package io.zer0.muse.ui.speech

import android.util.Base64
import io.zer0.common.Logger
import io.zer0.common.Result
import io.zer0.common.resultOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * B6-04: Fish Audio 语音克隆 Provider。
 *
 * 端点（默认 https://api.fish.audio）：
 * - POST   /model             multipart 上传样本 + 元信息，返回 _id（voice id）
 * - GET    /model/{id}        轮询克隆状态（state=trained 表示可用）
 * - GET    /model?page_size=100 列出已克隆音色
 * - DELETE /model/{id}        删除音色
 *
 * v2.6.1 (#3): 修正为官方实际端点与字段 ——
 *   旧实现打的是 `/v1/voices`（该路径不存在）且只发 `name`/`files` 字段，
 *   导致用户拿正确 apiKey 也稳定报错。官方创建模型需 multipart 的
 *   `type=tts` / `title` / `train_mode=fast` / `visibility` / `voices=@file`，
 *   返回体为模型对象（`_id` 为音色 id，`state` 为训练状态）。
 *   注意：TTS 合成端点仍是 /v1/tts（见 CloudTtsService），与克隆不同域。
 *
 * 认证：`Authorization: Bearer {apiKey}`，与 [CloudTtsService.synthesizeFishAudio] 一致。
 */
class FishAudioVoiceCloningProvider(
    private val client: OkHttpClient,
) : VoiceCloningProvider {

    @Volatile
    var apiKey: String = ""

    @Volatile
    var endpoint: String = DEFAULT_ENDPOINT

    private val timedClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    override val providerId: String = "fish"

    override suspend fun cloneVoice(name: String, sampleAudioBase64: String): Result<String> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext Result.Error("Fish Audio apiKey is empty")
            resultOf {
                val audioBytes = Base64.decode(sampleAudioBase64, Base64.DEFAULT)
                if (audioBytes.isEmpty()) throw IOException("Sample audio is empty after base64 decode")

                val multipart = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    // v2.6.1 (#3): 官方创建模型字段 —— type=tts / title / train_mode=fast
                    //   / visibility=private / voices=样本文件。
                    .addFormDataPart("type", "tts")
                    .addFormDataPart("title", name)
                    .addFormDataPart("train_mode", "fast")
                    .addFormDataPart("visibility", "private")
                    .addFormDataPart("voices", SAMPLE_FILENAME, audioBytes.toRequestBody(SAMPLE_MIME.toMediaType()))
                    .build()
                val req = Request.Builder()
                    .url("${baseUrl()}/model")
                    .header("Authorization", "Bearer ${apiKey.trim()}")
                    .header("Accept", "application/json")
                    .post(multipart)
                    .build()

                val voiceId = timedClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("Fish Audio cloneVoice failed: HTTP ${resp.code}, body=${resp.body.string()}")
                    }
                    val root = parseJson(resp.body.string())
                    // 官方返回模型对象，音色 id 在 _id（兼容 id / voice_id 变体）。
                    root["_id"]?.jsonPrimitive?.contentOrNull
                        ?: root["id"]?.jsonPrimitive?.contentOrNull
                        ?: root["voice_id"]?.jsonPrimitive?.contentOrNull
                        ?: throw IOException("Fish Audio cloneVoice response missing _id")
                }

                // 轮询克隆状态，最多 60s
                var ready = false
                repeat(MAX_POLL_ATTEMPTS) {
                    if (isVoiceReady(voiceId)) {
                        ready = true
                        return@repeat
                    }
                    delay(POLL_INTERVAL_MS)
                }
                if (!ready) throw IOException("Fish Audio cloneVoice timed out waiting for ready")
                Logger.i(TAG, "Cloned voice: name=$name, voiceId=$voiceId")
                voiceId
            }
        }

    override suspend fun listClonedVoices(): Result<List<ClonedVoice>> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.Error("Fish Audio apiKey is empty")
        resultOf {
            val req = Request.Builder()
                .url("${baseUrl()}/model?page_size=100")
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .header("Accept", "application/json")
                .get()
                .build()
            timedClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("Fish Audio listVoices failed: HTTP ${resp.code}, body=${resp.body.string()}")
                }
                val root = parseJson(resp.body.string())
                // v2.6.1 (#3): 官方列表返回 {items:[...]}（兼容 voices 别名）。
                val arr = root["items"] as? JsonArray
                    ?: root["voices"] as? JsonArray
                    ?: throw IOException("Fish Audio listVoices response missing items[]")
                arr.mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    val voiceId = obj["_id"]?.jsonPrimitive?.contentOrNull
                        ?: obj["id"]?.jsonPrimitive?.contentOrNull
                        ?: obj["voice_id"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null
                    val voiceName = obj["title"]?.jsonPrimitive?.contentOrNull
                        ?: obj["name"]?.jsonPrimitive?.contentOrNull
                        ?: voiceId
                    ClonedVoice(voiceId = voiceId, name = voiceName, createdAt = 0L)
                }
            }
        }
    }

    override suspend fun deleteVoice(voiceId: String): Result<Unit> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.Error("Fish Audio apiKey is empty")
        resultOf {
            val req = Request.Builder()
                .url("${baseUrl()}/model/$voiceId")
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .header("Accept", "application/json")
                .delete()
                .build()
            timedClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("Fish Audio deleteVoice failed: HTTP ${resp.code}, body=${resp.body.string()}")
                }
                Logger.i(TAG, "Deleted voice: voiceId=$voiceId")
                Unit
            }
        }
    }

    private suspend fun isVoiceReady(voiceId: String): Boolean = runCatching {
        val req = Request.Builder()
            .url("${baseUrl()}/model/$voiceId")
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Accept", "application/json")
            .get()
            .build()
        timedClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use false
            val root = parseJson(resp.body.string())
            // v2.6.1 (#3): 官方用 state（训练状态），兼容旧字段名 status。
            val state = (root["state"] ?: root["status"])?.jsonPrimitive?.contentOrNull?.lowercase()
            // train_mode=fast 时通常直接 trained；null 视为可用（旧行为兜底）。
            state == null || state == "trained" || state == "ready" || state == "succeeded"
        }
    }.getOrDefault(false)

    private fun baseUrl(): String = normalizeEndpoint(endpoint)

    private fun parseJson(text: String): JsonObject = Json { ignoreUnknownKeys = true }.parseToJsonElement(text) as? JsonObject
        ?: throw IOException("Fish Audio response is not JSON")

    companion object {
        private const val TAG = "FishAudioVoiceClone"

        /**
         * v2.6.1 (#3): 克隆端点与 TTS 端点不同域 ——
         * 克隆走 https://api.fish.audio/model（不带 /v1），TTS 走 https://api.fish.audio/v1/tts。
         * 旧值 `.../v1` 拼出的 /v1/voices 并不存在。
         */
        const val DEFAULT_ENDPOINT = "https://api.fish.audio"

        /** B8-05: 归一化 Fish Audio 服务地址,便于纯单测。 */
        internal fun normalizeEndpoint(endpoint: String): String = endpoint.trim().trimEnd('/')
            .ifBlank { DEFAULT_ENDPOINT }
            // 兼容用户误把 TTS 端点（.../v1）填进克隆地址的情况：剥掉结尾 /v1。
            .removeSuffix("/v1")
        private const val SAMPLE_MIME = "audio/mpeg"
        private const val SAMPLE_FILENAME = "sample.mp3"
        private const val TIMEOUT_SECONDS = 30L
        private const val MAX_POLL_ATTEMPTS = 12
        private const val POLL_INTERVAL_MS = 5_000L
    }
}
