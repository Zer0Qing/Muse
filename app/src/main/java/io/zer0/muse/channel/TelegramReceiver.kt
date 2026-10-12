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
            // v2.6.0 (AUD-CHANNEL-F2): 单轮轮询抽成函数 —— 游标失败/网络异常/
            // 写入异常统一返回“等待时长”,循环不再堆跳转语句。
            cursor = pollOnce(config, cursor)
            delay(RETRY_DELAY_MS)
        }
    }

    /**
     * 执行一次轮询,返回下一轮应使用的游标。
     *
     * 任何本地/网络异常都已在函数内记日志,不影响循环继续 —— 这是 F2 的核心:
     * 接收循环不得因为一次异常而永久退出。
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private suspend fun pollOnce(config: ChannelConfig, cursor: TelegramCursor): TelegramCursor {
        val next = nextCursorFor(config, cursor) ?: return cursor
        val updates = TelegramClient.getUpdates(config.appSecret, next.offset).getOrNull()
            ?: return next
        return try {
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
            if (nextOffset != next.offset) {
                saveOffset(config.id, nextOffset)
            }
            TelegramCursor(config.id, nextOffset)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Logger.w(TAG, "Telegram 更新未能写入本地收件箱,保留轮询游标: ${error.message}")
            next
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
    /**
     * v2.6.0 (AUD-CHANNEL-F2): 解析下一次轮询游标。
     *
     * 返回 null 表示本地游标读取失败(已记日志),由调用方退避重试 ——
     * 原实现在循环内直接读取并抛错,会让接收 job 永久退出。
     */
    private fun nextCursorFor(config: ChannelConfig, cursor: TelegramCursor): TelegramCursor? {
        if (cursor.channelId == config.id) return cursor
        return runCatching { TelegramCursor(config.id, loadOffset(config.id)) }
            .onFailure { error ->
                Logger.w(TAG, "Telegram 游标初始化失败: ${error.message}")
            }
            .getOrNull()
    }

    /**
     * v2.6.0 (AUD-CHANNEL-F2): offset 文件损坏时不再抛错。
     *
     * 损坏时视为“无游标”返回 null（从最新一条开始拉），最多重复消费几条，
     * 好过永久失联。
     */
    private fun loadOffset(channelId: String): Long? {
        val target = telegramOffsetFile(context, channelId)
        if (!target.exists()) return null
        val parsed = target.readText().trim().toLongOrNull()
        if (parsed == null) {
            Logger.w(TAG, "Telegram offset 文件损坏(内容非数字),丢弃游标重新拉取")
            // 清掉坏文件,避免每次启动都重复告警
            runCatching { target.delete() }
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
