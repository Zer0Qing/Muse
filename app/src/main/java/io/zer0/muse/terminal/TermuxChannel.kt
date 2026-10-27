package io.zer0.muse.terminal

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.zer0.common.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * v2.2.1 Termux 通道 — 把命令送进用户安装的 Termux(完整 Linux 环境)执行并取回结果。
 *
 * 机制(对齐 Termux 官方 RUN_COMMAND 协议)：
 *  - 向 `com.termux.app.RunCommandService` 发送 action=`com.termux.RUN_COMMAND` 的 intent；
 *  - 通过 `com.termux.RUN_COMMAND_PENDING_INTENT` 收结果(stdout/stderr/exitCode/errmsg)；
 *  - `am startservice` 方式拿不到结果,故必须走代码路径。
 *
 * 用户侧前置(未就绪时 [describe] 给出逐步引导)：
 *  1. 安装 Termux;
 *  2. 系统设置→应用→Termux→附加权限,授予「运行 Termux 命令」(`com.termux.permission.RUN_COMMAND`);
 *  3. Termux 内 `~/.termux/termux.properties` 写 `allow-external-apps = true` 并重启 Termux;
 *  4. 部分系统(Android 10+)需允许 Termux 被外部启动(如「关联应用」选项)。
 *
 * 说明:结果包字段名按 Termux 官方常量;解析做防御性兼容(stdout/stderr/exitCode/err/errmsg,
 * 兼容 exit_code/errCode 变体),未知格式会记录原始 keySet 便于排查。
 */
class TermuxChannel private constructor(private val context: Context) {

    /** 通道可用性。 */
    sealed class Availability {
        data object Ready : Availability()
        data object NotInstalled : Availability()
        data object PermissionMissing : Availability()
        data class NotConfigured(val detail: String?) : Availability()
    }

