package io.zer0.muse.channel

import android.content.Context
import io.zer0.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v2.0: 微信 ClawBot(iLink)接收器 — 长轮询循环。
 *
 * 存在启用中的 WECLAW 渠道时循环调用 getupdates(长轮询约 30s):
 * 用户消息写入 [ChannelInbox](触发自动回复链路),context_token 写入
 * [WeClawContextCache] 供回发携带。配置保存/删除后由 UI 调用 [restart]。
 */
class WeClawReceiver(
    private val channelManager: ChannelManager,
    private val context: Context,
    private val appScope: CoroutineScope,
) {
    private var job: Job? = null

    /** 启动轮询(幂等;已有循环先停再起)。 */
    fun restart() {
        job?.cancel()
        job = appScope.launch {
            ChannelInbox.attach(context)
            pollLoop()
        }
    }

    /** 停止轮询(App 关闭或渠道被删时)。 */
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun pollLoop() {
        var buffer = ""
        while (currentCoroutineContext().isActive) {
            channelManager.refresh()
            val config = channelManager.channels.value.firstOrNull {
                it.enabled && it.platform == ChannelPlatform.WECLAW && it.appSecret.isNotBlank()
            }
            if (config == null) {
                Logger.i(TAG, "无启用中的 ClawBot 渠道,接收循环退出")
                return
            }
            val updates = WeClawClient.getUpdates(config.appSecret, buffer).getOrNull()
            if (updates == null) {
                // 网络异常/服务端错误:退避后重试
                delay(RETRY_DELAY_MS)
                continue
            }
            buffer = updates.buffer.ifBlank { buffer }
            updates.messages.forEach { msg ->
                WeClawContextCache.put(msg.fromUserId, msg.contextToken)
                val media = msg.media
                if (media == null) {
                    ChannelInbox.record("WECLAW", msg.fromUserId, msg.text, "")
                } else {
                    handleMediaMessage(msg, media)
                }
            }
        }
    }

    /**
     * v2.0.1: 媒体消息处理 — 图片下载(CDN + AES 解密)并压缩入库;
     * 语音使用服务端转写(parseMessages 已填充);视频/文件本轮仅占位。
     */
    private suspend fun handleMediaMessage(msg: WeClawClient.InboundMsg, media: WeClawClient.MediaRef) {
        val from = msg.fromUserId
        when (media.kind) {
            "image" -> {
                val bytes = withTimeoutOrNull(MEDIA_DOWNLOAD_TIMEOUT_MS) {
                    WeClawClient.downloadMedia(media).getOrNull()
                }
                val base64 = bytes?.let { ChannelMediaUtils.toCompactImageBase64(it) }
                if (base64 != null) {
                    ChannelInbox.record(
                        platform = "WECLAW",
                        from = from,
                        text = "[图片]",
                        rawPayload = "",
                        mediaKind = "image",
                        mediaBase64 = base64,
                    )
                } else {
                    Logger.w(TAG, "图片下载或解码失败(from=$from)")
                    ChannelInbox.record("WECLAW", from, "[图片(未能获取)]", "")
                }
            }
            "voice" -> {
                // iLink 语音自带服务端 ASR 转写;无转写时给占位。
                ChannelInbox.record("WECLAW", from, msg.text.ifBlank { "[语音]" }, "")
            }
            "video" -> ChannelInbox.record("WECLAW", from, "[视频]", "")
            else -> ChannelInbox.record(
                "WECLAW",
                from,
                "[文件: ${media.fileName.ifBlank { "未知" }}]",
                "",
            )
        }
    }

    companion object {
        private const val TAG = "WeClawReceiver"

        /** 出错重试退避(毫秒)。 */
        private const val RETRY_DELAY_MS = 5_000L

        /** v2.0.1: 媒体下载超时(毫秒)。 */
        private const val MEDIA_DOWNLOAD_TIMEOUT_MS = 60_000L
    }
}
