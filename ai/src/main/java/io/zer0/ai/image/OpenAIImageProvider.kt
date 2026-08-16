package io.zer0.ai.image

import io.zer0.ai.core.ProviderHttpSupport
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.common.ErrorCode
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.common.toMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * v1.0.18: OpenAI 兼容图片生成 Provider。
 *
 * 兼容 OpenAI 官方及所有 OpenAI 兼容中转站的绘图接口。从原 ImageService 抽离,
 * 保留成熟的参数校验、重试、错误解析与参考图(multipart)逻辑。
 *
 * 端点:
 *  - 文生图: POST {baseUrl}/images/generations
 *  - 图生图: POST {baseUrl}/images/edits(gpt-image 系列 / dall-e-2)
 *
 * 模型行为差异:
 *  - DALL-E 3: 固定尺寸 1024x1024 / 1792x1024 / 1024x1792,不支持参考图;
 *  - DALL-E 2: 支持参考图(/images/edits),n 可达 10;
 *  - gpt-image-1: 支持参考图(/images/edits),不支持 response_format 参数。
 *
 * 路径来源: ProviderSpecificConfig.OpenAI.imagesPath(默认 /images/generations),
 * edits 路径把末尾 generations 段替换为 edits(对齐旧 ImageService 实现)。
 */
