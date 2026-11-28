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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap

internal fun sanitizeDingtalkPayloadForStorage(payload: JsonObject): String = JsonObject(
    payload.toMutableMap().apply {
        remove("sessionWebhook")
        remove("sessionWebhookExpiredTime")
    },
).toString()

/**
 * v2.0: 钉钉接收器 — Stream 模式 WebSocket 长连接（支持多实例并行）。
 */
class DingtalkReceiver(
    private val channelManager: ChannelManager,
    private val context: Context,
    private val appScope: CoroutineScope,
) : ChannelReceiver {
    override val platform: ChannelPlatform = ChannelPlatform.DINGTALK

    private val lock = Any()
    private var watcherJob: Job? = null
    private val channelJobs = ConcurrentHashMap<String, Job>()
    private val channelFingerprints = ConcurrentHashMap<String, String>()

    /** 启动长连接(幂等;已有连接先停再起)。 */
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

    /** 停止长连接。 */
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
                val nextFp = nextCfg?.let { "${it.dingtalkApiBase}:${it.appId}:${it.appSecret}" }
                if (nextCfg == null || channelFingerprints[id] != nextFp) {
                    channelJobs.remove(id)?.cancel()
                    channelFingerprints.remove(id)
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
                    channelFingerprints[cfg.id] = "${cfg.dingtalkApiBase}:${cfg.appId}:${cfg.appSecret}"
                    channelJobs[cfg.id] = appScope.launch {
                        connectLoopForChannel(cfg)
                    }
                }
            }
        }
    }

    private suspend fun connectLoopForChannel(config: ChannelConfig) {
        while (currentCoroutineContext().isActive) {
            ChannelRuntimeRegistry.markConnecting(config.id, platform)
            runCatching {
                connectOnce(config)
            }.onFailure { e ->
                Logger.w(TAG, "钉钉 Stream 连接异常: ${e.message}")
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
    private suspend fun connectOnce(config: ChannelConfig) {
        val connection = DingtalkClient.openStreamConnection(
            config.appId,
            config.appSecret,
            config.dingtalkApiBase,
        ).getOrElse { e ->
            Logger.w(TAG, "钉钉 Stream 注册失败: ${e.message}")
            if (ChannelRuntimeRegistry.looksLikeAuthError(e.message)) {
                ChannelRuntimeRegistry.markAuthError(config.id, platform, e.message.orEmpty())
            } else {
                ChannelRuntimeRegistry.markReconnecting(config.id, platform, e.message.orEmpty())
            }
            return
        }
        val disconnected = CompletableDeferred<Unit>()
        val request = Request.Builder().url(DingtalkClient.streamUrl(connection)).build()
        val socket = DingtalkClient.WEB_SOCKET.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    ChannelRuntimeRegistry.markConnected(config.id, platform, config.botDisplayName)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    appScope.launch {
                        runCatching { handleFrame(webSocket, text, config) }
                            .onFailure { e -> Logger.w(TAG, "钉钉帧处理失败: ${e.message}") }
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Logger.i(TAG, "钉钉 WebSocket 关闭: code=$code $reason")
                    ChannelRuntimeRegistry.markReconnecting(config.id, platform, reason)
                    disconnected.complete(Unit)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Logger.w(TAG, "钉钉 WebSocket 断开: ${t.message}")
                    ChannelRuntimeRegistry.markReconnecting(config.id, platform, t.message.orEmpty())
                    disconnected.complete(Unit)
                }
            },
        )
        Logger.i(TAG, "钉钉 Stream 已连接(${config.id})")
        ChannelRuntimeRegistry.markConnected(config.id, platform, config.botDisplayName)
        try {
            disconnected.await()
        } finally {
            socket.cancel()
        }
    }

    /** 处理 Stream 推送帧(协议见钉钉开发者文档)。 */
    private suspend fun handleFrame(webSocket: WebSocket, frame: String, config: ChannelConfig) {
        val obj = runCatching { AppJson.parseToJsonElement(frame).jsonObject }.getOrNull() ?: return
        val type = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val headers = obj["headers"] as? JsonObject
        val messageId = headers?.get("messageId")?.jsonPrimitive?.contentOrNull.orEmpty()
        val topic = headers?.get("topic")?.jsonPrimitive?.contentOrNull.orEmpty()
        val data = obj["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
        when {
            type == "SYSTEM" && topic == "ping" -> {
                val opaque = runCatching {
                    AppJson.parseToJsonElement(data).jsonObject["opaque"]?.jsonPrimitive?.contentOrNull
                }.getOrNull().orEmpty()
                webSocket.send(DingtalkClient.ackFrame(messageId, "{\"opaque\":\"$opaque\"}"))
            }
            type == "SYSTEM" && topic == "disconnect" -> {
                Logger.i(TAG, "钉钉服务端请求断开: ${data.take(200)}")
                webSocket.cancel()
            }
            type == "CALLBACK" && topic == "/v1.0/im/bot/messages/get" -> {
                handleRobotMessage(data, config, messageId)
                webSocket.send(DingtalkClient.ackFrame(messageId, "{\"response\": null}"))
            }
            else -> {
                // 其他推送(事件/卡片回调)礼貌 ACK,不影响主链路
                if (messageId.isNotBlank()) {
                    webSocket.send(DingtalkClient.ackFrame(messageId, "{\"response\": null}"))
                }
            }
        }
    }

    /** 解析机器人消息回调并写入收件箱。 */
    private suspend fun handleRobotMessage(dataJson: String, config: ChannelConfig, eventId: String) {
        val payload = runCatching { AppJson.parseToJsonElement(dataJson).jsonObject }.getOrNull() ?: return
        // text 字段为对象 {"content": "..."};防御式兼容纯字符串形式
        val content = (payload["text"] as? JsonObject)
            ?.get("content")?.jsonPrimitive?.contentOrNull
            ?: payload["text"]?.jsonPrimitive?.contentOrNull
        val text = content?.trim().orEmpty()

        // v2.7: 图片消息的 text 为空，不能在这里丢弃；先取 downloadCode。

        val downloadCode = ChannelImageFetcher.dingtalkDownloadCode(payload)

        if (text.isBlank() && downloadCode == null) return
        val conversationId = payload["conversationId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val senderStaffId = payload["senderStaffId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val conversationType = payload["conversationType"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val senderNick = payload["senderNick"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val conversationTitle = payload["conversationTitle"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val isInAtList = payload["isInAtList"]?.jsonPrimitive?.booleanOrNull == true
        val webhook = payload["sessionWebhook"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val expireAt = payload["sessionWebhookExpiredTime"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        // 群/单聊双键缓存,回发侧无需区分来源类型
        DingtalkSessionCache.put(listOf(conversationId, senderStaffId), webhook, expireAt)
        val encryptedReplyContextToken = protectDingtalkReplyContextToken(webhook)
        // 群聊(conversationType=2)用会话 id,单聊用发送人 id,与发送侧目标语义一致
        val isGroup = conversationType == "2"
        val from = if (isGroup) conversationId else senderStaffId
        val displayName = if (isGroup) conversationTitle else senderNick
        val source = ChannelInbox.Source(
            platform = "DINGTALK",
            from = from,
            channelId = config.id,
            eventId = eventId,
            encryptedReplyContextToken = encryptedReplyContextToken,
            isGroup = isGroup,
            isMentioned = isInAtList,
            senderDisplayName = displayName,
        )
        ChannelRuntimeRegistry.markInbound(config.id, platform)

        if (downloadCode == null) {
            ChannelInbox.record(source, text, sanitizeDingtalkPayloadForStorage(payload))
        } else {
            appScope.launch {
                val base64 = runCatching {
                    val token = DingtalkClient.fetchAccessToken(config.appId, config.appSecret, config.dingtalkApiBase)
                        .getOrNull()?.accessToken

                    if (token.isNullOrBlank()) {
                        null
                    } else {
                        ChannelImageFetcher.fetchDingTalk(
                            apiBase = config.dingtalkApiBase,
                            accessToken = token,
                            downloadCode = downloadCode,
                        )
                    }
                }.getOrNull()

                if (base64 != null) {
                    ChannelInbox.record(
                        source = source,
                        text = text.ifBlank { "[图片]" },
                        rawPayload = sanitizeDingtalkPayloadForStorage(payload),
                        media = ChannelInbox.Media(kind = "image", base64 = base64),
                    )
                } else {
                    Logger.w(TAG, "钉钉图片获取失败(from=$from)")

                    ChannelInbox.record(source, text.ifBlank { "[图片(未能获取)]" }, sanitizeDingtalkPayloadForStorage(payload))
                }
            }
        }
    }

    companion object {
        private const val TAG = "DingtalkReceiver"

        /** 断开后重连退避(毫秒)。 */
        private const val RECONNECT_DELAY_MS = 5_000L
    }
}
