package io.zer0.common

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局日志门面,默认 tag 为 `Muse`。
 *
 * Phase 11.3 增强:文件日志能力(便于真机验证后回捞)
 *  - [initFileLog]:在 Application onCreate 调用一次,开启文件日志
 *  - 日志文件:app cacheDir/logs/muse.log(单文件,超过 [MAX_LOG_FILE_BYTES]=约 1MB 轮转为 muse.log.1)
 *    （此处原写 100KB，与常量不符；常量后来提到 1MB 时没回来改注释）
 *  - 等级:W/E 自动写文件;I/D 可选(由 [fileLogLevel] 控制)
 *  - 线程安全:PrintWriter synchronized
 *
 * release 构建可把 [enabled] 置为 false 关闭全部日志(Logcat + 文件)。
 */
object Logger {
    private const val DEFAULT_TAG = "Muse"
    private const val LOG_FILE_NAME = "muse.log"
    /** 约 1MB rolling。`internal` 是为了让轮转测试引用同一个数，而不是抄一份字面量。 */
    internal const val MAX_LOG_FILE_BYTES = 1_000 * 1024L
    /** 轮转失败后的重试冷却：避免每条日志都去"关句柄 + 删备份 + 改名"。 */
    private const val ROLL_RETRY_COOLDOWN_MS = 60_000L
    /** 因果链最多展开几层：再多就快到栈顶了，消息也长。 */
    internal const val MAX_CAUSE_DEPTH = 8

    @Volatile
    var enabled: Boolean = true

    /** 文件日志等级:低于此等级的 I/D 日志不写文件(W/E 总是写)。 */
    @Volatile
    var fileLogLevel: Level = Level.DEBUG

    /** 文件日志启用状态(由 [initFileLog] 设置)。 */
    @Volatile
    private var fileLogEnabled: Boolean = false

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var logDir: File? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    /**
     * 常驻的文件写入句柄 + 已写字节数（2026-11-01 性能整改）。
     *
     * 旧实现每行 `file.appendText(line)` = open + write + close 三次系统调用；
     * 实测单条日志（含 PII 脱敏）约 **0.25ms**（JVM），一次普通对话回合几十条日志就是十几毫秒，
     * 而且这部分开销落在写日志的线程上（可能是主线程）。
     *
     * 现在句柄常驻、每行 flush：省掉 open/close，但**不改变持久化时机** ——
     * 每行仍立刻落盘，崩溃不会丢日志，多进程追加的安全性也维持原样（仍是 O_APPEND 追加）。
     * 字节数自己记账，避免每行再 stat 一次文件大小。
     */
    private var fileWriter: java.io.Writer? = null
    private var writtenBytes: Long = 0L

    /**
     * 上次**尝试**轮转的时间戳。轮转失败时不能每条日志都重试：
     * 那会把缓冲写变成逐行落盘（每次都要开关句柄），比不轮转更伤。
     */
    private var lastRollAttemptAt: Long = 0L

    /** 日志等级枚举(用于 [fileLogLevel] 阈值控制)。 */
    enum class Level { DEBUG, INFO, WARN, ERROR }

    /**
     * 调试日志 sink 钩子 — app 模块的 DebugLogStore 通过设置此变量接收所有日志。
     *
     * 设计动机:common 模块不能反向依赖 app 模块,但 Logger 需要把每条日志
     * 同步推送到 DebugLogStore 供 DebugScreen 展示。通过 var 回调解耦:
     *  - app 启动时 `Logger.sink = { level, tag, msg, t -> DebugLogStore.log(...) }`
     *  - Logger 在 d/i/w/e 内部统一调用 [dispatchToSink]
     *  - 默认 null(不推送),release 构建可不设置以避免内存占用
     *
     * 参数顺序与 [DebugLogStore.log] 对齐:level(字符串大写) / tag / message / throwable。
     */
    @Volatile
    var sink: ((String, String, String, Throwable?) -> Unit)? = null

    /** 把日志推送到 sink(若已注册)。内部统一入口,避免 d/i/w/e 各处重复判空。 */
    private fun dispatchToSink(level: Level, tag: String, msg: String, t: Throwable?) {
        val sinkRef = sink ?: return
        try {
            // level.name 直接得到 "DEBUG" / "INFO" / "WARN" / "ERROR",与 DebugLogStore 常量对齐
            sinkRef.invoke(level.name, tag, msg, t)
        } catch (_: Throwable) {
            // sink 失败不能影响主流程(DebugLogStore 自身 bug 不应拖垮 Logger)
        }
    }

