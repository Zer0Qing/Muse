package io.zer0.muse.channel

import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 飞书媒体发送：先上传换 key，再按 image/file 发送。 */
internal object FeishuMediaSender {
    private const val TAG = "FeishuMedia"

    suspend fun send(config: ChannelConfig, media: OutboundMedia, targetOverride: String?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val token = FeishuChannelSender().fetchToken(config).first
            val target = targetOverride?.takeIf { it.isNotBlank() } ?: config.targetId
            require(target.isNotBlank()) { "缺少发送目标" }
            val payload = MediaPayload.from(media) ?: error("无法读取媒体内容")
            val base = baseOf(config)
            val isImage = media.kind == MediaKind.IMAGE
            val key = upload(base, token, payload, media.kind)
            val content = if (isImage) {
                buildJsonObject { put("image_key", key) }.toString()
            } else {
                buildJsonObject { put("file_key", key) }.toString()
            }
            val body = buildJsonObject {
                put("receive_id", target)
                put("msg_type", if (isImage) "image" else "file")
                put("content", content)
            }.toString()
            val req = Request.Builder()
                .url("$base/open-apis/im/v1/messages?receive_id_type=${idTypeOf(target)}")
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody(MediaHttp.jsonMediaType))
                .build()
            MediaHttp.postAndCheck(req, "飞书")
        }.onFailure { e -> Logger.w(TAG, "飞书媒体发送失败: ${e.message}") }
    }

    private fun baseOf(config: ChannelConfig): String =
        if (config.region == "intl") "https://open.larksuite.com" else "https://open.feishu.cn"

    private fun idTypeOf(target: String): String = when {
        target.startsWith("oc_") -> "chat_id"
        target.startsWith("ou_") -> "open_id"
        target.startsWith("on_") -> "union_id"
        else -> "chat_id"
    }

    private fun upload(base: String, token: String, payload: MediaPayload, kind: MediaKind): String {
        val isImage = kind == MediaKind.IMAGE
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        if (isImage) {
            builder.addFormDataPart("image_type", "message")
        } else {
            val fileType = when (kind) {
                MediaKind.AUDIO -> "opus"
                MediaKind.VIDEO -> "mp4"
                else -> "stream"
            }
            builder.addFormDataPart("file_type", fileType)
            builder.addFormDataPart("file_name", payload.fileName)
        }
        builder.addFormDataPart(
            if (isImage) "image" else "file",
            payload.fileName,
            payload.bytes.toRequestBody(payload.mimeType.toMediaTypeOrNull()),
        )
        val url = if (isImage) "$base/open-apis/im/v1/images" else "$base/open-apis/im/v1/files"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(builder.build())
            .build()
        MediaHttp.client.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(300)}")
            val obj = AppJson.parseToJsonElement(text).jsonObject
            val code = obj["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: -1
            if (code != 0) error("飞书上传失败 code=$code")
            val data = obj["data"]?.jsonObject ?: error("上传响应缺 data")
            val key = if (isImage) "image_key" else "file_key"
            return data[key]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: error("上传响应缺 $key")
        }
    }
}
