package io.zer0.muse.doc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import io.zer0.common.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Phase 8.6: 本地 OCR 管理器(ML Kit Text Recognition v2,中英文)。
 *
 * - 离线识别(模型随 APK 打包,首次使用时由 GMS 下载并缓存到设备)
 * - 支持中文 + 拉丁字符(单一依赖 `text-recognition-chinese` 即可)
 * - 输入: URI(Bitmap 解码) 或 Bitmap
 * - 输出: 拼接后的纯文本(段落间换行)
 *
 * 局限:
 *  - ML Kit 依赖 Google Play services(国产 ROM 可能需手动安装 GMS)
 *  - 首次使用会触发模型下载(~20MB)
 *  - 不支持手写体识别(ML Kit 单独的手写模型,未集成)
 */
class OcrManager {

    // L-OCR2: recognizer 随 App 生命周期常驻(lazy 单例),暂不 close()。
    // TextRecognizer.close() 会释放模型资源,但重新创建需重新加载模型(首次有下载开销),
    // 对频繁 OCR 场景得不偿失。若未来需精细管理内存,可在 Activity onDestroy 中 close。
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * 从 URI 识别文本(自动读取 EXIF 旋转元数据校正方向)。
     * @param uri 图片 URI(SAF / 相册 / 相机均可用)
     * @param context 用于 ContentResolver
     * @return 识别出的纯文本;失败返回空字符串
     */
    suspend fun recognize(uri: Uri, context: Context): String {
        val rotation = readExifRotation(uri, context)
        val bitmap = decodeBitmap(uri, context) ?: return ""
        return recognize(bitmap, rotation)
    }

    /**
     * 从 Bitmap 识别文本(使用指定旋转角度校正方向)。
     * @param bitmap 输入位图
     * @param rotationDegrees 旋转角度(0/90/180/270),由 EXIF 元数据计算
     * @return 识别出的纯文本;失败返回空字符串
     */
    suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int = 0): String = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, rotationDegrees)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val sb = StringBuilder()
                for (block in result.textBlocks) {
                    val blockText = block.text.trim()
                    if (blockText.isNotEmpty()) {
                        if (sb.isNotEmpty()) sb.appendLine()
                        sb.append(blockText)
                    }
                }
                cont.resume(sb.toString())
            }
            .addOnFailureListener { e ->
                Logger.w("OcrManager", "OCR 识别失败: ${e.message}", e)
                cont.resume("")
            }
        cont.invokeOnCancellation { }
    }

    /** 读取 URI 指向的图片 EXIF 旋转元数据,返回旋转角度(0/90/180/270)。读取失败返回 0。 */
    private fun readExifRotation(uri: Uri, context: Context): Int {
        return runCatching {
            val input = context.contentResolver.openInputStream(uri) ?: return@runCatching 0
            input.use { stream ->
                val exif = android.media.ExifInterface(stream)
                when (exif.getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL,
                )) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        }.getOrNull() ?: 0
    }

    /** 解码 URI 为 Bitmap;自动按目标尺寸缩放(防 OOM)。 */
    private fun decodeBitmap(uri: Uri, context: Context): Bitmap? {
        return runCatching {
            val resolver = context.contentResolver
            val input = resolver.openInputStream(uri) ?: return null
            input.use {
                // 第一遍:仅解码尺寸
                val opts = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(input, null, opts)
                // 计算 inSampleSize:最长边 ≤ 2048(OCR 不需要超高清)
                val target = 2048
                var sample = 1
                while (opts.outWidth / sample > target || opts.outHeight / sample > target) {
                    sample *= 2
                }
                // 第二遍:实际解码
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                }
                resolver.openInputStream(uri)?.use { realInput ->
                    BitmapFactory.decodeStream(realInput, null, decodeOpts)
                }
            }
        }.recoverCatching { e ->
            // L-OCR4: 解码失败记日志,避免静默返回 null 难以排查
            Logger.w("OcrManager", "Bitmap 解码失败: uri=$uri, ${e.message}", e)
            null
        }.getOrNull()
    }
}
