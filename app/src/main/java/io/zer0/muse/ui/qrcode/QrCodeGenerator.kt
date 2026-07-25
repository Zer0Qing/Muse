package io.zer0.muse.ui.qrcode

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.zer0.ai.core.ProviderConfig
import io.zer0.common.AppJson
import io.zer0.common.Logger
import android.util.Base64

/**
 * v1.97: 二维码工具 — Provider 配置的二维码生成与解析。
 *
 * 参考 rikkahub 的 QR code 分享设计,格式:
 * ```
 * ai-provider:v1:<Base64(JSON(ProviderConfig))>
 * ```
 *
 * 用法:
 * ```
 * // 生成二维码
 * val bitmap = QrCodeGenerator.generateProviderQr(providerConfig, size = 600)
 *
 * // 解析二维码内容
 * val provider = QrCodeGenerator.parseProviderQr(qrContent)
 * ```
 *
 * 安全提示:二维码包含 apiKey 明文,扫描后请仅分享给信任的接收方。
 */
object QrCodeGenerator {

    private const val TAG = "QrCodeGenerator"

    /** 二维码前缀,标识 Provider 配置版本。 */
    private const val QR_PREFIX = "ai-provider:v1:"

    /** 默认二维码尺寸(像素)。 */
    const val DEFAULT_QR_SIZE = 600

    /**
     * 把 ProviderConfig 编码为二维码字符串。
     *
     * @param config Provider 配置
     * @return 形如 "ai-provider:v1:<Base64>" 的字符串
     */
    fun encodeProvider(config: ProviderConfig): String {
        val json = AppJson.encodeToString(ProviderConfig.serializer(), config)
        val base64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return QR_PREFIX + base64
    }

    /**
     * 生成 Provider 配置的二维码 Bitmap。
     *
     * @param config Provider 配置
     * @param size 二维码尺寸(像素,默认 600)
     * @return Bitmap;失败返回 null
     */
    fun generateProviderQr(config: ProviderConfig, size: Int = DEFAULT_QR_SIZE): Bitmap? {
        val content = encodeProvider(config)
        return generateQrBitmap(content, size)
    }

    /**
     * 生成任意文本的二维码 Bitmap。
     *
     * @param content 文本内容
     * @param size 二维码尺寸(像素)
     * @return Bitmap;失败返回 null
     */
    fun generateQrBitmap(content: String, size: Int): Bitmap? {
        if (content.isEmpty()) return null
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            val bitMatrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }.also { result ->
                // 调用方使用完后应 recycle(详见 QrCodeDialogs 中 generateQrBitmap 调用点)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "生成二维码失败: ${e.message}", e)
            null
        }
    }

    /**
     * 解析二维码字符串为 ProviderConfig。
     *
     * @param content 二维码扫描结果
     * @return ProviderConfig;格式不匹配或解析失败返回 null
     */
    fun parseProviderQr(content: String): ProviderConfig? {
        val trimmed = content.trim()
        if (!trimmed.startsWith(QR_PREFIX)) {
            Logger.w(TAG, "二维码前缀不匹配,期望 '$QR_PREFIX'")
            return null
        }
        val base64 = trimmed.removePrefix(QR_PREFIX)
        return try {
            val json = String(Base64.decode(base64, Base64.DEFAULT), Charsets.UTF_8)
            AppJson.decodeFromString(ProviderConfig.serializer(), json)
        } catch (e: Exception) {
            Logger.w(TAG, "解析 Provider 二维码失败", e)
            null
        }
    }

    /**
     * 判断字符串是否为 Provider 二维码格式。
     */
    fun isProviderQr(content: String): Boolean {
        return content.trim().startsWith(QR_PREFIX)
    }
}
