package io.zer0.ai.video

import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
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
 * 可灵(Kling)视频生成 Provider 实现(v1.137 重构,实现 [VideoProvider])。
 *
 * API 参考: https://kling.kuaishou.com/docs
 *
 * 端点:
 *  - 文生视频: POST {baseUrl}/videos/generations/text2video
 *  - 图生视频: POST {baseUrl}/videos/generations/image2video
 *  - 查询任务: GET  {baseUrl}/videos/generations/{taskId}
 *
 * 认证: Bearer Token(API Key 直接放 Authorization 头)
 *
 * 任务状态映射([mapStatus]):
 *  - "submit" / "submitted" / "pending" → [PollStatus.PENDING]
 *  - "processing" / "running"          → [PollStatus.PENDING](仍属处理中,继续轮询)
 *  - "succeed" / "success" / "succeeds" → [PollStatus.SUCCESS]
 *  - "failed" / "fail" / "error"       → [PollStatus.FAILED]
 *  - 未知状态 → [PollStatus.FAILED](避免静默卡死)
 *
 * v1.137 改动:
 *  - 实现新的 [VideoProvider] 接口,统一 submit/poll 协议
 *  - 轮询调度上移到 [VideoGenerationService],本类只负责单次查询
 *  - 修复 providerId 路由 bug:providerId 保持 "kling",
 *    由 [VideoProviderRegistry] 按 specId/host 匹配 preset_kling / kling / 用户自定义 ID
 *  - [supportsMultiFrameToVideo] = false(Kling 仅支持单图图生视频)
 *
 * @param client OkHttp 客户端(由 Koin 注入 named("chat"))
 * @param baseUrl 可灵 API 基础 URL,默认 https://api.klingai.com/v1
 */
