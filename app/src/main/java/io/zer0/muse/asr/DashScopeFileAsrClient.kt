package io.zer0.muse.asr

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Phase 11.1.5: DashScope 异步文件转录 Client。
 *
 * 与 [DashScopeAsrClient](WebSocket 流式)不同,本 client 走 **异步文件转录** 协议
 * (https://help.aliyun.com/document_detail/90727.html):
 *
 * 1. **Submit**:POST `https://dashscope.aliyuncs.com/api/v1/services/audio/asr/transcription`
 *    传 `file_urls`(公网可访问的音频 URL),返回 `task_id`
 * 2. **Query**:GET `https://dashscope.aliyuncs.com/api/v1/tasks/{task_id}`
 *    轮询任务状态:PENDING → RUNNING → SUCCEEDED / FAILED
 * 3. SUCCEEDED 后从 `results.transcription_url` 拿转录 JSON,解析 `sentences.text` 拼接
 *
 * 适用场景:
 *  - 长音频文件转录(>1 分钟,流式 WebSocket 会超时)
 *  - 已有公网音频 URL(如 OSS 上传后)
 *  - 批量离线转录
 *
 * 不适用场景(用 [DashScopeAsrClient]):
 *  - 实时麦克风输入(需流式)
 *  - 短音频快速识别
 *
 * 注意:
 *  - `recognize(audioData, sampleRate)` 接口要求 PCM 字节,但本 client 需要 URL。
 *    调用方应使用 [recognizeFile] 传 URL,而非 [recognize]。
 *  - 若调用 [recognize],会尝试把 PCM 上传(当前未实现,返回 null)。
 *
 * 独立编写(参考 DashScope 官方文档),Apache 2.0。
 */
class DashScopeFileAsrClient(
    private val config: AsrConfig,
) : AsrClient {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(AsrConstants.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(AsrConstants.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }

    private companion object {
        const val TAG = "DashScopeFileASR"
        const val SUBMIT_ENDPOINT = "https://dashscope.aliyuncs.com/api/v1/services/audio/asr/transcription"
        const val QUERY_ENDPOINT = "https://dashscope.aliyuncs.com/api/v1/tasks/"
    }

    /**
     * 异步文件转录:从音频 URL 提交任务并轮询结果。
     *
     * @param audioUrl 公网可访问的音频 URL(支持 mp3/wav/m4a/aac/flac/opus 等)
     * @return 识别结果;失败返回 null
     */
    suspend fun recognizeFile(audioUrl: String): AsrResult? = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) {
            Logger.w(TAG, "apiKey 为空")
            return@withContext null
        }
        val taskId = submitTranscription(audioUrl) ?: return@withContext null
        Logger.d(TAG, "submit 成功,task_id=$taskId,开始轮询")
        val result = pollTask(taskId) ?: return@withContext null
        // result.transcription_url 指向一个 JSON,需再 fetch
        val transcriptionJson = fetchTranscriptionUrl(result.transcriptionUrl) ?: return@withContext null
        val text = parseTranscriptionJson(transcriptionJson)
        if (text.isBlank()) {
            Logger.w(TAG, "转录结果文本为空")
            return@withContext null
        }
        AsrResult(text = text, isFinal = true, durationMs = result.audioDurationMs)
    }

    /**
     * AsrClient 接口实现:异步文件转录需 URL 而非 PCM 字节。
     *
     * 当前实现:若 [AsrConfig.fileAudioUrl] 已配置,直接用配置的 URL;
     * 否则返回 null(不支持把 PCM 字节直接上传)。
     *
     * 后续可扩展:加 OSS 上传逻辑(需用户配 OSS 凭证),把 PCM 转 wav 上传拿 URL。
     */
    override suspend fun recognize(audioData: ByteArray, sampleRate: Int): AsrResult? {
        val url = config.fileAudioUrl.ifBlank {
            Logger.w(TAG, "异步文件转录需要 fileAudioUrl 配置,当前为空")
            return null
        }
        return recognizeFile(url)
    }

    override fun close() {
        // OkHttp 客户端无 WebSocket 长连接,无需显式关闭
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    /** Step 1: 提交转录任务,返回 task_id。 */
    private suspend fun submitTranscription(audioUrl: String): String? = withContext(Dispatchers.IO) {
        resultOf {
            val requestBody = buildJsonObject {
                put("model", config.model.ifBlank { "paraformer-v2" })
                put("input", buildJsonObject {
                    put("file_urls", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(audioUrl))))
                })
                put("parameters", buildJsonObject {
                    put("language_hints", kotlinx.serialization.json.JsonArray(
                        (config.language?.takeIf { it.isNotBlank() }?.split(",") ?: listOf("zh"))
                            .map { JsonPrimitive(it.trim()) }
                    ))
                    put("disfluency_removal_enabled", true)
                    put("phrase_id", JsonPrimitive(""))
                })
            }
            val req = Request.Builder()
                .url(SUBMIT_ENDPOINT)
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("X-DashScope-DataInspection", "enable")
                .header("Content-Type", "application/json")
                .post(AppJson.encodeToString(JsonObject.serializer(), requestBody).toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Logger.w(TAG, "submit 失败: HTTP ${resp.code} ${resp.body.string().take(200)}")
                    return@resultOf null
                }
                val body = resp.body.string()
                val obj = AppJson.decodeFromString(JsonObject.serializer(), body)
                val output = obj["output"]?.jsonObject
                val taskId = output?.get("task_id")?.jsonPrimitive?.contentOrNull
                if (taskId.isNullOrBlank()) {
                    Logger.w(TAG, "submit 响应缺 task_id: ${body.take(200)}")
                    return@resultOf null
                }
                taskId
            }
        }.onError { msg, t -> Logger.w(TAG, "submitTranscription 失败: $msg", t) }
            .getOrNull()
    }

    /** Step 2: 轮询任务状态,直到 SUCCEEDED / FAILED / 超时。 */
    private suspend fun pollTask(taskId: String): TranscriptionResult? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + config.pollTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            val status = queryTask(taskId) ?: return@withContext null
            when (status.taskStatus) {
                "SUCCEEDED" -> {
                    Logger.d(TAG, "task=$taskId SUCCEEDED,耗时 ${System.currentTimeMillis() - (deadline - config.pollTimeoutMs)}ms")
                    return@withContext status.result
                }
                "FAILED" -> {
                    Logger.w(TAG, "task=$taskId FAILED: ${status.message}")
                    return@withContext null
                }
                else -> {
                    // PENDING / RUNNING,继续轮询
                    delay(config.pollIntervalMs)
                }
            }
        }
        Logger.w(TAG, "task=$taskId 轮询超时(${config.pollTimeoutMs}ms)")
        null
    }

    /** 单次查询任务状态。 */
    private suspend fun queryTask(taskId: String): TaskStatus? = withContext(Dispatchers.IO) {
        resultOf {
            val req = Request.Builder()
                .url("$QUERY_ENDPOINT$taskId")
                .header("Authorization", "Bearer ${config.apiKey}")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@resultOf null
                val body = resp.body.string()
                val obj = AppJson.decodeFromString(JsonObject.serializer(), body)
                val output = obj["output"]?.jsonObject
                val taskStatus = output?.get("task_status")?.jsonPrimitive?.contentOrNull ?: return@resultOf null
                // DashScope 错误码字段可能是 message 或 code,优先取 message
                val message = output["message"]?.jsonPrimitive?.contentOrNull
                    ?: obj["message"]?.jsonPrimitive?.contentOrNull ?: ""
                val result = output["results"]?.jsonObject
                    ?.let { parseResult(it) }
                TaskStatus(taskStatus = taskStatus, message = message, result = result)
            }
        }.onError { msg, t -> Logger.w(TAG, "queryTask($taskId) 失败: $msg", t) }
            .getOrNull()
    }

    /** 从 results 对象解析转录 URL + 音频时长。 */
    private fun parseResult(results: JsonObject): TranscriptionResult? {
        // results.transcription_url 指向一个 JSON 文件 URL,内含 sentences
        val transcriptionUrl = results["transcription_url"]?.jsonPrimitive?.contentOrNull
            ?: return null
        val audioDurationMs = results["audio_duration"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        return TranscriptionResult(transcriptionUrl = transcriptionUrl, audioDurationMs = audioDurationMs)
    }

    /** fetch 转录 JSON 文件(公网 URL)。 */
    private suspend fun fetchTranscriptionUrl(url: String): String? = withContext(Dispatchers.IO) {
        resultOf {
            val req = Request.Builder().url(url).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@resultOf null
                resp.body.string()
            }
        }.onError { msg, t -> Logger.w(TAG, "fetchTranscriptionUrl 失败: $msg", t) }
            .getOrNull()
    }

    /** 解析转录 JSON,提取所有 sentence 文本拼接。 */
    private fun parseTranscriptionJson(jsonText: String): String {
        return resultOf {
            val obj = AppJson.decodeFromString(JsonObject.serializer(), jsonText)
            val transcripts = obj["transcripts"]?.jsonArray ?: return@resultOf ""
            // 每个 transcript 含 sentences 数组,每句有 text 字段
            transcripts.joinToString("") { transcriptEl ->
                val transcript = transcriptEl.jsonObject
                val sentences = transcript["sentences"]?.jsonArray ?: return@joinToString ""
                sentences.joinToString("") { sentenceEl ->
                    sentenceEl.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: ""
                }
            }
        }.onError { msg, t -> Logger.w(TAG, "parseTranscriptionJson 失败: $msg", t) }
            .getOrNull() ?: ""
    }

    private data class TaskStatus(
        val taskStatus: String,
        val message: String,
        val result: TranscriptionResult?,
    )

    private data class TranscriptionResult(
        val transcriptionUrl: String,
        val audioDurationMs: Long,
    )
}
