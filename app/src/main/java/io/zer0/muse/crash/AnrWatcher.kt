package io.zer0.muse.crash

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.zer0.common.Logger
import io.zer0.common.Perf
import io.zer0.common.resultOf
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.audit.AuditLogger
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ANR(应用无响应)检测器。
 *
 * 检测机制:
 *  - 独立守护线程 "AnrWatcher" 每 [CHECK_INTERVAL_MS] 向主线程 Handler 投递一个 Ping
 *  - Ping 在主线程被执行时更新 [lastPongTime](主线程响应即认为未阻塞)
 *  - 若主线程阻塞,[lastPongTime] 长时间不更新,超过 [ANR_TIMEOUT_MS] 即判定 ANR
 *
 * ANR 时采集:
 *  - 全部线程堆栈(主线程 + 所有其他线程)
 *  - 内存状态(Runtime.totalMemory / freeMemory / maxMemory + 堆使用率)
 *  - 最近 Perf 埋点记录([Perf.snapshotRecent])
 *
 * ANR 日志写入 filesDir/crash/anr_{timestamp}.txt(与 [MuseCrashHandler] 同目录),
 * 并复用 [MuseCrashHandler.redactSensitive] 脱敏堆栈(避免 token/apiKey 泄露);
 * 同时记录到 [AuditLogger](若可用)与 [Logger.e]。
 *
 * 安全设计(避免自身导致 ANR):
 *  - 独立守护线程,不依赖主线程 Looper,自身不会阻塞主线程
 *  - 主线程 Handler 用 [WeakReference] 持有,避免泄漏
 *  - 检测线程优先级 [Thread.MIN_PRIORITY],减少对正常调度的影响
 *  - ANR 后用 [anrInProgress] 标志位去重,避免日志风暴
 *  - 循环内全部 try-catch,任何异常都不能让检测线程意外退出
 *
 * 可配置开关:[SettingsRepository.anrDetectionCache](默认 true),通过 [SettingsRepository] 同步缓存读取,
 * 支持运行时切换(用户在设置页关闭后,下一个检测周期即停止判定)。
 *
 * @param appContext 应用上下文(用于定位 filesDir/crash)
 * @param settings 设置仓库(读取 anrDetection 开关)
 * @param auditLogger 审计日志记录器(可选,为 null 时跳过审计记录)
 */
