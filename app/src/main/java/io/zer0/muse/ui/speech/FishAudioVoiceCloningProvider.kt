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
 * 端点（默认 https://api.fish.audio/v1）：
 * - POST   /voices               multipart 上传样本 + name，返回 voice_id
 * - GET    /voices/{id}          轮询克隆状态（status=ready 表示完成）
 * - GET    /voices?page_size=100 列出已克隆音色
 * - DELETE /voices/{voice_id}    删除音色
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
                    .addFormDataPart("name", name)
                    .addFormDataPart("files", SAMPLE_FILENAME, audioBytes.toRequestBody(SAMPLE_MIME.toMediaType()))
                    .build()
                val req = Request.Builder()
                    .url("${baseUrl()}/voices")
                    .header("Authorization", "Bearer ${apiKey.trim()}")
                    .header("Accept", "application/json")
                    .post(multipart)
                    .build()

                val voiceId = timedClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("Fish Audio cloneVoice failed: HTTP ${resp.code}, body=${resp.body.string()}")
                    }
                    val root = parseJson(resp.body.string())
                    root["voice_id"]?.jsonPrimitive?.contentOrNull
                        ?: root["id"]?.jsonPrimitive?.contentOrNull
                        ?: throw IOException("Fish Audio cloneVoice response missing voice_id")
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

    override suspend fun listClonedVoices(): Result<List<ClonedVoice>> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext Result.Error("Fish Audio apiKey is empty")
            resultOf {
                val req = Request.Builder()
                    .url("${baseUrl()}/voices?page_size=100")
                    .header("Authorization", "Bearer ${apiKey.trim()}")
                    .header("Accept", "application/json")
                    .get()
                    .build()
                timedClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("Fish Audio listVoices failed: HTTP ${resp.code}, body=${resp.body.string()}")
                    }
                    val root = parseJson(resp.body.string())
                    val arr = root["items"] as? JsonArray
                        ?: root["voices"] as? JsonArray
                        ?: throw IOException("Fish Audio listVoices response missing items[]")
                    arr.mapNotNull { item ->
                        val obj = item as? JsonObject ?: return@mapNotNull null
                        val voiceId = obj["voice_id"]?.jsonPrimitive?.contentOrNull
                            ?: obj["id"]?.jsonPrimitive?.contentOrNull
                            ?: return@mapNotNull null
                        val voiceName = obj["name"]?.jsonPrimitive?.contentOrNull ?: voiceId
                        ClonedVoice(voiceId = voiceId, name = voiceName, createdAt = 0L)
                    }
                }
            }
        }

    override suspend fun deleteVoice(voiceId: String): Result<Unit> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext Result.Error("Fish Audio apiKey is empty")
            resultOf {
                val req = Request.Builder()
                    .url("${baseUrl()}/voices/$voiceId")
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
            .url("${baseUrl()}/voices/$voiceId")
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Accept", "application/json")
            .get()
            .build()
        timedClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use false
            val root = parseJson(resp.body.string())
            val status = root["status"]?.jsonPrimitive?.contentOrNull?.lowercase()
            status == null || status == "ready" || status == "succeeded"
        }
    }.getOrDefault(false)

    private fun baseUrl(): String = normalizeEndpoint(endpoint)

    private fun parseJson(text: String): JsonObject =
        Json { ignoreUnknownKeys = true }.parseToJsonElement(text) as? JsonObject
            ?: throw IOException("Fish Audio response is not JSON")

    companion object {
        private const val TAG = "FishAudioVoiceClone"
        const val DEFAULT_ENDPOINT = "https://api.fish.audio/v1"
        /** B8-05: 归一化 Fish Audio 服务地址,便于纯单测。 */
        internal fun normalizeEndpoint(endpoint: String): String =
            endpoint.trim().trimEnd('/').ifBlank { DEFAULT_ENDPOINT }
        private const val SAMPLE_MIME = "audio/mpeg"
        private const val SAMPLE_FILENAME = "sample.mp3"
        private const val TIMEOUT_SECONDS = 30L
        private const val MAX_POLL_ATTEMPTS = 12
        private const val POLL_INTERVAL_MS = 5_000L
    }
}
