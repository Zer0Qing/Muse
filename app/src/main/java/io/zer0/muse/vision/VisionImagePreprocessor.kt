package io.zer0.muse.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * v1.0.5 视觉辅助重写:图片预处理器。
 *
 * 参考 openhanako 的 `core/model-image-preprocess.ts` 的 `MODEL_IMAGE_INPUT_POLICY`。
 *
 * 职责:
 *  - 对 base64 图片做尺寸压缩(最长边 ≤ [POLICY.maxDimension])和 JPEG 质量压缩
 *  - 通过 magic bytes 嗅探真实 MIME 类型(不信任声明)
 *  - 按图片数量分摊单图预算,超出 [POLICY.totalBase64BudgetBytes] 总预算抛错
 *  - 对非 JPEG/PNG/WebP/GIF 的图片统一转 JPEG
 *
 * 与 openhanako 的差异:
 *  - openhanako 用 piSdk.resizeModelImageInput(浏览器端 Canvas),Android 端用 [Bitmap.compress]
 *  - openhanako 单图 4.5MB + 总 24MB,这里保留同样数值,但 Android 端 JPEG 压缩通常比浏览器更紧凑
 *  - openhanako 解码 base64 校验长度对齐 + canonical,Android 端用 [Base64.decode] 直接校验
 */
object VisionImagePreprocessor {

    private const val TAG = "VisionImagePreprocessor"

    /**
     * 图片输入策略,对齐 openhanako 的 MODEL_IMAGE_INPUT_POLICY。
     *
     * 数值含义:
     *  - [maxDimension] 2000:与 openhanako 一致,最长边 2000px。足够清晰,又控制 base64 体积。
     *  - [maxImageBase64Bytes] 4.5MB:单张 base64 上限。超过则继续压缩到该体积内。
     *  - [totalBase64BudgetBytes] 24MB:整个请求 base64 总预算(provider 端 32MB cap 的安全余量)。
     *  - [jpegQuality] 80:与 openhanako 一致,质量与体积的平衡点。
     */
    object POLICY {
        const val maxDimension = 2000
        const val maxImageBase64Bytes = 4_500_000  // 4.5MB
        const val totalBase64BudgetBytes = 24_000_000  // 24MB
        const val jpegQuality = 80
    }

    /**
     * 预处理结果。
     *
     * @param base64 预处理后的 base64(无 data: 前缀)
     * @param mimeType 嗅探到的真实 MIME 类型
     * @param originalWidth 原图宽度(px),解码失败为 0
     * @param originalHeight 原图高度(px)
     * @param resizedWidth 压缩后宽度(px)
     * @param resizedHeight 压缩后高度(px)
     */
    data class PreparedImage(
        val base64: String,
        val mimeType: String,
        val originalWidth: Int,
        val originalHeight: Int,
        val resizedWidth: Int,
        val resizedHeight: Int,
    )

    /**
     * 预处理单张 base64 图片。
     *
     * 流程:
     *  1. 嗅探 MIME(magic bytes)
     *  2. Base64 解码为字节数组
     *  3. 解码为 Bitmap,读取原始尺寸
     *  4. 按 [perImageMaxBytes] 计算缩放比例,resize 到 ≤ [POLICY.maxDimension]
     *  5. JPEG 压缩,若体积仍超预算则递减质量直到达标
     *  6. 重新 base64 编码
     *
     * @param imageBase64 原始 base64(无 data: 前缀)
     * @param imageCount 本次请求的图片总数,用于分摊单图预算
     * @return 预处理后的图片;解码失败返回 null(调用方决定丢弃或走降级)
     */
    suspend fun prepareSingle(
        imageBase64: String,
        imageCount: Int = 1,
    ): PreparedImage? = withContext(Dispatchers.IO) {
        if (imageBase64.isBlank()) return@withContext null

        // 1. 嗅探 MIME + 解码
        val mimeType = sniffMimeType(imageBase64)
        val bytes = runCatching {
            Base64.decode(imageBase64, Base64.NO_WRAP)
        }.getOrElse {
            Logger.w(TAG, "base64 解码失败: ${it.message}")
            return@withContext null
        }

        // 2. 解码 Bitmap 读取原始尺寸
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        val origW = boundsOpts.outWidth.takeIf { it > 0 } ?: 0
        val origH = boundsOpts.outHeight.takeIf { it > 0 } ?: 0

        // 3. 计算缩放:目标 ≤ maxDimension,同时按 sampleSize 2^n 缩放(避免 OOM)
        val targetMaxDim = POLICY.maxDimension
        var sample = 1
        while (origW / sample > targetMaxDim || origH / sample > targetMaxDim) sample *= 2

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            ?: return@withContext null

        // 4. 进一步精确缩放(如果 sample 缩放后仍超过 maxDimension)
        val resized = if (bitmap.width > targetMaxDim || bitmap.height > targetMaxDim) {
            val scale = minOf(
                targetMaxDim.toFloat() / bitmap.width,
                targetMaxDim.toFloat() / bitmap.height,
            ).coerceAtMost(1f)
            val newW = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val newH = (bitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, newW, newH, true).also {
                if (it != bitmap) bitmap.recycle()
            }
        } else bitmap

        // 5. JPEG 压缩,按预算递减质量
        val perImageMaxBytes = computePerImageBudget(imageCount)
        val compressed = compressToBudget(resized, POLICY.jpegQuality, perImageMaxBytes)
        resized.recycle()

        val resultBase64 = Base64.encodeToString(compressed, Base64.NO_WRAP)
        PreparedImage(
            base64 = resultBase64,
            mimeType = "image/jpeg",  // 统一转 JPEG
            originalWidth = origW,
            originalHeight = origH,
            resizedWidth = if (origW > 0) minOf(origW, targetMaxDim) else 0,
            resizedHeight = if (origH > 0) minOf(origH, targetMaxDim) else 0,
        )
    }