    /**
     * Phase 11.3: 初始化文件日志。
     *
     * 在 Application.onCreate 调用一次。日志写到 `cacheDir/logs/muse.log`,
     * 超过 [MAX_LOG_FILE_BYTES]（约 1MB）自动 rolling(旧文件改名为 muse.log.1,新日志继续写 muse.log)。
     *
     * @param context Application/Context(用 cacheDir,卸载自动清理)
     */
    fun initFileLog(context: Context) {
        synchronized(lock) {
            val dir = File(context.cacheDir, "logs")
            if (!dir.exists()) dir.mkdirs()
            // 换文件时必须一起复位轮转状态：
            //  1. 旧句柄不关，日志会继续写进**上一个**文件（logFile 已经指向新路径，没人知道）；
            //  2. `writtenBytes` 是"当前这个文件写了多少字节"的记账，沿用上一个文件的大值
            //     会让新文件第一行就触发轮转 —— 实测表现为两个测试类互相打红。
            closeWriterLocked()
            writtenBytes = 0L
            lastRollAttemptAt = 0L
            logDir = dir
            logFile = File(dir, LOG_FILE_NAME)
            fileLogEnabled = true
        }
    }

    /**
     * Phase 11.3: 导出日志文件(用于真机验证后回捞)。
     * @return 日志文件 File;未启用文件日志返回 null
     */
    fun getLogFile(): File? = logFile?.takeIf { it.exists() }

    /**
     * M5: 过滤日志中的 PII 与凭据（手机号 / 邮箱 / 证件 / 卡号 / IP / 各种形状的密钥）。
     *
     * **模式表已经合到 `PiiEngine.scrubForLogs`**（同一模块里的一处真源）。
     * 以前 `Logger` 自带一份，和 `PiiEngine` 那份互有覆盖、又都不是全集：
     * 这里认得 header / URL query / JSON 键值形状的凭据，那边认得裸密钥形状与 PEM，
     * 于是"改一处忘另一处"是必然 —— `sk-proj-…` 那次翻车就是**两边同时漏**。
     * 现在只留入口，不再写第二份规则。
     *
     * 名字保持 `internal`：三份出口（Logcat / muse.log / DebugLogStore）都走它，
     * 且 `sanitizedThrowable` 的因果链也过同一份规则。
     * 日志里**不**抹姓名与中文地址，这个取舍写在 `PiiEngine.scrubForLogs` 的注释里。
     */
    internal fun sanitizePii(msg: String): String = PiiEngine.scrubForLogs(msg)

    fun d(tag: String = DEFAULT_TAG, msg: String) {
        if (enabled) {
            val safeMsg = sanitizePii(msg)
            Log.d(tag, safeMsg)
            maybeWriteFile(Level.DEBUG, tag, safeMsg, null)
            dispatchToSink(Level.DEBUG, tag, safeMsg, null)
        }
    }

    fun i(tag: String = DEFAULT_TAG, msg: String) {
        if (enabled) {
            val safeMsg = sanitizePii(msg)
            Log.i(tag, safeMsg)
            maybeWriteFile(Level.INFO, tag, safeMsg, null)
            dispatchToSink(Level.INFO, tag, safeMsg, null)
        }
    }

    fun w(tag: String = DEFAULT_TAG, msg: String, t: Throwable? = null) {
        if (enabled) {
            val safeMsg = sanitizePii(msg)
            // 构造一次、三份出口共用：Logcat、muse.log、DebugLogStore（后者会转成字符串
            // 显示在 DebugScreen 并写进导出文件）。以前这三份拿的都是未脱敏的 throwable。
            val safeTrace = t?.let(::sanitizedThrowable)
            Log.w(tag, safeMsg, safeTrace)
            maybeWriteFile(Level.WARN, tag, safeMsg, safeTrace)
            dispatchToSink(Level.WARN, tag, safeMsg, safeTrace)
        }
    }

    fun e(tag: String = DEFAULT_TAG, msg: String, t: Throwable? = null) {
        if (enabled) {
            val safeMsg = sanitizePii(msg)
            val safeTrace = t?.let(::sanitizedThrowable)
            Log.e(tag, safeMsg, safeTrace)
            maybeWriteFile(Level.ERROR, tag, safeMsg, safeTrace)
            dispatchToSink(Level.ERROR, tag, safeMsg, safeTrace)
        }
    }

    /**
     * 脱敏后的 throwable 视图。三份出口都走它：Logcat、`muse.log` 文件、
     * 以及 app 的 `DebugLogStore`（它把 throwable 转成字符串后显示在 DebugScreen
     * 并写进导出文件 —— 那是第三份未脱敏堆栈，而它的 KDoc 只声明了 message 已脱敏）。
     *
     *
     * 以前只有 `msg` 过 [sanitizePii]，throwable 是原样交出去的 —— 而本仓库的异常消息里
     * 常见完整 URL（含 `?key=…`）与上游错误体，`Failed to connect …` 这类也会把请求地址带出来。
     * 这里保留排查真正需要的三样：**类名与消息文本、调用栈、因果链**，
     * 只是每一层的消息都过一次脱敏；因果链展开成文本而不是挂回原始 cause
     * （挂回去等于让 Android 原样打印那条未脱敏的消息）。
     */
    internal fun sanitizedThrowable(t: Throwable): Throwable {
        val chain = buildString {
            var cur: Throwable? = t
            var depth = 0
            while (cur != null && depth < MAX_CAUSE_DEPTH) {
                if (depth > 0) append("\nCaused by: ")
                append(sanitizePii(cur.toString()))
                cur = cur.cause
                depth += 1
            }
        }
        return Throwable(chain).apply { stackTrace = t.stackTrace }
    }

