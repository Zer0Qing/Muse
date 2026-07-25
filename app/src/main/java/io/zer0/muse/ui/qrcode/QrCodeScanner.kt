package io.zer0.muse.ui.qrcode

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import io.zer0.common.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * v1.97: 二维码扫描器 — 用 ML Kit Barcode Scanning 从图片识别二维码。
 *
 * 用户从相册选图片后,用此扫描器识别二维码内容。
 * 支持 QR_CODE 格式,识别后返回原始字符串。
 *
 * 用法:
 * ```
 * val content = QrCodeScanner.scanFromBitmap(bitmap)
 * if (content != null && QrCodeGenerator.isProviderQr(content)) {
 *     val provider = QrCodeGenerator.parseProviderQr(content)
 * }
 * ```
 */
object QrCodeScanner {

    private const val TAG = "QrCodeScanner"

    /**
     * 从 Bitmap 扫描二维码。
     *
     * @param bitmap 待识别的图片
     * @return 第一个识别到的二维码内容;无识别结果或失败返回 null
     */
    suspend fun scanFromBitmap(bitmap: Bitmap): String? {
        val image = InputImage.fromBitmap(bitmap, 0)
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        val scanner = BarcodeScanning.getClient(options)
        return suspendCancellableCoroutine { cont ->
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val content = barcodes.firstOrNull()?.rawValue
                    scanner.close()
                    cont.resume(content)
                }
                .addOnFailureListener { e ->
                    Logger.w(TAG, "二维码扫描失败: ${e.message}")
                    scanner.close()
                    cont.resume(null)
                }
        }
    }
}
