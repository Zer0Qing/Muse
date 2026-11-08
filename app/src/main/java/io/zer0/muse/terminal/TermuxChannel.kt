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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
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
 * 结果回传有三条互相独立的路径，任一条先到就用哪条（见 [ExecResult.deliveryPath]）：
 *  1. `broadcast`：动态注册的导出接收器收 PendingIntent 广播（主路径）；
 *  2. `receiver`：清单静态接收器收同一封广播（动态注册被 ROM 丢弃/失效时兜底）；
 *  3. `loopback`：命令外壳自己经 127.0.0.1 环回 TCP 把完整输出写回来
 *     （广播机制被整层拦掉时仍然拿得到结果，且不受 Termux 约 100KB 的回传截断）。
 * 三条都拿不到才算超时；[diagnostics] 会记下各条路径当时是否可用、累计收到过多少回传，
 * 用来把「Termux 侧没动静」和「广播这一层被拦」区分开。
 *
 * 用户侧前置(未就绪时 [describe] 给出逐步引导)：
 *  1. 安装 Termux;
 *  2. 系统设置→应用→Termux→附加权限,授予「运行 Termux 命令」(`com.termux.permission.RUN_COMMAND`);
 *  3. Termux 内 `~/.termux/termux.properties` 写 `allow-external-apps = true` 并 `termux-reload-settings`;
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

        /** 已安装但版本过旧：结果回传需 0.118+，Play 商店版停更在 0.101，永远拿不到结果。 */
        data class OutdatedVersion(val versionName: String?) : Availability()
        data object PermissionMissing : Availability()
        data class NotConfigured(val detail: String?) : Availability()
    }

    /** 单次执行结果。error 非空表示通道级失败(启动失败/超时/服务端 errmsg)。 */
    data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val error: String?,
        /** 输出不完整。上限随回传路径不同：广播约 100KB，环回 [TermuxLoopbackProtocol.MAX_PAYLOAD_BYTES]。 */
        val truncated: Boolean = false,
        /** 结果实际由哪条冗余路径送回：broadcast / receiver / loopback / none。 */
        val deliveryPath: String = PATH_NONE,
    )

    /** 执行结果中间态。 */
    data class TermuxResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val error: String?,
        val truncated: Boolean = false,
    )

    /** 结果 + 它是从哪条冗余路径回来的。 */
    internal data class Delivery(val result: TermuxResult, val path: String)

    /**
     * 本进程的随机身份。结果广播必须带同一个 token 才认，挡住两件事：
     * 设备上其他应用伪造结果（自增 id 是可猜的，UUID 不是），以及上一个进程遗留的迟到结果。
     */
    private val sessionToken = UUID.randomUUID().toString()

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Delivery>>()
    private val nextRequestId = AtomicInteger(0)

    /** 只数广播这一层收到的结果：为 0 而 [deliveredResults] 非 0，就说明广播被拦、环回在兜底。 */
    private val receivedResults = AtomicInteger(0)

    /** 数所有路径成功送回的调用次数：判断「Termux 到底有没有跑过东西」只能看这个，不能看广播数。 */
    private val deliveredResults = AtomicInteger(0)
    private val receiverLock = Any()

    /** 最近处理完的请求 id：两条接收器路径会收到同一封广播，第二次投递不该记成「无主结果」。 */
    private val recentIds = ArrayDeque<Int>()

    @Volatile
    private var receiverRegistered = false

    @Volatile
    private var lastDeliveryPath = PATH_NONE

    @Volatile
    private var loopbackAvailable: Boolean? = null

    /** 最近一次通道级错误，供向导与工具反馈直接引用（不是命令自身的非零退出）。 */
    @Volatile
    var lastError: String? = null
        private set

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent != null) handleResult(intent, PATH_BROADCAST)
        }
    }

    /** 快速静态检查(不执行命令、不唤醒 Termux)。 */
    fun availability(): Availability {
        if (!isInstalled()) return Availability.NotInstalled
        val version = installedVersionName()
        if (!isVersionSupported(version)) return Availability.OutdatedVersion(version)
        if (!hasPermission()) return Availability.PermissionMissing
        return Availability.Ready
    }

    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(PKG, 0)
    }.isSuccess

    /** 已安装 Termux 的 versionName（未装或读取失败返回 null）。 */
    fun installedVersionName(): String? = runCatching {
        context.packageManager.getPackageInfo(PKG, 0).versionName
    }.getOrNull()

    fun hasPermission(): Boolean = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * RunCommandService 是否可解析。只作诊断，不作硬门禁：
     * 个别 ROM 的 resolveService 会误报 null，拿它拦执行会把本来能用的通道判死。
     */
    fun isServiceResolvable(): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.resolveService(Intent().setClassName(PKG, SERVICE_CLASS), 0) != null
    }.getOrDefault(false)

    /** probe 结果缓存：避免每次进向导都真实执行 echo 唤醒 Termux。手动刷新可强制重探。 */
    @Volatile
    private var cachedProbe: Availability? = null

    @Volatile
    private var cachedProbeAt = 0L

    /**
     * 完整链路探测:实际执行一条 echo 标记命令,验证 allow-external-apps 等配置。
     * 比 [availability] 慢(要先起 Termux 服务),供状态展示/排障使用。
     *
     * @param forceRefresh true 时忽略缓存真实重探（手动刷新）；false 时复用未过期的缓存（页面 RESUMED），
     *   免得每次回到向导都唤醒 Termux。前置静态检查失败时清缓存并直接返回。
     *
     * 缓存有 TTL 且会被真实执行证据回填（[recordEvidence]）：助手跑通一次 termux_exec，
     * 向导立刻显示就绪，不用再等一轮 echo；失败结论只缓存一分钟，用户改完配置回来就能看到变化。
     */
    suspend fun probe(forceRefresh: Boolean = false): Availability {
        val quick = availability()
        if (quick !is Availability.Ready) {
            cachedProbe = null
            return quick
        }
        val cached = cachedProbe
        val ttl = if (cached is Availability.Ready) PROBE_READY_TTL_MS else PROBE_FAILURE_TTL_MS
        if (!forceRefresh && cached != null && System.currentTimeMillis() - cachedProbeAt < ttl) {
            return cached
        }
        val result = exec(PROBE_COMMAND, timeoutMs = PROBE_TIMEOUT_MS)
        val verdict = when {
            result.error != null -> Availability.NotConfigured(result.error)
            result.stdout.contains(PROBE_MARKER) -> Availability.Ready
            else -> Availability.NotConfigured("探测输出异常(exit=${result.exitCode})")
        }
        cachedProbe = verdict
        cachedProbeAt = System.currentTimeMillis()
        return verdict
    }

    /**
     * 执行命令并等待结果。
     * @param command 以 `bash -c` 执行的完整命令
     * @param timeoutMs 等待上限(含 Termux 侧排队时间)
     * @param workdir 工作目录(默认 Termux home)
     */
    suspend fun exec(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS, workdir: String? = null): ExecResult {
        preflightFailure()?.let { return it }
        ensureReceiver()
        val requestId = nextRequestId.incrementAndGet()
        val delivery = CompletableDeferred<Delivery>()
        pending[requestId] = delivery
        // 先绑端口再发命令：外壳要把端口写进命令里。绑定失败就退回广播-only，不阻断执行。
        val listener = TermuxLoopbackListener.open(sessionToken, requestId)
        loopbackAvailable = listener != null
        val payload = listener?.let {
            TermuxLoopbackProtocol.buildWrapper(command, it.port, sessionToken, requestId)
        } ?: command
        Logger.d(TAG, "发送 RUN_COMMAND requestId=$requestId timeout=${timeoutMs}ms loopback=${listener != null}")
        try {
            val startFailure = startRunCommand(payload, workdir, requestId)
            if (startFailure != null) {
                recordEvidence(startFailure)
                return startFailure
            }
            val result = coroutineScope {
                if (listener != null) {
                    launch(Dispatchers.IO) {
                        val parsed = try {
                            listener.await(timeoutMs)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // 环回只是冗余路径：它出任何意外都不能把已经收到的广播结果一起带走。
                            Logger.w(TAG, "环回路径异常 requestId=$requestId: ${e.message}")
                            null
                        }
                        if (parsed != null) delivery.complete(Delivery(parsed, PATH_LOOPBACK))
                    }
                }
                try {
                    withTimeoutOrNull(timeoutMs) { delivery.await() }
                } finally {
                    // 关掉监听才能打断阻塞中的 accept；否则广播早就回来了，
                    // coroutineScope 仍会陪着环回路径干等到超时。
                    listener?.close()
                }
            }
            if (result != null) deliveredResults.incrementAndGet()
            val outcome = result?.let {
                val r = it.result
                ExecResult(r.exitCode, r.stdout, r.stderr, r.error, r.truncated, it.path)
            } ?: timeoutResult(requestId, timeoutMs, listener != null)
            recordEvidence(outcome)
            return outcome
        } finally {
            pending.remove(requestId)
            listener?.close()
        }
    }

    /** 执行前的静态门禁；通过返回 null。 */
    private fun preflightFailure(): ExecResult? {
        val version = installedVersionName()
        val reason = when {
            !isInstalled() -> "未安装 Termux"
            !hasPermission() -> "缺少 Termux 调用权限"
            !isVersionSupported(version) -> describe(Availability.OutdatedVersion(version))
            else -> null
        }
        return reason?.let { ExecResult(-1, "", "", it) }
    }

    /**
     * 发送 RUN_COMMAND，成功返回 null，失败返回可直接交给调用方的结果。
     *
     * 只对「后台启动被拒」重试一次：这一类错误系统明确拒绝了启动，命令绝无可能已经跑起来，
     * 重发不会造成重复执行；而超时/结果丢失时命令可能已在 Termux 侧执行，绝不能盲目重发
     * 一条已通过 HIGH 风险审批的命令。
     */
    private suspend fun startRunCommand(command: String, workdir: String?, requestId: Int): ExecResult? {
        val intent = buildRunCommandIntent(command, workdir, requestId)
        var lastMessage = "未知错误"
        repeat(START_ATTEMPTS) { attempt ->
            try {
                context.startService(intent)
                return null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastMessage = e.message ?: e.javaClass.simpleName
                Logger.w(TAG, "启动 Termux 服务失败(第 ${attempt + 1} 次) requestId=$requestId: $lastMessage")
                if (!isBackgroundStartRejection(lastMessage)) return startFailureResult(lastMessage)
                if (attempt < START_ATTEMPTS - 1) delay(START_RETRY_DELAY_MS)
            }
        }
        return startFailureResult(lastMessage)
    }

    private fun startFailureResult(raw: String): ExecResult {
        // Android 8+ 后台服务启动限制:App 纯后台时启动外部服务会被系统拒绝
        val hint = if (isBackgroundStartRejection(raw)) {
            "(Android 后台限制:请保持 App 在前台,或开启「设置→Agent→后台与可靠性→保持后台运行」)"
        } else if (!isServiceResolvable()) {
            "(Termux 的 RunCommandService 不可解析:请确认 Termux 未被禁用/冻结,且不是被裁剪过的改版)"
        } else {
            ""
        }
        return ExecResult(-1, "", "", "启动 Termux 服务失败: $raw$hint")
    }

    private fun timeoutResult(requestId: Int, timeoutMs: Long, loopback: Boolean): ExecResult {
        // 「Termux 有没有跑过东西」要看所有路径的投递数：环回跑通时广播数仍是 0，
        // 拿广播数下结论会把一条正在正常工作的通道说成从没执行过。
        val delivered = deliveredResults.get()
        val broadcasts = receivedResults.get()
        Logger.w(
            TAG,
            "等待结果超时 requestId=$requestId timeout=${timeoutMs}ms " +
                "累计投递=$delivered 其中广播=$broadcasts loopback=$loopback",
        )
        // 环回监听开着却一条载荷都没收到，说明命令外壳大概率没跑起来；
        // 但不能断言——bash 不支持 /dev/tcp 或 mktemp 缺失时外壳会退回直接执行，
        // 那种情况下只有广播一条路，广播再被拦就同样是全空。文案只说事实，不替用户下结论。
        val hint = when {
            delivered > 0 -> "Termux 此前已成功回传 $delivered 次结果,本次可能仍在执行或被系统限制"
            loopback ->
                "Termux 未回传任何结果(环回与广播均空):请确认 allow-external-apps = true 已生效" +
                    "(写入后需执行 termux-reload-settings),并允许 Termux 被外部启动/关联启动"
            else -> "Termux 未执行或结果广播被系统拦截(本进程未收到任何结果回传)"
        }
        return ExecResult(-1, "", "", "等待结果超时(${timeoutMs / 1000}s):$hint")
    }

    /** 真实执行证据优先于探测缓存：跑通一次就该显示就绪，失败一次就该带着原因显示待配置。 */
    private fun recordEvidence(result: ExecResult) {
        lastDeliveryPath = result.deliveryPath
        lastError = result.error
        cachedProbe = if (result.error == null) Availability.Ready else Availability.NotConfigured(result.error)
        cachedProbeAt = System.currentTimeMillis()
    }

    /** 一行机器可读的自检快照，供工具反馈与向导排障引用。 */
    fun diagnostics(): String = buildString {
        append("installed=").append(isInstalled())
        append(" version=").append(installedVersionName() ?: "?")
        append(" permission=").append(hasPermission())
        append(" serviceResolvable=").append(isServiceResolvable())
        append(" dynamicReceiver=").append(receiverRegistered)
        append(" broadcastsReceived=").append(receivedResults.get())
        append(" deliveries=").append(deliveredResults.get())
        append(" loopbackAvailable=").append(loopbackAvailable?.toString() ?: "untested")
        append(" lastDelivery=").append(lastDeliveryPath)
        append(" lastError=").append(lastError ?: "none")
    }

    /**
     * 接收并派发结果，动态接收器与清单接收器共用同一套判定。
     *
     * 安全边界靠三道限制维持：
     *  1. 必须带本进程的随机 sessionToken——外部应用猜不到，也复用不了上个进程的 token；
     *  2. 必须带 [EXTRA_REQUEST_ID] 且该 id 在 pending 表里；
     *  3. 收到的内容只当作字符串输出，不反射调用、不执行、不拼路径。
     */
    private fun handleResult(intent: Intent, path: String) {
        val requestId = intent.getIntExtra(EXTRA_REQUEST_ID, -1)
        // 只记 key 集合，不记内容：结果里可能有命令输出。
        Logger.d(TAG, "收到结果广播 path=$path requestId=$requestId extras=${intent.extras?.keySet()}")
        if (intent.getStringExtra(EXTRA_SESSION_TOKEN) != sessionToken) {
            Logger.w(TAG, "结果广播 token 不符 requestId=$requestId，已丢弃")
            return
        }
        val deferred = pending[requestId]
        if (deferred == null) {
            val duplicate = synchronized(recentIds) { recentIds.contains(requestId) }
            if (duplicate) {
                Logger.d(TAG, "结果重复投递 requestId=$requestId path=$path，已忽略")
            } else {
                Logger.w(TAG, "结果广播没有对应调用 requestId=$requestId（pending=${pending.keys}）")
            }
            return
        }
        val parsed = parseResultIntent(intent, requestId)
        // 两条接收器路径会各送一次同一封广播：complete 返回 false 的那次是重复投递，不是错误。
        if (!deferred.complete(Delivery(parsed, path))) return
        receivedResults.incrementAndGet()
        synchronized(recentIds) {
            recentIds.addLast(requestId)
            while (recentIds.size > MAX_RECENT_IDS) recentIds.removeFirst()
        }
    }

    private fun parseResultIntent(intent: Intent, requestId: Int): TermuxResult {
        // Termux 0.118.x 固定用 "result" 作结果包 key，长名只作兼容保留。
        val bundle = intent.getBundleExtra(EXTRA_RESULT_BUNDLE)
            ?: intent.getBundleExtra(EXTRA_RESULT_BUNDLE_TERMUX)
        if (bundle == null) {
            Logger.w(TAG, "Termux 结果缺 result bundle requestId=$requestId extras=${intent.extras?.keySet()}")
            return TermuxResult(-1, "", "", "结果格式异常(缺 result bundle)")
        }
        val entries = HashMap<String, Any?>()
        for (key in bundle.keySet()) {
            @Suppress("DEPRECATION")
            entries[key] = bundle.get(key)
        }
        return parseResultEntries(entries)
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
     * 安全边界靠三道限制维持（见 [handleResult]）：随机 sessionToken + pending 表里的请求 id +
     * 收到的内容只当字符串输出。
     *
     * 为什么不能按系统版本降级成 flags = 0：
     * [ContextCompat.registerReceiver] 在 flags **既不含 RECEIVER_EXPORTED 也不含
     * RECEIVER_NOT_EXPORTED** 时无条件抛 `IllegalArgumentException`，与 `Build.VERSION.SDK_INT`
     * 无关（旧实现的 `SDK_INT >= TIRAMISU` 判断让 Android 12 及以下都走 0 分支，于是在
     * vivo V1990A / Android 10 上一调 [exec] 就崩）。导出与否和系统版本无关，是唯一硬性要求，
     * 所以这里恒定传入 RECEIVER_EXPORTED，不要再按版本分支。
     *
     * 注册失败不再向上抛：清单里还有一份静态接收器，加上环回路径，动态注册失败不该让整次执行崩掉。
     */
    private fun ensureReceiver() {
        if (receiverRegistered) return
        synchronized(receiverLock) {
            if (receiverRegistered) return
            try {
                ContextCompat.registerReceiver(
                    context,
                    resultReceiver,
                    IntentFilter(ACTION_RESULT),
                    ContextCompat.RECEIVER_EXPORTED,
                )
                receiverRegistered = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "动态接收器注册失败，改由清单接收器与环回路径兜底: ${e.message}")
            }
        }
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    internal fun buildRunCommandIntent(command: String, workdir: String?, requestId: Int): Intent {
        // requestId 与 token 必须挂到 PendingIntent 的基础 Intent 上，不能只放在命令 Intent 里：
        // Termux 回传时只把 result bundle 作为 fill-in 回填，不会把调用方自定义的 extra 带回来，
        // 于是接收端永远按 -1 查不到 pending 表，结果被静默丢弃、只能等超时。
        val resultIntent = Intent(ACTION_RESULT)
            .setPackage(context.packageName)
            .putExtra(EXTRA_REQUEST_ID, requestId)
            .putExtra(EXTRA_SESSION_TOKEN, sessionToken)
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
        private const val EXTRA_SESSION_TOKEN = "io.zer0.muse.TERMUX_SESSION_TOKEN"
        private const val ACTION_RESULT = "io.zer0.muse.action.TERMUX_RESULT"

        const val PATH_BROADCAST = "broadcast"
        const val PATH_RECEIVER = "receiver"
        const val PATH_LOOPBACK = "loopback"
        const val PATH_NONE = "none"

        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val MAX_TIMEOUT_MS = 600_000L

        private const val PROBE_MARKER = "__MUSE_TERMUX_OK__"
        private const val PROBE_COMMAND = "echo $PROBE_MARKER"
        private const val PROBE_TIMEOUT_MS = 15_000L
        private const val PROBE_READY_TTL_MS = 600_000L
        private const val PROBE_FAILURE_TTL_MS = 60_000L
        private const val START_ATTEMPTS = 2
        private const val START_RETRY_DELAY_MS = 400L
        private const val MAX_RECENT_IDS = 64

        private fun isBackgroundStartRejection(message: String): Boolean =
            message.contains("Not allowed to start service") || message.contains("background")

        @Volatile
        private var instance: TermuxChannel? = null

        fun get(context: Context): TermuxChannel = instance ?: synchronized(this) {
            instance ?: TermuxChannel(context.applicationContext).also { instance = it }
        }

        /**
         * 清单静态接收器的入口。刻意**不**创建通道实例：本进程没有在等的调用时，
         * 为了一封结果广播把整个 App 唤醒毫无意义，只会白白耗电。
         */
        internal fun deliverStatic(intent: Intent) {
            instance?.handleResult(intent, PATH_RECEIVER)
        }

        /**
         * Termux 版本是否支持结果回传（需 ≥0.118；0.109 起支持、0.118 稳定）。
         * Play 商店版停更在 0.101，无 PendingIntent 回传机制，调用永远超时。
         * 解析不了版本号时不误报（按支持处理），避免把自定义构建判死。
         */
        internal fun isVersionSupported(versionName: String?): Boolean {
            if (versionName.isNullOrBlank()) return true
            val parts = versionName.trim().split('.')
            val major = parts.getOrNull(0)?.toIntOrNull() ?: return true
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: return true
            return major > 0 || minor >= 118
        }

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
            // Termux 把超 100KB 的输出从头截断,并带回原始长度;原始长度 > 实收长度即被截断。
            val stdoutOriginal = (entries["stdout_original_length"] as? Number)?.toInt()
            val stderrOriginal = (entries["stderr_original_length"] as? Number)?.toInt()
            val truncated = (stdoutOriginal != null && stdoutOriginal > stdout.length) ||
                (stderrOriginal != null && stderrOriginal > stderr.length)
            return TermuxResult(exitCode, stdout, stderr, error, truncated)
        }

        /** 面向用户/模型的引导文案(硬编码中文,与 device_shell 等运行时提示同口径)。 */
        fun describe(availability: Availability): String = when (availability) {
            is Availability.Ready -> "Termux 通道就绪"
            is Availability.NotInstalled ->
                "Termux 通道不可用:未安装 Termux。请先安装 Termux(F-Droid / GitHub),装好后回到 设置→工具 检测。"
            is Availability.OutdatedVersion ->
                "Termux 版本过旧(${availability.versionName ?: "未知"}):命令结果回传需 0.118+。" +
                    "Play 商店版已停更(0.101),拿不到执行结果;请从 F-Droid 或 GitHub 安装新版 Termux 后重试。"
            is Availability.PermissionMissing ->
                "Termux 通道待授权:请到 系统设置→应用→Termux→附加权限,勾选「运行 Termux 命令」" +
                    "(Run commands in Termux environment),然后重试。"
            is Availability.NotConfigured ->
                "Termux 通道待配置:请在 Termux 中执行 " +
                    "`echo 'allow-external-apps = true' >> ~/.termux/termux.properties && termux-reload-settings`;" +
                    "部分系统(Android 10+)还需允许 Termux 被外部启动(如「关联应用」选项)。" +
                    "\n最近一次错误:${availability.detail ?: "未知"}"
        }
    }
}
