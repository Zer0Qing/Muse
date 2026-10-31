package io.zer0.muse.miniphone.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 图片落盘。
 *
 * 选中的图一律复制进模块私有目录:消息里只存本地路径,用户删掉相册原件、宿主清缓存,
 * 都不会让聊天记录变成空图。文件名取内容 SHA-256 的前 16 位 —— 同一张图选两次得到
 * 同一个文件、同一个路径,库里两条消息指向同一份,不会重复占空间。
 *
 * 超过 [compressMinBytes] 的图先缩到长边 [maxEdgePx] 再存:聊天页最多显示 200dp,
 * 存十几 MB 的原图没意义。缩小失败就存原图,不让压缩成为丢图的理由。
 *
 * @param directory 落盘目录(模块私有,不对外暴露)
 * @param openStream 由 uri 取输入流;读不到返回 null
 */
class MpImageStore(
    private val directory: File,
    private val openStream: (Uri) -> InputStream?,
    private val compressMinBytes: Long = DEFAULT_COMPRESS_MIN_BYTES,
    private val maxEdgePx: Int = DEFAULT_MAX_EDGE_PX,
) {
    /**
     * 把 [uri] 指向的图存进私有目录,返回本地绝对路径。
     *
     * 读不到(权限被收回、原件已删、流读到一半断了)或写不进磁盘时返回 null:
     * 一次选九张,不能因为其中一张失效就整批放弃,调用方跳过这一张即可。
     */
    suspend fun import(uri: Uri): String? = withContext(Dispatchers.IO) {
        val original = readBytes(uri) ?: return@withContext null
        val payload = shrink(original) ?: original
        write(payload, digestOf(original))?.absolutePath
    }

    private fun readBytes(uri: Uri): ByteArray? = runCatching {
        openStream(uri)?.use(InputStream::readBytes)
    }.getOrNull()?.takeIf(ByteArray::isNotEmpty)

    /** 同名文件已存在就直接复用:内容相同,不必再写一遍。 */
    private fun write(payload: ByteArray, digest: String): File? = runCatching {
        directory.mkdirs()
        val target = File(directory, "$digest.${extensionOf(payload)}")
        if (!target.exists()) target.writeBytes(payload)
        target
    }.getOrNull()

    /** 超过阈值且长边超出上限才重编码;小图重编码只会掉画质。 */
    private fun shrink(bytes: ByteArray): ByteArray? {
        if (bytes.size.toLong() <= compressMinBytes) return null
        val scaled = decodeScaled(bytes) ?: return null
        return ByteArrayOutputStream().use { out ->
            if (scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) out.toByteArray() else null
        }
    }

    /**
     * 解开并缩到长边上限;不需要缩或解不开时返回 null。
     *
     * 先只用 [BitmapFactory.Options.inJustDecodeBounds] 读尺寸,再按 2 的幂给采样率,
     * 避免为了量一次尺寸就把整张大图读进内存。
     */
    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longEdge = max(bounds.outWidth, bounds.outHeight)
        if (longEdge <= maxEdgePx) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(longEdge, maxEdgePx) }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return scaleDown(decoded)
    }

    /** 采样率只取 2 的幂,采样后仍可能比长边上限大,这里再按比例精缩一次。 */
    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longEdge = max(bitmap.width, bitmap.height)
        if (longEdge <= maxEdgePx) return bitmap
        val ratio = maxEdgePx.toFloat() / longEdge
        val width = max(MIN_EDGE_PX, (bitmap.width * ratio).roundToInt())
        val height = max(MIN_EDGE_PX, (bitmap.height * ratio).roundToInt())
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    /** 后缀按文件头认:压缩过的图已经是 JPEG,不能沿用原件后缀。 */
    private fun extensionOf(bytes: ByteArray): String = when {
        bytes.hasPrefix(PNG_MAGIC) -> "png"
        bytes.hasPrefix(JPEG_MAGIC) -> "jpg"
        bytes.hasPrefix(GIF_MAGIC) -> "gif"
        else -> "img"
    }

    private fun ByteArray.hasPrefix(magic: IntArray): Boolean =
        size >= magic.size && magic.indices.all { (this[it].toInt() and BYTE_MASK) == magic[it] }

    /** 内容摘要取前 8 字节(16 个十六进制字符):足够长,又不至于让路径难认。 */
    private fun digestOf(bytes: ByteArray): String = MessageDigest.getInstance(HASH_ALGORITHM).digest(bytes)
        .take(NAME_HASH_BYTES)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and BYTE_MASK) }

    companion object {
        /** 模块私有目录,相对 `context.filesDir`。 */
        const val DIRECTORY_NAME: String = "miniphone/images"

        private const val DEFAULT_COMPRESS_MIN_BYTES: Long = 8L * 1024 * 1024
        private const val DEFAULT_MAX_EDGE_PX: Int = 1600
        private const val JPEG_QUALITY: Int = 85
        private const val MIN_EDGE_PX: Int = 1
        private const val NAME_HASH_BYTES: Int = 8
        private const val BYTE_MASK: Int = 0xFF
        private const val HASH_ALGORITHM: String = "SHA-256"

        private val PNG_MAGIC = intArrayOf(0x89, 0x50, 0x4E, 0x47)
        private val JPEG_MAGIC = intArrayOf(0xFF, 0xD8, 0xFF)
        private val GIF_MAGIC = intArrayOf(0x47, 0x49, 0x46, 0x38)
    }
}

/** 采样率取 2 的幂:让解码后的长边不小于目标值的一半,剩下的交给精确缩放。 */
internal fun sampleSizeFor(longEdge: Int, maxEdge: Int): Int {
    if (maxEdge <= 0) return 1
    var sample = 1
    while (longEdge / (sample * 2) >= maxEdge) sample *= 2
    return sample
}

/** 模块内默认实例:目录固定在私有 files 下,输入流从 ContentResolver 取。 */
fun miniPhoneImageStore(context: Context): MpImageStore = MpImageStore(
    directory = File(context.filesDir, MpImageStore.DIRECTORY_NAME),
    openStream = { uri -> context.contentResolver.openInputStream(uri) },
)
