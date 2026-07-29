package io.zer0.muse.debug

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 调试日志内存存储 — 参考 rikkahub LogPage / kelivo log_viewer_page 实现。
 *
 * 设计目标:
 *  - 在内存中保留最近 [MAX_ENTRIES] 条日志,超出淘汰最旧(环形缓冲语义)
 *  - 线程安全:[ConcurrentLinkedDeque] 支持多线程并发写(Logger 调用来自任意线程)
 *  - 与 [Logger] 解耦:Logger 通过 sink 回调推送,避免 common 模块反向依赖 app 模块
 *  - 支持导出为文件(用于分享 / 排查问题)
 *
 * 使用方式:
 *  - Application.onCreate 中调用 [init] 一次,完成 cacheDir 初始化
 *  - Logger.sink 自动把每条日志推送到 [log]
 *  - DebugScreen 通过 [getAll] 读取并展示
 *  - 用户在 DebugScreen 点"清空"调用 [clear],"导出"调用 [exportToFile]
 */
object DebugLogStore {

    /** 内存缓冲区上限,超出淘汰最旧。 */
    private const val MAX_ENTRIES = 500

    /** 日志等级常量(与 Logger.Level 对齐,但用字符串避免跨模块依赖)。 */
    const val LEVEL_DEBUG = "DEBUG"
    const val LEVEL_INFO = "INFO"
    const val LEVEL_WARN = "WARN"
    const val LEVEL_ERROR = "ERROR"

    /** 全部等级(供 UI 过滤器使用,顺序与显示一致)。 */
    val LEVELS: List<String> = listOf(LEVEL_DEBUG, LEVEL_INFO, LEVEL_WARN, LEVEL_ERROR)

    /** 线程安全的双端队列,尾部为最新。 */
    private val buffer = ConcurrentLinkedDeque<DebugLogEntry>()

    /** 导出日志文件的目录(cacheDir/logs,与 Logger 文件日志同目录,卸载自动清理)。 */
    @Volatile
    private var exportDir: File? = null

    /** 导出文件名时间戳格式(避免文件名冲突 + 便于按时间排序)。 */
    private val exportDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /**
     * 初始化导出目录。在 Application.onCreate 调用一次。
     *
     * @param context 任意 Context,内部取 cacheDir
     */
    fun init(context: Context) {
        val dir = File(context.cacheDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        exportDir = dir
    }

    /**
     * 追加一条日志。Logger.sink 回调入口。
     *
     * @param level 等级(DEBUG / INFO / WARN / ERROR)
     * @param tag   标签(模块名)
     * @param message 消息文本(已通过 Logger.sanitizePii 脱敏)
     * @param throwable 可选异常;非 null 时转换为 stack trace 字符串存储
     */
    fun log(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val entry = DebugLogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            throwable = throwable?.let { stackTraceToString(it) },
        )
        buffer.addLast(entry)
        // 超出上限淘汰最旧(ConcurrentLinkedDeque 没有 trim 等原子操作,先 add 再 trim 安全)
        while (buffer.size > MAX_ENTRIES) {
            buffer.pollFirst() ?: break
        }
    }

    /**
     * 获取全部日志(按时间升序,旧 → 新)。UI 直接展示,新日志在底部。
     */
    fun getAll(): List<DebugLogEntry> = buffer.toList()

    /** 当前缓冲区条数。 */
    fun size(): Int = buffer.size

    /** 清空全部日志。 */
    fun clear() {
        buffer.clear()
    }

    /**
     * 导出当前缓冲区全部日志到文件。
     *
     * 文件路径:`cacheDir/logs/debug_export_yyyyMMdd_HHmmss.txt`,
     * 已通过 FileProvider 暴露,可直接分享。
     *
     * @return 导出的文件;若 [init] 未调用或写入失败返回 null
     */
    fun exportToFile(): File? {
        val dir = exportDir ?: return null
        val entries = buffer.toList()
        if (entries.isEmpty()) return null
        val fileName = "debug_export_${exportDateFormat.format(Date())}.txt"
        val file = File(dir, fileName)
        return try {
            file.bufferedWriter().use { writer ->
                entries.forEach { entry ->
                    writer.write(entry.formatLine())
                    writer.newLine()
                }
            }
            file
        } catch (t: Throwable) {
            // 导出失败不能影响主流程,降级返回 null(DebugScreen 会提示用户)
            null
        }
    }

    /** Throwable → stack trace 字符串工具,与 Logger.stackTraceToString 实现一致。 */
    private fun stackTraceToString(t: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { pw -> t.printStackTrace(pw) }
        return sw.toString()
    }
}

/**
 * 单条调试日志条目。
 *
 * @param timestamp 毫秒时间戳
 * @param level     等级(DEBUG / INFO / WARN / ERROR)
 * @param tag       标签
 * @param message   消息文本(已脱敏)
 * @param throwable 可选 stack trace 字符串;非 null 时 UI 展开后可见
 */
data class DebugLogEntry(
    val timestamp: Long,
    val level: String,
    val tag: String,
    val message: String,
    val throwable: String? = null,
) {
    /** 导出文件时的单行格式:时间戳 等级/标签: 消息 [throwable]。 */
    fun formatLine(): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
        val levelShort = when (level) {
            DebugLogStore.LEVEL_DEBUG -> "D"
            DebugLogStore.LEVEL_INFO -> "I"
            DebugLogStore.LEVEL_WARN -> "W"
            DebugLogStore.LEVEL_ERROR -> "E"
            else -> level.firstOrNull() ?: '?'
        }
        val base = "$ts $levelShort/$tag: $message"
        return if (throwable.isNullOrEmpty()) base else "$base\n$throwable"
    }
}
