package io.zer0.muse.channel

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import io.zer0.muse.data.SecureKeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal const val WECLAW_REPLY_CONTEXT_TOKEN_PREFIX = "muse.weclaw.context.v1:"
private const val WECLAW_CURSOR_PREFIX = "muse.weclaw.cursor.v1:"

internal suspend fun protectWeClawReplyContextToken(token: String): String =
    if (token.isBlank()) "" else SecureKeyStore.encrypt(WECLAW_REPLY_CONTEXT_TOKEN_PREFIX + token)

internal suspend fun restoreWeClawReplyContextToken(encryptedToken: String): String? = SecureKeyStore.decryptOrNull(encryptedToken)
    ?.takeIf { it.startsWith(WECLAW_REPLY_CONTEXT_TOKEN_PREFIX) }
    ?.removePrefix(WECLAW_REPLY_CONTEXT_TOKEN_PREFIX)
    ?.takeIf { it.isNotBlank() }

internal suspend fun protectWeClawCursor(cursor: String): String =
    if (cursor.isBlank()) "" else SecureKeyStore.encrypt(WECLAW_CURSOR_PREFIX + cursor)

internal suspend fun restoreWeClawCursor(storedCursor: String): String {
    if (storedCursor.isBlank()) return ""
    val plaintext = SecureKeyStore.decryptOrNull(storedCursor)
        ?: error("无法解密 WeClaw 同步游标")
    return when {
        plaintext.startsWith(WECLAW_CURSOR_PREFIX) -> plaintext.removePrefix(WECLAW_CURSOR_PREFIX)
        storedCursor.startsWith("enc_v1:") -> error("WeClaw 同步游标密文格式不匹配")
        else -> plaintext
    }
}

/**
 * v2.0: 微信 ClawBot(iLink)接收器 — 长轮询循环（支持多实例并行）。
 */
