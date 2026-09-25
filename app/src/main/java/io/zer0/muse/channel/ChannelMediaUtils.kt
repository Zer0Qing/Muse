package io.zer0.muse.channel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * v2.0.1: 渠道媒体工具 — 入站图片压缩与 base64 编码。
 *
 * 目标:控制对话存储体积(单图目标 ≤ [TARGET_MAX_BYTES]),同时保持视觉可用的清晰度;
 * 解码失败返回 null,由调用方按占位文本降级。
 */
object ChannelMediaUtils {

    private const val MAX_DIMENSION = 1280
    private const val PRIMARY_QUALITY = 82
    private const val FALLBACK_QUALITY = 62
    private const val TARGET_MAX_BYTES = 320 * 1024

    /** 把原始图片字节解码并压缩为 JPEG base64(无 data: 前缀);失败返回 null。 */
    fun toCompactImageBase64(bytes: ByteArray): String? = runCatching {
        // 1. 边界采样:长边超过上限时按 2 的幂降采样
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / sample > MAX_DIMENSION || bounds.outHeight / sample > MAX_DIMENSION) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return@runCatching null
        // 2. 质量压缩(超预算时降质重压一次)
        var encoded = compressJpeg(bitmap, PRIMARY_QUALITY)
        if (encoded != null && encoded.size > TARGET_MAX_BYTES) {
            encoded = compressJpeg(bitmap, FALLBACK_QUALITY)
        }
        bitmap.recycle()
        encoded?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
    }.getOrNull()

    private fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray? = runCatching {
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray().takeIf { it.isNotEmpty() }
        }
    }.getOrNull()
}
