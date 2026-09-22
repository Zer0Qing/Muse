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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * v2.0: 钉钉接收器 — Stream 模式 WebSocket 长连接。
 *
 * 流程:注册连接凭证 → 建立 WebSocket → 处理推送帧:
 *  - CALLBACK 机器人消息 → 解析(文本 / sessionWebhook / 发送人)→ 写入 [ChannelInbox];
 *  - SYSTEM ping → 回 ACK(opaque 原样带回);
 *  - SYSTEM disconnect → 主动断开,由外层循环重新建连。
 *
 * 断开后按 [RECONNECT_DELAY_MS] 退避重连;无启用渠道时退出循环。
 * 出站连接,免公网与隧道。
 */
class DingtalkReceiver(
    private val channelManager: ChannelManager,
    private val context: Context,
    private val appScope: CoroutineScope,
) {
    private var job: Job? = null

    /** 启动长连接(幂等;已有连接先停再起)。 */
    fun restart() {
        job?.cancel()
        job = appScope.launch {
            ChannelInbox.attach(context)
            connectLoop()
        }
    }

    /** 停止长连接。 */
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun connectLoop() {
        while (currentCoroutineContext().isActive) {
            channelManager.refresh()
            val config = channelManager.channels.value.firstOrNull {
                it.enabled && it.platform == ChannelPlatform.DINGTALK &&
                    it.appId.isNotBlank() && it.appSecret.isNotBlank()
            }
            if (config == null) {
                Logger.i(TAG, "无启用中的钉钉渠道,接收循环退出")
                return
            }
            runCatching {
                connectOnce(config.appId, config.appSecret)
            }.onFailure { e -> Logger.w(TAG, "钉钉 Stream 连接异常: ${e.message}") }
            if (!currentCoroutineContext().isActive) return
            delay(RECONNECT_DELAY_MS)
        }
    }

    /** 建立一次 WebSocket 并挂起至断开。 */
    private suspend fun connectOnce(clientId: String, clientSecret: String) {
        val connection = DingtalkClient.openStreamConnection(clientId, clientSecret).getOrElse { e ->
            Logger.w(TAG, "钉钉 Stream 注册失败: ${e.message}")
            return
        }
        val disconnected = CompletableDeferred<Unit>()
        val request = Request.Builder().url(DingtalkClient.streamUrl(connection)).build()
        val socket = DingtalkClient.WEB_SOCKET.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching { handleFrame(webSocket, text) }
                        .onFailure { e -> Logger.w(TAG, "钉钉帧处理失败: ${e.message}") }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Logger.i(TAG, "钉钉 WebSocket 关闭: code=$code $reason")
                    disconnected.complete(Unit)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Logger.w(TAG, "钉钉 WebSocket 断开: ${t.message}")
                    disconnected.complete(Unit)
                }
            },
        )
        Logger.i(TAG, "钉钉 Stream 已连接")
        try {
            disconnected.await()
        } finally {
            socket.cancel()
        }
    }

    /** 处理 Stream 推送帧(协议见钉钉开发者文档)。 */
    private fun handleFrame(webSocket: WebSocket, frame: String) {
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
                handleRobotMessage(data)
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
    private fun handleRobotMessage(dataJson: String) {
        val payload = runCatching { AppJson.parseToJsonElement(dataJson).jsonObject }.getOrNull() ?: return
        // text 字段为对象 {"content": "..."};防御式兼容纯字符串形式
        val content = (payload["text"] as? JsonObject)
            ?.get("content")?.jsonPrimitive?.contentOrNull
            ?: payload["text"]?.jsonPrimitive?.contentOrNull
        val text = content?.trim().orEmpty()
        if (text.isBlank()) return
        val conversationId = payload["conversationId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val senderStaffId = payload["senderStaffId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val conversationType = payload["conversationType"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val webhook = payload["sessionWebhook"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val expireAt = payload["sessionWebhookExpiredTime"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        // 群/单聊双键缓存,回发侧无需区分来源类型
        DingtalkSessionCache.put(listOf(conversationId, senderStaffId), webhook, expireAt)
        // 群聊(conversationType=2)用会话 id,单聊用发送人 id,与发送侧目标语义一致
        val from = if (conversationType == "2") conversationId else senderStaffId
        ChannelInbox.record("DINGTALK", from, text, dataJson)
    }

    companion object {
        private const val TAG = "DingtalkReceiver"

        /** 断开后重连退避(毫秒)。 */
        private const val RECONNECT_DELAY_MS = 5_000L
    }
}
