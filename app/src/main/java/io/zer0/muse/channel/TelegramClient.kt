package io.zer0.muse.channel

import io.zer0.common.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * v2.0: Telegram Bot API 客户端 — 纯 HTTP/JSON,长轮询收消息(免公网)。
 *
 * 接入:用户在 Telegram 里找 @BotFather 创建机器人拿到 Bot Token(填 appSecret);
 * 收消息用 getUpdates 长轮询,回复用 sendMessage 发到 chat_id。
 * 与微信 ClawBot 同构:出站连接,不需要公网地址与隧道。
 */
internal object TelegramClient {

    private val HTTP: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // 长轮询服务端 hold 约 30s,读超时需大于该值
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** 一条入站消息。 */
    data class InboundMsg(val chatId: Long, val text: String, val chatType: String)

    /** getUpdates 结果。nextOffset 为下次请求应携带的 offset(最后 update_id + 1)。 */
    data class Updates(val messages: List<InboundMsg>, val nextOffset: Long?)

    /** 长轮询收消息。 */
    suspend fun getUpdates(token: String, offset: Long?, timeoutSeconds: Int = 30): Result<Updates> =
        withContext(Dispatchers.IO) {
            runCatching {
                val params = buildString {
                    append("?timeout=").append(timeoutSeconds)
                    if (offset != null) append("&offset=").append(offset)
                }
                val resp = request(
                    "GET",
                    "https://api.telegram.org/bot$token/getUpdates$params",
                ).getOrThrow()
                val obj = AppJson.parseToJsonElement(resp).jsonObject
                if (obj["ok"]?.jsonPrimitive?.booleanOrNull == false) {
                    error(
                        "Telegram getUpdates 失败: " +
                            obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                }
                val array = obj["result"] as? JsonArray ?: JsonArray(emptyList())
                var maxUpdateId: Long? = null
                val messages = mutableListOf<InboundMsg>()
                for (element in array) {
                    val update = element as? JsonObject ?: continue
                    val updateId = update["update_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    if (updateId != null && (maxUpdateId == null || updateId > maxUpdateId)) {
                        maxUpdateId = updateId
                    }
                    val message = update["message"] as? JsonObject ?: continue
                    val text = message["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (text.isBlank()) continue
                    val chat = message["chat"] as? JsonObject ?: continue
                    val chatId = chat["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: continue
                    messages += InboundMsg(
                        chatId = chatId,
                        text = text,
                        chatType = chat["type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                }
                Updates(
                    messages = messages,
                    nextOffset = maxUpdateId?.plus(1) ?: offset,
                )
            }
        }

    /** 发送文本到指定会话(chat_id 兼容数字与 @channelusername 两种形式)。 */
    suspend fun sendMessage(token: String, chatId: String, text: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = buildJsonObject {
                    put("chat_id", chatId)
                    put("text", text)
                }.toString()
                val resp = request(
                    "POST",
                    "https://api.telegram.org/bot$token/sendMessage",
                    body,
                ).getOrThrow()
                val obj = AppJson.parseToJsonElement(resp).jsonObject
                if (obj["ok"]?.jsonPrimitive?.booleanOrNull == false) {
                    error(
                        "Telegram 发送失败: " +
                            obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                }
            }
        }

    private fun request(method: String, url: String, body: String? = null): Result<String> = runCatching {
        val builder = Request.Builder().url(url)
        when (method) {
            "GET" -> builder.get()
            else -> builder.post((body ?: "{}").toRequestBody(JSON_MEDIA))
        }
        HTTP.newCall(builder.build()).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(300)}")
            text
        }
    }
}