class KlingVideoProvider(
    private val client: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : VideoProvider {

    override val providerId: String = PROVIDER_ID
    override val supportsImageToVideo: Boolean = true
    override val supportsMultiFrameToVideo: Boolean = false

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 按 taskId 缓存的 apiKey(poll 时需要)。
     *
     * v1.137: 任务终态后由 [poll] 在 SUCCESS/FAILED 时主动清理;
     * 若上层因超时/取消不再调用 poll,条目会残留(有界,不致内存泄漏)。
     */
    private val apiKeyForQuery = ConcurrentHashMap<String, String>()

    /**
     * 提交视频生成任务。
     *
     * 根据 [VideoGenRequest.referenceImages] 是否非空选择端点:
     *  - 空 → POST /videos/generations/text2video(文生视频)
     *  - 非空(取首张) → POST /videos/generations/image2video(图生视频)
     *
     * 返回 isAsync=true,taskId 为可灵任务 ID。
     */
    override suspend fun submit(request: VideoGenRequest): VideoSubmitResult =
        withContext(Dispatchers.IO) {
            resultOf {
                if (request.apiKey.isBlank()) {
                    error("Kling API key 为空")
                }
                if (request.prompt.isBlank()) {
                    error("Prompt 为空")
                }

                val images = request.referenceImages.filter { it.isNotBlank() }
                val path = if (images.isNotEmpty()) "image2video" else "text2video"
                val url = "${baseUrl.trimEnd('/')}/videos/generations/$path"

                val body = buildRequestBody(request, images.firstOrNull())
                val httpRequest = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${request.apiKey}")
                    .header("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                Logger.i(
                    TAG,
                    "submit: model=${request.model} duration=${request.duration} " +
                        "resolution=${request.resolution} hasImage=${images.isNotEmpty()}",
                )

                exec(httpRequest).use { resp ->
                    val respBody = readBody(resp)
                    if (!resp.isSuccessful) {
                        val apiMsg = parseApiErrorMessage(respBody)
                        error(
                            "Kling submit 失败: HTTP ${resp.code}" +
                                (apiMsg?.let { ": $it" } ?: if (respBody.isNotBlank()) ": $respBody" else ""),
                        )
                    }
                    val root = json.parseToJsonElement(respBody).jsonObject
                    val code = root["code"]?.jsonPrimitive?.content
                    // 可灵 API 业务错误:code != 0
                    if (code != null && code != "0") {
                        val msg = root["message"]?.jsonPrimitive?.content ?: "unknown error"
                        error("Kling submit API error: code=$code message=$msg")
                    }
                    val data = root["data"]?.jsonObject
                        ?: error("Kling submit 响应缺少 data: $respBody")
                    val taskId = data["task_id"]?.jsonPrimitive?.content
                        ?: error("Kling submit 响应缺少 task_id: $respBody")
                    apiKeyForQuery[taskId] = request.apiKey
                    Logger.i(TAG, "submit 成功: taskId=$taskId")
                    VideoSubmitResult(taskId = taskId, isAsync = true, modelName = request.model)
                }
            }.getOrThrow()
        }

    /**
     * 查询任务状态(单次,不做轮询调度)。
     *
     * 轮询调度(自适应频率 / 错误计数)由 [VideoGenerationService] 统一处理。
     * 本方法在网络异常时返回 PENDING + errorMessage,让上层按连续错误计数处理。
     */
    override suspend fun poll(taskId: String): VideoPollResult =
        withContext(Dispatchers.IO) {
            val r = resultOf {
                val url = "${baseUrl.trimEnd('/')}/videos/generations/$taskId"
                val apiKey = apiKeyForQuery[taskId] ?: ""
                val httpRequest = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $apiKey")
                    .get()
                    .build()

                exec(httpRequest).use { resp ->
                    val respBody = readBody(resp)
                    if (!resp.isSuccessful) {
                        return@use VideoPollResult(
                            status = PollStatus.PENDING,
                            errorMessage = "Kling query HTTP ${resp.code}: $respBody",
                        )
                    }
                    val root = json.parseToJsonElement(respBody).jsonObject
                    val code = root["code"]?.jsonPrimitive?.content
                    if (code != null && code != "0") {
                        val msg = root["message"]?.jsonPrimitive?.content ?: "unknown error"
                        // 业务错误直接判失败(非瞬时网络问题)
                        apiKeyForQuery.remove(taskId)
                        return@use VideoPollResult(
                            status = PollStatus.FAILED,
                            errorMessage = "Kling query API error: code=$code message=$msg",
                        )
                    }
                    val data = root["data"]?.jsonObject
                        ?: run {
                            apiKeyForQuery.remove(taskId)
                            return@use VideoPollResult(
                                status = PollStatus.FAILED,
                                errorMessage = "Kling query 响应缺少 data: $respBody",
                            )
                        }

                    val statusStr = data["task_status"]?.jsonPrimitive?.content ?: "unknown"
                    val status = mapStatus(statusStr)

                    when (status) {
                        PollStatus.SUCCESS -> {
                            val videoUrl = data["task_result"]?.jsonObject
                                ?.get("videos")?.jsonArray
                                ?.firstOrNull()
                                ?.jsonObject
                                ?.get("url")?.jsonPrimitive?.content
                            apiKeyForQuery.remove(taskId)
                            if (videoUrl == null) {
                                VideoPollResult(
                                    status = PollStatus.FAILED,
                                    errorMessage = "Kling 任务成功但未找到视频 URL: $respBody",
                                )
                            } else {
                                VideoPollResult(status = status, videoUrl = videoUrl)
                            }
                        }
                        PollStatus.FAILED -> {
                            val failInfo = data["task_fail_info"]?.jsonObject
                            val errMsg = failInfo?.get("message")?.jsonPrimitive?.content
                                ?: "Kling 任务失败(status=$statusStr)"
                            apiKeyForQuery.remove(taskId)
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
                    errorMessage = "Kling poll 异常: ${r.throwable?.message ?: r.throwable?.toString() ?: r.message}",
                )
            }
        }

    /**
     * 构造请求体 JSON(文生视频 / 图生视频通用)。
     *
     * @param imageUrl 单张参考图(图生视频时非空)
     */
    private fun buildRequestBody(request: VideoGenRequest, imageUrl: String?): JsonObject {
        return buildJsonObject {
            put("model", request.model.ifBlank { DEFAULT_MODEL })
            put("prompt", request.prompt)
            // duration 必须是 5 或 10(可灵限制),非法则回退 5
            val duration = if (request.duration == 5 || request.duration == 10) request.duration else 5
            put("duration", duration)
            put("resolution", request.resolution.ifBlank { "720p" })
            // mode 默认 std(标准模式),pro 模式需付费更高,不暴露
            put("mode", "std")
            if (!imageUrl.isNullOrBlank()) {
                put("image", imageUrl)
            }
        }
    }

    /**
     * 把可灵 task_status 字符串映射到 [PollStatus]。
     *
     * 注意:processing / running 在新模型下统一映射为 PENDING
     * (二者都属于"未完成,继续轮询"语义,[PollStatus] 不再单独区分 PROCESSING)。
     *
     * 未知状态映射为 FAILED 并告警,避免未知状态被静默当作 PENDING 导致任务卡死。
     */
    private fun mapStatus(statusStr: String): PollStatus {
        return when (statusStr.lowercase()) {
            "submit", "submitted", "pending" -> PollStatus.PENDING
            "processing", "running" -> PollStatus.PENDING
            "succeed", "success", "succeeds" -> PollStatus.SUCCESS
            "failed", "fail", "error" -> PollStatus.FAILED
            else -> {
                Logger.w(TAG, "未知任务状态: $statusStr，映射为 FAILED")
                PollStatus.FAILED
            }
        }
    }

    /**
     * 尝试解析可灵 API 错误响应 {"code": non-zero, "message": "..."}。
     */
    private fun parseApiErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            root["message"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    /**
     * 读取响应体(限制 1MB,防止异常响应导致 OOM)。
     */
    private fun readBody(resp: Response): String {
        val bytes = resp.body?.bytes() ?: return ""
        if (bytes.size > MAX_RESPONSE_BYTES) {
            error("Kling response too large: ${bytes.size} bytes")
        }
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * 协程取消可传播的 OkHttp 请求执行(参考 ImageService.exec)。
     */
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
        private const val TAG = "KlingVideoProvider"

        /** Provider 唯一标识(由 [VideoProviderRegistry] 匹配 specId/host)。 */
        const val PROVIDER_ID = "kling"

        /** 可灵 API 默认基础 URL。 */
        const val DEFAULT_BASE_URL = "https://api.klingai.com/v1"

        /** 默认模型(可灵 v1,5 秒视频生成)。 */
        const val DEFAULT_MODEL = "kling-v1"

        /** 响应体大小上限 1MB(任务查询响应通常 < 10KB,1MB 兜底)。 */
        private const val MAX_RESPONSE_BYTES = 1 * 1024 * 1024
    }
}
