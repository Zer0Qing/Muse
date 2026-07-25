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
 *  - 日志文件:app cacheDir/logs/muse.log(单文件,rolling 100KB)
 *  - 等级:W/E 自动写文件;I/D 可选(由 [fileLogLevel] 控制)
 *  - 线程安全:PrintWriter synchronized
 *
 * release 构建可把 [enabled] 置为 false 关闭全部日志(Logcat + 文件)。
 */
object Logger {
    private const val DEFAULT_TAG = "Muse"
    private const val LOG_FILE_NAME = "muse.log"
    private const val MAX_LOG_FILE_BYTES = 100 * 1024L  // 100KB rolling

    @Volatile
    var enabled: Boolean = true

    /** 文件日志等级:低于此等级的 I/D 日志不写文件(W/E 总是写)。 */
    @Volatile
    var fileLogLevel: Level = Level.WARN

    /** 文件日志启用状态(由 [initFileLog] 设置)。 */
    @Volatile
    private var fileLogEnabled: Boolean = false

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var logDir: File? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

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
     * 超过 100KB 自动 rolling(旧文件改名为 muse.log.1,新日志继续写 muse.log)。
     *
     * @param context Application/Context(用 cacheDir,卸载自动清理)
     */
    fun initFileLog(context: Context) {
        synchronized(lock) {
            val dir = File(context.cacheDir, "logs")
            if (!dir.exists()) dir.mkdirs()
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

    // M5: PII 过滤正则(缓存到 object 级 val,避免每次调用重新编译)
    private val PHONE_REGEX = Regex("""\b1[3-9]\d{9}\b""")
    private val EMAIL_REGEX = Regex("""\b[\w.-]+@[\w.-]+\.\w+\b""")

    // v1.114 安全修复:原 CREDENTIAL_REGEX (?i)(token|password|apikey|api_key|secret)\s*[=:]\s*\S+
    // 关键词不全且要求 [=:] 后紧跟非空白,导致以下常见凭据格式漏匹配:
    //   - "Authorization: Bearer xxx"(authorization 不在关键词列表)
    //   - "x-api-key: sk-xxx"(x-api-key 不在列表)
    //   - JSON "access_token":"xxx"(access_token 不在列表,冒号在引号内)
    //   - URL ?key=xxx(key 不在列表)
    // 拆分为三组正则分别覆盖不同格式,新增关键词:authorization, bearer, x-api-key, key,
    //   access_token, refresh_token, client_id, client_secret
    // 1) HEADER_CRED_REGEX:HTTP header 格式(authorization/bearer/x-api-key),分隔符为冒号或空格,
    //    值部分用 [^\n,;]+ 匹配到行尾/逗号/分号,确保 "Bearer xxx" 中的 token 也被一并脱敏
    // 2) KV_CRED_REGEX:key=value 格式(token/password/secret/apikey/api_key/access_token/
    //    refresh_token/client_secret/client_id),分隔符 [=:] 兼容 JSON 中的冒号
    // 3) URL_QUERY_CRED_REGEX:URL query 参数 ?key=xxx / &api_key=xxx,保留前导 ?/& 符号
    private val HEADER_CRED_REGEX = Regex("""(?i)\b(authorization|bearer|x-api-key)\s*[:\s]\s*[^\n,;]+""")
    private val KV_CRED_REGEX = Regex("""(?i)(token|password|secret|apikey|api_key|access_token|refresh_token|client_secret|client_id)\s*[=:]\s*\S+""")
    private val URL_QUERY_CRED_REGEX = Regex("""(?i)([?&])(key|token|api_key|apikey)=\S+""")

    /**
     * M5: 过滤日志中的 PII(手机号/邮箱/凭据),避免敏感信息输出到 Logcat 或日志文件。
     * - 手机号(11 位,1 开头)→ [手机号]
     * - 邮箱 → [邮箱]
     * - 凭据(authorization/bearer/x-api-key/token/password/secret/apikey/api_key/
     *   access_token/refresh_token/client_id/client_secret/key = xxx)→ key=[已脱敏]
     *
     * v1.114: 拆分为 header / key=value / URL query 三组正则,详见上方注释。
     */
    private fun sanitizePii(msg: String): String {
        return msg
            .replace(PHONE_REGEX, "[手机号]")
            .replace(EMAIL_REGEX, "[邮箱]")
            // v1.114: 三组凭据正则分别替换,保留 key 名便于排查
            .replace(HEADER_CRED_REGEX, "$1: [已脱敏]")
            .replace(KV_CRED_REGEX, "$1=[已脱敏]")
            .replace(URL_QUERY_CRED_REGEX, "$1$2=[已脱敏]")
    }

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
            Log.w(tag, safeMsg, t)
            maybeWriteFile(Level.WARN, tag, safeMsg, t)
            dispatchToSink(Level.WARN, tag, safeMsg, t)
        }
    }

    fun e(tag: String = DEFAULT_TAG, msg: String, t: Throwable? = null) {
        if (enabled) {
            val safeMsg = sanitizePii(msg)
            Log.e(tag, safeMsg, t)
            maybeWriteFile(Level.ERROR, tag, safeMsg, t)
            dispatchToSink(Level.ERROR, tag, safeMsg, t)
        }
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
                // Rolling:超 100KB 备份为 .1,新建空文件
                if (file.exists() && file.length() > MAX_LOG_FILE_BYTES) {
                    val backup = File(logDir, "$LOG_FILE_NAME.1")
                    if (backup.exists()) backup.delete()
                    file.renameTo(backup)
                }
                val timestamp = dateFormat.format(Date())
                val levelStr = when (level) {
                    Level.DEBUG -> "D"
                    Level.INFO -> "I"
                    Level.WARN -> "W"
                    Level.ERROR -> "E"
                }
                val line = "$timestamp $levelStr/$tag: $msg\n"
                file.appendText(line)
                if (t != null) {
                    file.appendText(stackTraceToString(t))
                    file.appendText("\n")
                }
            } catch (_: Throwable) {
                // 日志失败不能影响主流程
            }
        }
    }
}
