package io.zer0.muse.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * v2.6.2（用户反馈）: 聊天背景图片落盘工具 —— 三层背景（会话/助手/全局）共用。
 *
 * 为什么必须落盘：背景图若以 base64 存进 DataStore Preferences，每次 edit 全量重写，
 * 设置文件膨胀、冷启动变慢、还有 ANR 风险。这里统一压缩后写 filesDir，
 * DataStore 只存 "file://..." 路径字符串。
 *
 * 压缩策略：长边上限 1920，JPEG 质量 82（体积与观感的平衡点，壁纸类图片够用）。
 */
internal object ChatBackgroundStore {

    private const val MAX_SIDE = 1920
    private const val JPEG_QUALITY = 82

    /** 全局默认背景文件名（保持旧版兼容）。 */
    private const val GLOBAL_FILE = "chat_background.jpg"

    /**
     * 把用户选中的图片压缩落盘，返回 "file://" 开头的路径；失败返回 null。
     *
     * @param slot 落盘文件名（不含扩展名）。全局/助手/会话各用不同 slot，
     *   避免互相覆盖；传 null 用全局默认名（兼容旧行为）。
     */
    fun saveFromUri(context: Context, uri: Uri, slot: String? = null): String? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@runCatching null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        } else {
            bmp
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (scaled !== bmp) scaled.recycle()
        bmp.recycle()
        val fileName = if (slot.isNullOrBlank()) GLOBAL_FILE else "$slot.jpg"
        val target = File(context.filesDir, fileName)
        target.writeBytes(out.toByteArray())
        out.close()
        "file://" + target.absolutePath
    }.getOrNull()

    /** 删除某个 slot 对应的背景文件（清除背景时调用）。 */
    fun deleteSlot(context: Context, slot: String?) {
        runCatching {
            val fileName = if (slot.isNullOrBlank()) GLOBAL_FILE else "$slot.jpg"
            File(context.filesDir, fileName).delete()
        }
    }

    /** 会话级背景文件 slot。 */
    fun sessionSlot(sessionId: String): String = "chat_bg_session_${sessionId.sanitized()}"

    /** 助手级背景文件 slot。 */
    fun assistantSlot(assistantId: String): String = "chat_bg_assistant_${assistantId.sanitized()}"

    /** 文件名安全化：只保留字母数字下划线连字符。 */
    private fun String.sanitized(): String = replace(Regex("[^A-Za-z0-9_-]"), "_")
}
