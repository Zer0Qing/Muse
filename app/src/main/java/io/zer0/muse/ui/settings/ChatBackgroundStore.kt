package io.zer0.muse.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import io.zer0.common.Logger
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
     * @param unique 是否在文件名后追加时间戳（v2.6.2）。
     *   同一个 slot 重新选图时，若路径不变，SmartImage 会命中旧缓存而不刷新；
     *   追加时间戳使路径每次不同，强制重新加载。旧文件会被清掉。
     */
    fun saveFromUri(context: Context, uri: Uri, slot: String? = null, unique: Boolean = false): String? = runCatching {
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
        val base = if (slot.isNullOrBlank()) GLOBAL_FILE else "$slot.jpg"
        val fileName = if (unique) {
            val stem = base.substringBeforeLast('.')
            "${stem}_${System.currentTimeMillis()}.jpg"
        } else {
            base
        }
        // 清理同 slot 的旧文件（含带时间戳的历史文件），避免无限堆积。
        // 归属判定统一走 isSlotFile：这里以前抄了一份"前缀匹配"，
        // 于是清/换全局背景会连 chat_background_dark.jpg 一起删（详见 isSlotFile 注释）。
        deleteSlotFiles(context, slot)
        val target = File(context.filesDir, fileName)
        target.writeBytes(out.toByteArray())
        out.close()
        "file://" + target.absolutePath
    }.getOrNull()

    /** 删除某个 slot 对应的背景文件（清除背景时调用，包含带时间戳的历史文件）。 */
    fun deleteSlot(context: Context, slot: String?) {
        deleteSlotFiles(context, slot)
    }

    /**
     * 删掉属于该 slot 的背景文件（当前名 + 带时间戳的历史名）。
     *
     * 单个文件删不掉不影响其余（继续遍历），但**要留下日志**：
     * 用户看不到"文件还在盘上"这件事，只有排查磁盘占用时会撞上。
     */
    private fun deleteSlotFiles(context: Context, slot: String?) {
        runCatching {
            val base = if (slot.isNullOrBlank()) GLOBAL_FILE else "$slot.jpg"
            val stem = base.substringBeforeLast('.')
            var failed = 0
            context.filesDir.listFiles()?.forEach { f ->
                if (!isSlotFile(f.name, base, stem)) return@forEach
                if (!f.delete()) failed += 1
            }
            if (failed > 0) {
                Logger.w("ChatBackgroundStore", "清理聊天背景文件未全部成功 slot=${slot ?: "全局"} 失败 $failed 个")
            }
        }.onFailure { e ->
            Logger.w("ChatBackgroundStore", "清理聊天背景文件异常 slot=${slot ?: "全局"}: ${e.message}", e)
        }
    }

    /** 会话级背景文件 slot。 */
    fun sessionSlot(sessionId: String): String = "chat_bg_session_${sessionId.sanitized()}"

    /** 助手级背景文件 slot。 */
    fun assistantSlot(assistantId: String): String = "chat_bg_assistant_${assistantId.sanitized()}"

    /** 文件名安全化：只保留字母数字下划线连字符。 */
    private fun String.sanitized(): String = replace(Regex("[^A-Za-z0-9_-]"), "_")
}

/**
 * 某个文件名是否属于该 slot 自己的背景文件（当前名或带时间戳的历史名）。
 *
 * 这里曾经写成 `name.startsWith("${stem}_")`，看着无害，实际**会越界删文件**：
 * 全局背景的 stem 是 `chat_background`，而深色槽位的文件叫 `chat_background_dark.jpg` ——
 * 它同样满足这个前缀，于是**清掉或重选浅色背景会把深色背景的文件一起删掉**，
 * 深色那边存的 `file://` 路径就此指向不存在的文件（表现为深色模式没有背景，且无从解释）。
 *
 * 时间戳历史文件的后缀只会是数字（`${stem}_${millis}.jpg`），所以按"后缀全是数字"判定归属。
 */
internal fun isSlotFile(name: String, base: String, stem: String): Boolean {
    if (name == base) return true
    val prefix = "${stem}_"
    if (!name.startsWith(prefix) || !name.endsWith(JPG_SUFFIX)) return false
    val tail = name.substring(prefix.length, name.length - JPG_SUFFIX.length)
    return tail.isNotEmpty() && tail.all(Char::isDigit)
}

private const val JPG_SUFFIX = ".jpg"
