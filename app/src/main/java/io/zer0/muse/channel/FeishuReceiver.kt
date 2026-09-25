package io.zer0.muse.channel

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * v2.0.1: 飞书长连接接收器 — pbbp2 over WebSocket(免公网)。
 *
 * 流程:POST {openBase}/callback/ws/endpoint 换取 wss 地址(含 service_id)→ 连接 →
 * 周期性 control ping 保活;数据帧按 message_id 分片合并后解析事件,处理完回写 ack
 * (同帧 payload 置为 {"code":200})。兼容飞书中国版与 Lark 国际版(region 字段)。
 *
 * 事件:仅处理 `im.message.receive_v1`;p2p 消息回给发送者 open_id,群消息回给 chat_id
 * (发送侧 [FeishuChannelSender] 按 target 前缀自动判定 receive_id_type)。
 */
class FeishuReceiver(
    private val channelManager: ChannelManager,
    private val context: Context,
    private val appScope: CoroutineScope,
) {
    private var job: Job? = null

    /** 分片事件缓冲:message_id → 各分片 payload。 */
    private val pendingFrames = ConcurrentHashMap<String, MutableMap<Int, ByteArray>>()

    /** 启动接收循环(幂等;已有循环先停再起)。 */
    fun restart() {
        job?.cancel()
        job = appScope.launch {
            ChannelInbox.attach(context)
            connectLoop()
        }
    }

    /** 停止接收。 */
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun connectLoop() {
        while (currentCoroutineContext().isActive) {
            channelManager.refresh()
            val config = channelManager.channels.value.firstOrNull {
                it.enabled && it.platform == ChannelPlatform.FEISHU &&
                    it.appId.isNotBlank() && it.appSecret.isNotBlank()
            }
            if (config == null) {
                Logger.i(TAG, "无启用中的飞书渠道,接收循环退出")
                return
            }
            Logger.i(TAG, "飞书接收循环: 找到渠道 ${config.id},建立连接…")
            runCatching { connectOnce(config) }
                .onFailure { e -> Logger.w(TAG, "飞书连接异常: ${e.message}") }
            if (!currentCoroutineContext().isActive) return
            delay(RECONNECT_DELAY_MS)
        }
    }

    /** 建立一次 WebSocket 并挂起至断开。 */
    private suspend fun connectOnce(config: ChannelConfig) {
        val endpoint = requestEndpoint(config) ?: return
        val serviceId = queryParam(endpoint.url, "service_id")?.toIntOrNull() ?: 0
        Logger.i(TAG, "endpoint 就绪(service=$serviceId, ping=${endpoint.pingIntervalSeconds}s),建立 WebSocket…")
        val disconnected = CompletableDeferred<Unit>()
        val socketRef = AtomicReference<WebSocket?>(null)
        val pingJob = appScope.launch {
            while (isActive) {
                delay(endpoint.pingIntervalSeconds * 1000L)
                val webSocket = socketRef.get() ?: continue
                runCatching { webSocket.send(pingFrame(serviceId).toByteString()) }
            }
        }
        val request = Request.Builder().url(endpoint.url).build()
        val socket = WS_CLIENT.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Logger.i(TAG, "飞书 WS 已连接")
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    runCatching { handleFrame(bytes.toByteArray(), webSocket) }
                        .onFailure { e -> Logger.w(TAG, "飞书帧处理失败: ${e.message}") }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Logger.w(TAG, "飞书 WS 断开: ${t.message}")
                    disconnected.complete(Unit)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Logger.i(TAG, "飞书 WS 关闭(code=$code, reason=$reason)")
                    disconnected.complete(Unit)
                }
            },
        )
        socketRef.set(socket)
        disconnected.await()
        pingJob.cancel()
        socket.cancel()
    }

    /** 帧分派:control(ping/pong)与 data(事件)。 */
    private fun handleFrame(bytes: ByteArray, webSocket: WebSocket) {
        val frame = FeishuPbbp2.decode(bytes)
        when (frame.method) {
            FeishuPbbp2.METHOD_CONTROL -> Unit // ping 回显 / pong 配置更新(首版忽略)
            FeishuPbbp2.METHOD_DATA -> handleDataFrame(frame, webSocket)
        }
    }

    /** 数据帧:分片合并 → 事件分发 → ack 回写。 */
    private fun handleDataFrame(frame: FeishuPbbp2.Frame, webSocket: WebSocket) {
        if (frame.header("type") != "event") return
        val messageId = frame.header("message_id") ?: return
        val sum = frame.header("sum")?.toIntOrNull() ?: 1
        val seq = frame.header("seq")?.toIntOrNull() ?: 0
        val merged = mergeData(messageId, sum, seq, frame.payload) ?: return
        dispatchEvent(merged)
        // ack:同帧回写 payload(code=200),附加 biz_rt。
        val ackFrame = frame.copy(
            headers = frame.headers + FeishuPbbp2.Header("biz_rt", "0"),
            payload = "{\"code\":200}".toByteArray(Charsets.UTF_8),
        )
        runCatching { webSocket.send(FeishuPbbp2.encode(ackFrame).toByteString()) }
    }

    /** 分片合并(按 message_id 缓存,全部到齐后拼接为完整 JSON);未齐返回 null。 */
    private fun mergeData(messageId: String, sum: Int, seq: Int, payload: ByteArray): String? {
        val slots = pendingFrames.getOrPut(messageId) {
            // 简单的过期保护:仅保留最近 64 个待合并 message_id。
            if (pendingFrames.size > 64) pendingFrames.clear()
            ConcurrentHashMap<Int, ByteArray>()
        }
        slots[seq] = payload
        if (slots.size < sum) return null
        val merged = java.io.ByteArrayOutputStream()
        for (index in 0 until sum) {
            val chunk = slots[index] ?: return null
            merged.write(chunk)
        }
        pendingFrames.remove(messageId)
        return String(merged.toByteArray(), Charsets.UTF_8)
    }

    /** 事件 JSON 派发(仅 im.message.receive_v1)。 */
    private fun dispatchEvent(json: String) {
        val obj = runCatching { AppJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return
        val header = obj["header"]?.jsonObject ?: return
        val eventType = header["event_type"]?.jsonPrimitive?.contentOrNull ?: return
        if (eventType != "im.message.receive_v1") return
        val event = obj["event"]?.jsonObject ?: return
        val message = event["message"]?.jsonObject ?: return
        val sender = event["sender"]?.jsonObject
        val chatType = message["chat_type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val messageType = message["message_type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val openId = sender?.get("sender_id")?.jsonObject
            ?.get("open_id")?.jsonPrimitive?.contentOrNull.orEmpty()
        val chatId = message["chat_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        // v2.0.1: p2p 回给发送人(ou_);群聊回给会话(oc_)。
        val from = if (chatType == "p2p") openId else chatId
        if (from.isBlank()) return
        val text = when (messageType) {
            "text" -> parseTextContent(
                message["content"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
            "image" -> "[图片]"
            "audio" -> "[语音]"
            "media" -> "[视频]"
            "file" -> "[文件]"
            "post" -> "[富文本]"
            else -> if (messageType.isBlank()) "" else "[$messageType]"
        }
        if (text.isNotBlank()) ChannelInbox.record("FEISHU", from, text, "")
    }

    /** 解析 text 消息 content(JSON 字符串;去掉 @ 占位符)。 */
    private fun parseTextContent(contentJson: String): String = runCatching {
        AppJson.parseToJsonElement(contentJson).jsonObject
            .get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
            .replace(AT_PLACEHOLDER_REGEX, "")
            .trim()
    }.getOrDefault("")

    /** POST {openBase}/callback/ws/endpoint 换取连接配置。 */
    private suspend fun requestEndpoint(config: ChannelConfig): EndpointInfo? {
        val base = if (config.region == "intl") "https://open.larksuite.com" else "https://open.feishu.cn"
        return runCatching {
            val body = buildJsonObject {
                put("AppID", config.appId)
                put("AppSecret", config.appSecret)
            }.toString()
            val resp = postJson("$base/callback/ws/endpoint", body)
            val obj = AppJson.parseToJsonElement(resp).jsonObject
            val code = obj["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: -1
            if (code != 0) {
                error("endpoint 响应 code=$code ${obj["msg"]?.jsonPrimitive?.contentOrNull.orEmpty()}")
            }
            val data = obj["data"]?.jsonObject ?: error("endpoint 响应缺 data")
            val url = data["URL"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: error("endpoint 响应缺 URL")
            val pingSeconds = data["ClientConfig"]?.jsonObject
                ?.get("PingInterval")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?.takeIf { it > 0 } ?: DEFAULT_PING_SECONDS
            EndpointInfo(url = url, pingIntervalSeconds = pingSeconds)
        }.onFailure { e ->
            Logger.w(TAG, "飞书长连接 endpoint 获取失败: ${e.message}")
        }.getOrNull()
    }

    /** control ping 帧(service=serviceId)。 */
    private fun pingFrame(serviceId: Int): ByteArray = FeishuPbbp2.encode(
        FeishuPbbp2.Frame(
            service = serviceId,
            method = FeishuPbbp2.METHOD_CONTROL,
            headers = listOf(FeishuPbbp2.Header("type", "ping")),
        ),
    )

    private fun queryParam(url: String, name: String): String? =
        runCatching { android.net.Uri.parse(url).getQueryParameter(name) }.getOrNull()

    private fun postJson(url: String, body: String): String {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        HTTP.newCall(request).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(300)}")
            return text
        }
    }

    private data class EndpointInfo(val url: String, val pingIntervalSeconds: Long)

    companion object {
        private const val TAG = "FeishuReceiver"

        /** 重连退避(毫秒)。 */
        private const val RECONNECT_DELAY_MS = 5_000L

        /** endpoint 未返回 PingInterval 时的默认心跳周期(秒)。 */
        private const val DEFAULT_PING_SECONDS = 120L

        /** 文本消息中的 @ 占位符(如 @_user_1)。 */
        private val AT_PLACEHOLDER_REGEX = Regex("@_user_\\d+")

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private val HTTP: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /** 长连接专用客户端(保活)。 */
        private val WS_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build()
        }
    }
}
