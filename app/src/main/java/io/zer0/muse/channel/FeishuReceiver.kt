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
import kotlinx.serialization.json.JsonObject
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

    /** 分片事件缓冲:有界 FIFO 拼装,避免单个异常连接耗尽内存。 */
    private val frameAssembler = FeishuFrameAssembler()

    private data class InboundEvent(
        val from: String,
        val text: String,
        val eventId: String,
    )

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
                    runCatching { handleFrame(bytes.toByteArray(), webSocket, config.id) }
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
        awaitChannelSocketTermination(disconnected) {
            pingJob.cancel()
            socket.cancel()
        }
    }

    /** 帧分派:control(ping/pong)与 data(事件)。 */
    private fun handleFrame(bytes: ByteArray, webSocket: WebSocket, channelId: String) {
        val frame = FeishuPbbp2.decode(bytes)
        when (frame.method) {
            FeishuPbbp2.METHOD_CONTROL -> Unit // ping 回显 / pong 配置更新(首版忽略)
            FeishuPbbp2.METHOD_DATA -> handleDataFrame(frame, webSocket, channelId)
        }
    }

    /** 数据帧:分片合并 → 事件分发 → ack 回写。 */
    private fun handleDataFrame(frame: FeishuPbbp2.Frame, webSocket: WebSocket, channelId: String) {
        val messageId = frame.header("message_id")
        val frameType = frame.header("type")
        val merged = messageId
            ?.takeIf { frameType == "event" }
            ?.let {
                val sum = frame.header("sum")?.toIntOrNull() ?: 1
                val seq = frame.header("seq")?.toIntOrNull() ?: 0
                frameAssembler.add(it, sum, seq, frame.payload)
            }
        if (merged != null) {
            dispatchEvent(merged, channelId)
        }
        if (shouldAcknowledgeFeishuEventFrame(messageId, frameType)) {
            // 每个事件分片都确认传输层已收到;只有完整 payload 才进入上面的业务派发。
            val ackFrame = frame.copy(
                headers = frame.headers + FeishuPbbp2.Header("biz_rt", "0"),
                payload = "{\"code\":200}".toByteArray(Charsets.UTF_8),
            )
            runCatching { webSocket.send(FeishuPbbp2.encode(ackFrame).toByteString()) }
        }
    }

    /** 事件 JSON 派发(仅 im.message.receive_v1)。 */
    private fun dispatchEvent(json: String, channelId: String) {
        val event = parseInboundEvent(json)
        if (event != null) {
            ChannelInbox.record(
                ChannelInbox.Source("FEISHU", event.from, channelId, event.eventId),
                event.text,
                "",
            )
        }
    }

    private fun parseInboundEvent(json: String): InboundEvent? {
        val obj = runCatching { AppJson.parseToJsonElement(json).jsonObject }.getOrNull()
        val header = obj?.get("header")?.jsonObject
        val event = obj?.get("event")?.jsonObject
        val message = event?.get("message")?.jsonObject
        val sender = event?.get("sender")?.jsonObject
        val eventType = header?.get("event_type")?.jsonPrimitive?.contentOrNull
        val senderType = sender?.get("sender_type")?.jsonPrimitive?.contentOrNull
        val chatType = message?.get("chat_type")?.jsonPrimitive?.contentOrNull.orEmpty()
        val messageType = message?.get("message_type")?.jsonPrimitive?.contentOrNull.orEmpty()
        val openId = sender?.get("sender_id")?.jsonObject
            ?.get("open_id")?.jsonPrimitive?.contentOrNull.orEmpty()
        val chatId = message?.get("chat_id")?.jsonPrimitive?.contentOrNull.orEmpty()
        val from = if (chatType == "p2p") openId else chatId
        val text = inboundText(message, messageType)
        val eventId = header?.get("event_id")?.jsonPrimitive?.contentOrNull.orEmpty()
        val supportedEvent = eventType == "im.message.receive_v1"
        val userMessage = senderType == null || senderType == "user"
        val messageReady = from.isNotBlank() && text.isNotBlank()
        return if (supportedEvent && userMessage && messageReady) {
            InboundEvent(from, text, eventId)
        } else {
            null
        }
    }

    private fun inboundText(message: JsonObject?, messageType: String): String = feishuInboundText(message, messageType)

    /**
     * v2.6.0 (AUD-CHANNEL-F8): 从飞书消息提取用户可见文本（长连接/webhook 共用）。
     * webhook 路径此前直接取 message.content 原始串（{"text":"你好"}）当正文，
     * 模型收到 JSON 并可能复读；此处与长连接路径统一。
     */
    internal fun feishuInboundText(message: JsonObject?, messageType: String): String = when (messageType) {
        "text" -> parseFeishuTextContent(message?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty())
        "image" -> "[图片]"
        "audio" -> "[语音]"
        "media" -> "[视频]"
        "file" -> "[文件]"
        "post" -> "[富文本]"
        else -> if (messageType.isBlank()) "" else "[$messageType]"
    }

    /** 解析 text 消息 content(JSON 字符串;去掉 @ 占位符)。 */
    internal fun parseFeishuTextContent(contentJson: String): String = runCatching {
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

        /**
         * v2.6.0 (AUD-CHANNEL-F8): webhook 路径用的静态入口。
         * 从 message.content(JSON 串)与 message_type 解析用户可见文本,
         * 与长连接路径(实例方法 feishuInboundText)同口径。
         */
        internal fun parseFeishuMessageText(content: String, messageType: String): String = when (messageType) {
            "text", "" -> runCatching {
                AppJson.parseToJsonElement(content).jsonObject
                    .get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                    .replace(AT_PLACEHOLDER_REGEX, "")
                    .trim()
            }.getOrDefault("")
            "image" -> "[图片]"
            "audio" -> "[语音]"
            "media" -> "[视频]"
            "file" -> "[文件]"
            "post" -> "[富文本]"
            else -> "[$messageType]"
        }

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
