package io.zer0.muse.channel

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.CancellationException
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
import kotlinx.serialization.json.booleanOrNull
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * v2.0.1: QQ 机器人接收器 — WebSocket Gateway 长连接(免公网)，支持多实例并行。
 */
class QqReceiver(
    private val channelManager: ChannelManager,
    private val context: Context,
    private val appScope: CoroutineScope,
) : ChannelReceiver {
    override val platform: ChannelPlatform = ChannelPlatform.QQ

    private class SessionState(
        val sessionIdRef: AtomicReference<String?> = AtomicReference(null),
        val lastSeqRef: AtomicLong = AtomicLong(0L),
        val identifyRequired: AtomicBoolean = AtomicBoolean(false),
    )

    private data class FrameContext(
        val accessToken: String,
        val channelId: String,
        val heartbeatInterval: AtomicLong,
        val session: SessionState,
    )

    private val lock = Any()
    private var watcherJob: Job? = null
    private val channelJobs = ConcurrentHashMap<String, Job>()
    private val channelFingerprints = ConcurrentHashMap<String, String>()
    private val channelSessions = ConcurrentHashMap<String, SessionState>()

    /** 启动接收循环(幂等;已有循环先停再起)。 */
    override fun restart() {
        synchronized(lock) {
            stopAllLocked()
            watcherJob = appScope.launch {
                ChannelInbox.attach(context)
                channelManager.refresh()
                reconcile(channelManager.channels.value)
                channelManager.channels.collect { channels ->
                    reconcile(channels)
                }
            }
        }
    }

    /** 停止接收。 */
    override fun stop() {
        synchronized(lock) {
            stopAllLocked()
        }
    }

    private fun stopAllLocked() {
        watcherJob?.cancel()
        watcherJob = null
        channelJobs.keys.toList().forEach { id ->
            channelJobs.remove(id)?.cancel()
            channelFingerprints.remove(id)
            ChannelRuntimeRegistry.markStopped(id, platform)
        }
    }

    private fun reconcile(channels: List<ChannelConfig>) {
        synchronized(lock) {
            val active = channels.filter {
                it.enabled && it.platform == platform &&
                    it.appId.isNotBlank() && it.appSecret.isNotBlank()
            }
            val activeMap = active.associateBy { it.id }
            val allIds = channels.filter { it.platform == platform }.mapTo(HashSet()) { it.id }

            channelJobs.keys.toList().forEach { id ->
                val nextCfg = activeMap[id]
                val nextFp = nextCfg?.let { "${it.appId}:${it.appSecret}" }
                if (nextCfg == null || channelFingerprints[id] != nextFp) {
                    channelJobs.remove(id)?.cancel()
                    channelFingerprints.remove(id)
                    channelSessions.remove(id)
                    if (id !in allIds) {
                        ChannelRuntimeRegistry.remove(id)
                    } else if (nextCfg == null) {
                        ChannelRuntimeRegistry.markStopped(id, platform)
                    }
                }
            }

            channels.filter { it.platform == platform && !it.enabled }.forEach { disabled ->
                ChannelRuntimeRegistry.markStopped(disabled.id, platform)
            }

            active.forEach { cfg ->
                val existing = channelJobs[cfg.id]
                if (existing == null || !existing.isActive) {
                    channelFingerprints[cfg.id] = "${cfg.appId}:${cfg.appSecret}"
                    val session = channelSessions.getOrPut(cfg.id) { SessionState() }
                    channelJobs[cfg.id] = appScope.launch {
                        connectLoopForChannel(cfg, session)
                    }
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // 连接实现会抛 IO/SSL/协议异常，统一记录后退避重连；取消已单独放行。
    private suspend fun connectLoopForChannel(config: ChannelConfig, session: SessionState) {
        while (currentCoroutineContext().isActive) {
            ChannelRuntimeRegistry.markConnecting(config.id, platform)
            Logger.i(TAG, "QQ 接收循环: 建立连接 ${config.id}…")
            try {
                connectOnce(config, session)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                Logger.w(TAG, "QQ 连接异常: ${e::class.simpleName}: ${e.message}")
                if (ChannelRuntimeRegistry.looksLikeAuthError(e.message)) {
                    ChannelRuntimeRegistry.markAuthError(config.id, platform, e.message.orEmpty())
                } else {
                    ChannelRuntimeRegistry.markReconnecting(config.id, platform, e.message.orEmpty())
                }
            }
            if (!currentCoroutineContext().isActive) return
            delay(RECONNECT_DELAY_MS)
        }
    }

    /** 建立一次 WebSocket 并挂起至断开。 */
    private suspend fun connectOnce(config: ChannelConfig, session: SessionState) {
        Logger.i(TAG, "获取 QQ access_token…")
        val token = QqClient.fetchAccessToken(config.appId, config.appSecret).getOrElse { e ->
            Logger.w(TAG, "QQ access_token 获取失败: ${e.message}")
            if (ChannelRuntimeRegistry.looksLikeAuthError(e.message)) {
                ChannelRuntimeRegistry.markAuthError(config.id, platform, e.message.orEmpty())
            } else {
                ChannelRuntimeRegistry.markReconnecting(config.id, platform, e.message.orEmpty())
            }
            return
        }
        Logger.i(TAG, "access_token 就绪(有效期 ${token.expiresInSeconds}s),获取 gateway…")
        val gateway = QqClient.getGateway(token.accessToken).getOrElse { e ->
            Logger.w(TAG, "QQ gateway 获取失败: ${e.message}")
            ChannelRuntimeRegistry.markReconnecting(config.id, platform, e.message.orEmpty())
            return
        }
        Logger.i(TAG, "gateway 就绪(${gateway.take(60)}),建立 WebSocket…")
        val disconnected = CompletableDeferred<Unit>()
        val heartbeatInterval = AtomicLong(DEFAULT_HEARTBEAT_MS)
        val frameContext = FrameContext(token.accessToken, config.id, heartbeatInterval, session)
        val socketRef = AtomicReference<WebSocket?>(null)
        val heartbeatJob = appScope.launch {
            while (isActive) {
                delay(heartbeatInterval.get())
                val webSocket = socketRef.get() ?: continue
                val seq = session.lastSeqRef.get()
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
                        handleFrame(webSocket, text, frameContext)
                    }.onFailure { e -> Logger.w(TAG, "QQ 帧处理失败: ${e.message}") }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Logger.w(TAG, "QQ WS 断开: ${t.message} (http=${response?.code})")
                    ChannelRuntimeRegistry.markReconnecting(config.id, platform, t.message.orEmpty())
                    disconnected.complete(Unit)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Logger.i(TAG, "QQ WS 关闭(code=$code, reason=$reason)")
                    if (code == 4006 || code == 4007) {
                        session.sessionIdRef.set(null)
                        session.lastSeqRef.set(0L)
                        session.identifyRequired.set(true)
                        Logger.w(TAG, "QQ 会话不可恢复(code=$code),下次重新 Identify")
                    }
                    ChannelRuntimeRegistry.markReconnecting(config.id, platform, reason)
                    disconnected.complete(Unit)
                }

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Logger.i(TAG, "QQ WS 已连接,等待 Hello…")
                }
            },
        )
        socketRef.set(socket)
        awaitChannelSocketTermination(disconnected) {
            heartbeatJob.cancel()
            socket.cancel()
        }
    }

    /** 帧状态机(op: 10 Hello / 11 心跳 ACK / 0 事件 / 7 重连 / 9 无效会话)。 */
    private fun handleFrame(webSocket: WebSocket, text: String, frameContext: FrameContext) {
        val obj = AppJson.parseToJsonElement(text).jsonObject
        val op = obj["op"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return
        val session = frameContext.session
        when (op) {
            10 -> {
                obj["d"]?.jsonObject?.get("heartbeat_interval")
                    ?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    ?.let { interval -> frameContext.heartbeatInterval.set(interval) }
                val sessionId = session.sessionIdRef.get()
                val seq = session.lastSeqRef.get()
                val canResume = sessionId != null && seq > 0 && !session.identifyRequired.get()
                if (canResume) {
                    Logger.i(
                        TAG,
                        "QQ Hello(心跳周期 ${frameContext.heartbeatInterval.get()}ms),发送 Resume(seq=$seq) 以补回断档事件",
                    )
                    webSocket.send(resumePayload(frameContext.accessToken, sessionId, seq))
                } else {
                    Logger.i(TAG, "QQ Hello(心跳周期 ${frameContext.heartbeatInterval.get()}ms),发送 Identify")
                    session.identifyRequired.set(false)
                    webSocket.send(identifyPayload(frameContext.accessToken))
                }
            }
            11 -> Unit // Heartbeat ACK
            0 -> handleDispatch(obj, frameContext)
            7 -> {
                Logger.i(TAG, "QQ Gateway 要求重连")
                webSocket.close(1000, "reconnect")
            }
            9 -> {
                Logger.w(TAG, "QQ Invalid Session(intents 权限或参数问题,请检查 QQ 开放平台的单聊/群聊消息权限)")
                session.sessionIdRef.set(null)
                session.lastSeqRef.set(0L)
                session.identifyRequired.set(true)
                ChannelRuntimeRegistry.markAuthError(frameContext.channelId, platform, "QQ Gateway Invalid Session")
                webSocket.close(1000, "invalid session")
            }
        }
        obj["s"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.let { seq ->
            if (seq > session.lastSeqRef.get()) session.lastSeqRef.set(seq)
        }
    }

    /**
     * 全量模式下判断这条群消息是不是提到了机器人。
     */
    private fun isBotMentioned(data: JsonObject): Boolean {
        val mentions = data["mentions"] as? JsonArray ?: return false
        return mentions.any { item ->
            ((item as? JsonObject)?.get("bot")?.jsonPrimitive?.booleanOrNull) == true
        }
    }

    /** Dispatch 事件(op=0)。 */
    private fun handleDispatch(obj: JsonObject, frameContext: FrameContext) {
        val channelId = frameContext.channelId
        val session = frameContext.session
        val eventType = obj["t"]?.jsonPrimitive?.contentOrNull
        val d = obj["d"] as? JsonObject
        if (eventType != null && d != null) {
            when (eventType) {
                "C2C_MESSAGE_CREATE" -> handleC2cMessage(d, channelId)
                "GROUP_AT_MESSAGE_CREATE" -> handleGroupMessage(d, channelId)
                "GROUP_MESSAGE_CREATE" ->
                    if (isBotMentioned(d)) {
                        handleGroupMessage(d, channelId)
                    } else {
                        Logger.i(TAG, "QQ 群全量消息(未@机器人),不自动回复")
                    }
                "READY" -> {
                    val username = d["user"]?.jsonObject
                        ?.get("username")?.jsonPrimitive?.contentOrNull.orEmpty()
                    session.sessionIdRef.set(d["session_id"]?.jsonPrimitive?.contentOrNull)
                    session.lastSeqRef.set(0L)
                    session.identifyRequired.set(false)
                    ChannelRuntimeRegistry.markConnected(channelId, platform, username)
                    Logger.i(TAG, "QQ 已连接($username),session 已记录")
                }
                "RESUMED" -> {
                    ChannelRuntimeRegistry.markConnected(channelId, platform)
                    Logger.i(TAG, "QQ 会话已恢复,断档事件由平台补发")
                }
            }
        }
    }

    private fun handleC2cMessage(data: JsonObject, channelId: String) {
        val openid = data["author"]?.jsonObject
            ?.get("user_openid")?.jsonPrimitive?.contentOrNull.orEmpty()
        val messageId = data["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (openid.isNotBlank() && messageId.isNotBlank()) QqMsgIdCache.put(openid, messageId)
        val text = resolveInboundText(data)
        if (openid.isNotBlank() && text.isNotBlank()) {
            ChannelRuntimeRegistry.markInbound(channelId, platform)
            recordQqInbound(appScope, ChannelInbox.Source("QQ", openid, channelId, messageId), data, text)
        } else {
            Logger.i(TAG, "QQ 单聊消息无可用文本,跳过($messageId ← $openid)")
        }
    }

    private fun handleGroupMessage(data: JsonObject, channelId: String) {
        val groupOpenId = data["group_openid"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val target = "group:$groupOpenId"
        val messageId = data["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (messageId.isNotBlank() && QqMsgIdCache.get(target) == messageId) {
            Logger.i(TAG, "QQ 群消息重复推送,已忽略($messageId)")
            return
        }
        if (groupOpenId.isNotBlank() && messageId.isNotBlank()) QqMsgIdCache.put(target, messageId)
        val text = resolveInboundText(data)
        if (groupOpenId.isNotBlank() && text.isNotBlank()) {
            ChannelRuntimeRegistry.markInbound(channelId, platform)
            recordQqInbound(
                appScope,
                ChannelInbox.Source(
                    platform = "QQ",
                    from = target,
                    channelId = channelId,
                    eventId = messageId,
                    isGroup = true,
                    isMentioned = true,
                ),
                data,
                text,
            )
        } else {
            Logger.i(TAG, "QQ 群消息无可用文本,跳过($messageId ← $target)")
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

    companion object {
        private const val TAG = "QqReceiver"

        /** 重连退避(毫秒)。 */
        private const val RECONNECT_DELAY_MS = 5_000L

        /** 默认心跳周期(Hello 帧会覆盖)。 */
        private const val DEFAULT_HEARTBEAT_MS = 45_000L
    }
}

/** GROUP_AND_C2C_EVENT (1 << 25) — 单聊消息 + 群@消息。 */
private const val GROUP_AND_C2C_INTENT = 1 shl 25

/** Resume 载荷 — 带上一次会话的 session_id 与最后收到的 seq,平台据此补发断档事件。 */
private fun resumePayload(accessToken: String, sessionId: String, seq: Long): String = buildJsonObject {
    put("op", 6)
    putJsonObject("d") {
        put("token", "QQBot $accessToken")
        put("session_id", sessionId)
        put("seq", seq)
    }
}.toString()

/** Identify 载荷(token=QQBot {access_token};shard 单分片 [0,1])。 */
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
