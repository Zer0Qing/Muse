package io.zer0.muse.channel

import io.zer0.common.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * v2.0.1: QQ 机器人客户端 — access_token / WSS Gateway / 被动回复缓存。
 *
 * 接收:WebSocket Gateway 长连接(免公网,见 [QqReceiver]);
 * 发送:v2 HTTP API(见 QqChannelSender;被动回复携带 msg_id + 递增 msg_seq)。
 */
internal object QqClient {

    /** v2 API 基址。 */
    const val API_BASE = "https://api.sgroup.qq.com"

    /** access_token 获取端点。 */
    private const val TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken"

    private val HTTP: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** WebSocket 专用客户端(长连接保活)。 */
    val WEB_SOCKET: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    data class TokenInfo(val accessToken: String, val expiresInSeconds: Long)

    /** 获取 access_token(QQBot 鉴权用)。 */
    suspend fun fetchAccessToken(appId: String, appSecret: String): Result<TokenInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = buildJsonObject {
                    put("appId", appId)
                    put("clientSecret", appSecret)
                }.toString()
                val resp = postJson(TOKEN_URL, body).getOrThrow()
                val obj = AppJson.parseToJsonElement(resp).jsonObject
                TokenInfo(
                    accessToken = obj["access_token"]?.jsonPrimitive?.contentOrNull
                        ?: error("QQ token 响应缺字段: ${resp.take(200)}"),
                    expiresInSeconds = obj["expires_in"]?.jsonPrimitive?.contentOrNull
                        ?.toLongOrNull() ?: 7200L,
                )
            }
        }

    /** 获取通用 WSS 接入点(AUTHORIZATION: QQBot {access_token})。 */
    suspend fun getGateway(accessToken: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                // 注意:此处是 REST 调 `/gateway` 取 wss 地址;`/websocket/` 是 WS 端点本身
                // (直接 HTTP GET 会被返回 426 Upgrade Required)。
                val resp = getJson(
                    "$API_BASE/gateway",
                    mapOf("Authorization" to "QQBot $accessToken"),
                ).getOrThrow()
                val obj = AppJson.parseToJsonElement(resp).jsonObject
                obj["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: error("QQ gateway 响应缺 url: ${resp.take(200)}")
            }
        }

    private fun getJson(url: String, headers: Map<String, String>): Result<String> = runCatching {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        HTTP.newCall(builder.build()).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(300)}")
            text
        }
    }

    private fun postJson(url: String, body: String): Result<String> = runCatching {
        val request = Request.Builder().url(url).post(body.toRequestBody(JSON_MEDIA)).build()
        HTTP.newCall(request).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(300)}")
            text
        }
    }
}

/**
 * v2.0.1: QQ 被动回复缓存 — 最近一条入站消息 ID 与回复序号。
 *
 * msg_id 被动回复窗口 60 分钟;同一 msg_id 的多次回复需递增 msg_seq 去重
 * (相同 msg_id + msg_seq 重复发送会被平台拒绝)。
 */
internal object QqMsgIdCache {

    private const val VALID_WINDOW_MS = 60 * 60 * 1000L

    private data class Entry(val msgId: String, val at: Long)

    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    private val seqs = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

    fun put(target: String, msgId: String) {
        if (target.isBlank() || msgId.isBlank()) return
        entries[target] = Entry(msgId, System.currentTimeMillis())
        seqs.remove(target)
    }

    /** 60 分钟内有效的被动回复 msg_id;过期返回 null(发送退化为主动消息)。 */
    fun get(target: String): String? {
        val entry = entries[target] ?: return null
        if (System.currentTimeMillis() - entry.at > VALID_WINDOW_MS) return null
        return entry.msgId
    }

    /** 取下一个回复序号(同一 msg_id 的多次回复递增)。 */
    fun nextSeq(target: String): Int =
        seqs.getOrPut(target) { AtomicInteger(0) }.incrementAndGet()
}
