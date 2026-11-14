package io.zer0.muse.crash

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import io.zer0.common.Logger
import io.zer0.common.resultOf
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 8.10: 全局崩溃处理器 + Safe Mode 崩溃恢复。
 *
 * 实现:
 *  1. **崩溃捕获**: [Thread.setDefaultUncaughtExceptionHandler] 拦截未捕获异常
 *  2. **崩溃日志**: 写入 `filesDir/crash/crash-yyyyMMdd-HHmmss.txt`(含堆栈 + 设备信息)
 *  3. **Safe Mode 标记**: 崩溃后写 `safe_mode.flag` 文件,下次启动 [checkSafeMode] 返回 true
 *     v2.0+: 同时写 SharedPreferences(safe_mode_pending + crash_time + crash_trace),
 *     持久化崩溃时间与堆栈摘要,供 SafeModeScreen 直接展示,无需再读文件
 *  4. **Safe Mode 行为**: 启动时检测到 flag → 跳过 Koin 模块加载 / Web 服务器 / 后台任务,
 *     仅加载最小 UI 提示用户"上次崩溃,已进入安全模式,崩溃日志已保存"
 *  5. **flag 清理**: 用户确认后调 [clearSafeModeFlag] 清除 flag,下次正常启动
 *
 * 设计:
 *  - 崩溃日志文件保留最近 5 份,超过自动删最旧的
 *  - Safe Mode flag 是普通文件(非 DataStore),避免 DataStore 初始化失败时读不到
 *  - v2.0+: SP 持久化层与文件 flag 双写,SP 不依赖 Koin,可独立读取
 *  - 崩溃后不重启 App(避免崩溃循环),直接 finish 让系统回到桌面
 *
 * 限制:
 *  - 仅捕获未处理异常(OutOfMemoryError 等 Error 子类也能捕获)
 *  - ANR 不触发(由系统 ANR 机制处理)
 *  - v1.56: Compose 渲染异常通过 RuntimeExceptionHandler + logComposeException 捕获
 */
class MuseCrashHandler private constructor(private val appContext: Context) : Thread.UncaughtExceptionHandler {

