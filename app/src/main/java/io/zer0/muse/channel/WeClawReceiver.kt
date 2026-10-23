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
 * v2.0: 微信 ClawBot(iLink)接收器 — 长轮询循环。
 *
 * 存在启用中的 WECLAW 渠道时循环调用 getupdates(长轮询约 30s):
 * 用户消息写入 [ChannelInbox](触发自动回复链路),context_token 写入
 * 加密暂存于 inbox 直到派发完成,同时更新 [WeClawContextCache] 供其他回发路径使用。
 * 配置保存/删除后由 UI 调用 [restart]。
 */
class WeClawReceiver(
    private val channelManager: ChannelManager,
    private val context: Context,
    private val appScope: CoroutineScope,
) {
    private var job: Job? = null
    private data class PollCursor(val channelId: String = "", val buffer: String = "")

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
        var cursor = PollCursor()
        while (currentCoroutineContext().isActive) {
            channelManager.refresh()
            val config = channelManager.channels.value.firstOrNull {
                it.enabled && it.platform == ChannelPlatform.WECLAW && it.appSecret.isNotBlank()
            }
            if (config == null) {
                Logger.i(TAG, "无启用中的 ClawBot 渠道,接收循环退出")
                // v2.6.7 fix (AUDIT F17): 不要 return —— 旧实现让整个接收 job 终结且无人重启，
                // 用户停用再启用渠道后就再也收不到消息，直到重启 App 或再点一次保存。
                delay(30_000L)
                continue
            }
            cursor = pollOnce(config, cursor)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Transport, Keystore, and inbox failures all require retrying the same cursor.
    private suspend fun pollOnce(config: ChannelConfig, previousCursor: PollCursor): PollCursor = try {
        val cursor = if (previousCursor.channelId == config.id) {
            previousCursor
        } else {
            PollCursor(channelId = config.id, buffer = loadBuffer(config.id))
        }
        val updates = WeClawClient.getUpdates(config.appSecret, cursor.buffer).getOrThrow()
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
            )
            val media = msg.media
            if (media == null) {
                ChannelInbox.record(source, msg.text, "")
            } else {
                handleMediaMessage(msg, media, source)
            }
        }
        if (nextBuffer != cursor.buffer) saveBuffer(config.id, nextBuffer)
        PollCursor(channelId = config.id, buffer = nextBuffer)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Logger.w(TAG, "WeClaw 更新未能可靠入队,保留轮询游标: ${error.message}")
        delay(RETRY_DELAY_MS)
        previousCursor
    }

    private suspend fun loadBuffer(channelId: String): String {
        val cursorFile = cursorFile(channelId)
        if (!cursorFile.exists()) return ""
        return restoreWeClawCursor(cursorFile.readText())
    }

    private suspend fun saveBuffer(channelId: String, buffer: String) {
        AtomicFileStore.writeText(cursorFile(channelId), protectWeClawCursor(buffer))
    }

    private fun cursorFile(channelId: String): File {
        val digest = channelDigest(channelId)
        return File(context.filesDir, "channel_weclaw_cursor_$digest.txt")
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

        /** v2.0.1: 媒体下载超时(毫秒)。 */
        private const val MEDIA_DOWNLOAD_TIMEOUT_MS = 60_000L

        /** v2.x (B4): 视频/文件落盘目录(私有 filesDir 下)。 */
        private const val MEDIA_DIR = "channel_media"

        /** v2.x (B4): 视频/文件落盘大小上限(32MB),超出仅记录占位。 */
        private const val MAX_MEDIA_SAVE_BYTES = 32L * 1024 * 1024
    }
}

private fun defaultWeClawMediaFileName(media: WeClawClient.MediaRef, kind: String): String {
    val sanitized = sanitizeWeClawFileName(media.fileName)
    if (sanitized.isNotBlank()) return sanitized
    return "${kind}${if (kind == "video") ".mp4" else ".bin"}"
}

private fun sanitizeWeClawFileName(raw: String): String = raw.substringAfterLast('/').substringAfterLast('\\')
    .filter { it.code >= 0x20 && it.code != 0x7F }
    .trim()
    .take(120)

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
