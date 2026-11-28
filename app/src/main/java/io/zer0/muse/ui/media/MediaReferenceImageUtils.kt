package io.zer0.muse.ui.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * 将参考图按最大边长与体积上限压缩并编码为 JPEG Data URI。
 *
 * 1. 先仅解码边界获取原始宽高；
 * 2. 以 2 的幂次降采样，再用 Matrix 精确缩放到 [maxSide] 以内；
 * 3. 若 Base64 体积仍超出 [maxBase64Bytes]，依次降低 JPEG 质量与边长直至满足约束。
 */
internal fun compressMediaReferenceImage(
    uri: Uri,
    context: Context,
    maxSide: Int = 1024,
    quality: Int = 85,
    maxBase64Bytes: Int = 4 * 1024 * 1024,
): String {
    val resolver = context.contentResolver
    val (origW, origH) = decodeImageBounds(resolver, uri)
    if (origW <= 0 || origH <= 0) {
        error("decode bounds failed for $uri")
    }

    var sample = 1
    while (origW / sample / 2 >= maxSide || origH / sample / 2 >= maxSide) {
        sample *= 2
    }

    var bitmap = decodeSampledBitmap(resolver, uri, sample)
        ?: error("decode bitmap failed for $uri")

    val scaled = scaleBitmapToMaxSide(bitmap, maxSide)
    if (scaled !== bitmap) {
        bitmap.recycle()
        bitmap = scaled
    }

    var currentQuality = quality
    var currentBmp = bitmap
    var bytes = compressJpeg(currentBmp, currentQuality)
    var base64Len = estimateBase64Length(bytes.size)

    val qualitySteps = listOf(75, 65, 55)
    var stepIndex = 0
    while (base64Len > maxBase64Bytes && stepIndex < qualitySteps.size) {
        currentQuality = qualitySteps[stepIndex++]
        bytes = compressJpeg(currentBmp, currentQuality)
        base64Len = estimateBase64Length(bytes.size)
    }

    val sideSteps = listOf(768, 512, 384)
    var sideIndex = 0
    while (base64Len > maxBase64Bytes && sideIndex < sideSteps.size) {
        val newSide = sideSteps[sideIndex++]
        val shrunk = scaleBitmapToMaxSide(currentBmp, newSide)
        if (shrunk !== currentBmp) {
            currentBmp.recycle()
            currentBmp = shrunk
        }
        bytes = compressJpeg(currentBmp, currentQuality)
        base64Len = estimateBase64Length(bytes.size)
    }

    val width = currentBmp.width
    val height = currentBmp.height
    currentBmp.recycle()

    if (base64Len > maxBase64Bytes) {
        error("image still too large after compression (${width}x$height, ${bytes.size / 1024}KB)")
    }

    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
    return "data:image/jpeg;base64,$base64"
}

private fun decodeImageBounds(resolver: ContentResolver, uri: Uri): Pair<Int, Int> {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, opts)
    }
    return opts.outWidth to opts.outHeight
}

private fun decodeSampledBitmap(resolver: ContentResolver, uri: Uri, sampleSize: Int): Bitmap? {
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return resolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, opts)
    }
}

private fun scaleBitmapToMaxSide(src: Bitmap, maxSide: Int): Bitmap {
    val w = src.width
    val h = src.height
    val longSide = maxOf(w, h)
    if (longSide <= maxSide) return src
    val scale = maxSide.toFloat() / longSide
    val matrix = Matrix().apply { setScale(scale, scale) }
    return Bitmap.createBitmap(src, 0, 0, w, h, matrix, true)
}

private fun compressJpeg(bmp: Bitmap, quality: Int): ByteArray {
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return out.toByteArray()
}

private fun estimateBase64Length(byteCount: Int): Int = ((byteCount + 2) / 3) * 4
