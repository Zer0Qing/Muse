package io.zer0.ai.image

import io.zer0.common.ErrorCode
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.common.toMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * v1.0.18: Agnes AI 图片生成 Provider。
 *
 * 实现要点(参考 参考开源项目 plugins/image-gen/adapters/agnes.ts):
 *  - 端点: POST {baseUrl}/images/generations(baseUrl 默认 https://apihub.agnes-ai.com/v1)
 *  - 鉴权: Authorization: Bearer ${apiKey}
 *  - 请求体: { model, prompt, size, extra_body: { response_format: "b64_json", image: [...] } }
 *  - 尺寸映射: 比例("1:1" 等)→ 固定像素值;直接像素值若在支持集合内也接受。
 *  - 默认模型: agnes-image-2.1-flash
 *  - supportsImageEdit = true(通过 extra_body.image 传参考图)
 *  - supportsAsync = false(同步返回)
 *  - 响应解析: data[i].b64_json → base64, data[i].url → url;两者均兼容。
 *  - 错误处理: HTTP 非 2xx 解析 err.error.message。
 *
 * 与 OpenAI 兼容路径的关键差异:
 *  - 不走 /images/edits 端点,图生图通过 extra_body.image 字段传入同一 /images/generations;
 *  - response_format 放在 extra_body 内而非请求体顶层;
 *  - size 接受比例(如 "3:2"),由本 provider 映射为像素值后下发。
 */
class AgnesImageProvider(
    private val client: OkHttpClient,
) : ImageProvider {

    override val providerId: String = PROVIDER_ID
    override val supportsImageEdit: Boolean = true
    override val supportsAsync: Boolean = false

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun submit(request: ImageGenRequest): ImageSubmitResult =
        withContext(Dispatchers.IO) {
            val config = request.config
                ?: error(ErrorCode.IMAGE_API_KEY_MISSING.toMessage())
            if (config.apiKey.isBlank()) {
                error(ErrorCode.IMAGE_API_KEY_MISSING.toMessage())
            }

            val baseUrl = config.resolvedBaseUrl().trimEnd('/')
            val url = "$baseUrl/images/generations"
            val modelId = request.model.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL_ID
            val size = resolveSize(request.size)
            val refImages = request.referenceImages
                .map { resolveReferenceImage(it) }
                .takeIf { it.isNotEmpty() }

            // extra_body 放 response_format 与参考图(对齐 参考开源项目 agnes 适配器)
            val body = buildJsonObject {
                put("model", modelId)
                put("prompt", request.prompt)
                put("size", size)
                putJsonObject("extra_body") {
                    put("response_format", request.responseFormat.ifBlank { "b64_json" })
                    if (refImages != null) {
                        putJsonArray("image") {
                            refImages.forEach { add(JsonPrimitive(it)) }
                        }
                    }
                }
            }.toString()

            Logger.i(TAG, "submit: model=$modelId size=$size refs=${refImages?.size ?: 0}")

            val httpRequest = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            try {
                exec(httpRequest).use { resp ->
                    if (!resp.isSuccessful) {
                        val errBody = readBodySafely(resp)
                        val apiMsg = parseApiErrorMessage(errBody)
                        val hint = when (resp.code) {
                            401, 403 -> ErrorCode.AUTH_FAILED.toMessage()
                            429 -> ErrorCode.RATE_LIMITED.toMessage()
                            in 500..599 -> ErrorCode.SERVICE_UNAVAILABLE.toMessage()
                            else -> null
                        }
                        val msg = buildString {
                            append(ErrorCode.IMAGE_GEN_FAILED.toMessage())
                            append(" HTTP ${resp.code}")
                            hint?.let { append(" [").append(it).append("]") }
                            apiMsg?.let { append(": ").append(it) }
                            if (hint == null && apiMsg == null && errBody.isNotBlank()) {
                                append(": ").append(errBody)
                            }
                        }
                        Logger.w(TAG, "agnes image HTTP ${resp.code}")
                        error(msg)
                    }
                    val respBody = readBodySafely(resp)
                    if (respBody.isBlank()) error(ErrorCode.IMAGE_EMPTY_RESPONSE.toMessage())
                    val images = parseResponseImages(respBody)
                    if (images.isEmpty()) error(ErrorCode.IMAGE_NO_RESULTS.toMessage())
                    ImageSubmitResult(images = images, isAsync = false)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "agnes image failed: ${e.message}")
                error(ErrorCode.IMAGE_GEN_FAILED.toMessage(e.message ?: ""))
            }
        }

    override suspend fun poll(taskId: String): ImagePollResult {
        // Agnes 同步返回,不会走到此分支;兜底返回 FAILED。
        error("Agnes 图片生成不支持异步任务: $taskId")
    }

    /**
     * 解析响应中的图片列表,同时兼容 b64_json 与 url 两种返回格式。
     */
    private fun parseResponseImages(body: String): List<GeneratedImage> {
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonArray
            ?: return emptyList()
        return data.mapNotNull { item ->
            val obj = item.jsonObject
            val b64 = obj["b64_json"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val url = obj["url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            if (b64 == null && url == null) null else GeneratedImage(base64 = b64, url = url)
        }
    }

    /**
     * 尝试解析 Agnes 风格错误响应 {"error":{"message":"..."}} 或顶层 {"message":"..."}。
     */
    private fun parseApiErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        return resultOf {
            val root = json.parseToJsonElement(body).jsonObject
            root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                ?: root["message"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    private fun readBodySafely(resp: Response): String = try {
        resp.body?.string() ?: ""
    } catch (e: IOException) {
        ""
    } catch (e: IllegalStateException) {
        ""
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
        private const val TAG = "AgnesImageProvider"

        /** Provider 唯一标识。 */
        const val PROVIDER_ID = "agnes"

        /** 默认基础 URL(对齐 PresetProviders.ENDPOINT_AGNES)。 */
        const val DEFAULT_BASE_URL = "https://apihub.agnes-ai.com/v1"

        /** 默认模型 ID。 */
        const val DEFAULT_MODEL_ID = "agnes-image-2.1-flash"

        /**
         * 比例 → 像素尺寸映射(8 种)。
         *
         * 与 参考开源项目 AGNES_IMAGE_SIZES 略有差异:4:3 / 3:4 采用 1.5K 像素总量
         * (1152x896 / 896x1152),与 3:2 / 2:3 保持视觉一致性。
         */
        val SIZE_RATIOS: Map<String, String> = mapOf(
            "1:1" to "1024x1024",
            "16:9" to "1344x768",
            "21:9" to "1536x640",
            "9:16" to "768x1344",
            "4:3" to "1152x896",
            "3:4" to "896x1152",
            "3:2" to "1152x768",
            "2:3" to "768x1152",
        )

        /** 支持的像素尺寸集合(用于直接像素值校验)。 */
        val SUPPORTED_SIZES: Set<String> = SIZE_RATIOS.values.toSet()

        /**
         * 解析 size 参数:
         *  - 比例("1:1" 等)→ 映射为像素值;
         *  - 直接像素值("1024x1024")→ 在支持集合内则原样返回,否则报错;
         *  - 空值 → 默认 3:2(对齐 参考开源项目 AGNES_IMAGE_DEFAULTS.ratio)。
         */
        fun resolveSize(size: String?): String {
            if (size.isNullOrBlank()) return SIZE_RATIOS.getValue("3:2")
            val trimmed = size.trim()
            // 比例
            SIZE_RATIOS[trimmed]?.let { return it }
            // 直接像素值
            if (trimmed.matches(Regex("^\\d+x\\d+$", RegexOption.IGNORE_CASE))) {
                if (trimmed.lowercase() in SUPPORTED_SIZES) return trimmed.lowercase()
                error(ErrorCode.IMAGE_UNSUPPORTED_MODEL.toMessage("agnes size=$trimmed"))
            }
            error(ErrorCode.IMAGE_UNSUPPORTED_MODEL.toMessage("agnes size=$trimmed"))
        }

        /**
         * 把参考图引用归一化为 base64(无 data: 前缀)。
         * 支持:data:image URI(剥离前缀)、http/https URL(下载后转 base64)、本地 file 路径。
         */
        suspend fun resolveReferenceImage(ref: String): String {
            val trimmed = ref.trim()
            return when {
                trimmed.startsWith("data:") -> {
                    trimmed.substringAfter("base64,", "").ifBlank { trimmed }
                }
                trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> {
                    // ai 模块纯 JVM,直接用 java.net.URL 下载(参考图通常体积小)
                    val bytes = java.net.URL(trimmed).openStream().use { it.readBytes() }
                    if (bytes.size > MAX_REF_IMAGE_BYTES) {
                        error(ErrorCode.IMAGE_REFERENCE_TOO_LARGE.toMessage(bytes.size / 1024 / 1024))
                    }
                    java.util.Base64.getEncoder().encodeToString(bytes)
                }
                else -> {
                    val file = java.io.File(trimmed.removePrefix("file:"))
                    if (!file.exists()) error(ErrorCode.IMAGE_INVALID_URI.toMessage("ref_not_found"))
                    if (file.length() > MAX_REF_IMAGE_BYTES) {
                        error(ErrorCode.IMAGE_REFERENCE_TOO_LARGE.toMessage(file.length() / 1024 / 1024))
                    }
                    java.util.Base64.getEncoder().encodeToString(file.readBytes())
                }
            }
        }

        /** 参考图大小上限 10MB(对齐 ImageService 旧实现)。 */
        private const val MAX_REF_IMAGE_BYTES = 10L * 1024 * 1024
    }
}
