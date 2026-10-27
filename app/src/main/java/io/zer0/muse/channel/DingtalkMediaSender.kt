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
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 钉钉媒体发送：上传换 mediaId，再通过会话 webhook 发图片。 */
internal object DingtalkMediaSender {
    private const val TAG = "DingtalkMedia"

    suspend fun send(config: ChannelConfig, media: OutboundMedia, targetOverride: String?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val target = targetOverride?.takeIf { it.isNotBlank() } ?: config.targetId
            require(target.isNotBlank()) { "缺少发送目标" }
            val webhook = DingtalkSessionCache.get(target)
                ?: error("钉钉会话已过期，需要对方重新发消息")
            val info = DingtalkClient
                .fetchAccessToken(config.appId, config.appSecret, config.dingtalkApiBase)
                .getOrThrow()
            val payload = MediaPayload.from(media) ?: error("无法读取媒体内容")
            val mediaId = upload(config, info.accessToken, payload)
            val body = buildJsonObject {
                put("msgtype", "image")
                putJsonObject("image") { put("media_id", mediaId) }
            }.toString()
            val req = Request.Builder()
                .url(webhook)
                .post(body.toRequestBody(MediaHttp.jsonMediaType))
                .build()
            MediaHttp.postAndCheck(req, "钉钉")
        }.onFailure { e -> Logger.w(TAG, "钉钉媒体发送失败: ${e.message}") }
    }

    private fun upload(config: ChannelConfig, accessToken: String, payload: MediaPayload): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "media",
                payload.fileName,
                payload.bytes.toRequestBody(payload.mimeType.toMediaTypeOrNull()),
            )
            .build()
        val req = Request.Builder()
            .url("${config.dingtalkApiBase}/media/upload")
            .header("x-acs-dingtalk-access-token", accessToken)
            .post(body)
            .build()
        MediaHttp.client.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(300)}")
            val obj = AppJson.parseToJsonElement(text).jsonObject
            return obj["mediaId"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: error("上传响应缺 mediaId")
        }
    }
}