class OpenAIImageProvider(
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
            if (config.apiKey.isBlank()) error(ErrorCode.IMAGE_API_KEY_MISSING.toMessage())

            val specific = (config.resolvedSpecific() as? ProviderSpecificConfig.OpenAI)
                ?: ProviderSpecificConfig.OpenAI()
            val effectiveModelId = request.model.takeIf { it.isNotBlank() }
                ?: specific.imageModel.takeIf { it.isNotBlank() }
                ?: ImageModelCatalog.DEFAULT_MODEL_ID
            val model = ImageModelCatalog.resolveById(effectiveModelId)

            // 参数校验(复用旧 ImageService.validateParams 逻辑)
            val validated = validateParams(request, effectiveModelId, model)
            val hasReference = validated.referenceImages.isNotEmpty()
            if (hasReference && model != null && !model.supportsReferenceImage) {
                error(ErrorCode.IMAGE_UNSUPPORTED_MODEL.toMessage(effectiveModelId))
            }

            // 端点路径:优先 ProviderSpecificConfig.OpenAI.imagesPath
            val generationsPath = specific.imagesPath.trim().trim('/').ifBlank { "images/generations" }
            val editsPath = if (generationsPath.endsWith("generations")) {
                generationsPath.removeSuffix("generations").trimEnd('/') + "/edits"
            } else {
                "images/edits"
            }
            val path = if (hasReference) editsPath else generationsPath
            val baseUrl = config.resolvedBaseUrl()
            val url = "${baseUrl.trimEnd('/')}/$path"

            Logger.i(TAG, "submit: model=$effectiveModelId size=${validated.size} n=${validated.n} ref=$hasReference")

            val httpRequest = if (hasReference) {
                buildEditsRequest(url, config.apiKey, validated, model)
            } else {
                buildGenerationsRequest(url, config.apiKey, validated, model)
            }

            try {
                execWithRetry(httpRequest).use { resp ->
                    if (!resp.isSuccessful) {
                        val body = ProviderHttpSupport.readBodyCapped(resp)
                        val openAiMsg = parseOpenAiError(body)
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
                            openAiMsg?.let { append(": ").append(it) }
                            if (hint == null && openAiMsg == null && body.isNotBlank()) {
                                append(": ").append(body)
                            }
                        }
                        Logger.w(TAG, "openai image HTTP ${resp.code}")
                        error(msg)
                    }
                    val declaredLen = resp.body?.contentLength() ?: -1L
                    if (declaredLen > MAX_RESPONSE_BODY_BYTES) {
                        error(ErrorCode.IMAGE_RESPONSE_TOO_LARGE.toMessage(declaredLen / 1024 / 1024))
                    }
                    val respBody = ProviderHttpSupport.readBodySafely(resp)
                    if (respBody.isBlank()) error(ErrorCode.IMAGE_EMPTY_RESPONSE.toMessage())
                    val root = json.parseToJsonElement(respBody).jsonObject
                    val data = root["data"]?.jsonArray
                        ?: error(ErrorCode.INVALID_RESPONSE.toMessage("missing_data"))
                    if (data.isEmpty()) error(ErrorCode.IMAGE_NO_RESULTS.toMessage())
                    val mime = model?.outputMime ?: "image/png"
                    val images = data.mapNotNull { item ->
                        val obj = item.jsonObject
                        // v1.0.75 fix (生图链路): 过滤 JSON null 字段(JsonNull.content 返回 "null" 字符串,
                        // takeIf isNotBlank 会放行 → 拼出 base64,null 假图)
                        val b64 = obj["b64_json"]?.jsonPrimitive?.content
                            ?.takeIf { it.isNotBlank() && it != "null" }
                        val url = obj["url"]?.jsonPrimitive?.content
                            ?.takeIf { it.isNotBlank() && it != "null" }
                        if (b64 == null && url == null) null else GeneratedImage(base64 = b64, url = url)
                    }
                    // 保留 mime 在 base64 字段中,由 ImageService 拼 data URI 时剥离
                    val withMime = images.map { img ->
                        if (img.base64 != null) img.copy(base64 = "$mime|${img.base64}") else img
                    }
                    ImageSubmitResult(images = withMime, isAsync = false)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "openai image failed: ${e.message}")
                error(ErrorCode.IMAGE_GEN_FAILED.toMessage(e.message ?: ""))
            }
        }

    override suspend fun poll(taskId: String): ImagePollResult {
        // OpenAI 图片生成同步返回,不支持异步任务
        error("OpenAI 图片生成不支持异步任务: $taskId")
    }

    /**
     * 参数校验(迁移自旧 ImageService.validateParams)。
     * 把 [ImageGenRequest] 规范化为带模型能力约束的 [ValidatedParams]。
     */
    private fun validateParams(
        request: ImageGenRequest,
        modelId: String,
        model: ImageModel?,
    ): ValidatedParams {
        if (model == null) {
            return ValidatedParams(
                prompt = request.prompt,
                model = modelId,
                size = request.size.ifBlank { "1024x1024" },
                quality = request.quality,
                style = request.style,
                n = 1,
                referenceImages = request.referenceImages,
                responseFormat = request.responseFormat,
            )
        }
        val size = request.size.takeIf { it in model.supportedSizes } ?: model.defaultSize
        val quality = request.quality
            .takeIf { it.isBlank() || it in model.supportedQualities }
            ?: model.defaultQuality
        val style = request.style
            .takeIf { it.isBlank() || it in model.supportedStyles }
            ?: model.defaultStyle
        val responseFormat = when {
            !model.supportsResponseFormatParam -> ""
            !model.supportsB64Json && request.responseFormat == "b64_json" -> "url"
            else -> request.responseFormat
        }
        return ValidatedParams(
            prompt = request.prompt,
            model = modelId,
            size = size,
            quality = quality,
            style = style,
            n = request.n.coerceIn(1, model.maxN),
            referenceImages = request.referenceImages,
            responseFormat = responseFormat,
        )
    }

    private fun buildGenerationsRequest(
        url: String,
        apiKey: String,
        params: ValidatedParams,
        model: ImageModel?,
    ): Request {
        val body = buildGenerationsBody(
            prompt = params.prompt,
            n = params.n,
            size = params.size,
            model = params.model,
            quality = params.quality,
            style = params.style,
            responseFormat = params.responseFormat,
        )

        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
    }

    private suspend fun buildEditsRequest(
        url: String,
        apiKey: String,
        params: ValidatedParams,
        model: ImageModel?,
    ): Request {
        val refUri = params.referenceImages.first()
        val imageBytes = resolveImageBytes(refUri)
            ?: error(ErrorCode.IMAGE_REFERENCE_DOWNLOAD_FAILED.toMessage("no_bytes"))
        val mime = inferImageMime(imageBytes)
        val ext = mime.substringAfter('/')
        val imageBody = imageBytes.toRequestBody(mime.toMediaType())
        val promptBody = params.prompt.toRequestBody("text/plain".toMediaType())

        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", "reference.$ext", imageBody)
            .addFormDataPart("prompt", null, promptBody)
            .addFormDataPart("n", params.n.toString())
            .addFormDataPart("size", params.size)
        if (params.responseFormat.isNotBlank()) {
            builder.addFormDataPart("response_format", params.responseFormat)
        }
        builder.addFormDataPart("model", params.model)

        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(builder.build())
            .build()
    }

    /**
     * 把参考图 URI 解析为字节数组(迁移自旧 ImageService.resolveImageBytes)。
     * 支持 http/https URL、data URI、本地 file URI。content:// 由 app 层提前转 data URI。
     */
    private suspend fun resolveImageBytes(uri: String): ByteArray? {
        return when {
            uri.startsWith("data:image") -> {
                val base64 = uri.substringAfter("base64,", "")
                if (base64.length > MAX_REF_IMAGE_BYTES * 4 / 3) {
                    error(ErrorCode.IMAGE_REFERENCE_TOO_LARGE.toMessage(base64.length / 1024 / 1024))
                }
                java.util.Base64.getDecoder().decode(base64)
            }
            uri.startsWith("content://") -> {
                error(ErrorCode.IMAGE_INVALID_URI.toMessage("content_uri"))
            }
            uri.startsWith("http") -> {
                val request = Request.Builder().url(uri).build()
                exec(request).use { resp ->
                    if (!resp.isSuccessful) {
                        error(ErrorCode.IMAGE_REFERENCE_DOWNLOAD_FAILED.toMessage("HTTP ${resp.code}"))
                    }
                    val len = resp.body?.contentLength() ?: -1L
                    if (len > MAX_REF_IMAGE_BYTES) {
                        error(ErrorCode.IMAGE_REFERENCE_TOO_LARGE.toMessage(len / 1024 / 1024))
                    }
                    val bytes = resp.body?.bytes()
                        ?: error(ErrorCode.IMAGE_REFERENCE_DOWNLOAD_FAILED.toMessage("empty"))
                    if (bytes.size > MAX_REF_IMAGE_BYTES) {
                        error(ErrorCode.IMAGE_REFERENCE_TOO_LARGE.toMessage(bytes.size / 1024 / 1024))
                    }
                    bytes
                }
            }
            else -> {
                // 审计修复 (6.4): file 分支补大小限制(其他分支都有 MAX_REF_IMAGE_BYTES 检查,
                // 唯独此处直接 readBytes,超大本地文件 OOM)。
                val file = java.io.File(uri.removePrefix("file:"))
                if (!file.exists()) return null
                if (file.length() > MAX_REF_IMAGE_BYTES) {
                    error(ErrorCode.IMAGE_REFERENCE_TOO_LARGE.toMessage(file.length() / 1024 / 1024))
                }
                file.readBytes()
            }
        }
    }

    /** 从字节头推断图片 MIME 类型(迁移自旧 ImageService.inferImageMime)。 */
    private fun inferImageMime(bytes: ByteArray): String {
        if (bytes.size < 4) return "image/png"
        return when {
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"
            bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() -> "image/gif"
            bytes.size >= 12 &&
                bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
                bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
                bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte() -> "image/webp"
            else -> "image/png"
        }
    }

    /** 尝试解析 OpenAI 错误结构 {"error":{"message","code","type"}}。 */
    private fun parseOpenAiError(body: String): String? {
        if (body.isBlank()) return null
        return resultOf {
            val root = json.parseToJsonElement(body).jsonObject
            val err = root["error"]?.jsonObject ?: return@resultOf null
            err["message"]?.jsonPrimitive?.content
        }.getOrNull()
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

    /**
     * 对超时/429 做有限重试(1 次,迁移自旧 ImageService.execWithRetry)。
     * 图片生成按次计费,仅对未产生生成的失败(超时/429)重试。
     */
    private suspend fun execWithRetry(request: Request): Response {
        try {
            val resp = exec(request)
            if (resp.code != 429) return resp
            val retryAfter = resp.header("Retry-After")?.toLongOrNull()
            resp.close()
            delay(retryAfter?.let { it * 1000L } ?: 1000L)
            return exec(request)
        } catch (e: SocketTimeoutException) {
            // 超时:重试一次
        }
        delay(1000L)
        return exec(request)
    }

    /** 校验后的参数集合(内部传递用)。 */
    private data class ValidatedParams(
        val prompt: String,
        val model: String,
        val size: String,
        val quality: String,
        val style: String,
        val n: Int,
        val referenceImages: List<String>,
        val responseFormat: String,
    )

    companion object {
        private const val TAG = "OpenAIImageProvider"

        /** R-TEST-20: 文生图请求体构造纯函数。 */
        @Suppress("LongParameterList")
        internal fun buildGenerationsBody(
            prompt: String,
            n: Int,
            size: String,
            model: String,
            quality: String,
            style: String,
            responseFormat: String,
        ): String = buildJsonObject {
            put("prompt", prompt)
            put("n", n)
            put("size", size)
            put("model", model)
            if (quality.isNotBlank()) put("quality", quality)
            if (style.isNotBlank()) put("style", style)
            if (responseFormat.isNotBlank()) put("response_format", responseFormat)
        }.toString()
        /** Provider 唯一标识。 */
        const val PROVIDER_ID = "openai"

        /** 参考图大小上限 10MB。 */
        private const val MAX_REF_IMAGE_BYTES = 10L * 1024 * 1024

        /** 成功响应体大小上限 20MB(防止 b64_json 多图峰值内存)。 */
        private const val MAX_RESPONSE_BODY_BYTES = 20L * 1024 * 1024
    }
}
