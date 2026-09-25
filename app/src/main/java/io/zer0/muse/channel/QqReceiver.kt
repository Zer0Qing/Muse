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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * v2.0.1: QQ 机器人接收器 — WebSocket Gateway 长连接(免公网)。
 *
 * 协议:获取 WSS 接入点 → 连接(收到 Hello 携带心跳周期)→ Identify
 * (token=QQBot {access_token}, intents=GROUP_AND_C2C_EVENT)→ op=1 心跳 → op=0 事件派发。
 * 用户消息写入 [ChannelInbox] 并缓存 msg_id 供被动回复(见 [QqMsgIdCache])。
 * 配置保存/删除后由 UI 调用 [restart];App 启动时由 MuseApp 调起。
 */
class QqReceiver(
    private val channelManager: ChannelManager,
    private val context: Context,
    private val appScope: CoroutineScope,
) {
    private var job: Job? = null

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
                it.enabled && it.platform == ChannelPlatform.QQ &&
                    it.appId.isNotBlank() && it.appSecret.isNotBlank()
            }
            if (config == null) {
                Logger.i(TAG, "无启用中的 QQ 渠道,接收循环退出")
                return
            }
            Logger.i(TAG, "QQ 接收循环: 找到渠道 ${config.id},建立连接…")
            runCatching { connectOnce(config) }
                .onFailure { e -> Logger.w(TAG, "QQ 连接异常: ${e.message}") }
            if (!currentCoroutineContext().isActive) return
            delay(RECONNECT_DELAY_MS)
        }
    }

    /** 建立一次 WebSocket 并挂起至断开。 */
    private suspend fun connectOnce(config: ChannelConfig) {
        Logger.i(TAG, "获取 QQ access_token…")
        val token = QqClient.fetchAccessToken(config.appId, config.appSecret).getOrElse { e ->
            Logger.w(TAG, "QQ access_token 获取失败: ${e.message}")
            return
        }
        Logger.i(TAG, "access_token 就绪(有效期 ${token.expiresInSeconds}s),获取 gateway…")
        val gateway = QqClient.getGateway(token.accessToken).getOrElse { e ->
            Logger.w(TAG, "QQ gateway 获取失败: ${e.message}")
            return
        }
        Logger.i(TAG, "gateway 就绪(${gateway.take(60)}),建立 WebSocket…")
        val disconnected = CompletableDeferred<Unit>()
        val heartbeatInterval = AtomicLong(DEFAULT_HEARTBEAT_MS)
        val lastSeq = AtomicLong(0L)
        val socketRef = AtomicReference<WebSocket?>(null)
        val heartbeatJob = appScope.launch {
            while (isActive) {
                delay(heartbeatInterval.get())
                val webSocket = socketRef.get() ?: continue
                val seq = lastSeq.get()
                val payload = if (seq > 0) seq.toString() else "null"
                runCatching { webSocket.send("{\"op\":1,\"d\":$payload}") }
            }
        }
        val request = Request.Builder().url(gateway).build()
        val socket = QqClient.WEB_SOCKET.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        handleFrame(webSocket, text, token.accessToken, heartbeatInterval, lastSeq)
                    }.onFailure { e -> Logger.w(TAG, "QQ 帧处理失败: ${e.message}") }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Logger.w(TAG, "QQ WS 断开: ${t.message}")
                    disconnected.complete(Unit)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Logger.i(TAG, "QQ WS 关闭(code=$code, reason=$reason)")
                    disconnected.complete(Unit)
                }

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Logger.i(TAG, "QQ WS 已连接,等待 Hello…")
                }
            },
        )
        socketRef.set(socket)
        disconnected.await()
        heartbeatJob.cancel()
        socket.cancel()
    }

    /** 帧状态机(op: 10 Hello / 11 心跳 ACK / 0 事件 / 7 重连 / 9 无效会话)。 */
    private fun handleFrame(
        webSocket: WebSocket,
        text: String,
        accessToken: String,
        heartbeatInterval: AtomicLong,
        lastSeq: AtomicLong,
    ) {
        val obj = AppJson.parseToJsonElement(text).jsonObject
        val op = obj["op"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return
        when (op) {
            10 -> {
                obj["d"]?.jsonObject?.get("heartbeat_interval")
                    ?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    ?.let { interval -> heartbeatInterval.set(interval) }
                Logger.i(TAG, "QQ Hello(心跳周期 ${heartbeatInterval.get()}ms),发送 Identify")
                webSocket.send(identifyPayload(accessToken))
            }
            11 -> Unit // Heartbeat ACK
            0 -> handleDispatch(obj)
            7 -> {
                Logger.i(TAG, "QQ Gateway 要求重连")
                webSocket.close(1000, "reconnect")
            }
            9 -> {
                Logger.w(TAG, "QQ Invalid Session(intents 权限或参数问题,请检查 QQ 开放平台的单聊/群聊消息权限)")
                webSocket.close(1000, "invalid session")
            }
        }
        obj["s"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.let { seq ->
            if (seq > lastSeq.get()) lastSeq.set(seq)
        }
    }

    /** Dispatch 事件(op=0)。 */
    private fun handleDispatch(obj: JsonObject) {
        val eventType = obj["t"]?.jsonPrimitive?.contentOrNull ?: return
        val d = obj["d"] as? JsonObject ?: return
        when (eventType) {
            "C2C_MESSAGE_CREATE" -> {
                val openid = d["author"]?.jsonObject
                    ?.get("user_openid")?.jsonPrimitive?.contentOrNull.orEmpty()
                if (openid.isBlank()) return
                val msgId = d["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (msgId.isNotBlank()) QqMsgIdCache.put(openid, msgId)
                val text = resolveInboundText(d)
                if (text.isNotBlank()) ChannelInbox.record("QQ", openid, text, "")
            }
            "GROUP_AT_MESSAGE_CREATE" -> {
                val groupOpenid = d["group_openid"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (groupOpenid.isBlank()) return
                // v2.0.1: 群目标以 "group:" 前缀标记,发送侧据此路由到群接口。
                val target = "group:$groupOpenid"
                val msgId = d["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (msgId.isNotBlank()) QqMsgIdCache.put(target, msgId)
                val text = resolveInboundText(d)
                if (text.isNotBlank()) ChannelInbox.record("QQ", target, text, "")
            }
            "READY" -> {
                val username = d["user"]?.jsonObject
                    ?.get("username")?.jsonPrimitive?.contentOrNull.orEmpty()
                Logger.i(TAG, "QQ 已连接($username)")
            }
            else -> Unit
        }
    }

    /** v2.0.1: 入站文本提取 — 文本优先;语音用 ASR 参考;图片/视频/文件占位。 */
    private fun resolveInboundText(d: JsonObject): String {
        val content = d["content"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        if (content.isNotBlank()) return content
        val attachment = (d["attachments"] as? JsonArray)?.firstOrNull() as? JsonObject
        val contentType = attachment?.get("content_type")?.jsonPrimitive?.contentOrNull.orEmpty()
        return when {
            contentType == "voice" -> attachment?.get("asr_refer_text")
                ?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "[语音]" }
            contentType.startsWith("image/") -> "[图片]"
            contentType.startsWith("video/") -> "[视频]"
            contentType.isNotBlank() -> "[附件]"
            else -> ""
        }
    }

    /** Identify 载荷(token=QQBot {access_token};shard 单分片 [0,0])。 */
    private fun identifyPayload(accessToken: String): String = buildJsonObject {
        put("op", 2)
        putJsonObject("d") {
            put("token", "QQBot $accessToken")
            put("intents", GROUP_AND_C2C_INTENT)
            putJsonArray("shard") {
                // [shard_id, shard_count] — 单分片必须是 [0, 1](count=0 会被拒: shard count error)。
                add(0)
                add(1)
            }
            putJsonObject("properties") {
                put("\$os", "android")
                put("\$browser", "muse")
                put("\$device", "muse")
            }
        }
    }.toString()

    companion object {
        private const val TAG = "QqReceiver"

        /** 重连退避(毫秒)。 */
        private const val RECONNECT_DELAY_MS = 5_000L

        /** 默认心跳周期(Hello 帧会覆盖)。 */
        private const val DEFAULT_HEARTBEAT_MS = 45_000L

        /** GROUP_AND_C2C_EVENT (1 << 25) — 单聊消息 + 群@消息。 */
        private const val GROUP_AND_C2C_INTENT = 1 shl 25
    }
}