class AnrWatcher(
    private val appContext: Context,
    private val settings: SettingsRepository,
    private val auditLogger: AuditLogger? = null,
) {
    // 主线程 Handler(弱引用,避免持有 Context 导致泄漏;主线程 Looper 随进程生命周期,实际不会被回收)
    private val mainHandlerRef = WeakReference(Handler(Looper.getMainLooper()))

    // 最近一次主线程响应 Pong 的时间戳(由 Ping 在主线程执行时更新)
    @Volatile
    private var lastPongTime: Long = System.currentTimeMillis()

    // 是否已处于 ANR 状态(用于去重,避免持续阻塞期间重复触发日志)
    @Volatile
    private var anrInProgress: Boolean = false

    // 运行标志(stop 时置 false,通知检测线程退出)
    @Volatile
    private var running: Boolean = false

    // 守护线程引用(stop 时 interrupt + join)
    private var watcherThread: Thread? = null

    // Ping:投递到主线程,执行时更新 lastPongTime(主线程能调度到此 Runnable 即说明未阻塞)
    private val ping = Runnable { lastPongTime = System.currentTimeMillis() }

    /**
     * 启动 ANR 检测。
     *
     * 若 [SettingsRepository.anrDetectionCache] == false 则直接返回不启动。
     * 守护线程会持续运行直到 [stop] 被调用或进程结束。
     */
    fun start() {
        if (!settings.anrDetectionCache) {
            Logger.i(TAG, "ANR 检测已通过设置关闭,不启动")
            return
        }
        if (running) return
        running = true
        watcherThread = Thread({ watchLoop() }, THREAD_NAME).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY // 低优先级,减少对正常调度的影响
            start()
        }
        Logger.i(TAG, "ANR 检测已启动(check=${CHECK_INTERVAL_MS}ms, timeout=${ANR_TIMEOUT_MS}ms)")
    }

    /** 停止 ANR 检测(中断守护线程)。 */
    fun stop() {
        running = false
        watcherThread?.let { thread ->
            thread.interrupt()
            try {
                thread.join(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        watcherThread = null
        Logger.i(TAG, "ANR 检测已停止")
    }

    /**
     * 检测循环:每 [CHECK_INTERVAL_MS] 投递 Ping 并判定是否 ANR。
     *
     * 循环内全部 try-catch,任何异常都不能让检测线程意外退出(否则 ANR 检测静默失效)。
     */
    private fun watchLoop() {
        while (running) {
            try {
                // 双重检查开关:支持运行时通过设置切换
                if (!settings.anrDetectionCache) {
                    Thread.sleep(CHECK_INTERVAL_MS)
                    continue
                }
                // 投递 Ping 到主线程(非阻塞,立即返回)
                mainHandlerRef.get()?.post(ping)
                // 等待一个检测周期
                Thread.sleep(CHECK_INTERVAL_MS)
                // 判定是否 ANR:主线程超过 ANR_TIMEOUT_MS 未响应 Pong
                val now = System.currentTimeMillis()
                val silenceMs = now - lastPongTime
                if (silenceMs >= ANR_TIMEOUT_MS) {
                    // 进入 ANR 状态(去重:同一阻塞期间只记录一次)
                    if (!anrInProgress) {
                        anrInProgress = true
                        onAnrDetected(silenceMs)
                    }
                } else {
                    // 主线程已恢复响应,重置标志位(下次阻塞可再次触发)
                    anrInProgress = false
                }
            } catch (e: InterruptedException) {
                // stop() 触发的中断,正常退出循环
                Thread.currentThread().interrupt()
                break
            } catch (t: Throwable) {
                // 任何异常都不能让检测线程退出(否则 ANR 检测静默失效)
                Logger.w(TAG, "ANR 检测循环异常: ${t.message}", t)
                try {
                    Thread.sleep(CHECK_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
    }

    /**
     * ANR 检测到时的处理。
     *
     * 采集线程堆栈 + 内存 + Perf 记录,写入 anr_{timestamp}.txt,
     * 并记录到 AuditLogger(若可用)与 Logger.e。
     *
     * @param silenceMs 主线程无响应时长(毫秒)
     */
    private fun onAnrDetected(silenceMs: Long) {
        val anrTime = System.currentTimeMillis()
        Logger.e(TAG, "检测到 ANR! 主线程无响应 ${silenceMs}ms")
        // 写入 ANR 日志文件(脱敏后落盘,失败不抛出)
        resultOf { writeAnrLog(anrTime, silenceMs) }
            .onError { msg, t -> Logger.e(TAG, "写入 ANR 日志失败: $msg", t) }
        // 记录到审计日志(若可用,fire-and-forget)
        resultOf {
            auditLogger?.log(
                category = "system",
                action = "anr_detected",
                target = "",
                detail = mapOf(
                    "silence_ms" to silenceMs,
                    "time" to anrTime,
                ),
                success = false,
            )
        }
    }

    /**
     * 写 ANR 日志到 filesDir/crash/anr_{timestamp}.txt。
     *
     * 采集内容:
     *  - 设备信息(Brand/Model/Android/ABI)
     *  - 内存状态(堆 Used/Free/Total/Max/Usage%)
     *  - 主线程堆栈(name/id/state + stackTrace)
     *  - 全部线程堆栈(Thread.getAllStackTraces)
     *  - 最近 Perf 埋点记录([Perf.snapshotRecent])
     *
     * 写盘后复用 [MuseCrashHandler.redactSensitive] 脱敏,避免堆栈中 token/apiKey 泄露;
     * 并清理旧 ANR 日志(保留最近 [MAX_ANR_LOGS] 份)。
     *
     * @param anrTime ANR 发生时间戳
     * @param silenceMs 主线程无响应时长(毫秒)
     */
    private fun writeAnrLog(anrTime: Long, silenceMs: Long) {
        val crashDir = File(appContext.filesDir, "crash").apply { mkdirs() }
        val fmt = SimpleDateFormat(TIME_FMT, Locale.US)
        val fileName = "anr_${fmt.format(Date(anrTime))}.txt"
        val logFile = File(crashDir, fileName)

        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("===== muse ANR log =====")
            pw.println("Time: ${Date(anrTime)}")
            pw.println("Silence: ${silenceMs}ms (主线程无响应时长)")
            pw.println()
            pw.println("----- Device Info -----")
            pw.println("Brand: ${Build.BRAND}")
            pw.println("Model: ${Build.MODEL}")
            pw.println("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            pw.println("ABIs: ${Build.SUPPORTED_ABIS.joinToString(",")}")
            pw.println()
            pw.println("----- Memory Status -----")
            val rt = Runtime.getRuntime()
            val total = rt.totalMemory()
            val free = rt.freeMemory()
            val used = total - free
            val max = rt.maxMemory()
            pw.println("Heap Used: ${used / 1024} KB")
            pw.println("Heap Free: ${free / 1024} KB")
            pw.println("Heap Total: ${total / 1024} KB")
            pw.println("Heap Max: ${max / 1024} KB")
            pw.println("Heap Usage: ${if (max > 0) used * 100 / max else -1}%")
            pw.println()
            pw.println("----- Main Thread Stack -----")
            val mainThread = Looper.getMainLooper().thread
            pw.println("Thread: ${mainThread.name} (id=${mainThread.id}, state=${mainThread.state})")
            mainThread.stackTrace.forEach { pw.println("\tat $it") }
            pw.println()
            pw.println("----- All Threads Stacks -----")
            val allStacks = Thread.getAllStackTraces()
            pw.println("Total threads: ${allStacks.size}")
            // 按 thread id 倒序排列,输出每个线程的堆栈
            allStacks.entries.sortedByDescending { it.key.id }.forEach { (thread, stack) ->
                pw.println()
                pw.println("Thread: ${thread.name} (id=${thread.id}, state=${thread.state})")
                stack.forEach { pw.println("\tat $it") }
            }
            pw.println()
            pw.println("----- Recent Perf Records -----")
            val perfRecords = Perf.snapshotRecent()
            if (perfRecords.isEmpty()) {
                pw.println("(无 Perf 埋点记录)")
            } else {
                perfRecords.forEach { rec ->
                    pw.println("${rec.name}: ${rec.elapsedMs}ms @ ${Date(rec.timestamp)}")
                }
            }
        }
        // 脱敏后写盘(复用 MuseCrashHandler 的脱敏逻辑,避免堆栈中的 token/apiKey 泄露)
        val redacted = MuseCrashHandler.redactSensitive(sw.toString())
        logFile.writeText(redacted)

        // 清理旧 ANR 日志(保留最近 MAX_ANR_LOGS 份,仅清理 anr_ 前缀,不影响 crash_ 日志)
        crashDir.listFiles { f -> f.name.startsWith("anr_") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_ANR_LOGS)
            ?.forEach { it.delete() }

        Logger.e(TAG, "ANR 日志已写入: ${logFile.absolutePath}")
    }

    companion object {
        private const val TAG = "AnrWatcher"
        private const val THREAD_NAME = "AnrWatcher"
        // 检测间隔:主线程 Ping 投递周期(2s)
        private const val CHECK_INTERVAL_MS = 2_000L
        // ANR 判定阈值:主线程无响应超过此时长即判定 ANR(5s)
        private const val ANR_TIMEOUT_MS = 5_000L
        // ANR 日志文件名时间戳格式
        private const val TIME_FMT = "yyyyMMdd-HHmmss"
        // 保留最近 ANR 日志份数(与 MuseCrashHandler.MAX_CRASH_LOGS 对齐)
        private const val MAX_ANR_LOGS = 5
    }
}
