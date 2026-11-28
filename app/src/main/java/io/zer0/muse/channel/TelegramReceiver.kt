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
import java.util.concurrent.ConcurrentHashMap

/**
 * v2.0: Telegram 接收器 — 长轮询循环（支持同平台多渠道并行在线）。
 */
class TelegramReceiver(
    private val channelManager: ChannelManager,
    private val context: Context,
    private val appScope: CoroutineScope,
) : ChannelReceiver {
    override val platform: ChannelPlatform = ChannelPlatform.TELEGRAM

    private val lock = Any()
    private var watcherJob: Job? = null
    private val channelJobs = ConcurrentHashMap<String, Job>()
    private val channelSecrets = ConcurrentHashMap<String, String>()

    /** 启动轮询(幂等;已有循环先停再起)。 */
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

    /** 停止轮询。 */
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
            channelSecrets.remove(id)
            ChannelRuntimeRegistry.markStopped(id, platform)
        }
    }

    private fun reconcile(channels: List<ChannelConfig>) {
        synchronized(lock) {
            val active = channels.filter {
                it.enabled && it.platform == platform && it.appSecret.isNotBlank()
            }
            val activeMap = active.associateBy { it.id }
            val allIds = channels.filter { it.platform == platform }.mapTo(HashSet()) { it.id }

            channelJobs.keys.toList().forEach { id ->
                val nextCfg = activeMap[id]
                val secretChanged = nextCfg != null && channelSecrets[id] != nextCfg.appSecret
                if (nextCfg == null || secretChanged) {
                    channelJobs.remove(id)?.cancel()
                    channelSecrets.remove(id)
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
                    channelSecrets[cfg.id] = cfg.appSecret
                    channelJobs[cfg.id] = appScope.launch {
                        pollLoopForChannel(cfg)
                    }
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Preserve the current getUpdates offset for any local persistence failure.
    private suspend fun pollLoopForChannel(config: ChannelConfig) {
        var cursor = TelegramCursor()
        ChannelRuntimeRegistry.markConnecting(config.id, platform)
        while (currentCoroutineContext().isActive) {
            val result = pollOnce(config, cursor)
            cursor = result
            delay(RETRY_DELAY_MS)
        }
    }

    /** 落库一条入站消息；图片走两步下载。 */
    private suspend fun recordInbound(config: ChannelConfig, msg: TelegramClient.InboundMsg) {
        val isGroup = msg.chatType == "group" || msg.chatType == "supergroup"
        val source = ChannelInbox.Source(
            platform = "TELEGRAM",
            from = msg.chatId.toString(),
            channelId = config.id,
            eventId = msg.updateId?.toString().orEmpty(),
            isGroup = isGroup,
            isMentioned = msg.isMentioned,
            senderDisplayName = msg.senderDisplayName,
        )
        ChannelRuntimeRegistry.markInbound(config.id, platform)

        val fileId = msg.photoFileId

        if (fileId.isNullOrBlank()) {
            ChannelInbox.record(source, msg.text, "")

            return
        }

        // 图片两步下载 —— getFile 换 file_path，再按路径取字节。

        val base64 = TelegramClient.getFile(config.appSecret, fileId)
            .getOrNull()

            ?.let { path -> TelegramClient.downloadFile(config.appSecret, path).getOrNull() }

            ?.let { ChannelMediaUtils.toCompactImageBase64(it) }

        if (base64 != null) {
            ChannelInbox.record(
                source = source,
                text = msg.text.ifBlank { "[图片]" },
                rawPayload = "",
                media = ChannelInbox.Media(kind = "image", base64 = base64),
            )
        } else {
            Logger.w(TAG, "Telegram 图片获取失败(chat=${msg.chatId})")

            ChannelInbox.record(source, msg.text.ifBlank { "[图片(未能获取)]" }, "")
        }
    }

    private fun reportPollFailure(channelId: String, error: Throwable) {
        val message = error.message.orEmpty()
        if (ChannelRuntimeRegistry.looksLikeAuthError(message)) {
            ChannelRuntimeRegistry.markAuthError(channelId, platform, message)
        } else {
            ChannelRuntimeRegistry.markReconnecting(channelId, platform, message)
        }
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private suspend fun pollOnce(config: ChannelConfig, cursor: TelegramCursor): TelegramCursor {
        val next = nextCursorFor(config, cursor) ?: return cursor
        // 以前是 `.getOrNull() ?: return next`：拉取失败被当成"这轮没有新消息"，
        // 一句日志都不留。同函数里图片失败、写库失败都有日志，只有这条最要命的没有 ——
        // 它坏了的表现是"用户从 Telegram 发消息永远没反应"，而日志查不到任何线索。
        val updates = TelegramClient.getUpdates(config.appSecret, next.offset)
            .onFailure { e -> Logger.w(TAG, telegramPollIssue(config.id, e), e) }
            .onFailure { e -> reportPollFailure(config.id, e) }
            .getOrNull()
            ?: return next
        ChannelRuntimeRegistry.markConnected(config.id, platform, config.botDisplayName)
        return try {
            updates.messages.forEach { msg -> recordInbound(config, msg) }
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

        /** 没有配置渠道时的退避。不参与轮询，只等配置出现，所以可以拉长。 */
        private const val NO_CHANNEL_BACKOFF_MS = 60_000L
    }
}

private data class TelegramCursor(val channelId: String = "", val offset: Long? = null)

internal fun telegramOffsetFile(context: Context, channelId: String): File =
    File(context.filesDir, "channel_telegram_offset_${telegramCursorDigest(channelId)}.txt")

private fun telegramCursorDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
