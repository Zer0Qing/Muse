package io.zer0.ai.image

import io.zer0.ai.RefImageUrlValidator
import io.zer0.ai.core.ProviderHttpSupport
import io.zer0.ai.gemini.GeminiProvider
import io.zer0.common.AppJson
import io.zer0.common.ErrorCode
import io.zer0.common.Logger
import io.zer0.common.toMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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
 * v1.0.18 后续 (B14-2): Google Gemini 原生图片生成 Provider。
 *
 * 在此之前,Gemini 配置走 [OpenAIImageProvider] 兜底(请求 /images/generations,协议不兼容必失败),
 * 或由 app 层 [io.zer0.muse.ui.chat.ImageGenCoordinator] 走 streamChat 多模态路径。
 * 本类让 Gemini 具备独立的 [ImageProvider] 实现,可经 [ImageProviderRegistry] 正常路由,
 * 与 Agnes / OpenAI 适配器一致。
 *
 * API(Google 原生图像生成,模型如 `gemini-2.5-flash-image` / Nano Banana):
 *  - 端点: POST {baseUrl}/models/{model}:generateContent(与 chat 同端点,靠 generationConfig 区分)
 *  - 请求体:
 *      { "contents":[{"role":"user","parts":[{"text": prompt}, {"inlineData":{...}}(参考图)]}],
 *        "generationConfig": { "responseModalities": ["TEXT","IMAGE"] } }
 *  - 响应: candidates[0].content.parts[].inlineData.{mimeType,data}(base64,无 data: 前缀)
 *  - 同步返回(无异步任务),supportsAsync=false
 *  - 图生图通过同端点的 inlineData 输入图实现,supportsImageEdit=true
 *
 * Vertex AI:
 *  - 端点/鉴权复用 [GeminiProvider.buildUrl] / [GeminiProvider.resolveToken],
 *    自动覆盖 服务账号(OAuth Bearer)、Express Mode(API Key)、generativelanguage(API Key)三种模式,
 *    避免两套实现随协议演进漂移。
 *
 * 参考图解析复用 [AgnesImageProvider.resolveReferenceImage](data:/http(s)/file → base64,
 * 含 SSRF 校验与大小限制);返回的 base64 带 `mimeType|` 前缀交由 [ImageService] 拼 data URI。
 */
