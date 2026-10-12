package io.zer0.muse.channel

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

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

    @Suppress("TooGenericExceptionCaught") // Preserve the current getUpdates offset for any local persistence failure.
    private suspend fun pollLoop() {
        var cursor = TelegramCursor()
        while (currentCoroutineContext().isActive) {
            channelManager.refresh()
            val config = channelManager.channels.value.firstOrNull {
                it.enabled && it.platform == ChannelPlatform.TELEGRAM && it.appSecret.isNotBlank()
            }
            if (config == null) {
                Logger.i(TAG, "无启用中的 Telegram 渠道,接收循环退出")
                return
            }
            if (cursor.channelId != config.id) {
                // v2.6.0 (AUD-CHANNEL-F2): 游标初始化包 try —— 本地游标若再出现任何
                // 读取异常,退避后重试即可,不能让整个接收 job 退出(退出即永久失联)。
                cursor = runCatching { TelegramCursor(config.id, loadOffset(config.id)) }
                    .getOrElse { error ->
                        Logger.w(TAG, "Telegram 游标初始化失败,退避重试: ${error.message}")
                        delay(RETRY_DELAY_MS)
                        continue
                    }
            }
            val updates = TelegramClient.getUpdates(config.appSecret, cursor.offset).getOrNull()
            if (updates == null) {
                // 网络异常/服务端错误:退避后重试
                delay(RETRY_DELAY_MS)
                continue
            }
            try {
                updates.messages.forEach { msg ->
                    ChannelInbox.record(
                        ChannelInbox.Source(
                            "TELEGRAM",
                            msg.chatId.toString(),
                            config.id,
                            msg.updateId?.toString().orEmpty(),
                        ),
                        msg.text,
                        "",
                    )
                }
                val nextOffset = updates.nextOffset
                if (nextOffset != cursor.offset) {
                    saveOffset(config.id, nextOffset)
                }
                cursor = TelegramCursor(config.id, nextOffset)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Logger.w(TAG, "Telegram 更新未能写入本地收件箱,保留轮询游标: ${error.message}")
                delay(RETRY_DELAY_MS)
            }
        }
    }

    /**
     * v2.6.0 (AUD-CHANNEL-F2): offset 文件损坏时不再抛错。
     *
     * 原实现 error(...) 在循环外抛出，会让整个接收 job 终结且无人重启 ——
     * 用户只能杀进程或进设置页手动触发，表现为「Telegram 再也收不到消息」。
     * 损坏时视为“无游标”返回 null（从最新一条开始拉），最多重复消费几条，
     * 好过永久失联。
     */
    private fun loadOffset(channelId: String): Long? {
        val target = telegramOffsetFile(context, channelId)
        if (!target.exists()) return null
        val raw = target.readText().trim()
        val parsed = raw.toLongOrNull()
        if (parsed == null) {
            Logger.w(TAG, "Telegram offset 文件损坏(内容非数字),丢弃游标重新拉取")
            // 清掉坏文件,避免每次启动都重复告警
            runCatching { target.delete() }
            return null
        }
        return parsed
    }

    private fun saveOffset(channelId: String, offset: Long?) {
        if (offset == null) return
        AtomicFileStore.writeText(telegramOffsetFile(context, channelId), offset.toString())
    }

    companion object {
        private const val TAG = "TelegramReceiver"

        /** 出错重试退避(毫秒)。 */
        private const val RETRY_DELAY_MS = 5_000L
    }
}

private data class TelegramCursor(val channelId: String = "", val offset: Long? = null)

internal fun telegramOffsetFile(context: Context, channelId: String): File =
    File(context.filesDir, "channel_telegram_offset_${telegramCursorDigest(channelId)}.txt")

private fun telegramCursorDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