    /**
     * 批量预处理多张图片,校验总预算。
     *
     * @param images 原始 base64 列表
     * @return 预处理后的图片列表(失败的图片被丢弃);若总预算超限抛 [VisionImagePreprocessException]
     */
    suspend fun prepareBatch(images: List<String>): List<PreparedImage> = withContext(Dispatchers.IO) {
        if (images.isEmpty()) return@withContext emptyList()
        val count = images.size
        val results = mutableListOf<PreparedImage>()
        var totalBytes = 0L

        for (img in images) {
            val prepared = prepareSingle(img, count) ?: run {
                Logger.w(TAG, "第 ${results.size + 1} 张图片预处理失败,丢弃")
                continue
            }
            totalBytes += prepared.base64.length
            if (totalBytes > POLICY.totalBase64BudgetBytes) {
                throw VisionImagePreprocessException(
                    "压缩后图片总大小(${totalBytes / 1024}KB)超过预算(${POLICY.totalBase64BudgetBytes / 1024}KB)",
                )
            }
            results.add(prepared)
        }
        results
    }

    /** 计算单图预算:总预算按图片数量均分,但不超过单图上限。 */
    private fun computePerImageBudget(imageCount: Int): Int {
        val safeCount = imageCount.coerceAtLeast(1)
        return minOf(
            POLICY.maxImageBase64Bytes,
            POLICY.totalBase64BudgetBytes / safeCount,
        )
    }

    /**
     * JPEG 压缩到预算内:从 [startQuality] 递减质量,直到体积 ≤ [maxBytes] 或质量降到 30。
     */
    private fun compressToBudget(bitmap: Bitmap, startQuality: Int, maxBytes: Int): ByteArray {
        var quality = startQuality.coerceIn(30, 100)
        var bytes: ByteArray
        do {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            bytes = baos.toByteArray()
            if (bytes.size <= maxBytes || quality <= 30) return bytes
            quality -= 10
        } while (true)
    }

    /**
     * 通过 base64 头部字符嗅探 MIME 类型。
     *
     * 参考 openhanako 的 `sniffImageMimeType`(magic bytes 匹配)。
     * 支持的格式:JPEG / PNG / GIF / WebP,其他回退 image/jpeg。
     */
    fun sniffMimeType(base64: String): String {
        val trimmed = base64.trim()
        return when {
            trimmed.startsWith("/9j/") -> "image/jpeg"
            trimmed.startsWith("iVBOR") -> "image/png"
            trimmed.startsWith("R0lGOD") -> "image/gif"
            trimmed.startsWith("UklGR") -> "image/webp"
            else -> "image/jpeg"
        }
    }

    /**
     * 计算图片 base64 的短 hash(用于缓存 key)。
     * 取 SHA-256 前 16 字符,足够区分不同图片。
     */
    fun hashShort(base64: String): String {
        if (base64.isBlank()) return "empty"
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(base64.toByteArray())
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }
}

/**
 * 图片预处理异常(总预算超限等)。
 */
class VisionImagePreprocessException(message: String) : Exception(message)