    private val previousHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    /** 安装为全局未捕获异常处理器。 */
    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(this)
        // v1.52: 启动时清理旧崩溃日志(保留最近 MAX_CRASH_LOGS 份),
        // 避免 crash/ 目录越积越大(之前只在崩溃时清理,正常启动不触发)。
        resultOf {
            // v1.0.53: 清理 external + 私有两处目录(新日志写 external,旧日志可能残留在私有目录)
            val dirs = listOfNotNull(
                appContext.getExternalFilesDir("crash"),
                File(appContext.filesDir, "crash"),
            )
            dirs.forEach { crashDir ->
                crashDir.listFiles()
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(MAX_CRASH_LOGS)
                    ?.forEach { it.delete() }
            }
        }
        Logger.i(TAG, "Crash handler installed")
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        Logger.e(TAG, "Uncaught exception on ${t.name}", e)
        // M9: 注意:崩溃线程同步磁盘 IO,OOM/磁盘满场景可能二次失败。
        // resultOf 包裹确保不传播,但日志可能丢失。这是 Android CrashHandler 的常见权衡
        resultOf {
            val crashText = writeCrashLog(t, e)
            // v2.0+: 同时写 SP 持久化层,带上崩溃时间和堆栈摘要,
            // SafeModeScreen 启动后可直接从 SP 读取,无需依赖文件 IO
            markSafeMode(crashText)
            // 崩溃上报队列:把脱敏后的崩溃文本入队,下次启动时若用户已授权,
            // 弹窗询问是否上报(默认不上报,隐私优先)
            enqueueCrashReport(appContext, crashText)
        }
        // 交给 previous handler(通常是系统默认,会弹"应用已停止"对话框)
        previousHandler?.uncaughtException(t, e)
    }

    /**
     * 写崩溃日志到 filesDir/crash/crash-yyyyMMdd-HHmmss.txt。
     *
     * v2.0+: 返回脱敏后的崩溃文本,供调用方写入 SharedPreferences 持久化层,
     * SafeModeScreen 可直接从 SP 读取展示,无需再读文件。
     */
    private fun writeCrashLog(t: Thread, e: Throwable): String {
        // v1.0.53: 优先 externalFilesDir(adb/文件管理器可见),回退私有 filesDir
        val externalCrashDir = appContext.getExternalFilesDir("crash")
        val crashDir = (externalCrashDir ?: File(appContext.filesDir, "crash")).apply { mkdirs() }
        val fmt = SimpleDateFormat(CRASH_TIME_FMT, Locale.US)
        val fileName = "crash-${fmt.format(Date())}.txt"
        val logFile = File(crashDir, fileName)

        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("===== muse crash log =====")
            pw.println("Time: ${Date()}")
            pw.println("Thread: ${t.name} (id=${t.id})")
            pw.println()
            pw.println("----- Device Info -----")
            pw.println("Brand: ${Build.BRAND}")
            pw.println("Model: ${Build.MODEL}")
            pw.println("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            pw.println("ABIs: ${Build.SUPPORTED_ABIS.joinToString(",")}")
            // v2.6.22: OOM 类崩溃靠堆数字才能区分「一次大分配」还是「整体已满」。
            val runtime = Runtime.getRuntime()
            val maxMb = runtime.maxMemory() / (1024 * 1024)
            val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            pw.println("Heap: used ${usedMb}MB / max ${maxMb}MB")
            pw.println("OutOfMemory: ${e is OutOfMemoryError}")
            pw.println()
            if (e is OutOfMemoryError) appendMemoryDetail(pw)
            pw.println("----- Stack Trace -----")
            e.printStackTrace(pw)
        }
        // v1.104: 脱敏后写盘(避免 API key / Bearer token / Authorization 头泄露到崩溃日志)
        val redacted = redactSensitive(sw.toString())
        logFile.writeText(redacted)

        // 清理旧崩溃日志(保留最近 5 份)
        crashDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_CRASH_LOGS)
            ?.forEach { it.delete() }

        return redacted
    }

    /**
     * 写 safe_mode.flag 文件 + SharedPreferences 持久化层。
     *
     * v2.0+: 在原文件 flag 基础上,同步写 SP:
     *  - safe_mode_pending = true
     *  - crash_time = 当前时间(yyyy-MM-dd HH:mm:ss 格式,供 UI 展示)
     *  - crash_trace = 崩溃堆栈摘要(截断至 [MAX_SP_TRACE_LENGTH] 字符,避免 SP 写入超大字符串)
     *
     * 双写策略:文件 flag 是历史兼容路径,SP 是新增结构化数据层。
     * SafeModeScreen 优先读 SP(结构化),回退读文件(无 SP 时旧版本升级场景)。
     */
    private fun markSafeMode(crashTrace: String = "") {
        File(appContext.filesDir, SAFE_MODE_FLAG).writeText("1")
        val now = SimpleDateFormat(CRASH_TIME_DISPLAY_FMT, Locale.US).format(Date())
        writeSafeModeSp(appContext, now, crashTrace.take(MAX_SP_TRACE_LENGTH))
    }

    /**
     * 崩溃现场的内存构成（仅 OOM 时写入）。
     *
     * OOM 堆栈只能告诉我们谁最后倒下：已收到的报告里受害者分别是 Compose 快照推进、
     * 协程调度器、厂商 Jank 统计、通知 Ranking 解析、vivo 帧率提升、ConnectivityThread 的一行日志
     * ——全是随机受害者，对“谁占着堆”没有信息。这里在写日志的这一刻量一遍已知大户。
     *
     * 每一项独立 `runCatching`：诊断代码不能把崩溃流程再崩一次，也不能在堆已满时
     * 因为一次分配失败而丢掉后面的信息。
     */
    private fun appendMemoryDetail(pw: PrintWriter) {
        pw.println("----- Memory Detail -----")
        // 堆上限来自 ROM 的 dalvik.vm.heapsize(largeHeap) 或 heapgrowthlimit,
        // 应用改不了。同时记下 ActivityManager 的两个值和 isLowRamDevice,
        // 才能判断「这台机器到底给了多少」以及 largeHeap 是否真的生效。
        runCatching {
            val am = appContext.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as? android.app.ActivityManager
            if (am != null) {
                pw.println("memory class: ${am.memoryClass}MB")
                pw.println("large memory class: ${am.largeMemoryClass}MB")
                pw.println("is low ram device: ${am.isLowRamDevice}")
            }
        }
        runCatching {
            pw.println(
                "memory budget: heap=${io.zer0.muse.perf.MemoryBudget.heapMaxMb}MB " +
                    "lowHeap=${io.zer0.muse.perf.MemoryBudget.isLowHeap} " +
                    "imageTotal=${io.zer0.muse.perf.MemoryBudget.imageCacheBytes / (1024 * 1024)}MB",
            )
        }
        runCatching {
            val nativeMb = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
            pw.println("native heap allocated: ${nativeMb}MB")
        }
        runCatching {
            val cache = coil.Coil.imageLoader(appContext).memoryCache
            if (cache != null) {
                pw.println(
                    "coil image cache: ${cache.size / 1024}KB / ${cache.maxSize / 1024}KB",
                )
            }
        }
        runCatching {
            pw.println(
                "data-uri bitmaps: ${io.zer0.muse.perf.DataUriImageCache.size()} entries, " +
                    "${io.zer0.muse.perf.DataUriImageCache.bytes() / (1024 * 1024)}MB " +
                    "/ ${io.zer0.muse.perf.DataUriImageCache.maxBytesForReport() / (1024 * 1024)}MB",
            )
        }
        runCatching {
            pw.println("markdown cache entries: ${io.zer0.muse.perf.MarkdownParseCache.size()}")
        }
        runCatching {
            pw.println("debug log entries: ${io.zer0.muse.debug.DebugLogStore.size()}")
        }
        pw.println()
    }

    companion object {
        private const val TAG = "MuseCrashHandler"
        private const val SAFE_MODE_FLAG = "safe_mode.flag"

        /** 标记内容：旧版本一直写的是 "1"，所以按内容判仍然兼容旧文件。 */
        internal const val SAFE_MODE_MARK_ON = "1"
        internal const val SAFE_MODE_MARK_OFF = "0"
        private const val MAX_CRASH_LOGS = 5

        // L4-1: 崩溃日志时间戳格式串
        private const val CRASH_TIME_FMT = "yyyyMMdd-HHmmss"

        // v2.0+: SP 中崩溃时间展示格式(可读格式,供 SafeModeScreen 直接显示)
        private const val CRASH_TIME_DISPLAY_FMT = "yyyy-MM-dd HH:mm:ss"

        // v2.0+: SP 文件名 — 独立 SP 文件,避免与其他模块 SP 冲突,且不依赖 Koin
        private const val SAFE_MODE_SP = "muse_safe_mode"

        // v2.0+: SP 字段 key — 与任务规约保持一致(safe_mode_pending + crash_time + crash_trace)
        private const val SP_KEY_PENDING = "safe_mode_pending"
        private const val SP_KEY_CRASH_TIME = "crash_time"
        private const val SP_KEY_CRASH_TRACE = "crash_trace"

        // v2.0+: SP crash_trace 字段最大长度(避免 SP 写入超大字符串导致 ANR)
        private const val MAX_SP_TRACE_LENGTH = 8000

        // 崩溃上报队列 SP 文件名 — 与 safe_mode SP 分离,互不影响,且不依赖 Koin
        private const val CRASH_QUEUE_SP = "muse_crash_queue"

        // 队列字段 key — 与任务规约保持一致(queue_crash_reports)
        private const val SP_KEY_QUEUE = "queue_crash_reports"

        // 队列上限 — 超出丢弃最旧的,避免 SP 越积越大导致 ANR
        private const val MAX_QUEUE_SIZE = 10

        /** 获取 Safe Mode 专用 SharedPreferences(不依赖 Koin,可在启动早期读取)。 */
        private fun safeModeSp(appContext: Context): SharedPreferences = appContext.getSharedPreferences(SAFE_MODE_SP, Context.MODE_PRIVATE)

        /** 获取崩溃上报队列专用 SharedPreferences(不依赖 Koin,可在启动早期读取)。 */
        private fun crashQueueSp(appContext: Context): SharedPreferences =
            appContext.getSharedPreferences(CRASH_QUEUE_SP, Context.MODE_PRIVATE)

        /**
         * 从 SP 读取待上报队列 — 用换行分隔的字符串数组(避免引入 JSON 依赖)。
         *
         * 每条记录是一段脱敏后的崩溃文本(含 device info 头 + 堆栈),用 [QUEUE_DELIMITER] 分隔。
         * 解析失败时返回空列表(兼容旧版本无此字段 / SP 损坏场景)。
         */
        private fun readQueueFromSp(sp: SharedPreferences): List<String> {
            val raw = sp.getString(SP_KEY_QUEUE, null) ?: return emptyList()
            if (raw.isEmpty()) return emptyList()
            return raw.split(QUEUE_DELIMITER).filter { it.isNotEmpty() }
        }

        /** 把队列编码为 SP 存储字符串(用 [QUEUE_DELIMITER] 拼接)。 */
        private fun encodeQueue(queue: List<String>): String = queue.joinToString(QUEUE_DELIMITER)

        // 队列条目分隔符 — 用零宽不可见字符序列,避免与崩溃堆栈文本冲突
        private const val QUEUE_DELIMITER = "\u0000\u0001<CRASH_ENTRY>\u0001\u0000"

        /**
         * 把崩溃文本入队待上报队列。
         *
         * 调用时机:
         *  - [uncaughtException]:崩溃线程同步入队
         *  - [logComposeException]:Compose 渲染异常入队
         *
         * 设计:
         *  - 队列上限 [MAX_QUEUE_SIZE] 条,超出丢弃最旧的(FIFO)
         *  - 用 commit() 同步落盘,确保崩溃后进程被 kill 时数据不丢失
         *  - 仅存储已脱敏的崩溃文本,设备信息在上报时通过 [buildStandardMetadata] 现取
         *  - 队列与 safe_mode SP 文件分离,互不影响
         *
         * 上报时机：用户点「立即上报」时由设置页调 [io.zer0.muse.crash.CrashReporterFactory.submit]，
         * 上报出口固定（开发者服务器 → 失败退回开发者邮箱），用户不需要也不提供任何配置。
         */
        fun enqueueCrashReport(appContext: Context, crashText: String) {
            val sp = crashQueueSp(appContext)
            val queue = readQueueFromSp(sp).toMutableList()
            queue.add(crashText.take(MAX_SP_TRACE_LENGTH))
            // 超出上限丢弃最旧的(列表头)
            while (queue.size > MAX_QUEUE_SIZE) queue.removeAt(0)
            // commit() 同步落盘:崩溃后进程可能立即被 kill,apply() 异步写可能丢失
            sp.edit().putString(SP_KEY_QUEUE, encodeQueue(queue)).commit()
        }

        /**
         * 读取待上报的崩溃列表(按入队顺序,最旧的在前)。
         *
         * 调用方应在用户授权后遍历列表逐条上报,完成后调 [clearCrashQueue] 清空。
         */
        fun getPendingCrashReports(appContext: Context): List<String> = readQueueFromSp(crashQueueSp(appContext))

        /** 是否有待上报的崩溃(快速检查,避免启动时遍历整列表)。 */
        fun hasPendingCrashReports(appContext: Context): Boolean = readQueueFromSp(crashQueueSp(appContext)).isNotEmpty()

        /** 清空待上报队列(用户已上报或选择不上报时调用)。 */
        fun clearCrashQueue(appContext: Context) {
            crashQueueSp(appContext).edit().clear().commit()
        }

        /** 写 SharedPreferences 的 Safe Mode 持久化层(含崩溃时间和堆栈摘要)。 */
        private fun writeSafeModeSp(appContext: Context, crashTime: String, crashTrace: String) {
            // 用 commit() 同步落盘:崩溃后进程可能被立即 kill,apply() 异步写盘可能未完成,
            // 导致下次启动读不到 safe_mode_pending,反复崩溃形成崩溃循环。
            safeModeSp(appContext).edit()
                .putBoolean(SP_KEY_PENDING, true)
                .putString(SP_KEY_CRASH_TIME, crashTime)
                .putString(SP_KEY_CRASH_TRACE, crashTrace)
                .commit()
        }

        /**
         * 崩溃日志脱敏 — 把凭据替换为 ***。
         *
         * 崩溃堆栈可能包含 OkHttp 请求 URL(含查询串凭据)或自定义异常 message
         * (含请求头)。这些敏感串若随 zip 邮件外发会泄露凭据。
         *
         * 覆盖的**形状**比「某几个参数名」更重要：同一份日志里凭据可能出现在
         * 查询串、请求头、路径段、以及 PEM 块里。只认 `apiKey=` 一种写法时，
         * `?key=`、`?token=`、`x-api-key:` 全都是漏网的。
         */
        private val REDACT_PATTERNS = listOf(
            // URL 查询串里的凭据：?key=… / &token=… / ?api_key=… / ?apikey=…
            Regex("""(?i)([?&](?:key|token|api[_-]?key|apikey|access[_-]?token)=)[^&\s"'<>]+"""),
            // 其它位置的 apiKey=xxx / api_key=xxx
            Regex("""(?i)(api[_-]?key\s*[=:]\s*)[^&\s"'<>]+"""),
            // 请求头：Authorization / x-api-key / x-auth-token
            Regex("""(?i)((?:authorization|x-api-key|x-auth-token)\s*:\s*)(?:bearer\s+)?[^\s\r\n,;<>}]+"""),
            // 裸 Bearer xxx
            Regex("""(?i)(bearer\s+)[A-Za-z0-9\-_.=]+"""),
            // OpenAI sk- / Anthropic sk-ant- 开头的 key(至少 20 字符)
            Regex("""\bsk-(?:ant-)?[A-Za-z0-9\-_]{20,}"""),
            // 令牌在路径里：/bot<数字>:<令牌>/ (Telegram Bot API 的形状)
            Regex("""(/bot\d+:)[^/\s]+"""),
            // PEM 私钥正文
            Regex(
                """-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----""",
            ),
        )

        fun redactSensitive(text: String): String {
            var result = text
            for (p in REDACT_PATTERNS) {
                result = p.replace(result) { mr ->
                    val g1 = mr.groupValues.getOrNull(1)
                    if (g1.isNullOrEmpty()) "***" else "$g1***"
                }
            }
            return result
        }

        /** 安装全局崩溃处理器(应在 [android.app.Application.onCreate] 最早调用)。 */
        fun install(appContext: Context) {
            MuseCrashHandler(appContext).install()
        }

/**
         * 安全模式标记是否**有效**。
         *
         * 以前判的是"文件存在"，于是清除**只能靠删文件**；而 `File.delete()` 失败是
         * 返回 false 不抛异常（目录项被占用等情形就会失败）。删不掉的结果是用户
         * **永远卡在安全模式**：点了"继续正常启动"，下次照旧进来，而且看不出为什么。
         *
         * 现在按内容判：旧版本写的本来就是 "1"（三处写入点都一样），所以读得到 "1" 才算标记在；
         * 清除时仍优先删文件，删不掉改成写 "0" —— 写文件只需要文件可写，比删目录项容易得多。
         */
        internal fun hasSafeModeMark(file: File): Boolean = runCatching {
            file.isFile && file.readText().trim() == SAFE_MODE_MARK_ON
        }.getOrDefault(false)

        /**
         * 清除标记：先删，删不掉再写 "0"。
         *
         * @param deleter 删除动作，默认 `File.delete()`；留成参数是为了能让
         *                "删不掉"这条分支被测出来（真造一个不可删的文件跨平台不稳定）。
         * @return "deleted" / "cleared" / null（两种办法都没生效）
         */
        internal fun clearSafeModeMark(flagFile: File, deleter: (File) -> Boolean = { it.delete() }): String? {
            if (!flagFile.exists()) return "deleted"
            val removed = runCatching { deleter(flagFile) }.getOrDefault(false)
            if (removed) return "deleted"
            val overwritten = runCatching {
                flagFile.writeText(SAFE_MODE_MARK_OFF)
                true
            }.getOrDefault(false)
            return if (overwritten && !hasSafeModeMark(flagFile)) "cleared" else null
        }

        /**
         * 检查是否应进入 Safe Mode(上次崩溃)。
         *
         * v2.0+: 同时检查文件 flag 和 SP safe_mode_pending,任一存在即返回 true。
         * 双检保证旧版本(只写文件)升级后仍能识别 Safe Mode 状态。
         */

        fun checkSafeMode(appContext: Context): Boolean = hasSafeModeMark(File(appContext.filesDir, SAFE_MODE_FLAG)) ||
            safeModeSp(appContext).getBoolean(SP_KEY_PENDING, false)

        /**
         * 清除 Safe Mode 标记(用户确认后调用,下次正常启动)。
         *
         * v2.0+: 同时清除文件 flag 和 SP 持久化层,避免任一残留导致重复进入 Safe Mode。
         *
         * P0 修复:此前 SP 清除用 apply() 异步落盘,紧接着 [android.os.Process.killProcess]
         * 会立即终止进程,导致 SP 写入未完成就被杀死。下次启动 [checkSafeMode] 读到
         * safe_mode_pending 仍为 true,再次进入安全模式,形成"继续正常启动 → 再次进入安全模式"循环。
         * 同时崩溃日志文件未清理,[SafeModeScreen] 回退读取 [readLatestCrashLog] 仍展示旧堆栈。
         *
         * 修复策略:
         *  1. SP 改用 commit() 同步落盘,确保 killProcess 前数据已持久化
         *  2. 同步删除崩溃日志文件,避免下次启动 SafeModeScreen 通过 readLatestCrashLog
         *     回退展示旧崩溃堆栈(即使 SP 已清空,文件残留仍会让用户看到旧崩溃信息)
         */
        fun clearSafeModeFlag(appContext: Context) {
            val flagFile = File(appContext.filesDir, SAFE_MODE_FLAG)
            when (clearSafeModeMark(flagFile)) {
                null -> Logger.w(
                    TAG,
                    "安全模式标记既删不掉也改写不了：下次启动仍会进安全模式 path=${flagFile.absolutePath}",
                )
                "cleared" -> Logger.i(TAG, "安全模式标记删不掉，已改写为 0 生效")
                else -> Unit
            }
            // commit() 同步落盘:后续立即 killProcess,apply() 异步写可能未完成,
            // 导致下次启动 SP 中 safe_mode_pending 仍为 true,反复进入安全模式
            safeModeSp(appContext).edit().clear().commit()
            // 崩溃日志删不掉要留数：SafeModeScreen 在 SP 无 crash_trace 时会回退读文件，
            // 残留文件会让用户下次仍看到**上一次**的崩溃堆栈
            var failed = 0
            listCrashLogs(appContext).forEach { f ->
                if (!runCatching { f.delete() }.getOrDefault(false)) failed += 1
            }
            if (failed > 0) Logger.w(TAG, "崩溃日志有 $failed 个没删掉，SafeModeScreen 可能仍展示旧堆栈")
        }

        /**
         * v1.91-hotfix: 主动标记 Safe Mode(用于 startKoin 失败等非崩溃场景)。
         *
         * 当 startKoin 抛异常时,MuseCrashHandler 的 uncaughtException 不会被触发
         * (因为异常被 resultOf 捕获),需通过此方法手动标记 Safe Mode,
         * 确保下次启动进入安全模式而非重复崩溃。
         *
         * v2.0+: 同步写 SP 持久化层,记录崩溃时间和提示信息(非崩溃场景 trace 为提示文案)。
         */
        fun markSafeMode(appContext: Context) {
            File(appContext.filesDir, SAFE_MODE_FLAG).writeText("1")
            val now = SimpleDateFormat(CRASH_TIME_DISPLAY_FMT, Locale.US).format(Date())
            // 非崩溃场景(startKoin 失败等),trace 字段写入提示文案,
            // 让 SafeModeScreen 能区分展示
            writeSafeModeSp(appContext, now, "Koin 启动失败(应用初始化阶段异常,非主线程崩溃)")
        }

        /**
         * v2.0+: 读取上次崩溃时间(yyyy-MM-dd HH:mm:ss 格式)。
         *
         * 数据来源:SharedPreferences 持久化层,崩溃时由 [markSafeMode] 写入。
         * 若 SP 中无值(旧版本升级场景),返回 null,调用方可回退到崩溃日志文件的 mtime。
         */
        fun getCrashTime(appContext: Context): String? = safeModeSp(appContext).getString(SP_KEY_CRASH_TIME, null)

        /**
         * v2.0+: 读取上次崩溃堆栈摘要(脱敏后的完整崩溃日志文本)。
         *
         * 数据来源:SharedPreferences 持久化层,已截断至 [MAX_SP_TRACE_LENGTH] 字符。
         * 若 SP 中无值,返回 null,调用方应回退到 [readLatestCrashLog] 读文件。
         */
        fun getCrashTrace(appContext: Context): String? = safeModeSp(appContext).getString(SP_KEY_CRASH_TRACE, null)

        /**
         * 列出全部崩溃日志文件(按时间降序)。
         *
         * v2.x: 同时扫描外部目录(externalFilesDir/crash,当前崩溃日志实际落盘位置)
         * 与私有目录(filesDir/crash,旧版本残留 / 外部存储不可用场景),
         * 按文件名去重后合并 — 此前只读私有目录,导致崩溃日志面板/导出为空。
         */
        fun listCrashLogs(appContext: Context): List<File> {
            val dirs = listOfNotNull(appContext.getExternalFilesDir("crash"), File(appContext.filesDir, "crash"))
            return dirs
                .flatMap { dir -> dir.listFiles()?.toList() ?: emptyList() }
                .distinctBy { it.name }
                .sortedByDescending { it.lastModified() }
        }

        /** 读取最近一份崩溃日志内容(用于 Safe Mode UI 展示)。 */
        fun readLatestCrashLog(appContext: Context): String? {
            val logs = listCrashLogs(appContext)
            return logs.firstOrNull()?.readText()
        }

        /**
         * v1.95: 把全部崩溃日志 + 设备信息打包成 zip,供邮件附件一键发送。
         *
         * zip 内含 device_info.txt(设备/版本摘要)+ 各崩溃日志文件。
         * 位于 cacheDir(已通过 FileProvider 暴露),无日志时返回 null。
         */
        fun packageCrashLogsToZip(appContext: Context): File? {
            val logs = listCrashLogs(appContext)
            val zipFile = File(appContext.cacheDir, "muse_crash_logs.zip")
            // 无论有无日志都生成 zip(至少包含设备信息),便于用户反馈
            zipFile.outputStream().use { fos ->
                java.util.zip.ZipOutputStream(fos).use { zos ->
                    val deviceInfo = buildString {
                        appendLine("===== muse crash report =====")
                        appendLine("Time: ${Date()}")
                        appendLine("Brand: ${Build.BRAND}")
                        appendLine("Model: ${Build.MODEL}")
                        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                        appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString(",")}")
                    }
                    zos.putNextEntry(java.util.zip.ZipEntry("device_info.txt"))
                    zos.write(deviceInfo.toByteArray())
                    zos.closeEntry()
                    logs.forEach { log ->
                        zos.putNextEntry(java.util.zip.ZipEntry(log.name))
                        zos.write(log.readBytes())
                        zos.closeEntry()
                    }
                }
            }
            return zipFile.takeIf { logs.isNotEmpty() }
        }

        /**
         * v1.56: 记录 Compose 渲染异常(不杀进程,仅写日志 + 标记 Safe Mode)。
         *
         * Compose 的 RuntimeExceptionHandler 不会走 Thread.setDefaultUncaughtExceptionHandler,
         * 需通过此方法手动记录。下次启动时用户可在 Safe Mode 中查看崩溃日志。
         *
         * v2.0+: 同步写 SP 持久化层(含崩溃时间和堆栈摘要),与 uncaughtException 路径对齐。
         */
        fun logComposeException(appContext: Context, throwable: Throwable) {
            Logger.e(TAG, "Compose runtime exception", throwable)
            resultOf {
                val crashDir = File(appContext.filesDir, "crash").apply { mkdirs() }
                val fmt = SimpleDateFormat(CRASH_TIME_FMT, Locale.US)
                val fileName = "crash-compose-${fmt.format(Date())}.txt"
                val logFile = File(crashDir, fileName)

                val sw = StringWriter()
                PrintWriter(sw).use { pw ->
                    pw.println("===== muse compose crash log =====")
                    pw.println("Time: ${Date()}")
                    pw.println("Thread: ${Thread.currentThread().name} (id=${Thread.currentThread().id})")
                    pw.println()
                    pw.println("----- Device Info -----")
                    pw.println("Brand: ${Build.BRAND}")
                    pw.println("Model: ${Build.MODEL}")
                    pw.println("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    pw.println("ABIs: ${Build.SUPPORTED_ABIS.joinToString(",")}")
                    pw.println()
                    pw.println("----- Stack Trace -----")
                    throwable.printStackTrace(pw)
                }
                // v1.104: 脱敏后写盘
                val redacted = redactSensitive(sw.toString())
                logFile.writeText(redacted)

                // 清理旧崩溃日志
                crashDir.listFiles()
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(MAX_CRASH_LOGS)
                    ?.forEach { it.delete() }

                // 标记 Safe Mode,下次启动提示用户
                // v2.0+: 文件 flag + SP 双写(SP 含崩溃时间和堆栈摘要)
                File(appContext.filesDir, SAFE_MODE_FLAG).writeText("1")
                val now = SimpleDateFormat(CRASH_TIME_DISPLAY_FMT, Locale.US).format(Date())
                writeSafeModeSp(appContext, now, redacted.take(MAX_SP_TRACE_LENGTH))

                // 崩溃上报队列:与 uncaughtException 路径对齐,把 Compose 渲染异常也入队
                enqueueCrashReport(appContext, redacted)
            }
        }
    }
}