class WeClawReceiver(
    private val channelManager: ChannelManager,
    private val context: Context,
    private val appScope: CoroutineScope,
) : ChannelReceiver {
    override val platform: ChannelPlatform = ChannelPlatform.WECLAW

    private val lock = Any()
    private var watcherJob: Job? = null
    private val channelJobs = ConcurrentHashMap<String, Job>()
    private val channelFingerprints = ConcurrentHashMap<String, String>()

    private data class PollCursor(
        val channelId: String = "",
        val buffer: String = "",
        /** 这份游标属于哪个 bot token;凭据一变旧游标就不能再用。 */
        val secretFingerprint: String = "",
        /** 连续失败次数,用于失效游标的自愈。 */
        val failures: Int = 0,
    )

    /** 启动入口:刷新持久配置并对齐可用渠道的长轮询协程。 */
    suspend fun startForEnabledChannels() {
        channelManager.refresh()
        restart()
    }

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

    /** 停止轮询(App 关闭或渠道被删时)。 */
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
            val active = channels.filter { it.isUsableWeClaw() }
            val activeMap = active.associateBy { it.id }
            val allIds = channels.filter { it.platform == platform }.mapTo(HashSet()) { it.id }

            channelJobs.keys.toList().forEach { id ->
                val nextCfg = activeMap[id]
                val nextFp = nextCfg?.let { weClawSecretFingerprint(it.appSecret) }
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
                    channelFingerprints[cfg.id] = weClawSecretFingerprint(cfg.appSecret)
                    channelJobs[cfg.id] = appScope.launch {
                        pollLoopForChannel(cfg)
                    }
                }
            }
        }
    }

    private suspend fun pollLoopForChannel(config: ChannelConfig) {
        var cursor = PollCursor()
        ChannelRuntimeRegistry.markConnecting(config.id, platform)
        while (currentCoroutineContext().isActive) {
            cursor = pollOnce(config, cursor)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Transport, Keystore, and inbox failures all require retrying the same cursor.
    private suspend fun pollOnce(config: ChannelConfig, previousCursor: PollCursor): PollCursor {
        val fingerprint = weClawSecretFingerprint(config.appSecret)
        val cursor = if (previousCursor.channelId == config.id && previousCursor.secretFingerprint == fingerprint) {
            previousCursor
        } else {
            PollCursor(
                channelId = config.id,
                buffer = loadBuffer(config.id, fingerprint),
                secretFingerprint = fingerprint,
            )
        }
        return try {
            val updates = WeClawClient.getUpdates(config.appSecret, cursor.buffer).getOrThrow()
            ChannelRuntimeRegistry.markConnected(config.id, platform, config.botDisplayName)
            val nextBuffer = updates.buffer.ifBlank { cursor.buffer }
            updates.messages.forEachIndexed { index, msg ->
                WeClawContextCache.put(msg.fromUserId, msg.contextToken)
                val eventId = weClawBatchEventId(cursor.buffer, nextBuffer, index, msg)
                val source = ChannelInbox.Source(
                    platform = "WECLAW",
                    from = msg.fromUserId,
                    channelId = config.id,
                    eventId = eventId,
                    encryptedReplyContextToken = protectWeClawReplyContextToken(msg.contextToken),
                    isGroup = false,
                )
                ChannelRuntimeRegistry.markInbound(config.id, platform)
                val media = msg.media
                if (media == null) {
                    ChannelInbox.record(source, msg.text, "")
                } else {
                    handleMediaMessage(msg, media, source)
                }
            }
            if (nextBuffer != cursor.buffer) saveBuffer(config.id, fingerprint, nextBuffer)
            PollCursor(channelId = config.id, buffer = nextBuffer, secretFingerprint = fingerprint)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failures = cursor.failures + 1
            Logger.w(TAG, "WeClaw 更新未能可靠入队(第 $failures 次): ${error.message}")
            if (ChannelRuntimeRegistry.looksLikeAuthError(error.message)) {
                ChannelRuntimeRegistry.markAuthError(config.id, platform, error.message.orEmpty())
            } else {
                ChannelRuntimeRegistry.markReconnecting(config.id, platform, error.message.orEmpty())
            }
            if (cursor.buffer.isNotBlank() && failures >= MAX_CONSECUTIVE_FAILURES) {
                // 游标可能已失效(服务端会话重置/绑定后台数据被清)。带着它只会永远失败,
                // 清空后重新同步一次,好过让用户以为「只能发不能收」而去重装。
                saveBuffer(config.id, fingerprint, "")
                Logger.w(TAG, "WeClaw 连续失败 $failures 次,已丢弃本地游标重新同步")
                delay(RETRY_DELAY_MS)
                return PollCursor(channelId = config.id, secretFingerprint = fingerprint)
            }
            delay(RETRY_DELAY_MS)
            cursor.copy(failures = failures)
        }
    }

    private suspend fun loadBuffer(channelId: String, secretFingerprint: String): String {
        val marker = fingerprintFile(channelId)
        val stored = if (marker.exists()) marker.readText().trim() else ""
        if (stored != secretFingerprint) {
            // 凭据变了(解绑后重新扫码、换了 bot token):旧游标属于上一个会话。
            // 继续带着它,服务端会认为「你已经读到那个位置」——只能发不能收,且不会自愈。
            cursorFile(channelId).delete()
            AtomicFileStore.writeText(marker, secretFingerprint)
            Logger.i(TAG, "WeClaw 凭据已变更,本地游标已重置")
            return ""
        }
        val cursorFile = cursorFile(channelId)
        if (!cursorFile.exists()) return ""
        return restoreWeClawCursor(cursorFile.readText())
    }

    private suspend fun saveBuffer(channelId: String, secretFingerprint: String, buffer: String) {
        AtomicFileStore.writeText(cursorFile(channelId), protectWeClawCursor(buffer))
        AtomicFileStore.writeText(fingerprintFile(channelId), secretFingerprint)
    }

    private fun cursorFile(channelId: String): File {
        val digest = channelDigest(channelId)
        return File(context.filesDir, "channel_weclaw_cursor_$digest.txt")
    }

    /**
     * 游标归属指纹:记录这份游标属于哪个 bot token。
     * 凭据一变就作废旧游标,避免解绑重新绑定后永久收不到消息。
     */
    private fun fingerprintFile(channelId: String): File {
        val digest = channelDigest(channelId)
        return File(context.filesDir, "channel_weclaw_cursor_$digest.fp")
    }

    /**
     * v2.0.1: 媒体消息处理 — 图片下载(CDN + AES 解密)并压缩入库;
     * 语音使用服务端转写(parseMessages 已填充);
     * v2.x (B4): 视频/文件下载到私有目录并记录本地路径(文本占位升级为含文件名/路径)。
     */
    private suspend fun handleMediaMessage(msg: WeClawClient.InboundMsg, media: WeClawClient.MediaRef, source: ChannelInbox.Source) {
        val from = source.from
        when (media.kind) {
            "image" -> {
                val bytes = withTimeoutOrNull(MEDIA_DOWNLOAD_TIMEOUT_MS) {
                    WeClawClient.downloadMedia(media).getOrNull()
                }
                val base64 = bytes?.let { ChannelMediaUtils.toCompactImageBase64(it) }
                if (base64 != null) {
                    ChannelInbox.record(
                        source = source,
                        text = "[图片]",
                        rawPayload = "",
                        media = ChannelInbox.Media(kind = "image", base64 = base64),
                    )
                } else {
                    Logger.w(TAG, "图片下载或解码失败(from=$from)")
                    ChannelInbox.record(
                        source,
                        "[图片(未能获取)]",
                        "",
                    )
                }
            }
            "voice" -> {
                // iLink 语音自带服务端 ASR 转写;无转写时给占位。
                ChannelInbox.record(
                    source,
                    msg.text.ifBlank { "[语音]" },
                    "",
                )
            }
            // v2.x (B4): 视频/文件 — 下载 + 落盘 + 记录(含文件名与路径)
            "video" -> handleBinaryMedia(media, source, kind = "video", label = "[视频]")
            else -> handleBinaryMedia(media, source, kind = "file", label = "[文件]")
        }
    }

    /**
     * v2.x (B4): 视频/文件入站 — 下载并解密到 [MEDIA_DIR],记录含文件名与本地路径。
     *
     * v2.x 遗留收尾:改为**流式**下载(边下边写文件),不再一次性读入内存;
     * [MAX_MEDIA_SAVE_BYTES] 保护在流内生效(超出立即中止并删除半成品)。
     * 失败/超限/超时降级为占位文本(不抛异常,不阻断后续轮询)。
     */
    @Suppress("TooGenericExceptionCaught") // Cleanup must run for every persistence failure after a media file is created.
    private suspend fun handleBinaryMedia(
        media: WeClawClient.MediaRef,
        source: ChannelInbox.Source,
        kind: String,
        label: String,
    ) {
        val from = source.from
        val channelId = source.channelId
        val eventId = source.eventId
        val target = File(
            File(context.filesDir, MEDIA_DIR),
            "${System.currentTimeMillis()}_${defaultWeClawMediaFileName(media, kind)}",
        )
        val result = withTimeoutOrNull(MEDIA_DOWNLOAD_TIMEOUT_MS) {
            WeClawClient.downloadMediaToFile(media, target, MAX_MEDIA_SAVE_BYTES)
        }
        if (result == null) {
            Logger.w(TAG, "媒体下载超时(kind=$kind, from=$from)")
            ChannelInbox.record(
                source,
                "$label(未能获取)",
                "",
                media = ChannelInbox.Media(kind = kind),
            )
            return
        }
        val written = result.getOrNull()
        if (written == null) {
            val error = result.exceptionOrNull()
            if (error is MediaTooLargeException) {
                Logger.w(TAG, "媒体超出大小上限(kind=$kind, from=$from)")
                ChannelInbox.record(
                    source,
                    "$label(超出大小上限 ${MAX_MEDIA_SAVE_BYTES / 1024 / 1024}MB,未落盘)",
                    "",
                    ChannelInbox.Media(kind = kind),
                )
            } else {
                Logger.w(TAG, "媒体下载失败(kind=$kind, from=$from, err=${error?.message})")
                ChannelInbox.record(
                    source,
                    "$label(未能获取)",
                    "",
                    media = ChannelInbox.Media(kind = kind),
                )
            }
            return
        }
        val displayName = media.fileName.ifBlank { target.name }
        try {
            val accepted = ChannelInbox.record(
                source = source,
                text = "$label $displayName",
                rawPayload = "",
                media = ChannelInbox.Media(kind = kind, path = target.absolutePath),
            )
            if (!accepted) target.delete()
        } catch (error: Exception) {
            val eventStored = ChannelInbox.messages.value.any {
                it.sourceChannelId == channelId && it.sourceEventId == eventId
            }
            if (!eventStored) target.delete()
            throw error
        }
    }

    companion object {
        private const val TAG = "WeClawReceiver"

        /** 出错重试退避(毫秒)。 */
        private const val RETRY_DELAY_MS = 5_000L

        /** 连续失败多少次后丢弃可能有问题的本地游标。 */
        private const val MAX_CONSECUTIVE_FAILURES = 3

        /** v2.0.1: 媒体下载超时(毫秒)。 */
        private const val MEDIA_DOWNLOAD_TIMEOUT_MS = 60_000L

        /** v2.x (B4): 视频/文件落盘目录(私有 filesDir 下)。 */
        private const val MEDIA_DIR = "channel_media"

        /** v2.x (B4): 视频/文件落盘大小上限(32MB),超出仅记录占位。 */
        private const val MAX_MEDIA_SAVE_BYTES = 32L * 1024 * 1024
    }
}

/**
 * 渠道可用性 + 当前是否在跑 → 该怎么做什么。
 *
 * 「两边一致就 NONE」是防自触发的全部秘密:stop 之后渠道没变,同一份值再进来也是 NONE,
 * 不会把刚停掉的循环重新拉起来;真要重新拉起,只能等渠道配置真的变回「有启用渠道」。
 */
internal fun weClawPollAction(available: Boolean, running: Boolean): WeClawPollAction = when {
    available && !running -> WeClawPollAction.START
    !available && running -> WeClawPollAction.STOP
    else -> WeClawPollAction.NONE
}

/** [weClawPollAction] 的三种结果。 */
internal enum class WeClawPollAction { START, STOP, NONE }

/** 这条渠道配置能不能撑起 WECLAW 长轮询(enabled + 平台匹配 + 有密钥)。 */
private fun ChannelConfig.isUsableWeClaw(): Boolean = enabled && platform == ChannelPlatform.WECLAW && appSecret.isNotBlank()

/**
 * 渠道列表里有没有能跑 WECLAW 长轮询的那一条。
 *
 * 抽成纯函数是为了让「启动门禁」可以被单测直接喂假配置,而不是只能靠真机验证:
 * 空列表、只有停用渠道、密钥为空、平台不是 WECLAW 都不该把接收循环拉起来。
 */
internal fun weClawChannelAvailable(channels: List<ChannelConfig>): Boolean = channels.any { it.isUsableWeClaw() }

private fun defaultWeClawMediaFileName(media: WeClawClient.MediaRef, kind: String): String {
    val sanitized = sanitizeWeClawFileName(media.fileName)
    if (sanitized.isNotBlank()) return sanitized
    return "${kind}${if (kind == "video") ".mp4" else ".bin"}"
}

private fun sanitizeWeClawFileName(raw: String): String = raw.substringAfterLast('/').substringAfterLast('\\')
    .filter { it.code >= 0x20 && it.code != 0x7F }
    .trim()
    .take(120)

/**
 * 渠道可用性变化时该记的那一条日志;不需要记就返回 null。
 *
 * 接收循环必须每 30 秒重试一次(见 [WeClawReceiver.pollLoop] 里的 v2.6.7 注释),
 * 所以「无渠道」是个会长期重复的状态,而不是一个事件。把它当事件记一次,
 * 状态真正变化时才再记 —— 既保留「什么时候开始收不到」这个关键时间点,
 * 也不会让日志被同一句话刷屏。
 *
 * @param hasChannel 本轮是否找到了可用渠道
 * @param previousHasChannel 上一轮的结果;null 表示这是循环的第一轮
 * @return 本条日志文本;null 表示状态未变、不该记
 */
internal fun channelAvailabilityLog(hasChannel: Boolean, previousHasChannel: Boolean?): String? = when {
    previousHasChannel == null && hasChannel -> "接收循环启动,当前有可用渠道"
    previousHasChannel == null -> "无启用中的 ClawBot 渠道,30 秒后重试"
    previousHasChannel == hasChannel -> null
    hasChannel -> "渠道恢复,继续接收"
    else -> "无启用中的 ClawBot 渠道,30 秒后重试"
}

internal fun weClawBatchEventId(requestBuffer: String, responseBuffer: String, index: Int, message: WeClawClient.InboundMsg): String {
    val media = message.media
    val fingerprint = listOf(
        requestBuffer,
        responseBuffer,
        index.toString(),
        message.fromUserId,
        message.text,
        media?.kind.orEmpty(),
        media?.fileName.orEmpty(),
        media?.durationMs?.toString().orEmpty(),
        media?.fullUrl.orEmpty(),
        media?.encryptedQueryParam.orEmpty(),
        media?.aesKeyBase64.orEmpty(),
    ).joinToString("\u0000")
    return "weclaw-${channelDigest(fingerprint)}"
}

private fun channelDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

/** 凭据指纹:只用于判定游标是否属于同一个 bot token,不可逆且只取前 16 位。 */
private fun weClawSecretFingerprint(secret: String): String = channelDigest(secret).take(16)
