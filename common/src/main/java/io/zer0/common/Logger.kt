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
    private const val MAX_LOG_FILE_BYTES = 1_000 * 1024L // 约 1MB rolling
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
    // H-SEC-2: 扩展覆盖身份证号、银行卡号、IPv4 地址,与 app.privacy.PiiGuard 对齐
    private val PHONE_REGEX = Regex("""\b1[3-9]\d{9}\b""")
    private val EMAIL_REGEX = Regex("""\b[\w.-]+@[\w.-]+\.\w+\b""")

    // 18位身份证(含15位兼容): 地区码+生日+顺序码+校验位
    // 18 位与 15 位老式都盖：`PiiEngine` 的两条都覆盖，这边以前只有 18 位（对表见
    // docs/common/地基模块体检.md 的覆盖矩阵）。
    private val ID_CARD_REGEX = Regex(
        """\b(?:[1-9]\d{5}(?:18|19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[\dXx]""" +
            """|[1-9]\d{5}\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3})\b""",
    )

    // 银行卡号: 16-19位连续数字,或 4-4-4-4 分隔格式
    private val BANK_CARD_REGEX = Regex("""\b\d{16,19}\b|\b\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{4,7}\b""")

    // IPv4 地址
    private val IPV4_REGEX = Regex("""\b(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\b""")

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
    //    refresh_token/client_secret/client_id),分隔符 [=:]
    //    （原注释在这里写着"兼容 JSON 中的冒号"—— 2026-11-04 实测**并不兼容**，见下方补记）
    // 3) URL_QUERY_CRED_REGEX:URL query 参数 ?key=xxx / &api_key=xxx,保留前导 ?/& 符号
    private val HEADER_CRED_REGEX = Regex("""(?i)\b(authorization|bearer|x-api-key)\s*[:\s]\s*[^\n,;]+""")
    // 2026-11-04 实测补两处泄漏（LoggerSanitizeTest 抓到）：
    //  a) JSON 形式 `"access_token":"xxx"` —— 键后面是**引号再冒号**，原来的 `\s*[=:]`
    //     要求紧跟 = 或 :，所以整条漏匹配（注释里那句"兼容 JSON 中的冒号"其实没做到）；
    //  b) 纯文本 `key=xxx` —— 只有 URL query 里的 `?key=` 被覆盖，`sort key=abc` 这类漏了。
    //  `\b` 放在键名前，避免把 monkey=… 这类普通词也吃掉；`api_key` 排在 `key` 之前。
    private val KV_CRED_REGEX =
        Regex("""(?i)\b(token|password|secret|apikey|api_key|access_token|refresh_token|client_secret|client_id|key)\s*"?\s*[=:]\s*"?\S+""")
    private val URL_QUERY_CRED_REGEX = Regex("""(?i)([?&])(key|token|api_key|apikey)=\S+""")

    // 2026-11-04 与 `PiiEngine` 对表后补上（矩阵见 docs/common/地基模块体检.md）：
    // 这两类以前只有 `PiiEngine` 认得，而 `Logger` 谁都能调 —— 于是一条
    // `Logger.e("chat", "请求失败 sk-proj-abcdef…")` 会**原样**进 muse.log 并被导出到设备之外。
    // 它们对排查毫无价值（看不出是哪把钥匙），所以直接整段换掉。
    private val BARE_SECRET_REGEX = Regex(
        """(?i)\b(?:sk-[a-z0-9_-]{20,}|AKIA[0-9A-Z]{16}|gsk_[a-z0-9_]{20,}|ghp_[a-z0-9]{36}|glpat-[a-z0-9_-]{20,}|xoxb-[0-9a-z]+)\b""",
    )
    private val PRIVATE_KEY_BLOCK_REGEX = Regex(
        """-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----[\s\S]*?-----END (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----""",
    )

    /**
     * M5: 过滤日志中的 PII(手机号/邮箱/身份证/银行卡/IP/凭据),避免敏感信息输出到 Logcat 或日志文件。
     * - 手机号(11 位,1 开头)→ [手机号]
     * - 邮箱 → [邮箱]
     * - H-SEC-2: 身份证号(18位)→ [身份证],银行卡号(16-19位)→ [银行卡],IPv4→ [IP]
     * - 凭据(authorization/bearer/x-api-key/token/password/secret/apikey/api_key/
     *   access_token/refresh_token/client_id/client_secret/key = xxx)→ key=[已脱敏]
     *   含 `键名="值"` 与 JSON `"键名":"值"` 两种写法（后一种是 2026-11-04 补的漏匹配）
     * - 裸密钥形状（sk-/AKIA/gsk_/ghp_/glpat-/xoxb-）与 PEM 私钥块 → [密钥] / [私钥块]
     *
     * 与 `PiiEngine` 的分工（2026-11-04 对表）：**互不包含**。
     * `PiiEngine` 认得裸密钥形状、PEM、SSN、姓名、中文地址；
     * 这边认得传输形状的凭据（HTTP header、URL query、JSON 键值）。
     * 日志里**不**抹姓名与地址：那会吃掉排查真正需要的上下文，而聊天/记忆链路已有 `PiiEngine` 兜住。
     *
     * v1.114: 拆分为 header / key=value / URL query 三组正则,详见上方注释。
     */
    internal fun sanitizePii(msg: String): String {
        return msg
            // 私钥块与裸密钥先走：它们是整段/整串替换，不该被后面的键值规则切碎
            .replace(PRIVATE_KEY_BLOCK_REGEX, "[私钥块]")
            .replace(BARE_SECRET_REGEX, "[密钥]")
            .replace(PHONE_REGEX, "[手机号]")
            .replace(EMAIL_REGEX, "[邮箱]")
            .replace(ID_CARD_REGEX, "[身份证]")
            .replace(BANK_CARD_REGEX, "[银行卡]")
            .replace(IPV4_REGEX, "[IP]")
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
                    if (writtenBytes > MAX_LOG_FILE_BYTES) {
                        closeWriterLocked()
                        val backup = File(logDir, "$LOG_FILE_NAME.1")
                        if (backup.exists()) backup.delete()
                        file.renameTo(backup)
                        writtenBytes = 0L
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
