package io.zer0.ai.video

import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * v1.137: 通用 OpenAI 兼容视频生成 Provider(重构,实现 [VideoProvider])。
 *
 * 调用端点: POST {baseUrl}{videoGenerationsPath}(默认 /videos/generations)。
 * 认证: Bearer Token。
 *
 * 该 Provider 同时兼容两类响应形态:
 *  1. 同步返回:响应体中直接包含 video URL → isAsync=false
 *  2. 异步任务:响应体中包含 task_id / id → isAsync=true,后续走 [poll] 轮询
 *
 * v1.137 改动:
 *  - 实现新的 [VideoProvider] 接口,统一 submit/poll 协议
 *  - 增加异步任务支持:解析 task_id 走轮询(GET {baseUrl}{path}/{taskId})
 *  - 移除虚假的 gpt-4o-video 默认模型(该模型实际不存在,会导致 API 调用失败)
 *  - 轮询调度上移到 [VideoGenerationService],本类只负责单次查询
 *
 * 异步查询端点: GET {baseUrl}{videoGenerationsPath}/{taskId}
 *  - 通用 OpenAI 兼容端点(DashScope / MiniMax 等)通常遵循此约定
 *  - 若供应商返回的查询 URL 不同,建议实现专门的 [VideoProvider] 而非走本通用兜底
 *
 * @param client OkHttp 客户端
 */
