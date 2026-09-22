package io.zer0.muse.channel

import android.content.Context
import io.zer0.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * v2.0: Telegram 接收器 — 长轮询循环。
 *
 * 存在启用中的 TELEGRAM 渠道时循环调用 getUpdates(长轮询约 30s):
 * 用户消息写入 [ChannelInbox](触发自动回复链路)。出站连接,免公网。
 * 配置保存/删除后由 UI 或应用启动时调用 [restart]。
 */
class TelegramReceiver(
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

    /** 停止轮询。 */
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun pollLoop() {
        var offset: Long? = null
        while (currentCoroutineContext().isActive) {
            channelManager.refresh()
            val config = channelManager.channels.value.firstOrNull {
                it.enabled && it.platform == ChannelPlatform.TELEGRAM && it.appSecret.isNotBlank()
            }
            if (config == null) {
                Logger.i(TAG, "无启用中的 Telegram 渠道,接收循环退出")
                return
            }
            val updates = TelegramClient.getUpdates(config.appSecret, offset).getOrNull()
            if (updates == null) {
                // 网络异常/服务端错误:退避后重试
                delay(RETRY_DELAY_MS)
                continue
            }
            offset = updates.nextOffset
            updates.messages.forEach { msg ->
                ChannelInbox.record("TELEGRAM", msg.chatId.toString(), msg.text, "")
            }
        }
    }

    companion object {
        private const val TAG = "TelegramReceiver"

        /** 出错重试退避(毫秒)。 */
        private const val RETRY_DELAY_MS = 5_000L
    }
}
