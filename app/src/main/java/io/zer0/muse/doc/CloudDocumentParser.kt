package io.zer0.muse.doc

import io.zer0.common.AppJson
import io.zer0.common.Result
import io.zer0.common.resultOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * CLOUD / MinerU 云端文档解析客户端。
 *
 * 协议约定(兼容常见实现):
 *  - 上传:POST multipart/form-data,字段名为 `file`
 *  - 同步返回:响应体直接是文本,或 JSON 中包含 `text/content/markdown/full_ocr` 等语义文本字段
 *  - 异步返回:响应 JSON 包含 `task_id/taskId` 等字段,随后轮询任务状态直到成功/失败/超时
 *  - MinerU 模式:endpoint 未以 `/file_parse`、`/parse`、`/upload` 结尾时自动补 `/file_parse`;
 *    轮询优先使用响应中的 result_url,否则按 `/get_task_results/{taskId}` 推断
 *
 * 只提取语义文本字段,坐标、样式等非语义内容直接丢弃。
 */
internal class CloudDocumentParser(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val maxPollAttempts: Int = MAX_POLL_ATTEMPTS,
) {

    /**
     * 上传并解析文档字节流。
     *
     * @param bytes 文件完整字节(调用方负责读取与大小限制)
     * @param fileName 上传文件名,用于 multipart 文件名与 MIME 推断
     * @param endpoint 云端 endpoint
     * @param token 可选 Token,MinerU 模式会同时作为 Bearer Token 与 `token` form 字段
     * @param mineruMode 是否为 MinerU 专用模式
     */
    fun parse(
        bytes: ByteArray,
        fileName: String,
        endpoint: String,
        token: String = "",
        mineruMode: Boolean = false,
    ): Result<String> {
        if (endpoint.isBlank()) return Result.Error("云端解析 endpoint 未配置")
        if (bytes.isEmpty()) return Result.Error("待解析文件内容为空")
        return resultOf {
            val uploadUrl = resolveUploadUrl(endpoint, mineruMode)
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, bytes.toRequestBody(guessMediaType(fileName)))
                .apply {
                    if (mineruMode && token.isNotBlank()) addFormDataPart("token", token)
                }
                .build()
            val request = Request.Builder()
                .url(uploadUrl)
                .post(multipart)
                .apply {
                    if (token.isNotBlank()) header("Authorization", "Bearer $token")
                }
                .build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("云端解析请求失败(HTTP ${response.code}): ${response.message} ${body.take(200)}")
                }
                val taskId = extractTaskId(body)
                if (taskId != null) {
                    pollResult(taskId, endpoint, body, token, mineruMode)
                } else {
                    extractResultText(body)
                        ?: error("云端解析成功，但响应中未找到可读文本: ${body.take(200)}")
                }
            }
        }
    }

    private fun pollResult(
        taskId: String,
        endpoint: String,
        initialBody: String,
        token: String,
        mineruMode: Boolean,
    ): String {
        var pollUrl = extractResultUrl(initialBody) ?: buildPollUrl(endpoint, taskId, mineruMode)
        repeat(maxPollAttempts) { attempt ->
            if (attempt > 0) Thread.sleep(pollIntervalMs)
            val text = httpClient.newCall(
                Request.Builder()
                    .url(pollUrl)
                    .apply {
                        if (token.isNotBlank()) header("Authorization", "Bearer $token")
                    }
                    .get()
                    .build(),
            ).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("云端解析轮询失败(HTTP ${response.code}): ${response.message} ${body.take(200)}")
                }
                when (classifyStatus(extractStatus(body))) {
                    CloudTaskStatus.FAILED -> error("云端解析任务失败: ${extractError(body)}")
                    CloudTaskStatus.DONE -> {
                        extractResultText(body)
                            ?: error("云端解析任务已完成，但结果为空")
                    }
                    CloudTaskStatus.RUNNING, CloudTaskStatus.UNKNOWN -> {
                        extractResultUrl(body)?.let { pollUrl = it }
                        extractResultText(body)
                    }
                }
            }
            if (text != null) return text
        }
        error("云端解析超时(已等待 ${maxPollAttempts * pollIntervalMs / 1000} 秒)")
    }

    internal fun resolveUploadUrl(endpoint: String, mineruMode: Boolean): String {
        val base = endpoint.trimEnd('/')
        if (!mineruMode) return base
        val lower = base.lowercase()
        return when {
            lower.endsWith("/file_parse") ||
                lower.endsWith("/parse") ||
                lower.endsWith("/upload") -> base
            else -> "$base/file_parse"
        }
    }

    internal fun buildPollUrl(endpoint: String, taskId: String, mineruMode: Boolean): String {
        val base = endpoint.trimEnd('/')
        if (mineruMode) {
            val lower = base.lowercase()
            return when {
                "/file_parse" in lower ->
                    base.substringBeforeLast("/file_parse", base) + "/get_task_results/$taskId"
                "/parse" in lower ->
                    base.substringBeforeLast("/parse", base) + "/get_task_results/$taskId"
                "/upload" in lower ->
                    base.substringBeforeLast("/upload", base) + "/get_task_results/$taskId"
                else -> "$base/get_task_results/$taskId"
            }
        }
        return "$base/$taskId"
    }

    internal fun extractTaskId(body: String): String? {
        return parseJsonElement(body)?.let { element ->
            findPrimitive(element, TASK_ID_KEYS)?.content?.takeIf { it.isNotBlank() }
        }
    }

    internal fun extractStatus(body: String): String? {
        return parseJsonElement(body)?.let { element ->
            findPrimitive(element, STATUS_KEYS)?.content?.takeIf { it.isNotBlank() }
        }
    }

    internal fun extractResultUrl(body: String): String? {
        return parseJsonElement(body)?.let { element ->
            findPrimitive(element, RESULT_URL_KEYS)?.content?.takeIf { it.isNotBlank() }
        }
    }

    internal fun extractResultText(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return trimmed
        return parseJsonElement(trimmed)?.let { element ->
            when (element) {
                is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }
                else -> findPrimitive(element, RESULT_TEXT_KEYS)?.content?.takeIf { it.isNotBlank() }
            }
        }
    }

    private fun parseJsonElement(body: String): JsonElement? {
        return try {
            AppJson.parseToJsonElement(body.trim())
        } catch (_: Exception) {
            null
        }
    }

    private fun findPrimitive(element: JsonElement, keys: List<String>): JsonPrimitive? {
        return when (element) {
            is JsonObject -> {
                for (key in keys) {
                    val value = element[key]
                    if (value is JsonPrimitive && value.content.isNotBlank()) return value
                }
                for (value in element.values) {
                    findPrimitive(value, keys)?.let { return it }
                }
                null
            }
            is JsonArray -> {
                for (value in element) {
                    findPrimitive(value, keys)?.let { return it }
                }
                null
            }
            else -> null
        }
    }

    private fun classifyStatus(status: String?): CloudTaskStatus {
        val normalized = status?.trim()?.lowercase()
        if (normalized.isNullOrBlank()) return CloudTaskStatus.UNKNOWN
        val code = normalized.toIntOrNull()
        if (code != null) {
            return when (code) {
                0, 200 -> CloudTaskStatus.DONE
                else -> CloudTaskStatus.FAILED
            }
        }
        return when (normalized) {
            in DONE_STATUSES -> CloudTaskStatus.DONE
            in FAILED_STATUSES -> CloudTaskStatus.FAILED
            in RUNNING_STATUSES -> CloudTaskStatus.RUNNING
            else -> CloudTaskStatus.UNKNOWN
        }
    }

    private fun extractError(body: String): String {
        return parseJsonElement(body)?.let { element ->
            findPrimitive(element, ERROR_KEYS)?.content?.takeIf { it.isNotBlank() }
        } ?: body.take(200)
    }

    private fun guessMediaType(fileName: String): MediaType {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".docx") ->
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            lower.endsWith(".pptx") ->
                "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            lower.endsWith(".xlsx") ->
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            lower.endsWith(".epub") -> "application/epub+zip"
            lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".csv") ||
                lower.endsWith(".json") || lower.endsWith(".xml") || lower.endsWith(".html") -> "text/plain"
            else -> "application/octet-stream"
        }.toMediaType()
    }

    private enum class CloudTaskStatus {
        RUNNING,
        DONE,
        FAILED,
        UNKNOWN,
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2_000L
        private const val MAX_POLL_ATTEMPTS = 60

        private val TASK_ID_KEYS = listOf("task_id", "taskId", "task-id", "batch_id", "batchId")
        private val STATUS_KEYS = listOf("status", "state", "task_status", "phase", "code")
        private val RESULT_TEXT_KEYS = listOf(
            "full_ocr",
            "md_content",
            "markdown",
            "content",
            "text",
            "parse_result",
            "parsed_text",
            "result",
            "data",
        )
        private val RESULT_URL_KEYS = listOf(
            "result_url",
            "query_url",
            "poll_url",
            "status_url",
            "resultUrl",
            "queryUrl",
            "pollUrl",
            "statusUrl",
        )
        private val ERROR_KEYS = listOf("error", "message", "detail", "reason")
        private val RUNNING_STATUSES = setOf(
            "pending",
            "queued",
            "running",
            "processing",
            "waiting",
            "created",
            "submitted",
        )
        private val DONE_STATUSES = setOf(
            "done",
            "success",
            "succeeded",
            "completed",
            "finished",
            "ok",
        )
        private val FAILED_STATUSES = setOf(
            "failed",
            "error",
            "cancelled",
            "canceled",
            "timeout",
            "exception",
        )

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