    /** 单次执行结果。error 非空表示通道级失败(启动失败/超时/服务端 errmsg)。 */
    data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val error: String?,
    )

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<TermuxResult>>()
    private val nextRequestId = AtomicInteger(0)
    private val receiverLock = Any()

    @Volatile
    private var receiverRegistered = false

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent == null) return
            val requestId = intent.getIntExtra(EXTRA_REQUEST_ID, -1)
            // 广播带的 extras 会原样回显到日志里，但没有 id 的结果只能丢：
            // 无主结果比丢一条更糟（会把别人的结果塞给这个调用方）。
            val deferred = pending.remove(requestId) ?: return
            // Termux 0.118.x 固定用 "result" 作结果包 key，长名只作兼容保留。
            val bundle = intent.getBundleExtra(EXTRA_RESULT_BUNDLE)
                ?: intent.getBundleExtra(EXTRA_RESULT_BUNDLE_TERMUX)
            if (bundle == null) {
                Logger.w(TAG, "Termux 结果缺 result bundle,extras=${intent.extras?.keySet()}")
                deferred.complete(TermuxResult(-1, "", "", "结果格式异常(缺 result bundle)"))
                return
            }
            val entries = HashMap<String, Any?>()
            for (key in bundle.keySet()) {
                @Suppress("DEPRECATION")
                entries[key] = bundle.get(key)
            }
            deferred.complete(parseResultEntries(entries))
        }
    }

    /** 快速静态检查(不执行命令)。 */
    fun availability(): Availability {
        if (!isInstalled()) return Availability.NotInstalled
        if (!hasPermission()) return Availability.PermissionMissing
        return Availability.Ready
    }

    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(PKG, 0)
    }.isSuccess

    fun hasPermission(): Boolean = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * 完整链路探测:实际执行一条 echo 标记命令,验证 allow-external-apps 等配置。
     * 比 [availability] 慢(要先起 Termux 服务),供状态展示/排障使用。
     */
    suspend fun probe(): Availability {
        val quick = availability()
        if (quick !is Availability.Ready) return quick
        val result = exec("echo __MUSE_TERMUX_OK__", timeoutMs = 15_000L)
        return when {
            result.error != null -> Availability.NotConfigured(result.error)
            result.stdout.contains("__MUSE_TERMUX_OK__") -> Availability.Ready
            else -> Availability.NotConfigured("探测输出异常(exit=${result.exitCode})")
        }
    }

    /**
     * 执行命令并等待结果。
     * @param command 以 `bash -c` 执行的完整命令
     * @param timeoutMs 等待上限(含 Termux 侧排队时间)
     * @param workdir 工作目录(默认 Termux home)
     */
    suspend fun exec(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS, workdir: String? = null): ExecResult {
        if (!isInstalled()) return ExecResult(-1, "", "", "未安装 Termux")
        if (!hasPermission()) return ExecResult(-1, "", "", "缺少 Termux 调用权限")
        ensureReceiver()
        val requestId = nextRequestId.incrementAndGet()
        val deferred = CompletableDeferred<TermuxResult>()
        pending[requestId] = deferred
        try {
            context.startService(buildRunCommandIntent(command, workdir, requestId))
        } catch (e: Exception) {
            pending.remove(requestId)
            Logger.w(TAG, "启动 Termux 服务失败: ${e.message}")
            val raw = e.message ?: e.javaClass.simpleName
            // Android 8+ 后台服务启动限制:App 纯后台时启动外部服务会被系统拒绝
            val hint = if (raw.contains("Not allowed to start service") || raw.contains("background")) {
                "(Android 后台限制:请保持 App 在前台,或开启「设置→Agent→后台与可靠性→保持后台运行」)"
            } else {
                ""
            }
            return ExecResult(-1, "", "", "启动 Termux 服务失败: $raw$hint")
        }
        val result = try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(requestId)
        }
        if (result == null) {
            return ExecResult(-1, "", "", "等待结果超时(${timeoutMs / 1000}s):Termux 可能被系统限制后台启动或未响应")
        }
        return ExecResult(result.exitCode, result.stdout, result.stderr, result.error)
    }

    /**
     * 注册接收 Termux 结果的广播接收器。
     *
     * v2.6.13 修正：必须用 RECEIVER_EXPORTED。
     *
     * 旧实现声明了 RECEIVER_NOT_EXPORTED，而结果广播是 **Termux 那个应用** 发回来的
     * （不同 uid、不同进程）。非导出接收器只接受同应用发出的广播，于是 Termux 的回复
     * 每次都被系统静默丢弃，[exec] 永远等到超时 —— 用户看到的就是“授权了但完全不能用”，
     * 而所有前置检查（已安装 / 已授权 / allow-external-apps）都显示正常。
     *
     * 安全边界靠两道限制维持：
     *  1. 结果广播必须带 [EXTRA_REQUEST_ID]，且该 id 必须在本进程的 pending 表里——
     *     外部应用猜不到自增 id，也无法伪造未知 id 的“结果”；
     *  2. 收到的内容只当作字符串输出，不反射调用、不执行、不拼路径。
     */
    private fun ensureReceiver() {
        if (receiverRegistered) return
        synchronized(receiverLock) {
            if (receiverRegistered) return
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.RECEIVER_EXPORTED
            } else {
                0
            }
            ContextCompat.registerReceiver(
                context,
                resultReceiver,
                IntentFilter(ACTION_RESULT),
                flags,
            )
            receiverRegistered = true
        }
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    internal fun buildRunCommandIntent(command: String, workdir: String?, requestId: Int): Intent {
        // requestId 必须挂到 PendingIntent 的基础 Intent 上，不能只放在命令 Intent 里：
        // Termux 回传时只把 result bundle 作为 fill-in 回填，不会把调用方自定义的 extra 带回来，
        // 于是接收端永远按 -1 查不到 pending 表，结果被静默丢弃、只能等超时。
        val resultIntent = Intent(ACTION_RESULT)
            .setPackage(context.packageName)
            .putExtra(EXTRA_REQUEST_ID, requestId)
        val mutability = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestId,
            resultIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutability,
        )
        return Intent().apply {
            setClassName(PKG, SERVICE_CLASS)
            action = ACTION_RUN
            putExtra(EXTRA_PATH, BASH_PATH)
            putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
            putExtra(EXTRA_WORKDIR, workdir ?: HOME_DIR)
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_SESSION_ACTION, "0")
            putExtra(EXTRA_PENDING_INTENT, pendingIntent)
            putExtra(EXTRA_REQUEST_ID, requestId)
        }
    }

    companion object {
        private const val TAG = "TermuxChannel"

        const val PKG = "com.termux"
        const val SERVICE_CLASS = "com.termux.app.RunCommandService"
        const val ACTION_RUN = "com.termux.RUN_COMMAND"
        const val PERMISSION = "com.termux.permission.RUN_COMMAND"
        const val BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
        const val HOME_DIR = "/data/data/com.termux/files/home"

        private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
        private const val EXTRA_RESULT_BUNDLE = "com.termux.RUN_COMMAND_RESULT_BUNDLE"

        /** Termux 0.118.x 实际使用的结果包 key（TermuxPluginUtils 固定写入）。 */
        private const val EXTRA_RESULT_BUNDLE_TERMUX = "result"
        private const val EXTRA_REQUEST_ID = "io.zer0.muse.TERMUX_REQUEST_ID"
        private const val ACTION_RESULT = "io.zer0.muse.action.TERMUX_RESULT"

        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val MAX_TIMEOUT_MS = 600_000L

        @Volatile
        private var instance: TermuxChannel? = null

        fun get(context: Context): TermuxChannel = instance ?: synchronized(this) {
            instance ?: TermuxChannel(context.applicationContext).also { instance = it }
        }

        /** 执行结果中间态。 */
        data class TermuxResult(
            val exitCode: Int,
            val stdout: String,
            val stderr: String,
            val error: String?,
        )

        /**
         * 防御性解析结果字段(可单测):兼容 exitCode/exit_code、err/errCode 与字符串数值。
         */
        internal fun parseResultEntries(entries: Map<String, Any?>): TermuxResult {
            val stdout = (entries["stdout"] as? String).orEmpty()
            val stderr = (entries["stderr"] as? String).orEmpty()
            val exitCode = when (val v = entries["exitCode"] ?: entries["exit_code"]) {
                is Number -> v.toInt()
                is String -> v.toIntOrNull() ?: -1
                else -> -1
            }
            val errCode = when (val v = entries["err"] ?: entries["errCode"]) {
                is Number -> v.toInt()
                is String -> v.toIntOrNull()
                else -> null
            }
            val errmsg = (entries["errmsg"] as? String)?.takeIf { it.isNotBlank() }
            // Termux 的成功码是 Errno.ERRNO_SUCCESS = -1，ResultData.getErrCode() 无错时返回 -1。
            // 拿 “!= 0” 判定会把每次成功都写成「Termux 服务错误码 -1」，通道看上去永久不可用。
            // 只有正数才是真错误；0 交给旧版本/其他实现当成功码。
            val error = errmsg ?: if (errCode != null && errCode > 0) "Termux 服务错误码 $errCode" else null
            return TermuxResult(exitCode, stdout, stderr, error)
        }

        /** 面向用户/模型的引导文案(硬编码中文,与 device_shell 等运行时提示同口径)。 */
        fun describe(availability: Availability): String = when (availability) {
            is Availability.Ready -> "Termux 通道就绪"
            is Availability.NotInstalled ->
                "Termux 通道不可用:未安装 Termux。请先安装 Termux(应用商店 / F-Droid),装好后回到 设置→工具 检测。"
            is Availability.PermissionMissing ->
                "Termux 通道待授权:请到 系统设置→应用→Termux→附加权限,勾选「运行 Termux 命令」" +
                    "(Run commands in Termux environment),然后重试。"
            is Availability.NotConfigured ->
                "Termux 通道待配置:请在 Termux 中执行 " +
                    "`echo 'allow-external-apps = true' >> ~/.termux/termux.properties` 并重启 Termux;" +
                    "部分系统(Android 10+)还需允许 Termux 被外部启动(如「关联应用」选项)。" +
                    "\n最近一次错误:${availability.detail ?: "未知"}"
        }
    }
}