class GeminiImageProvider(
    private val client: OkHttpClient,
    /**
     * G4: http(s) 参考图下载前的 SSRF 预校验器(与 Agnes / OpenAI 同口径),
     * 由 app 层 Koin 装配注入,null 时回退旧行为(直接下载,无逐跳校验)。
     */
    private val referenceImageUrlValidator: RefImageUrlValidator? = null,
) : ImageProvider {

    override val providerId: String = PROVIDER_ID
    override val supportsImageEdit: Boolean = true
    override val supportsAsync: Boolean = false

    override suspend fun submit(request: ImageGenRequest): ImageSubmitResult = withContext(Dispatchers.IO) {
        val config = request.config
            ?: error(ErrorCode.IMAGE_API_KEY_MISSING.toMessage())
        if (config.apiKey.isBlank() && !config.allowMissingApiKey) {
            error(ErrorCode.IMAGE_API_KEY_MISSING.toMessage())
        }
        val modelId = request.model.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL_ID

        try {
            // 参考图 → base64(无 data: 前缀):解析失败/超限/命中内网会抛业务错误,原样上抛
            val refBase64 = request.referenceImages.map { ref ->
                AgnesImageProvider.resolveReferenceImage(ref, referenceImageUrlValidator)
            }
            val aspectRatio = resolveAspectRatio(request.size)
            val body = buildImageRequestBody(request.prompt, refBase64, aspectRatio)

            // 复用 GeminiProvider 的端点/鉴权解析(含 Vertex Express / 服务号 OAuth / generativelanguage)
            val endpoint = GeminiProvider(config)
            val url = endpoint.buildUrl(model = modelId, stream = false)
            val token = endpoint.resolveToken()

            val httpRequest = Request.Builder()
                .url(url)
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            Logger.i(TAG, "submit: model=$modelId refs=${refBase64.size} vertex=${url.contains("aiplatform")}")

            exec(httpRequest).use { r ->
                if (!r.isSuccessful) {
                    val errBody = ProviderHttpSupport.readBodyCapped(r)
                    val hint = when (r.code) {
                        401, 403 -> ErrorCode.AUTH_FAILED.toMessage()
                        429 -> ErrorCode.RATE_LIMITED.toMessage()
                        in 500..599 -> ErrorCode.SERVICE_UNAVAILABLE.toMessage()
                        else -> null
                    }
                    val msg = buildString {
                        append(ErrorCode.IMAGE_GEN_FAILED.toMessage())
                        append(" HTTP ${r.code}")
                        hint?.let { append(" [").append(it).append("]") }
                        if (errBody.isNotBlank()) append(": ").append(errBody)
                    }
                    Logger.w(TAG, "gemini image HTTP ${r.code}")
                    error(msg)
                }
                // B-03: contentLength 未知(chunked)时同样限长 — 流式读取,超限即中断
                val (respBody, overLimit) = ProviderHttpSupport.readBodyCappedStreaming(r, MAX_RESPONSE_BODY_BYTES)
                if (overLimit) {
                    error(ErrorCode.IMAGE_RESPONSE_TOO_LARGE.toMessage(MAX_RESPONSE_BODY_BYTES / 1024 / 1024))
                }
                if (respBody.isBlank()) error(ErrorCode.IMAGE_EMPTY_RESPONSE.toMessage())
                // 提示级安全拦截:给出明确原因而非"无结果"
                val blockReason = runCatching {
                    AppJson.parseToJsonElement(respBody).jsonObject["promptFeedback"]
                        ?.jsonObject?.get("blockReason")?.jsonPrimitive?.content
                }.getOrNull()
                if (!blockReason.isNullOrBlank()) {
                    error(ErrorCode.IMAGE_GEN_FAILED.toMessage("safety: $blockReason"))
                }
                val images = parseInlineImages(respBody)
                if (images.isEmpty()) error(ErrorCode.IMAGE_NO_RESULTS.toMessage())
                ImageSubmitResult(images = images, isAsync = false)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            // error() 抛出的业务错误原样传播
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "gemini image failed: ${e.message}")
            error(ErrorCode.IMAGE_GEN_FAILED.toMessage(e.message ?: ""))
        }
    }

    override suspend fun poll(taskId: String): ImagePollResult {
        error("Gemini 图片生成不支持异步任务: $taskId")
    }

    /**
     * 构造 generateContent 请求体。
     *
     * 参考图以 inlineData part 插入(文本在前,与 Gemini 推荐顺序一致),
     * responseModalities=["TEXT","IMAGE"] 开启图片输出。
     */
    internal fun buildImageRequestBody(prompt: String, referenceImagesBase64: List<String>, aspectRatio: String? = null): String {
        val parts = buildJsonArray {
            add(buildJsonObject { put("text", prompt) })
            referenceImagesBase64.forEach { b64 ->
                add(
                    buildJsonObject {
                        putJsonObject("inlineData") {
                            put("mimeType", inferMimeType(b64))
                            put("data", b64)
                        }
                    },
                )
            }
        }
        return buildJsonObject {
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("parts", parts)
                    },
                )
            }
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") {
                    add(JsonPrimitive("TEXT"))
                    add(JsonPrimitive("IMAGE"))
                }
                if (!aspectRatio.isNullOrBlank()) {
                    putJsonObject("imageConfig") {
                        put("aspectRatio", aspectRatio)
                    }
                }
            }
        }.toString()
    }

    /**
     * 解析 candidates[0].content.parts[].inlineData 为图片列表。
     *
     * 与 Agnes / OpenAI 一致:只接受字符串标量,过滤 JSON null / 数字 / 布尔 / 空串。
     * 返回的 base64 带 `mimeType|` 前缀([ImageService] 拼 data URI 时剥离)。
     */
    internal fun parseInlineImages(body: String): List<GeneratedImage> {
        val root = AppJson.parseToJsonElement(body).jsonObject
        val parts = root["candidates"]?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray
            ?: return emptyList()
        return parts.mapNotNull { part ->
            val inline = part.jsonObject["inlineData"]?.jsonObject ?: return@mapNotNull null
            val data = (inline["data"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content?.takeIf { it.isNotBlank() && it != "null" }
                ?: return@mapNotNull null
            val mime = (inline["mimeType"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content?.takeIf { it.isNotBlank() }
                ?: "image/png"
            GeneratedImage(base64 = "$mime|$data")
        }
    }

    /** 从 base64 头部 magic bytes 推断图片 MIME(readReferenceImage 不携带类型信息)。 */
    private fun inferMimeType(base64: String): String {
        val head = base64.take(16).uppercase()
        return when {
            head.startsWith("IVBORW0") -> "image/png" // PNG: iVBORw0K
            head.startsWith("/9J/") -> "image/jpeg" // JPEG: /9j/
            head.startsWith("UKLGR") -> "image/webp" // WebP: UklGR
            head.startsWith("R0LGOD") -> "image/gif" // GIF: R0lGOD
            else -> "image/png"
        }
    }

    private suspend fun exec(request: Request): Response = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
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
        private const val TAG = "GeminiImageProvider"

        /** Provider 唯一标识。 */
        const val PROVIDER_ID = "gemini"

        /**
         * 默认绘图模型: Gemini 2.5 Flash Image(代号 Nano Banana)。
         * 仅当调用方未显式传入 model、且 ProviderConfig.models 中也没有图片输出模型时兜底。
         */
        const val DEFAULT_MODEL_ID = "gemini-2.5-flash-image"

        /** 成功响应体上限 32MB(inlineData base64 体积较大)。 */
        private const val MAX_RESPONSE_BODY_BYTES = 32 * 1024 * 1024

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val DIRECT_ASPECT_RATIOS = setOf("1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3", "21:9")

        private val SIZE_TO_ASPECT_RATIO = mapOf(
            "1024x1024" to "1:1",
            "1536x1536" to "1:1",
            "1792x1024" to "16:9",
            "1024x1792" to "9:16",
            "1536x1024" to "3:2",
            "1024x1536" to "2:3",
        )

        /** Map explicit aspect ratios or common pixel sizes into Gemini imageConfig.aspectRatio. */
        internal fun resolveAspectRatio(size: String): String? {
            val trimmed = size.trim()
            if (trimmed in DIRECT_ASPECT_RATIOS) return trimmed
            return SIZE_TO_ASPECT_RATIO[trimmed]
        }
    }
}