class GenericOpenAiVideoProvider(
    private val client: OkHttpClient,
) : VideoProvider {

    override val providerId: String = PROVIDER_ID
    override val supportsImageToVideo: Boolean = true
    override val supportsMultiFrameToVideo: Boolean = false

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 按 taskId 缓存的上下文(poll 时需要 apiKey / baseUrl / path)。
     */
    private val taskContext = ConcurrentHashMap<String, TaskContext>()

    private data class TaskContext(
        val apiKey: String,
        val baseUrl: String,
        val path: String,
        val modelName: String,
    )

    /**
     * 提交视频生成任务。
     *
     * 响应分类:
     *  - 含 video URL(无 task_id) → isAsync=false,直接返回 videoUrl
     *  - 含 task_id / id(无 video URL) → isAsync=true,缓存上下文供 poll 使用
     */
    override suspend fun submit(request: VideoGenRequest): VideoSubmitResult =
        withContext(Dispatchers.IO) {
            resultOf {
                if (request.apiKey.isBlank()) {
                    error("视频生成 API Key 为空")
                }
                if (request.prompt.isBlank()) {
                    error("Prompt 为空")
                }

                val baseUrl = (request.baseUrl ?: DEFAULT_BASE_URL).trimEnd('/')
                val path = request.videoGenerationsPath?.trim()?.trim('/')?.ifBlank { DEFAULT_PATH }
                    ?: DEFAULT_PATH
                val url = "$baseUrl/$path"

                val body = buildRequestBody(request)
                val httpRequest = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${request.apiKey}")
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                Logger.i(
                    TAG,
                    "submit: model=${request.model} baseUrl=$baseUrl path=$path",
                )

                exec(httpRequest).use { resp ->
                    val respBody = readBody(resp)
                    if (!resp.isSuccessful) {
                        val apiMsg = parseApiErrorMessage(respBody)
                        error(
                            "视频生成请求失败: HTTP ${resp.code}" +
                                (apiMsg?.let { ": $it" } ?: if (respBody.isNotBlank()) ": $respBody" else ""),
                        )
                    }

                    val root = json.parseToJsonElement(respBody).jsonObject

                    // 优先尝试同步结果:响应体中直接包含 video URL
                    val syncVideoUrl = extractVideoUrl(root)
                    if (syncVideoUrl != null) {
                        Logger.i(TAG, "submit 同步返回视频: url=$syncVideoUrl")
                        return@use VideoSubmitResult(
                            // 同步任务用 URL 派生 taskId,便于日志关联;不会进入 poll
                            taskId = "$SYNC_PREFIX${System.nanoTime()}",
                            videoUrl = syncVideoUrl,
                            isAsync = false,
                            modelName = request.model,
                        )
                    }

                    // 否则按异步任务处理:解析 task_id / id
                    val taskId = root["task_id"]?.jsonPrimitive?.content
                        ?: root["id"]?.jsonPrimitive?.content
                        ?: root["task"]?.jsonPrimitive?.content
                        ?: error("视频生成响应中未找到视频 URL 或 task_id: $respBody")

                    taskContext[taskId] = TaskContext(
                        apiKey = request.apiKey,
                        baseUrl = baseUrl,
                        path = path,
                        modelName = request.model,
                    )
                    Logger.i(TAG, "submit 异步任务: taskId=$taskId")
                    VideoSubmitResult(taskId = taskId, isAsync = true, modelName = request.model)
                }
            }.getOrThrow()
        }

    /**
     * 查询异步任务状态。
     *
     * GET {baseUrl}{path}/{taskId}
     */
    override suspend fun poll(taskId: String): VideoPollResult =
        withContext(Dispatchers.IO) {
            val r = resultOf {
                val ctx = taskContext[taskId]
                if (ctx == null) {
                    return@resultOf VideoPollResult(
                        status = PollStatus.FAILED,
                        errorMessage = "GenericOpenAi poll 缺少任务上下文(taskId=$taskId, 可能是同步任务被误调 poll)",
                    )
                }

                val url = "${ctx.baseUrl}/${ctx.path}/${taskId.encodeUrlPathSegment()}"
                val httpRequest = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${ctx.apiKey}")
                    .get()
                    .build()

                exec(httpRequest).use { resp ->
                    val respBody = readBody(resp)
                    if (!resp.isSuccessful) {
                        // HTTP 错误不直接判失败,交给上层重试机制处理
                        return@use VideoPollResult(
                            status = PollStatus.PENDING,
                            errorMessage = "GenericOpenAi poll HTTP ${resp.code}: $respBody",
                        )
                    }
                    val root = json.parseToJsonElement(respBody).jsonObject
                    val statusStr = root["status"]?.jsonPrimitive?.content?.lowercase()
                        ?: root["state"]?.jsonPrimitive?.content?.lowercase()
                        ?: "unknown"
                    val status = mapStatus(statusStr)

                    when (status) {
                        PollStatus.SUCCESS -> {
                            val videoUrl = extractVideoUrl(root)
                            taskContext.remove(taskId)
                            if (videoUrl == null) {
                                VideoPollResult(
                                    status = PollStatus.PENDING,
                                    errorMessage = "GenericOpenAi 状态为成功但未找到视频 URL: $respBody",
                                )
                            } else {
                                VideoPollResult(status = status, videoUrl = videoUrl)
                            }
                        }
                        PollStatus.FAILED -> {
                            val errMsg = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                                ?: root["message"]?.jsonPrimitive?.content
                                ?: "GenericOpenAi 视频生成失败(status=$statusStr)"
                            taskContext.remove(taskId)
                            VideoPollResult(status = status, errorMessage = errMsg)
                        }
                        PollStatus.PENDING -> VideoPollResult(status = status)
                    }
                }
            }
            when (r) {
                is io.zer0.common.Result.Success -> r.data
                is io.zer0.common.Result.Error -> VideoPollResult(
                    status = PollStatus.PENDING,
                    errorMessage = "GenericOpenAi poll 异常: ${r.throwable?.message ?: r.throwable?.toString() ?: r.message}",
                )
            }
        }

    /**
     * 构造请求体 JSON。
     *
     * v1.137: 移除虚假的 gpt-4o-video 默认模型 — model 必须由调用方传入
     * (从 ProviderConfig 的模型列表中筛选 supportsVideoOutput() 的模型)。
     */
    private fun buildRequestBody(request: VideoGenRequest): String {
        return buildJsonObject {
            if (request.model.isNotBlank()) {
                put("model", request.model)
            }
            put("prompt", request.prompt)
            if (request.duration > 0) put("duration", request.duration)
            if (request.resolution.isNotBlank()) put("resolution", request.resolution)
            val images = request.referenceImages.filter { it.isNotBlank() }
            if (images.size == 1) {
                put("image", images[0])
            } else if (images.size > 1) {
                // 多图以数组形式传递(兼容支持多图的端点)
                put("image", JsonArray(images.map { JsonPrimitive(it) }))
            }
        }.toString()
    }

    /**
     * 从响应中提取视频 URL。
     *
     * 支持格式:
     *  - {"data":[{"url":"..."}]}
     *  - {"video":{"url":"..."}}
     *  - {"url":"..."}
     *  - {"output_url":"..."} / {"video_url":"..."}
     */
    private fun extractVideoUrl(root: JsonObject): String? {
        // 直接顶层字段
        for (key in VIDEO_URL_KEYS) {
            val v = root[key]
            if (v is JsonPrimitive && v.isString) {
                val s = v.content
                if (s.startsWith("http://", true) || s.startsWith("https://", true)) return s
            }
        }
        // data 数组
        val data = root["data"]
        if (data is JsonArray) {
            for (item in data) {
                if (item is JsonObject) {
                    extractVideoUrl(item)?.let { return it }
                }
            }
        }
        // video 对象
        val video = root["video"]
        if (video is JsonObject) {
            extractVideoUrl(video)?.let { return it }
        }
        return null
    }

    private fun mapStatus(statusStr: String): PollStatus = when (statusStr) {
        "completed", "success", "succeeded", "done" -> PollStatus.SUCCESS
        "failed", "error", "cancelled", "canceled" -> PollStatus.FAILED
        else -> PollStatus.PENDING
    }

    /**
     * 尝试解析 OpenAI 风格的错误响应 {"error":{"message":"..."}} 或普通 {"message":"..."}。
     */
    private fun parseApiErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                ?: root["message"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    private fun readBody(resp: Response): String {
        val bytes = resp.body?.bytes() ?: return ""
        if (bytes.size > MAX_RESPONSE_BYTES) {
            error("视频生成响应过大: ${bytes.size} bytes")
        }
        return String(bytes, Charsets.UTF_8)
    }

    private suspend fun exec(request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            })
        }

    companion object {
        private const val TAG = "GenericOpenAiVideoProvider"

        /** Provider 唯一标识,在 [VideoProviderRegistry] 中作为通用兜底。 */
        const val PROVIDER_ID = "openai_video_generic"

        /** 默认基础 URL(未传入 baseUrl 时回退)。 */
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

        /** 默认视频生成端点路径。 */
        const val DEFAULT_PATH = "videos/generations"

        /** 同步任务 ID 前缀(用于日志关联,不会进入 poll)。 */
        private const val SYNC_PREFIX = "sync:"

        /** 响应体大小上限 1MB。 */
        private const val MAX_RESPONSE_BYTES = 1 * 1024 * 1024

        /** 视频结果 URL 候选字段(按优先级排序)。 */
        private val VIDEO_URL_KEYS = listOf("url", "video_url", "output_url")
    }
}

/** String 扩展:URL 路径段编码(避免特殊字符破坏请求)。 */
private fun String.encodeUrlPathSegment(): String =
    java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
