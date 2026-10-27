package io.zer0.muse.channel

import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 渠道入站图片下载。
 *
 * 背景：此前只有微信桥接端真正下载图片并转 base64，飞书 / QQ 等端在解析消息时
 * 直接把图片写成 `"[图片]"` 字面量。结果是模型只看到四个字，视觉辅助也拿不到字节
 * （它的前置条件是 `mediaBase64` 非空），两个症状同源。
 *
 * 这里按平台补齐「取附件标识 → 下载 → 压缩成 base64」这一段，
 * 失败一律降级成占位文本，不抛异常、不阻断接收循环。
 */
internal object ChannelImageFetcher {
    private const val TAG = "ChannelImageFetcher"

    /** 单张图片下载超时。 */
    private const val TIMEOUT_MS = 15_000L

    /**
     * 单张图片的兜底上限。
     *
     * 真实上限由各渠道的能力表决定；这里只防「能力表没声明到」的极端情况，
     * 取各渠道声明中的最大值，保证不会比平台限制更严。
     */
    private const val FALLBACK_MAX_BYTES = 50L * 1024 * 1024

    /** 取某渠道某输入方式下图片的字节上限。 */
    private fun imageLimit(platform: ChannelPlatform, mode: MediaInputMode): Long {
        val limit = platform.capabilities.mediaCapabilities.limitFor(mode, MediaKind.IMAGE)
        return if (limit > 0L) limit else FALLBACK_MAX_BYTES
    }

    private val JSON: okhttp3.MediaType =
        "application/json; charset=utf-8".toMediaType()

