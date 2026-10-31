package io.zer0.muse.web

import java.io.File
import java.util.Locale

/**
 * 媒体类型判定：先看文件头，再退到扩展名。
 *
 * 消息图片落盘时用的是 `.bin` 后缀（`{messageId}_{idx}_{hash}.bin`），扩展名给不出信息；
 * 语音存档的后缀来自 TTS 返回的容器（wav/m4a/mp3 都可能）。两侧都不可靠时，
 * 文件头是唯一的判据，所以先看头、后看名字。
 *
 * 返回 `application/octet-stream` 表示判不出来 —— 路由仍会把文件发出去，
 * 只是浏览器会当附件处理，不会因此 404。
 */
internal object HostMediaContentType {

    /** 读文件头用的字节数：够覆盖各格式的 magic number（最长的是 12 字节的 RIFF/WEBP）。 */
    private const val HEADER_BYTES = 16

    const val OCTET_STREAM = "application/octet-stream"

    private const val PNG = "image/png"
    private const val JPEG = "image/jpeg"
    private const val GIF = "image/gif"
    private const val WEBP = "image/webp"
    private const val MP3 = "audio/mpeg"
    private const val MP4 = "audio/mp4"
    private const val AAC = "audio/aac"
    private const val WAV = "audio/wav"
    private const val OGG = "audio/ogg"

    /** 扩展名 → MIME。与 [MIME_TO_EXTENSION] 互为反向，只收录媒体目录里真会出现的类型。 */
    private val EXTENSION_TO_MIME = mapOf(
        "png" to PNG,
        "jpg" to JPEG,
        "jpeg" to JPEG,
        "gif" to GIF,
        "webp" to WEBP,
        "mp3" to MP3,
        "m4a" to MP4,
        "mp4" to MP4,
        "aac" to AAC,
        "wav" to WAV,
        "ogg" to OGG,
        "opus" to OGG,
    )

    private val MIME_TO_EXTENSION = mapOf(
        PNG to "png",
        JPEG to "jpg",
        GIF to "gif",
        WEBP to "webp",
        MP3 to "mp3",
        MP4 to "m4a",
        AAC to "aac",
        WAV to "wav",
        OGG to "ogg",
    )

    /** MIME → 落盘用的扩展名；未知类型返回 null（不落盘，不发链接）。 */
    fun extensionFor(mime: String): String? = MIME_TO_EXTENSION[mime.lowercase(Locale.ROOT).substringBefore(';')]

    /** 按文件头判定；判不出返回 null。图片与音频各判一半。 */
    fun detect(head: ByteArray): String? = detectImage(head) ?: detectAudio(head)

    private fun detectImage(head: ByteArray): String? = when {
        head.size < 4 -> null
        head[0] == 0x89.toByte() && ascii(head, 1, 3) == "PNG" -> PNG
        head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte() -> JPEG
        ascii(head, 0, 6) == "GIF87a" || ascii(head, 0, 6) == "GIF89a" -> GIF
        ascii(head, 0, 4) == "RIFF" && head.size >= 12 && ascii(head, 8, 4) == "WEBP" -> WEBP
        else -> null
    }

    private fun detectAudio(head: ByteArray): String? = when {
        head.size < 4 -> null
        ascii(head, 0, 4) == "RIFF" && head.size >= 12 && ascii(head, 8, 4) == "WAVE" -> WAV
        ascii(head, 0, 4) == "OggS" -> OGG
        ascii(head, 0, 3) == "ID3" -> MP3
        head.size >= 8 && ascii(head, 4, 4) == "ftyp" -> MP4
        // ADTS 帧同步字：11 个 1，首字节 0xFF、次字节高 6 位全 1。
        // 无 ID3 头的裸 MPEG 帧与 ADTS 开头重合，判不成时由扩展名兑底。
        head[0] == 0xFF.toByte() && head[1].toInt() and 0xF6 == 0xF0 -> AAC
        else -> null
    }

    /**
     * 判定已落盘文件的类型：文件头优先，判不出再看扩展名，都不认识返回 [OCTET_STREAM]。
     *
     * 读头失败（文件被并发清理、无权限）返回 null，由调用方兑底；不当错误抛出去。
     */
    fun of(file: File): String? {
        val head = readHead(file) ?: return null
        return detect(head) ?: EXTENSION_TO_MIME[file.extension.lowercase(Locale.ROOT)] ?: OCTET_STREAM
    }

    /**
     * 读文件头。
     *
     * 不用 `InputStream.readNBytes`：那是 API 33 才有的方法。文件比预期短时补零，
     * 判定函数本就按长度取前几字节。
     */
    private fun readHead(file: File): ByteArray? = runCatching {
        val buffer = ByteArray(HEADER_BYTES)
        file.inputStream().use { input ->
            var filled = 0
            while (filled < HEADER_BYTES) {
                val read = input.read(buffer, filled, HEADER_BYTES - filled)
                if (read < 0) break
                filled += read
            }
        }
        buffer
    }.getOrNull()

    /** `data:` URI 里声明的类型；无声明返回 null。 */
    fun declaredMime(value: String): String? {
        if (!value.startsWith("data:")) return null
        val header = value.substringAfter("data:").substringBefore(',', "")
        if (!header.contains("base64")) return null
        return header.substringBefore(';').takeIf { it.contains('/') }?.lowercase(Locale.ROOT)
    }

    /** 剥掉 `data:…;base64,` 前缀，返回纯 base64 载荷。 */
    fun inlinePayload(value: String): String = value.substringAfter(',', value)

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        if (bytes.size < offset + length) "" else String(bytes, offset, length, Charsets.US_ASCII)
}
