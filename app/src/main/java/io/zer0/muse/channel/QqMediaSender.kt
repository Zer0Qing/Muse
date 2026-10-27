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

/** QQ 媒体发送：先上传换 file_info，再按 msg_type=7 发送。 */
internal object QqMediaSender {
    private const val TAG = "QqMedia"

    /** 上传所需的全部参数。 */
    private data class Upload(
        val appId: String,
        val accessToken: String,
        val path: String,
        val target: String,
        val fileType: Int,
        val payload: MediaPayload,
    )

    suspend fun send(config: ChannelConfig, media: OutboundMedia, targetOverride: String?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val info = QqClient.fetchAccessToken(config.appId, config.appSecret).getOrThrow()
            val overrideTarget = targetOverride?.takeIf { it.isNotBlank() }
            val rawTarget = overrideTarget ?: config.targetId
            val isGroup = if (overrideTarget != null) {
                overrideTarget.startsWith("group:")
            } else {
                config.targetType == "group"
            }
            val target = rawTarget.removePrefix("group:")
            val payload = MediaPayload.from(media) ?: error("无法读取媒体内容")
            val path = if (isGroup) "groups" else "users"
            val fileInfo = upload(
                Upload(
                    appId = config.appId,
                    accessToken = info.accessToken,
                    path = path,
                    target = target,
                    fileType = fileTypeOf(media.kind),
                    payload = payload,
                ),
            )
            val body = buildJsonObject {
                put("msg_type", 7)
                putJsonObject("media") { put("file_info", fileInfo) }
            }.toString()
            val req = Request.Builder()
                .url("${QqClient.API_BASE}/v2/$path/$target/messages")
                .header("Authorization", "QQBot ${info.accessToken}")
                .header("X-Union-Appid", config.appId)
                .post(body.toRequestBody(MediaHttp.jsonMediaType))
                .build()
            MediaHttp.postAndCheck(req, "QQ")
        }.onFailure { e -> Logger.w(TAG, "QQ 媒体发送失败: ${e.message}") }
    }

    /** QQ 富媒体类型：1 图片 / 2 视频 / 3 语音 / 4 文件。 */
    private fun fileTypeOf(kind: MediaKind): Int = when (kind) {
        MediaKind.IMAGE -> 1
        MediaKind.VIDEO -> 2
        MediaKind.AUDIO -> 3
        MediaKind.DOCUMENT -> 4
    }

    private fun upload(spec: Upload): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file_type", spec.fileType.toString())
            .addFormDataPart("file_name", spec.payload.fileName)
            .addFormDataPart(
                "file_data",
                spec.payload.fileName,
                spec.payload.bytes.toRequestBody(spec.payload.mimeType.toMediaTypeOrNull()),
            )
            .build()
        val req = Request.Builder()
            .url("${QqClient.API_BASE}/v2/${spec.path}/${spec.target}/files")
            .header("Authorization", "QQBot ${spec.accessToken}")
            .header("X-Union-Appid", spec.appId)
            .post(body)
            .build()
        MediaHttp.client.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(300)}")
            val obj = AppJson.parseToJsonElement(text).jsonObject
            return (obj["file_info"] ?: obj["file_uuid"] ?: obj["id"])
                ?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: error("上传响应缺 file_info: ${text.take(200)}")
        }
    }
}