    /** 把 throwable 转成 stack trace 字符串(便于写日志文件)。 */
    fun stackTraceToString(t: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            t.printStackTrace(pw)
        }
        return sw.toString()
    }

    /** 等级阈值判断 + 文件写入(带 rolling)。 */
    private fun maybeWriteFile(level: Level, tag: String, msg: String, t: Throwable?) {
        if (!fileLogEnabled) return
        // I/D 受 fileLogLevel 控制;W/E 总是写
        if (level.ordinal < fileLogLevel.ordinal && level != Level.WARN && level != Level.ERROR) {
            return
        }
        synchronized(lock) {
            val file = logFile ?: return
            try {
                // Rolling:超 1MB 备份为 .1,新建空文件。轮转前必须先关句柄，
                // 否则重命名后句柄仍指向被改名的那份文件，新日志会写进备份。
                if (writtenBytes > MAX_LOG_FILE_BYTES || fileWriter == null) {
                    // 轮转失败时要**有冷却**：`writerLocked` 会把 writtenBytes 读回真实大小，
                    // 于是每一条日志都会重新触发一次"关句柄 + 删备份 + 改名"——
                    // 缓冲失效、逐行落盘，比不轮转还糟。
                    val now = System.currentTimeMillis()
                    if (writtenBytes > MAX_LOG_FILE_BYTES && now - lastRollAttemptAt > ROLL_RETRY_COOLDOWN_MS) {
                        lastRollAttemptAt = now
                        closeWriterLocked()
                        val backup = File(logDir, "$LOG_FILE_NAME.1")
                        if (backup.exists()) backup.delete()
                        if (!file.renameTo(backup)) {
                            // 与全仓 12 处原子写同一约定：改名不行就复制+截断，
                            // 至少备份留得住、当前文件回到可控大小。
                            val salvaged = runCatching {
                                file.copyTo(backup, overwrite = true)
                                java.io.FileWriter(file, false).close()
                                true
                            }.getOrDefault(false)
                            if (!salvaged) {
                                android.util.Log.w("Logger", "日志轮转失败(改名与复制都不行)，冷却 ${ROLL_RETRY_COOLDOWN_MS / 1000}s 后重试；期间日志文件会继续增长")
                            }
                        }
                    }
                }
                val timestamp = dateFormat.format(Date())
                val levelStr = when (level) {
                    Level.DEBUG -> "D"
                    Level.INFO -> "I"
                    Level.WARN -> "W"
                    Level.ERROR -> "E"
                }
                val sink = writerLocked(file)
                val body = "$timestamp $levelStr/$tag: $msg\n"
                sink.append(body)
                // 按"字符数 × 2"估算字节数：中文日志一行 3 字节/字符，只数字符会让 1MB 上限实际放大到 3MB。
                // 乘 2 是 BMP 字符（3 字节）与代理对（4 字节/2 字符）的折中估计 —— 偏保守，宁可早轮转。
                writtenBytes += body.length * 2L
                if (t != null) {
                    // 进来的已经是脱敏视图（w/e 各出口共用同一份，避免重复构造）；
                    // 这份 muse.log 要从真机回捞、导出到设备之外，所以绝不能再传原始 throwable。
                    val trace = stackTraceToString(t) + "\n"
                    sink.append(trace)
                    writtenBytes += trace.length * 2L
                }
                // 每行都刷盘：持久化时机与旧实现一致。
                sink.flush()
            } catch (_: Throwable) {
                // 日志失败不能影响主流程（句柄可能在异常中作废，下次写入会重建）
                closeWriterLocked()
            }
        }
    }

    /** 取常驻句柄；文件被外部删除/轮转后自动重建。 */
    private fun writerLocked(file: File): java.io.Writer {
        fileWriter?.let { return it }
        val created = java.io.BufferedWriter(java.io.FileWriter(file, true), 8 * 1024)
        fileWriter = created
        writtenBytes = if (file.exists()) file.length() else 0L
        return created
    }

    /** 关闭并丢弃句柄。 */
    private fun closeWriterLocked() {
        runCatching { fileWriter?.close() }
        fileWriter = null
    }
}
