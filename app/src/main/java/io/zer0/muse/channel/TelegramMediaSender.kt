package io.zer0.muse.channel

import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Telegram 媒体发送：sendPhoto / sendVideo / sendAudio / sendDocument。 */
internal object TelegramMediaSender {
    private const val TAG = "TelegramMedia"

    suspend fun send(config: ChannelConfig, media: OutboundMedia, targetOverride: String?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val token = config.appSecret.trim()
            require(token.isNotBlank()) { "Telegram Bot Token 未配置" }
            val chatId = targetOverride?.takeIf { it.isNotBlank() } ?: config.targetId
            require(chatId.isNotBlank()) { "缺少 chat_id" }
            val (method, field) = methodOf(media.kind)
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
            if (media is OutboundMedia.RemoteUrl) {
                body.addFormDataPart(field, media.url)
            } else {
                val payload = MediaPayload.from(media) ?: error("无法读取媒体内容")
                body.addFormDataPart(
                    field,
                    payload.fileName,
                    payload.bytes.toRequestBody(payload.mimeType.toMediaTypeOrNull()),
                )
            }
            val req = Request.Builder()
                .url("https://api.telegram.org/bot$token/$method")
                .post(body.build())
                .build()
            MediaHttp.postAndCheck(req, "Telegram")
        }.onFailure { e -> Logger.w(TAG, "Telegram 媒体发送失败: ${e.message}") }
    }

    private fun methodOf(kind: MediaKind): Pair<String, String> = when (kind) {
        MediaKind.IMAGE -> "sendPhoto" to "photo"
        MediaKind.VIDEO -> "sendVideo" to "video"
        MediaKind.AUDIO -> "sendAudio" to "audio"
        MediaKind.DOCUMENT -> "sendDocument" to "document"
    }
}
