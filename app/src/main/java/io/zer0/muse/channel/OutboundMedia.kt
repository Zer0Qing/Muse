package io.zer0.muse.channel

/**
 * 待发送的媒体。
 *
 * 三种来源对应不同的上传路径：内存字节、本地文件、公开地址。
 * 具体走哪种由各平台的能力表决定（见 [MediaCapabilities.inputModes]），
 * 调用方不需要知道平台差异。
 */
sealed interface OutboundMedia {
    /** 媒体类型，决定投递方式。 */
    val kind: MediaKind

    /** 文件名，部分平台必填（如飞书上传需要）。 */
    val fileName: String

    /** MIME 类型。 */
    val mimeType: String

    /** 内存字节。 */
    data class Bytes(
        override val kind: MediaKind,
        val bytes: ByteArray,
        override val fileName: String,
        override val mimeType: String,
    ) : OutboundMedia

    /** 本地文件路径。 */
    data class LocalFile(
        override val kind: MediaKind,
        val path: String,
        override val fileName: String,
        override val mimeType: String,
    ) : OutboundMedia

    /** 公开可访问的地址。 */
    data class RemoteUrl(
        override val kind: MediaKind,
        val url: String,
        override val fileName: String,
        override val mimeType: String,
    ) : OutboundMedia
}

/** 媒体发送结果。失败时给出可读原因，便于回执给用户。 */
data class MediaSendResult(
    val ok: Boolean,
    val detail: String = "",
) {
    companion object {
        fun success(): MediaSendResult = MediaSendResult(ok = true)
        fun failure(reason: String): MediaSendResult = MediaSendResult(ok = false, detail = reason)
    }
}

/**
 * 检查某个渠道能否按指定方式发送某种媒体。
 *
 * 返回 null 表示可以发；否则返回不可发的原因。
 * 上限判断按 [MediaInputMode] 区分，因为同一媒体走内存和走 URL 限制不同。
 */
fun ChannelPlatform.checkMediaSendable(media: OutboundMedia): String? {
    val caps = capabilities.mediaCapabilities
    val mode = when (media) {
        is OutboundMedia.Bytes -> MediaInputMode.BUFFER
        is OutboundMedia.LocalFile -> MediaInputMode.LOCAL_FILE
        is OutboundMedia.RemoteUrl -> MediaInputMode.PUBLIC_URL
    }
    val actual = when (media) {
        is OutboundMedia.Bytes -> media.bytes.size.toLong()
        is OutboundMedia.LocalFile -> runCatching { java.io.File(media.path).length() }.getOrDefault(0L)
        // 远端地址无法预知大小，交给平台自己判断。
        is OutboundMedia.RemoteUrl -> 0L
    }
    return when {
        !caps.supports(media.kind) ->
            "$name 不支持发送 ${media.kind.name.lowercase()}"
        mode !in caps.inputModes ->
            "$name 不支持以 ${mode.name.lowercase()} 方式发送"
        else -> {
            val limit = caps.limitFor(mode, media.kind)
            if (actual > 0L && limit > 0L && actual > limit) {
                val mb = actual / (1024.0 * 1024.0)
                val limitMb = limit / (1024.0 * 1024.0)
                "$name 的 ${media.kind.name.lowercase()} 上限是 " +
                    "${"%.1f".format(limitMb)}MB，当前 ${"%.1f".format(mb)}MB"
            } else {
                null
            }
        }
    }
}

/**
 * 待上传的内容。
 *
 * 各平台的上传接口参数高度相似（字节 + 文件名 + MIME），
 * 打包成一个对象避免每个方法都摊开七八个参数。
 */
data class MediaPayload(
    val kind: MediaKind,
    val bytes: ByteArray,
    val fileName: String,
    val mimeType: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MediaPayload) return false
        return kind == other.kind &&
            bytes.contentEquals(other.bytes) &&
            fileName == other.fileName &&
            mimeType == other.mimeType
    }

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + mimeType.hashCode()
        return result
    }

    companion object {
        /** 从 [OutboundMedia] 读取字节；本地文件读盘、远端地址下载。 */
        suspend fun from(media: OutboundMedia): MediaPayload? {
            val bytes = when (media) {
                is OutboundMedia.Bytes -> media.bytes
                is OutboundMedia.LocalFile ->
                    runCatching { java.io.File(media.path).readBytes() }.getOrNull()
                is OutboundMedia.RemoteUrl -> ChannelImageFetcher.downloadRaw(media.url)
            } ?: return null
            return MediaPayload(
                kind = media.kind,
                bytes = bytes,
                fileName = media.fileName,
                mimeType = media.mimeType,
            )
        }
    }
}

/**
 * 上传目标。
 *
 * 各平台都需要「发到哪个会话」，只是字段名不同。
 */
data class UploadTarget(
    /** 平台侧的目标标识（chat_id / openid / openConversationId 等）。 */
    val id: String,
    /** 是否群会话。 */
    val isGroup: Boolean,
)
