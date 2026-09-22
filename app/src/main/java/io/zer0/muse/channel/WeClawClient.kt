package io.zer0.muse.channel

import io.zer0.common.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * v2.0: 微信 ClawBot(iLink)协议客户端 — 纯 HTTP/JSON,4 个端点。
 *
 * 流程:扫码注册(get_bot_qrcode + 轮询 get_qrcode_status)→ 长轮询收消息(getupdates)
 * → 回发(sendmessage)。协议与具体框架无耦合,任何能发 HTTP 请求的程序都可对接。
 *
 * 凭据映射到 [ChannelConfig]:appId=ilink_bot_id,appSecret=bot_token,targetId=ilink_user_id。
 * 回复必须携带来源消息的 context_token(见 [WeClawContextCache])。
 */
internal object WeClawClient {

    /** 默认接入域名(扫码确认响应可能带回 baseurl 覆盖值)。 */
    const val DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com"

    private val HTTP: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // 长轮询服务端 hold 约 30s,读超时需大于该值
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** 绑定二维码信息。 */
    data class QrCodeInfo(val qrcode: String, val imgContent: String)

    /** 扫码状态。status: wait / scaned / confirmed / expired。 */
    data class QrStatus(
        val status: String,
        val botToken: String = "",
        val botId: String = "",
        val userId: String = "",
        val baseUrl: String = "",
    )

    /** 一条入站用户消息。 */
    data class InboundMsg(val fromUserId: String, val text: String, val contextToken: String)

    /** getupdates 结果。buffer 为下次请求携带的同步游标。 */
    data class Updates(val messages: List<InboundMsg>, val buffer: String)

    /** 1. 获取绑定二维码(bot_type=3 为当前公开类型)。 */
    suspend fun getQrCode(baseUrl: String = DEFAULT_BASE_URL): Result<QrCodeInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "${baseUrl.trimEnd('/')}/ilink/bot/get_bot_qrcode?bot_type=3"
                val resp = request("GET", url).getOrThrow()
                val obj = AppJson.parseToJsonElement(resp).jsonObject
                QrCodeInfo(
                    qrcode = obj["qrcode"]?.jsonPrimitive?.contentOrNull
                        ?: error("二维码响应缺少 qrcode 字段"),
                    imgContent = obj["qrcode_img_content"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
        }

    /** 2. 轮询扫码状态;confirmed 时携带凭据。 */
    suspend fun getQrStatus(qrcode: String, baseUrl: String = DEFAULT_BASE_URL): Result<QrStatus> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "${baseUrl.trimEnd('/')}/ilink/bot/get_qrcode_status" +
                    "?qrcode=${java.net.URLEncoder.encode(qrcode, "UTF-8")}"
                val resp = request(
                    "GET",
                    url,
                    headers = mapOf("iLink-App-ClientVersion" to "1"),
                ).getOrThrow()
                val obj = AppJson.parseToJsonElement(resp).jsonObject
                QrStatus(
                    status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "wait",
                    botToken = obj["bot_token"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    botId = obj["ilink_bot_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    userId = obj["ilink_user_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    baseUrl = obj["baseurl"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
        }

    /** 3. 长轮询收消息(服务端 hold 约 30s,无消息返回空列表)。 */
    suspend fun getUpdates(
        botToken: String,
        buffer: String,
        baseUrl: String = DEFAULT_BASE_URL,
    ): Result<Updates> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${baseUrl.trimEnd('/')}/ilink/bot/getupdates"
            val body = buildJsonObject {
                put("get_updates_buf", buffer)
                putJsonObject("base_info") { put("channel_version", "0.1.0") }
            }.toString()
            val resp = request(
                "POST",
                url,
                body,
                headers = mapOf(
                    "Authorization" to "Bearer $botToken",
                    "AuthorizationType" to "ilink_bot_token",
                    "X-WECHAT-UIN" to randomUin(),
                ),
            ).getOrThrow()
            val obj = AppJson.parseToJsonElement(resp).jsonObject
            val ret = obj["ret"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            if (ret != 0) error("iLink ret=$ret: ${resp.take(200)}")
            val messages = parseMessages(obj["msgs"] as? JsonArray ?: JsonArray(emptyList()))
            Updates(
                messages = messages,
                buffer = obj["get_updates_buf"]?.jsonPrimitive?.contentOrNull ?: buffer,
            )
        }
    }

    /** 4. 发送消息(message_type=2 / message_state=2 表示完成的 Bot 消息)。 */
    suspend fun sendMessage(
        botToken: String,
        toUserId: String,
        text: String,
        contextToken: String?,
        baseUrl: String = DEFAULT_BASE_URL,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${baseUrl.trimEnd('/')}/ilink/bot/sendmessage"
            val msg = buildJsonObject {
                put("to_user_id", toUserId)
                put("client_id", UUID.randomUUID().toString())
                put("message_type", 2)
                put("message_state", 2)
                putJsonArray("item_list") {
                    addJsonObject {
                        put("type", 1)
                        putJsonObject("text_item") { put("text", text) }
                    }
                }
                if (!contextToken.isNullOrBlank()) put("context_token", contextToken)
            }
            val body = buildJsonObject { put("msg", msg) }.toString()
            val resp = request(
                "POST",
                url,
                body,
                headers = mapOf(
                    "Authorization" to "Bearer $botToken",
                    "AuthorizationType" to "ilink_bot_token",
                ),
            ).getOrThrow()
            val obj = runCatching { AppJson.parseToJsonElement(resp).jsonObject }.getOrNull()
            val ret = obj?.get("ret")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            if (ret != null && ret != 0) error("iLink send ret=$ret: ${resp.take(200)}")
        }
    }

    /** 解析 msgs 数组:仅保留用户文本消息(message_type=1 / item.type=1)。 */
    private fun parseMessages(array: JsonArray): List<InboundMsg> = array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val type = obj["message_type"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
        if (type != 1) return@mapNotNull null
        val from = obj["from_user_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (from.isBlank()) return@mapNotNull null
        val text = (obj["item_list"] as? JsonArray)?.firstNotNullOfOrNull { item ->
            val itemObj = item as? JsonObject ?: return@firstNotNullOfOrNull null
            val itemType = itemObj["type"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
            if (itemType != 1) return@firstNotNullOfOrNull null
            itemObj["text_item"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
        }
        if (text.isNullOrBlank()) return@mapNotNull null
        InboundMsg(
            fromUserId = from,
            text = text,
            contextToken = obj["context_token"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    /** 发起 HTTP 请求;非 2xx 抛带响应片段的异常。 */
    private fun request(
        method: String,
        url: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Result<String> = runCatching {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
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

    /** 每次请求随机生成的设备标识(协议要求,base64)。 */
    private fun randomUin(): String {
        val bytes = ByteArray(12).also { java.util.Random().nextBytes(it) }
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }
}

/**
 * v2.0: 最近一条入站消息的 context_token 缓存。
 *
 * iLink 协议要求回发消息必须携带来源消息的 context_token;
 * 接收器在收到消息时写入,发送器在回发时读取。
 */
internal object WeClawContextCache {
    private val tokens = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun put(userId: String, token: String) {
        if (userId.isNotBlank() && token.isNotBlank()) tokens[userId] = token
    }

    fun get(userId: String): String? = tokens[userId]
}