    private val HTTP: OkHttpClient by lazy {
        io.zer0.ai.core.ProxyProvider.applyTo(OkHttpClient.Builder())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 飞书图片：`GET {openBase}/open-apis/im/v1/images/{image_key}`，带 tenant_access_token。
     *
     * @return 压缩后的 base64；任何一步失败返回 null。
     */
    suspend fun fetchFeishu(config: ChannelConfig, imageKey: String, tokenProvider: (ChannelConfig) -> Pair<String, Long>): String? =
        withContext(Dispatchers.IO) {
            if (imageKey.isBlank()) return@withContext null
            runCatching {
                val maxBytes = imageLimit(ChannelPlatform.FEISHU, MediaInputMode.BUFFER)
                val (token, _) = tokenProvider(config)
                val base = if (config.region == "intl") {
                    "https://open.larksuite.com"
                } else {
                    "https://open.feishu.cn"
                }
                val request = Request.Builder()
                    .url("$base/open-apis/im/v1/images/$imageKey")
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                val bytes = withTimeoutOrNull(TIMEOUT_MS) {
                    HTTP.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            error("HTTP ${resp.code}")
                        }
                        val len = resp.body.contentLength()
                        if (len > maxBytes) error("图片过大: $len > $maxBytes")
                        resp.body.bytes()
                    }
                }
                if (bytes == null) {
                    Logger.w(TAG, "飞书图片下载超时")
                    return@runCatching null
                }
                ChannelMediaUtils.toCompactImageBase64(bytes)
            }.onFailure { e ->
                Logger.w(TAG, "飞书图片获取失败: ${e.message}")
            }.getOrNull()
        }

    /**
     * QQ 图片：attachments 里直接给地址，直连下载。
     *
     * 地址带时效，必须在收到消息后立即下载，不能缓存等后续处理。
     *
     * @return 压缩后的 base64；任何一步失败返回 null。
     */
    suspend fun fetchQq(imageUrl: String): String? = withContext(Dispatchers.IO) {
        if (imageUrl.isBlank()) return@withContext null
        runCatching {
            val maxBytes = imageLimit(ChannelPlatform.QQ, MediaInputMode.PUBLIC_URL)
            val request = Request.Builder().url(imageUrl).get().build()
            val bytes = withTimeoutOrNull(TIMEOUT_MS) {
                HTTP.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        error("HTTP ${resp.code}")
                    }
                    val len = resp.body.contentLength()
                    if (len > maxBytes) error("图片过大: $len > $maxBytes")
                    resp.body.bytes()
                }
            }
            if (bytes == null) {
                Logger.w("QQ", "图片下载超时")
                return@runCatching null
            }
            ChannelMediaUtils.toCompactImageBase64(bytes)
        }.onFailure { e ->
            Logger.w("QQ", "图片获取失败: ${e.message}")
        }.getOrNull()
    }

    /**

     * 从 QQ 消息的 attachments 里取图片地址。

     *

     * 兼容 `url` 与 `file_url` 两种字段写法。

     */

    fun qqImageUrl(attachment: kotlinx.serialization.json.JsonObject?): String? {
        val contentType = attachment

            ?.get("content_type")

            ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            .orEmpty()

        return if (!contentType.startsWith("image/")) {
            null
        } else {
            listOf("url", "file_url", "image_url")
                .asSequence()
                .mapNotNull { key ->
                    runCatching { attachment?.get(key)?.jsonPrimitive?.content }
                        .getOrNull()

                        ?.trim()
                }
                .firstOrNull { it.isNotBlank() }
        }
    }

    /**
     * 钉钉图片：downloadCode + access_token 换临时地址，再下载。
     *
     * @return 压缩后的 base64；任何一步失败返回 null。
     */
    suspend fun fetchDingTalk(apiBase: String, accessToken: String, downloadCode: String): String? = withContext(Dispatchers.IO) {
        if (downloadCode.isBlank() || accessToken.isBlank()) return@withContext null
        runCatching {
            val maxBytes = imageLimit(ChannelPlatform.DINGTALK, MediaInputMode.BUFFER)
            val body = "{\"downloadCode\":\"$downloadCode\"}"
            val req = Request.Builder()
                .url("$apiBase/robot/messages/download")
                .header("x-acs-dingtalk-access-token", accessToken)
                .post(body.toRequestBody(JSON))
                .build()
            val url = withTimeoutOrNull(TIMEOUT_MS) {
                HTTP.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val obj = io.zer0.common.AppJson.parseToJsonElement(resp.body.string())
                        .jsonObject
                    obj["downloadUrl"]?.jsonPrimitive?.content.orEmpty()
                }
            }
            if (url.isNullOrBlank()) {
                Logger.w(TAG, "钉钉未返回下载地址")
                return@runCatching null
            }
            val imgReq = Request.Builder().url(url).get().build()
            val bytes = withTimeoutOrNull(TIMEOUT_MS) {
                HTTP.newCall(imgReq).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val len = resp.body.contentLength()
                    if (len > maxBytes) error("图片过大: $len > $maxBytes")
                    resp.body.bytes()
                }
            }
            if (bytes == null) {
                Logger.w(TAG, "钉钉图片下载超时")
                return@runCatching null
            }
            ChannelMediaUtils.toCompactImageBase64(bytes)
        }.onFailure { e ->
            Logger.w(TAG, "钉钉图片获取失败: ${e.message}")
        }.getOrNull()
    }

    /** 从钉钉机器人回调里取图片 downloadCode。 */
    fun dingtalkDownloadCode(payload: kotlinx.serialization.json.JsonObject): String? {
        val msgType = runCatching {
            payload["msgtype"]?.jsonPrimitive?.content.orEmpty()
        }.getOrDefault("")
        if (msgType != "picture") return null
        return runCatching {
            (payload["content"] as? kotlinx.serialization.json.JsonObject)
                ?.get("downloadCode")?.jsonPrimitive?.content?.trim().orEmpty()
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * 从飞书消息 content 里取 image_key。
     *
     * content 形如 `{"image_key":"img_v2_xxx"}`。
     */
    fun feishuImageKey(contentJson: String): String? = runCatching {
        AppJson.parseToJsonElement(contentJson).jsonObject
            .get("image_key")?.jsonPrimitive?.content?.trim().orEmpty()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * 下载任意媒体字节，不做压缩。
     *
     * 图片走 [toCompactImageBase64] 会重编码，视频/文档不能这么做，
     * 所以发送路径用这个直接拿原始字节。
     */
    suspend fun downloadRaw(url: String, maxBytes: Long = 50L * 1024 * 1024): ByteArray? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        runCatching {
            val request = Request.Builder().url(url).get().build()
            withTimeoutOrNull(TIMEOUT_MS) {
                HTTP.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val len = resp.body.contentLength()
                    if (len > maxBytes) error("媒体过大: $len > $maxBytes")
                    resp.body.bytes()
                }
            }
        }.onFailure { e ->
            Logger.w(TAG, "媒体下载失败: ${e.message}")
        }.getOrNull()
    }
}
