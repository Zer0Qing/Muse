@file:Suppress("NestedBlockDepth", "ReturnCount")

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
 * v1.137: Agnes AI 视频生成 Provider 实现。
 *
 * 对齐实现说明(既有实现 plugins/image-gen/adapters/agnes.ts):
 *
 * 端点:
 *  - 提交: POST {baseUrl}/videos        (baseUrl 默认 https://apihub.agnes-ai.com/v1)
 *  - 查询: GET  {rootBase}/agnesapi?video_id=X&model_name=Y
 *          (rootBase 为剥离 /v1 后缀的根,即 https://apihub.agnes-ai.com)
 *
 * 认证: Bearer Token。
 *
 * 模式:
 *  - text2video:        无参考图
 *  - image2video:       单张参考图(image 字段)
 *  - multiframe2video:  2+ 张参考图(extra_body.image 数组)
 *
 * 帧数约束([resolveNumFrames]):
 *  - num_frames 必须满足 8n+1,范围 [81, 441](对应 3-18s @ 1-60fps)
 *  - 调用方传入的 numFrames 若不满足约束,会自动校正到最近的合法值
 *
 * 状态映射([mapStatus]):
 *  - failed / error / cancelled / canceled → [PollStatus.FAILED]
 *  - completed / success / succeeded / done → [PollStatus.SUCCESS]
 *  - 其他 → [PollStatus.PENDING]
 *
 * 结果提取([extractVideoUrl]):
 *  - 依次尝试 remixed_from_video_id / video_url / url / output_url 字段
 *  - 兼容嵌套 data 数组
 *
 * @param client OkHttp 客户端(由 Koin 注入 named("chat"))
 */
class AgnesVideoProvider(
    private val client: OkHttpClient,
) : VideoProvider {

    override val providerId: String = PROVIDER_ID
    override val supportsImageToVideo: Boolean = true
    override val supportsMultiFrameToVideo: Boolean = true

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 按 taskId 缓存的上下文(poll 时需要 apiKey / modelName)。
     *
     * key = submit 返回的 taskId,value = 该任务的 apiKey 与提交时使用的模型名。
     * 任务终态后由 [VideoGenerationService] 的清理逻辑兜底(此处仅 put,不主动 remove,
     * 因为同一 taskId 不会被复用,条目数有界)。
     */
    private val taskContext = ConcurrentHashMap<String, TaskContext>()

    private data class TaskContext(val apiKey: String, val modelName: String, val baseUrl: String)

    /**
     * 提交视频生成任务。
     *
     * 根据 [VideoGenRequest.referenceImages] 数量自动选择模式:
     *  - 0 张 → text2video
     *  - 1 张 → image2video(image 字段)
     *  - 2+ 张 → multiframe2video(extra_body.image 数组)
     */
    /** R-TEST-20: Agnes 视频请求体构造(纯 DTO,便于测试)。 */
    internal fun buildRequestBody(request: VideoGenRequest, numFrames: Int): JsonObject {
        val images = request.referenceImages.filter { it.isNotBlank() }
        return buildJsonObject {
            put("model", request.model.ifBlank { DEFAULT_MODEL })
            put("prompt", request.prompt)
            put("width", request.width)
            put("height", request.height)
            put("frame_rate", request.frameRate)
            put("num_frames", numFrames)
            when {
                images.size == 1 -> put("image", images[0])
                images.size > 1 -> {
                    put("extra_body", buildJsonObject {
                        put("image", JsonArray(images.map { JsonPrimitive(it) }))
                    })
                }
            }
        }
    }

    override suspend fun submit(request: VideoGenRequest): VideoSubmitResult =
        withContext(Dispatchers.IO) {
            resultOf {
                if (request.apiKey.isBlank()) {
                    error("Agnes API key 为空")
                }
                if (request.prompt.isBlank()) {
                    error("Prompt 为空")
                }

                val model = request.model.ifBlank { DEFAULT_MODEL }
                val base = agnesV1Base(request.baseUrl)
                val url = "$base/videos"

                // 校正帧数到 8n+1 合法值
                val numFrames = resolveNumFrames(request.numFrames, request.frameRate, request.duration)

                val body = buildRequestBody(request, numFrames)

                val httpRequest = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${request.apiKey}")
                    .header("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                Logger.i(
                    TAG,
                    "submit: model=$model size=${request.width}x${request.height} " +
                        "frameRate=${request.frameRate} numFrames=$numFrames images=" +
                            "${request.referenceImages.count { it.isNotBlank() }}",
                )

                exec(httpRequest).use { resp ->
                    val respBody = readBody(resp)
                    if (!resp.isSuccessful) {
                        val apiMsg = parseApiErrorMessage(respBody)
                        error(
                            "Agnes submit 失败: HTTP ${resp.code}" +
                                (apiMsg?.let { ": $it" } ?: if (respBody.isNotBlank()) ": $respBody" else ""),
                        )
                    }
                    val root = json.parseToJsonElement(respBody).jsonObject
                    // 优先取 video_id,其次 task_id / id
                    val taskId = root["video_id"]?.jsonPrimitive?.content
                        ?: root["task_id"]?.jsonPrimitive?.content
                        ?: root["id"]?.jsonPrimitive?.content
                        ?: error("Agnes submit 响应缺少 video_id/task_id: $respBody")

                    // 同步返回检查:部分场景下完成态可能直接返回视频 URL
                    val syncVideoUrl = extractVideoUrl(root)
                    if (syncVideoUrl != null) {
                        Logger.i(TAG, "submit 同步返回视频: taskId=$taskId")
                        return@use VideoSubmitResult(
                            taskId = taskId,
                            videoUrl = syncVideoUrl,
                            isAsync = false,
                            modelName = model,
                        )
                    }

                    taskContext[taskId] = TaskContext(
                        apiKey = request.apiKey,
                        modelName = model,
                        // 审计修复 (7.6): 存 baseUrl,poll 用同一端点(原 poll 硬编码默认域名,
                        // 用户配置自建代理/中转时提交成功但轮询打到默认域名,任务永远查不到)
                        baseUrl = agnesRootBase(request.baseUrl),
                    )
                    Logger.i(TAG, "submit 成功: taskId=$taskId")
                    VideoSubmitResult(taskId = taskId, isAsync = true, modelName = model)
                }
            }.getOrThrow()
        }

    /**
     * 查询任务状态。
     *
     * GET {rootBase}/agnesapi?video_id=X&model_name=Y
     * rootBase 为 baseUrl 剥离 /v1 后缀。
     */
    override suspend fun poll(taskId: String): VideoPollResult =
        withContext(Dispatchers.IO) {
            val r = resultOf {
                val ctx = taskContext[taskId]
                val apiKey = ctx?.apiKey ?: ""
                val modelName = ctx?.modelName ?: ""
                if (apiKey.isBlank()) {
                    return@resultOf VideoPollResult(
                        status = PollStatus.FAILED,
                        errorMessage = "Agnes poll 缺少 apiKey(taskId=$taskId, 任务上下文已丢失)",
                    )
                }

                val rootBase = ctx?.baseUrl ?: agnesRootBase(null)
                val queryBuilder = StringBuilder("?video_id=").append(urlEncode(taskId))
                if (modelName.isNotBlank()) {
                    queryBuilder.append("&model_name=").append(urlEncode(modelName))
                }
                val url = "$rootBase/agnesapi$queryBuilder"

                val httpRequest = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $apiKey")
                    .get()
                    .build()

                exec(httpRequest).use { resp ->
                    val respBody = readBody(resp)
                    if (!resp.isSuccessful) {
                        // HTTP 错误不直接判失败,交给上层重试机制处理(连续 5 次才失败)
                        return@use VideoPollResult(
                            status = PollStatus.PENDING,
                            errorMessage = "Agnes poll HTTP ${resp.code}: $respBody",
                        )
                    }
                    val root = json.parseToJsonElement(respBody).jsonObject
                    val statusStr = root["status"]?.jsonPrimitive?.content?.lowercase() ?: "unknown"
                    val status = mapStatus(statusStr)

                    when (status) {
                        PollStatus.SUCCESS -> {
                            val videoUrl = extractVideoUrl(root)
                                ?: return@use VideoPollResult(
                                    status = PollStatus.PENDING,
                                    errorMessage = "Agnes 状态为成功但未找到视频 URL: $respBody",
                                )
                            // 成功后清理上下文
                            taskContext.remove(taskId)
                            VideoPollResult(status = status, videoUrl = videoUrl)
                        }
                        PollStatus.FAILED -> {
                            val errMsg = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                                ?: root["message"]?.jsonPrimitive?.content
                                ?: "Agnes 视频生成失败(status=$statusStr)"
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
                    errorMessage = "Agnes poll 异常: ${r.throwable?.message ?: r.throwable?.toString() ?: r.message}",
                )
            }
        }

    // ── 帧数约束 ─────────────────────────────────────────────────────────────

    /**
     * 校正 numFrames 到合法的 8n+1 值,范围 [81, 441]。
     *
     *  - 若传入值已合法,直接使用
     *  - 否则向下取最近的 8n+1,小于下限则取下限(81),大于上限则取上限(441)
     *
     * 实现说明:既有实现 plugins/image-gen/adapters/agnes.ts resolveVideoFrameCount
     */
    private fun resolveNumFrames(numFrames: Int, frameRate: Int, duration: Int): Int {
        // 若 numFrames 已是合法的 8n+1 且在范围内,直接用
        if (numFrames in MIN_FRAMES..MAX_FRAMES && (numFrames - 1) % 8 == 0) {
            return numFrames
        }
        // 否则按 duration * frameRate + 1 推导目标帧数
        val rate = if (frameRate in 1..60) frameRate else DEFAULT_FRAME_RATE
        val dur = if (duration in 3..18) duration else DEFAULT_DURATION
        val target = dur * rate + 1
        return clampToValidFrames(target)
    }

    /**
     * 把任意帧数 clamp 到最近的合法 8n+1 值。
     */
    private fun clampToValidFrames(target: Int): Int {
        if (target <= MIN_FRAMES) return MIN_FRAMES
        if (target >= MAX_FRAMES) return MAX_FRAMES
        // (n - 1) / 8 取整,再 *8 + 1
        val n = (target - 1) / 8
        val candidate = n * 8 + 1
        return if (candidate in MIN_FRAMES..MAX_FRAMES) candidate else MIN_FRAMES
    }

    // ── 状态映射 ─────────────────────────────────────────────────────────────

    internal fun mapStatus(statusStr: String): PollStatus = when (statusStr) {
        "failed", "error", "cancelled", "canceled" -> PollStatus.FAILED
        "completed", "success", "succeeded", "done" -> PollStatus.SUCCESS
        else -> PollStatus.PENDING
    }

    // ── 响应解析 ─────────────────────────────────────────────────────────────

    /**
     * 从响应中提取视频 URL。
     *
     * 依次尝试: remixed_from_video_id / video_url / url / output_url
     * 兼容嵌套 data 数组(递归查找)。
     */
    internal fun extractVideoUrl(root: JsonObject): String? {
        for (key in VIDEO_URL_KEYS) {
            val v = root[key]
            if (v is JsonPrimitive && v.isString) {
                val s = v.content
                if (s.startsWith("http://", true) || s.startsWith("https://", true)) return s
            }
        }
        // 嵌套 data 数组
        val data = root["data"]
        if (data is JsonArray) {
            for (item in data) {
                if (item is JsonObject) {
                    extractVideoUrl(item)?.let { return it }
                }
            }
        }
        return null
    }

    /**
     * 尝试解析 Agnes 错误响应 {"error":{"message":"..."}} 或 {"message":"..."}。
     */
    internal fun parseApiErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                ?: root["message"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    // ── URL 工具 ─────────────────────────────────────────────────────────────

    /**
     * 把 baseUrl 规范化为带 /v1 后缀的形式。
     *  - 留空 → 默认 [DEFAULT_BASE_URL]
     *  - 已带 /v1 → 原样使用
     *  - 不带 /v1 → 追加 /v1
     */
    internal fun agnesV1Base(baseUrl: String?): String {
        val base = baseUrl?.trim()?.trimEnd('/')?.ifBlank { DEFAULT_BASE_URL } ?: DEFAULT_BASE_URL
        return if (base.endsWith("/v1", ignoreCase = true)) base else "$base/v1"
    }

    /**
     * 把 baseUrl 规范化为剥离 /v1 后缀的根。
     * 用于查询端点 /agnesapi(根路径,非 /v1)。
     */
    internal fun agnesRootBase(baseUrl: String?): String {
        return agnesV1Base(baseUrl).replace(Regex("/v1$", RegexOption.IGNORE_CASE), "")
    }

    /** 简易 URL query 值编码(仅编码特殊字符)。 */
    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    // ── HTTP 工具(与 KlingVideoProvider 一致) ───────────────────────────────

    private fun readBody(resp: Response): String {
        val bytes = resp.body?.bytes() ?: return ""
        if (bytes.size > MAX_RESPONSE_BYTES) {
            error("Agnes 响应过大: ${bytes.size} bytes")
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
        private const val TAG = "AgnesVideoProvider"

        /** Provider 唯一标识。 */
        const val PROVIDER_ID = "agnes"

        /** Agnes API 默认基础 URL。 */
        const val DEFAULT_BASE_URL = "https://apihub.agnes-ai.com/v1"

        /** 默认视频模型。 */
        const val DEFAULT_MODEL = "agnes-video-v2.0"

        /** 默认帧率。 */
        private const val DEFAULT_FRAME_RATE = 24

        /** 默认时长(秒)。 */
        private const val DEFAULT_DURATION = 5

        /** 帧数下限(8n+1,对应 3s@24fps)。 */
        private const val MIN_FRAMES = 81

        /** 帧数上限(8n+1,对应 18s@24fps)。 */
        private const val MAX_FRAMES = 441

        /** 响应体大小上限 1MB。 */
        private const val MAX_RESPONSE_BYTES = 1 * 1024 * 1024

        /** 视频结果 URL 候选字段(按优先级排序)。 */
        private val VIDEO_URL_KEYS = listOf(
            "remixed_from_video_id",
            "video_url",
            "url",
            "output_url",
        )
    }
}
