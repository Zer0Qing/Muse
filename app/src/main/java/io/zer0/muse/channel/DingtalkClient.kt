package io.zer0.muse.channel

import io.zer0.common.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * v2.0: 钉钉机器人客户端 — Stream 长连接接收 + sessionWebhook / OpenAPI 发送。
 *
 * 接收:Stream 模式(POST /v1.0/gateway/connections/open 获取 endpoint + ticket,
 * 建立 WebSocket;出站连接,免公网)。发送两条路:
 *  1. sessionWebhook — 收到消息时随消息下发,约 2 小时内有效,无需鉴权;
 *  2. OpenAPI — oauth2/accessToken + 机器人消息接口(主动发送兜底)。
 *
 * 协议注意:Stream 通道本身不能直接回复 IM 消息(只能 ACK),回复必须走上述两条路。
 */
internal object DingtalkClient {

    private val HTTP: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** WebSocket 专用客户端(保活心跳)。 */
    val WEB_SOCKET: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** Stream 连接凭据。 */
    data class StreamConnection(val endpoint: String, val ticket: String)

    /** 应用访问凭据。 */
    data class TokenInfo(val accessToken: String, val expireInSeconds: Long)

    /** 注册 Stream 连接凭证(仅订阅机器人消息回调)。 */
    suspend fun openStreamConnection(clientId: String, clientSecret: String): Result<StreamConnection> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = buildJsonObject {
                    put("clientId", clientId)
                    put("clientSecret", clientSecret)
                    putJsonArray("subscriptions") {
                        addJsonObject {
                            put("topic", "/v1.0/im/bot/messages/get")
                            put("type", "CALLBACK")
                        }
                    }
                    put("ua", "muse-app/2.0")
                }.toString()
                val resp = postJson(
                    "https://api.dingtalk.com/v1.0/gateway/connections/open",
                    body,
                    emptyMap(),
                ).getOrThrow()
                val obj = AppJson.parseToJsonElement(resp).jsonObject
                StreamConnection(
                    endpoint = obj["endpoint"]?.jsonPrimitive?.contentOrNull
                        ?: error("Stream 响应缺少 endpoint: ${resp.take(200)}"),
                    ticket = obj["ticket"]?.jsonPrimitive?.contentOrNull
                        ?: error("Stream 响应缺少 ticket: ${resp.take(200)}"),
                )
            }
        }

    /** WebSocket 连接 URL(endpoint + ticket;ticket 有效期 90 秒且仅可用一次)。 */
    fun streamUrl(connection: StreamConnection): String =
        "${connection.endpoint}?ticket=${java.net.URLEncoder.encode(connection.ticket, "UTF-8")}"

    /** 用 sessionWebhook 回复(临时地址自带会话凭据,无需鉴权)。 */
    suspend fun sendViaSessionWebhook(webhookUrl: String, text: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = buildJsonObject {
                    put("msgtype", "text")
                    putJsonObject("text") { put("content", text) }
                }.toString()
                val resp = postJson(webhookUrl, body, emptyMap()).getOrThrow()
                val obj = runCatching { AppJson.parseToJsonElement(resp).jsonObject }.getOrNull()
                val errcode = obj?.get("errcode")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                if (errcode != null && errcode != 0) {
                    error("钉钉回发失败: errcode=$errcode ${obj["errmsg"]?.jsonPrimitive?.contentOrNull.orEmpty()}")
                }
            }
        }

    /** 获取企业内部应用 access_token。 */
    suspend fun fetchAccessToken(appKey: String, appSecret: String): Result<TokenInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = buildJsonObject {
                    put("appKey", appKey)
                    put("appSecret", appSecret)
                }.toString()
                val resp = postJson(
                    "https://api.dingtalk.com/v1.0/oauth2/accessToken",
                    body,
                    emptyMap(),
                ).getOrThrow()
                val obj = AppJson.parseToJsonElement(resp).jsonObject
                TokenInfo(
                    accessToken = obj["accessToken"]?.jsonPrimitive?.contentOrNull
                        ?: error("钉钉 token 响应缺 accessToken: ${resp.take(200)}"),
                    expireInSeconds = obj["expireIn"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 7200L,
                )
            }
        }

    /** 主动发送:单聊(人与机器人会话;userId 从消息回调的 senderStaffId 获取)。 */
    suspend fun sendToUser(
        accessToken: String,
        robotCode: String,
        userId: String,
        text: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("robotCode", robotCode)
                putJsonArray("userIds") { add(userId) }
                put("msgKey", "sampleText")
                put("msgParam", buildJsonObject { put("content", text) }.toString())
            }.toString()
            val resp = postJson(
                "https://api.dingtalk.com/v1.0/robot/oToMessages/batchSend",
                body,
                mapOf("x-acs-dingtalk-access-token" to accessToken),
            ).getOrThrow()
            validateRobotResponse(resp)
        }
    }

    /** 主动发送:群聊(openConversationId 从消息回调的 conversationId 获取)。 */
    suspend fun sendToGroup(
        accessToken: String,
        robotCode: String,
        openConversationId: String,
        text: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("robotCode", robotCode)
                put("openConversationId", openConversationId)
                put("msgKey", "sampleText")
                put("msgParam", buildJsonObject { put("content", text) }.toString())
            }.toString()
            val resp = postJson(
                "https://api.dingtalk.com/v1.0/robot/groupMessages/send",
                body,
                mapOf("x-acs-dingtalk-access-token" to accessToken),
            ).getOrThrow()
            validateRobotResponse(resp)
        }
    }

    /** 钉钉机器人发送接口错误校验(成功时无 errcode / processQueryKey 正常返回)。 */
    private fun validateRobotResponse(resp: String) {
        val obj = runCatching { AppJson.parseToJsonElement(resp).jsonObject }.getOrNull() ?: return
        val code = obj["code"]?.jsonPrimitive?.contentOrNull
        if (!code.isNullOrBlank() && code != "0") {
            error("钉钉发送失败: code=$code ${obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty()}")
        }
    }

    private fun postJson(url: String, body: String, headers: Map<String, String>): Result<String> =
        runCatching {
            val builder = Request.Builder().url(url).post(body.toRequestBody(JSON_MEDIA))
            headers.forEach { (k, v) -> builder.header(k, v) }
            HTTP.newCall(builder.build()).execute().use { resp ->
                val text = resp.body.string()
                if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(300)}")
                text
            }
        }

    /** Stream 推送帧的 ACK 应答(按协议回传 messageId 与 data)。 */
    fun ackFrame(messageId: String, data: String): String = buildJsonObject {
        put("code", 200)
        put("message", "OK")
        putJsonObject("headers") {
            put("messageId", messageId)
            put("contentType", "application/json")
        }
        put("data", data)
    }.toString()
}

/**
 * v2.0: 钉钉会话 Webhook 缓存 — 收到消息时记录 sessionWebhook,回发时优先使用。
 *
 * 群聊以 conversationId 为键、单聊以 senderStaffId 为键(同时双写,回复侧无需区分);
 * 过期时间取自消息体的 sessionWebhookExpiredTime。
 */
internal object DingtalkSessionCache {
    private data class Entry(val webhookUrl: String, val expireAt: Long)

    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    fun put(keys: List<String>, webhookUrl: String, expireAt: Long) {
        if (webhookUrl.isBlank()) return
        val entry = Entry(webhookUrl, expireAt)
        keys.filter { it.isNotBlank() }.forEach { entries[it] = entry }
    }

    /** 返回未过期的 sessionWebhook;无可用值返回 null。 */
    fun get(key: String): String? =
        entries[key]?.takeIf { it.expireAt > System.currentTimeMillis() }?.webhookUrl
}
