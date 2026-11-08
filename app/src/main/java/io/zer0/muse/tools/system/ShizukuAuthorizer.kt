package io.zer0.muse.tools.system

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import io.zer0.common.Logger
import io.zer0.muse.BuildConfig
import io.zer0.muse.tools.TOOL_OUTPUTS_DIR
import io.zer0.muse.tools.ToolOutputCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

private const val TAG = "ShizukuAuthorizer"

/**
 * P3-3: Shizuku 授权器 — 集成 rikk.shizuku:api SDK。
 *
 * Shizuku 以 shell 权限(ADB 级别)执行命令,无需 root,是三通道路由中
 * 安全性与权限的平衡点(比 root 安全,比无障碍能执行 shell)。
 *
 * 命令执行有两条路，都跑在 Shizuku 服务进程的 shell 权限下：
 *  1. 快路径 [ShizukuRemoteExecutor]：用 Shizuku 服务 binder 直接起 `sh -c`。
 *     没有 app_process 冷启动、没有 dex 加载、不需要连接回调，binder 活着就能执行。
 *  2. 兜底路径：[IShellService] AIDL + [ShellService] 跑在 Shizuku 托管的独立进程里，
 *     由 [Shizuku.bindUserService] 绑定后通过 IPC 调用。
 *
 * 只有快路径**没把命令跑起来**时才退回兜底路径（见 [execute]）。命令已经执行过之后再退回，
 * 会让带副作用的命令被做两遍。快路径随时可能被上游收掉，所以兜底路径始终保留。
 *
 * 职责:
 *  1. [isAvailable]: Shizuku 服务是否运行(pingBinder)
 *  2. [checkPermission]: 应用是否已获 Shizuku 授权
 *  3. [requestPermission]: 弹出 Shizuku 授权请求(suspend,等待用户操作)
 *  4. [isSuiBackendAvailable]: Sui 后端兼容(root 用户无需装 Shizuku,Sui 以 root 提供 Shizuku 接口)
 *  5. [execute]: 以 shell 权限执行命令(快路径优先,UserService 兜底)
 *  6. [lastExecutionPath]: 上一条命令走到了哪一步,供诊断与工具侧失败反馈引用
 *
 * 生命周期:
 *  - Shizuku 服务可能在运行中被用户停止,每次执行前应 [checkPermission] 重新校验
 *  - UserService 在首次执行时惰性绑定;绑定超时**不解绑**,迟到的连接回调照样生效
 *  - 授权监听器在请求完成后自动移除,避免泄漏
 */
class ShizukuAuthorizer(
    private val context: Context,
    /**
     * 可注入的授权位探针(默认走 Shizuku SDK checkSelfPermission)。
     * 注入点在单测中提供,便于锁定「未授权 → execute 绝不触发 shell service」的
     * 短路路径,无需 Robolectric/真实 Shizuku 服务。
     */
    private val checkPermissionProbe: (() -> Boolean)? = null,
    /** 快路径执行器；注入点让单测能锁定「快路径起不来才退回 UserService」这条决策。 */
    private val remoteExec: ShizukuRemoteExecutor = ShizukuRemoteExecutor(),
) {

    companion object {
        /** 授权请求码(任意,用于 OnRequestPermissionResultListener 回调匹配)。 */
        private const val PERMISSION_REQUEST_CODE = 0xC3A3

        /** Sui(Magisk 模块)的包名,提供 Shizuku 兼容接口。 */
        private const val SUI_PACKAGE = "moe.shizuku.privileged.sui"

        /** 自动探测的绑定等待(毫秒)：只等热绑定，避免拖慢每轮提示词组装。 */
        private const val SERVICE_BIND_TIMEOUT_MS = 3_000L

        /**
         * 用户主动「刷新状态」或真正要执行命令时的绑定等待(毫秒)。
         *
         * 冷启动时 Shizuku 要在独立进程里 app_process 并加载本 APK 的 dex，在限制后台进程启动的
         * ROM 上可明显超过 3 秒 —— 真机日志里连续 9 次都卡在 3 秒超时。主动路径等 12 秒并记录真实耗时。
         */
        private const val SERVICE_BIND_TIMEOUT_MANUAL_MS = 12_000L

        /** UserService 稳定标识，避免 R8/混淆后以类名误判服务身份。 */
        private const val SHELL_SERVICE_TAG = "muse_shell"

        /** R-SVC-03: 授权弹窗等待超时(毫秒)。没有弹窗时不能干等，超时后转人工引导。 */
        private const val PERMISSION_TIMEOUT_MS = 30_000L

        /** 「拒绝且不再询问」时弹窗不会再出现，只等一个短窗口就转人工引导。 */
        private const val PERMISSION_RATIONALE_TIMEOUT_MS = 3_000L

        /** UserService 绑定失败后的短退避窗口。 */
        private const val BIND_FAILURE_BACKOFF_MS = 5_000L

        /** 快路径自检命令的超时：只是 fork 一个 sh 跑内建 no-op，正常是毫秒级。 */
        private const val REMOTE_PROBE_TIMEOUT_MS = 3_000L

        /** 自检结果缓存窗口，避免每轮提示词组装都 fork 一次进程。 */
        private const val REMOTE_PROBE_TTL_MS = 60_000L

        /** 路径标识，供 [lastExecutionPath] 与诊断文案引用。 */
        const val PATH_REMOTE = "shizuku_new_process"
        const val PATH_USER_SERVICE = "shizuku_user_service"
        const val PATH_NONE = "none"
    }

    /** 已绑定的 shell 服务代理(可能为 null,表示未绑定)。 */
    @Volatile
    private var shellService: IShellService? = null

    /** R-SVC-03: 当前绑定的 UserServiceArgs(unbind 时需要)。 */
    @Volatile
    private var boundArgs: Shizuku.UserServiceArgs? = null

    /** UserService 绑定状态。 */
    @Volatile
    private var serviceBound = false

    /** 串行化 bind/unbind，避免多个自动化调用同时创建多个 UserService。 */
    private val serviceMutex = Mutex()

    // 绑定失败时做短暂退避，避免设置页每次重组/恢复都重复阻塞 3 秒并刷屏。
    @Volatile
    private var lastBindFailureAt = 0L

    /** 本次 bind 的起始时刻，用于把真实连接耗时写进日志（排查 ROM 冷启动慢）。 */
    @Volatile
    private var bindStartedAt = 0L

    /** 用户点过「刷新状态」：下一次绑定用长等待（冷启动慢的 ROM 需要）。 */
    @Volatile
    private var manualBindRequested = false

    /**
     * 本轮绑定的连接信号。每次真正发起 bind 前换新，避免上一轮已完成/已断开的旧信号
     * 让新一轮的等待立刻返回。连接回调直接 complete 它，不再靠轮询猜。
     */
    @Volatile
    private var connectionSignal = CompletableDeferred<Unit>()

    /** 快路径自检缓存。 */
    @Volatile
    private var remoteProbeAt = 0L

    @Volatile
    private var remoteProbeOk = false

    /**
     * 上一次命令走的路径（[PATH_REMOTE] / [PATH_USER_SERVICE] / [PATH_NONE]）。
     *
     * 记的是**走到哪一步**，不是「命令一定执行过」：快路径没起来时这里会是
     * [PATH_USER_SERVICE]，而兜底路径同样可能连服务都没拿到。
     */
    @Volatile
    var lastExecutionPath: String = PATH_NONE
        private set

    /** Shizuku binder 是否存活（由 sticky received / dead 监听维护，事件驱动刷新用）。 */
    @Volatile
    var binderAlive: Boolean = false
        private set

    private val binderStateListeners = java.util.concurrent.CopyOnWriteArrayList<(alive: Boolean) -> Unit>()

    /** 注册 binder 存活状态监听（幂等）；协调层据此在 Shizuku 启动/停止时自动刷新权限快照。 */
    fun addBinderStateListener(listener: (alive: Boolean) -> Unit) {
        binderStateListeners.addIfAbsent(listener)
    }

    fun removeBinderStateListener(listener: (alive: Boolean) -> Unit) {
        binderStateListeners.remove(listener)
    }

    init {
        // 官方要求的生命周期跟踪：不注册监听时，Shizuku 中途启动/被杀后探测结果会一直停在旧值。
        // sticky 变体保证注册前 binder 已到达也会立即回调一次。
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(
                Shizuku.OnBinderReceivedListener {
                    binderAlive = true
                    // 新的服务进程：清掉绑定失败退避，允许立即重绑 UserService。
                    lastBindFailureAt = 0L
                    binderStateListeners.forEach { runCatching { it(true) } }
                },
            )
            Shizuku.addBinderDeadListener(
                Shizuku.OnBinderDeadListener {
                    binderAlive = false
                    // binder 死亡意味着 UserService 进程也已终止，清掉失效引用。
                    shellService = null
                    serviceBound = false
                    boundArgs = null
                    connectionSignal = CompletableDeferred()
                    remoteProbeAt = 0L
                    remoteProbeOk = false
                    binderStateListeners.forEach { runCatching { it(false) } }
                },
            )
        }.onFailure { Logger.w(TAG, "注册 Shizuku binder 监听失败: ${it.message}") }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val proxy = service?.let(IShellService.Stub::asInterface)
            if (proxy == null) {
                shellService = null
                serviceBound = false
                Logger.e(TAG, "Shell UserService 连接回调没有返回有效 binder")
                return
            }
            shellService = proxy
            serviceBound = true
            lastBindFailureAt = 0L
            val startedAt = bindStartedAt
            val cost = if (startedAt > 0) System.currentTimeMillis() - startedAt else -1L
            Logger.i(TAG, "Shell UserService 已连接(耗时 ${cost}ms)")
            // 迟到的连接也算数：上一次等待可能已经超时返回，但进程随后起来了。
            connectionSignal.complete(Unit)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            serviceBound = false
            boundArgs = null
            connectionSignal = CompletableDeferred()
            Logger.w(TAG, "Shell UserService 连接断开")
        }
    }

    /** Shizuku 服务是否运行(进程已启动 + binder 可达)。 */
    fun isAvailable(): Boolean {
        return try {
            !Shizuku.isPreV11() && Shizuku.pingBinder()
        } catch (e: Throwable) {
            // 必要容错:Shizuku 未安装时调用 SDK 可能抛异常
            Logger.w(TAG, "Shizuku.pingBinder 失败: ${e.message}")
            false
        }
    }

    /** 应用是否已获 Shizuku 授权(需先 [isAvailable])。 */
    fun checkPermission(): Boolean {
        checkPermissionProbe?.let { return it() }
        if (!isAvailable()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            Logger.w(TAG, "Shizuku.checkSelfPermission 失败: ${e.message}")
            false
        }
    }

    /**
     * 请求 Shizuku 授权(suspend,等待用户在授权弹窗操作)。
     *
     * 返回细分结果而非 Boolean：用户勾过「拒绝且不再询问」后 [Shizuku.requestPermission]
     * 静默无效（官方语义），干等只会超时；调用方收到 [PermissionRequestResult.PermanentlyDenied]
     * 应直开 Shizuku 应用内的授权管理页让用户手动允许。
     */
    suspend fun requestPermission(): PermissionRequestResult {
        if (!isAvailable()) {
            Logger.i(TAG, "requestPermission: Shizuku 不可用（未运行或版本过旧）")
            return PermissionRequestResult.Unavailable
        }
        if (checkPermission()) {
            // 已经授权：再请求也不会弹窗。调用方应按「已授权」刷新状态，
            // 而不是把「Shell 服务未绑上」误当成「没授权」反复来点。
            Logger.i(TAG, "requestPermission: 已授权，无需再次请求")
            return PermissionRequestResult.Granted
        }
        // 部分 Shizuku 版本对「从未请求过」也会返回 true；不能据此提前返回，
        // 否则请求根本发不出去（用户看到的就是「点授权没有任何反应」）。
        // 只把它当作「弹窗不会出现」的短超时判据。
        val rationale = runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)
        Logger.i(TAG, "requestPermission: 发送授权请求 rationale=$rationale")
        return try {
            withTimeout(if (rationale) PERMISSION_RATIONALE_TIMEOUT_MS else PERMISSION_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val listener = object : Shizuku.OnRequestPermissionResultListener {
                        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                            if (requestCode == PERMISSION_REQUEST_CODE) {
                                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                                // 必须移除监听器,避免泄漏与重复回调
                                Shizuku.removeRequestPermissionResultListener(this)
                                if (cont.isActive) {
                                    cont.resume(
                                        if (granted) PermissionRequestResult.Granted else PermissionRequestResult.Denied,
                                    )
                                }
                            }
                        }
                    }
                    Shizuku.addRequestPermissionResultListener(listener)
                    cont.invokeOnCancellation {
                        Shizuku.removeRequestPermissionResultListener(listener)
                    }
                    try {
                        Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
                        Logger.i(TAG, "requestPermission: 请求已发送，等待用户操作")
                    } catch (e: Throwable) {
                        Shizuku.removeRequestPermissionResultListener(listener)
                        Logger.w(TAG, "Shizuku.requestPermission 失败: ${e.message}")
                        if (cont.isActive) cont.resume(PermissionRequestResult.Unavailable)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Logger.w(TAG, "Shizuku 授权等待超时（rationale=$rationale）")
            if (rationale) PermissionRequestResult.PermanentlyDenied else PermissionRequestResult.Timeout
        }
    }

    /** Shizuku 授权请求的细分结果。 */
    sealed interface PermissionRequestResult {
        data object Granted : PermissionRequestResult

        data object Denied : PermissionRequestResult

        /** 用户曾「拒绝且不再询问」：系统弹窗不会再出现，需去 Shizuku 应用内手动授权。 */
        data object PermanentlyDenied : PermissionRequestResult

        /** Shizuku 服务不可用（未安装/未启动/版本过旧）。 */
        data object Unavailable : PermissionRequestResult

        data object Timeout : PermissionRequestResult
    }

    /**
     * Sui 后端兼容检测。
     *
     * Sui 是 Magisk 模块,以 root 身份提供 Shizuku 兼容接口,root 用户无需安装 Shizuku APK。
     * 检测方式:Sui 包已安装 && root 可用(说明 Sui 以 root 运行)。
     */
    fun isSuiBackendAvailable(): Boolean {
        val suiInstalled = try {
            context.packageManager.getPackageInfo(SUI_PACKAGE, 0) != null
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        // Sui 需要 root 才能工作（被动检测,不弹窗、不阻塞）
        return suiInstalled && RootAuthorizer(context).checkPermission()
    }

    /**
     * 返回可供设置页展示的真实 Shizuku 能力状态。
     * “已授权”并不等于 Muse 的 UserService 已经可用，因此这里单独验证绑定结果。
     */
    suspend fun diagnose(): ShizukuStatus {
        val installed = ShizukuInstaller(context).isInstalled()
        val suiAvailable = if (!installed) isSuiBackendAvailable() else false
        if (!installed && !suiAvailable) {
            return ShizukuStatus(ShizukuState.NOT_INSTALLED, "未安装 Shizuku")
        }
        if (!isAvailable()) {
            // binder 活着但 isAvailable=false：pre-v11 旧版（UserService 自 v11 起才有），
            // 与「服务未运行」是两种修法，不能混报。
            val tooOld = binderAlive && runCatching { Shizuku.isPreV11() }.getOrDefault(false)
            if (tooOld) {
                return ShizukuStatus(ShizukuState.VERSION_TOO_OLD, "Shizuku 版本过旧,请更新到 v13+")
            }
            return ShizukuStatus(
                ShizukuState.NOT_RUNNING,
                if (suiAvailable) "Sui 已安装,但 Shizuku 服务未运行" else "Shizuku 服务未运行",
            )
        }
        if (!checkPermission()) return ShizukuStatus(ShizukuState.NOT_AUTHORIZED, "尚未授权 Muse")
        if (remotePathReady()) {
            return ShizukuStatus(ShizukuState.READY, "Shizuku Shell 已就绪(直连进程)")
        }
        if (!ensureServiceBound()) {
            return ShizukuStatus(ShizukuState.USER_SERVICE_UNAVAILABLE, "Muse Shell 服务暂不可用")
        }
        return ShizukuStatus(ShizukuState.READY, "Shizuku Shell 已就绪(UserService)")
    }

    /**
     * 以 shell 权限执行命令(需已授权)。
     *
     * 先走快路径：直接在 Shizuku 服务进程里起 `sh -c`，没有 app_process 冷启动。
     * 只有**命令根本没跑起来**时才退回 UserService —— 跑起来之后再退回会把命令执行两遍。
     *
     * @return [ShizukuExecResult] 包含退出码与输出
     */
    suspend fun execute(command: String): ShizukuExecResult {
        return withContext(Dispatchers.IO) {
            if (!checkPermission()) return@withContext ShizukuExecResult(-1, "", "Shizuku 未授权")
            remoteExec.execute(command)?.let {
                lastExecutionPath = PATH_REMOTE
                return@withContext it
            }
            lastExecutionPath = PATH_USER_SERVICE
            if (!ensureServiceBound(longWait = true)) return@withContext ShizukuExecResult(-1, "", "Shizuku shell service 绑定失败")
            val service = shellService ?: return@withContext ShizukuExecResult(-1, "", "Shizuku shell service 不可用")
            try {
                val raw = service.execute(command)
                parseExecResult(raw)
            } catch (e: android.os.RemoteException) {
                Logger.e(TAG, "Shizuku 远程调用失败: ${e.message}", e)
                // 远程调用失败可能是因为服务进程崩溃,清除引用以便下次重连
                release()
                ShizukuExecResult(-1, "", e.message ?: "远程调用异常")
            } catch (e: Throwable) {
                Logger.e(TAG, "Shizuku 执行异常: ${e.message}", e)
                ShizukuExecResult(-1, "", e.message ?: "执行异常")
            }
        }
    }

    /** Stream device-tool output through a pipe into app-private storage, avoiding Binder String caps. */
    suspend fun executeForTool(command: String): ShizukuExecResult = withContext(Dispatchers.IO) {
        if (!checkPermission()) return@withContext ShizukuExecResult(-1, "", "Shizuku 未授权")
        val outputDir = File(context.filesDir, TOOL_OUTPUTS_DIR)
        val capture = ToolOutputCapture(outputDir, "shizuku_command")
        val text = Utf8TextSink { capture.append(it) }
        val fast = remoteExec.streamTo(command, text)
        if (fast != null) {
            lastExecutionPath = PATH_REMOTE
            text.flushRemainder()
            val output = runCatching {
                capture.finish(
                    header = if (fast < 0) "Shizuku 输出未能完整接收，以下内容可能不完整" else "",
                    emptyMessage = "(无输出)",
                )
            }.getOrElse {
                capture.close()
                ""
            }
            return@withContext ShizukuExecResult(fast, output, "")
        }
        capture.close()
        lastExecutionPath = PATH_USER_SERVICE
        val service = (if (ensureServiceBound(longWait = true)) shellService else null)
            ?: return@withContext ShizukuExecResult(-1, "", "Shizuku shell service 不可用")
        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (error: Exception) {
            return@withContext ShizukuExecResult(-1, "", "创建 Shell 输出管道失败: ${error.message}")
        }
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        val pipeCapture = ToolOutputCapture(outputDir, "shizuku_command")
        val readerFailure = AtomicReference<Throwable?>(null)
        val reader =
            Thread(
                {
                    try {
                        ParcelFileDescriptor.AutoCloseInputStream(readEnd).use { input ->
                            InputStreamReader(input, Charsets.UTF_8).buffered().use { stream ->
                                val buffer = CharArray(8_192)
                                while (true) {
                                    val count = stream.read(buffer)
                                    if (count < 0) break
                                    pipeCapture.append(String(buffer, 0, count))
                                }
                            }
                        }
                    } catch (error: Throwable) {
                        readerFailure.set(error)
                    }
                },
                "muse-shizuku-output",
            )
        reader.start()

        var exitCode = -1
        var remoteFailure: Throwable? = null
        try {
            exitCode = service.executeToPipe(command, writeEnd)
        } catch (error: android.os.RemoteException) {
            Logger.e(TAG, "Shizuku 流式远程调用失败: ${error.message}", error)
            remoteFailure = error
            release()
        } catch (error: Throwable) {
            Logger.e(TAG, "Shizuku 流式执行异常: ${error.message}", error)
            remoteFailure = error
        } finally {
            runCatching { writeEnd.close() }
        }

        reader.join()
        val failure = readerFailure.get()
        if (failure != null) {
            remoteFailure = remoteFailure ?: failure
        }
        if (exitCode < 0 && remoteFailure == null) {
            remoteFailure = IllegalStateException("Shizuku 命令超时或执行失败")
        }
        val output = if (reader.isAlive) {
            pipeCapture.close()
            ""
        } else {
            runCatching {
                pipeCapture.finish(
                    header = if (remoteFailure != null) {
                        "Shizuku 输出未能完整接收，以下内容可能不完整"
                    } else {
                        ""
                    },
                    emptyMessage = "(无输出)",
                )
            }
                .getOrElse {
                    pipeCapture.close()
                    ""
                }
        }
        val error = remoteFailure?.message
        ShizukuExecResult(
            exitCode = if (remoteFailure == null) exitCode else -1,
            stdout = output,
            stderr = error.orEmpty(),
        )
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────────

    /**
     * 把命令的原始字节写进 [file]（截图等二进制输出）。
     *
     * 与 [executeForTool] 同一套路由：快路径直接把字节流进文件，退回时才走 UserService 的管道。
     * 两条路都不能把输出重定向到 App 私有目录 —— 命令以 shell 身份运行，写不了 /data/user/0/<pkg>。
     */
    suspend fun executeToFile(command: String, file: File): ShizukuExecResult = withContext(Dispatchers.IO) {
        if (!checkPermission()) return@withContext ShizukuExecResult(-1, "", "Shizuku 未授权")
        // 打开文件失败发生在命令执行之前，此时退回是安全的；一旦 streamTo 拿到了非 null，
        // 命令就已经跑过，不能再退回。
        val sink = try {
            file.outputStream().buffered()
        } catch (error: IOException) {
            return@withContext ShizukuExecResult(-1, "", "创建输出文件失败: ${error.message}")
        }
        val fast = sink.use { remoteExec.streamTo(command, it) }
        if (fast != null) {
            lastExecutionPath = PATH_REMOTE
            if (fast < 0) runCatching { file.delete() }
            return@withContext if (fast < 0) {
                ShizukuExecResult(-1, "", "Shizuku 命令超时或执行失败")
            } else {
                ShizukuExecResult(fast, "", "")
            }
        }
        runCatching { file.delete() }
        lastExecutionPath = PATH_USER_SERVICE
        val service = (if (ensureServiceBound(longWait = true)) shellService else null)
            ?: return@withContext ShizukuExecResult(-1, "", "Shizuku shell service 不可用")
        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (error: Exception) {
            return@withContext ShizukuExecResult(-1, "", "创建 Shell 输出管道失败: ${error.message}")
        }
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        val readerFailure = AtomicReference<Throwable?>(null)
        val reader = Thread(
            {
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(readEnd).use { input ->
                        file.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                } catch (error: Throwable) {
                    readerFailure.set(error)
                }
            },
            "muse-shizuku-file",
        )
        reader.start()

        var exitCode = -1
        var remoteFailure: Throwable? = null
        try {
            exitCode = service.executeToPipe(command, writeEnd)
        } catch (error: android.os.RemoteException) {
            Logger.e(TAG, "Shizuku 文件流远程调用失败: ${error.message}", error)
            remoteFailure = error
            release()
        } catch (error: Throwable) {
            Logger.e(TAG, "Shizuku 文件流执行异常: ${error.message}", error)
            remoteFailure = error
        } finally {
            runCatching { writeEnd.close() }
        }

        reader.join()
        val failure = remoteFailure ?: readerFailure.get()
        if (failure != null) {
            runCatching { file.delete() }
            return@withContext ShizukuExecResult(-1, "", failure.message ?: "执行失败")
        }
        ShizukuExecResult(exitCode, "", "")
    }

    /** R-SVC-03: 解绑 UserService 并清理引用(App 退出/通道关闭时调用)。 */
    fun release() {
        val args = boundArgs
        if (args != null) {
            try {
                Shizuku.unbindUserService(args, serviceConnection, true)
            } catch (e: Throwable) {
                Logger.w(TAG, "unbindUserService 失败: ${e.message}")
            }
        }
        shellService = null
        serviceBound = false
        boundArgs = null
        connectionSignal = CompletableDeferred()
    }

    /**
     * 确保 UserService 已绑定。
     * - 已绑定:直接返回 true
     * - 未绑定:先 peek 一次（不创建进程），再 [Shizuku.bindUserService]，等连接回调
     *
     * 等待上限分两档：自动状态探测 3 秒（每轮提示词组装都会走到，不能拖慢对话）；
     * [longWait] 的真实命令执行与用户手动刷新 12 秒。
     *
     * **超时不再 unbind。** 之前的做法是超时即 `unbindUserService(remove=true)`，服务端收到
     * 后会调 destroy() → System.exit(0)，把那个还没连上、其实正在启动的进程杀掉；下一次又从零
     * 开始，形成永远连不上的自我挫败循环。现在超时只记退避，连接请求留着 —— 进程随后起来时
     * onServiceConnected 照样会到，下一次调用直接就是已绑定。
     */
    private suspend fun ensureServiceBound(longWait: Boolean = false): Boolean = serviceMutex.withLock {
        if (shellService != null && serviceBound) return@withLock true
        val now = System.currentTimeMillis()
        if (now - lastBindFailureAt < BIND_FAILURE_BACKOFF_MS) {
            return@withLock false
        }
        val waitMs = when {
            manualBindRequested -> {
                manualBindRequested = false
                SERVICE_BIND_TIMEOUT_MANUAL_MS
            }
            longWait -> SERVICE_BIND_TIMEOUT_MANUAL_MS
            else -> SERVICE_BIND_TIMEOUT_MS
        }
        return@withLock try {
            val args = shellServiceArgs
            boundArgs = args
            bindStartedAt = System.currentTimeMillis()
            connectionSignal = CompletableDeferred()
            // Shizuku 的连接回调与 Android ServiceConnection 统一在主线程注册,
            // 避免某些 ROM 从 IO 线程绑定时不派发 onServiceConnected。
            withContext(Dispatchers.Main.immediate) {
                // peek 带上 no-create 标志：服务端已有记录时只 attach，不要求它再起一个进程。
                // 返回值只记日志不解读——各版本语义不一致，据它下结论会误判。
                val peeked = runCatching { Shizuku.peekUserService(args, serviceConnection) }
                Logger.d(TAG, "peekUserService code=${peeked.getOrNull()} failure=${peeked.exceptionOrNull()?.message}")
                if (shellService == null) Shizuku.bindUserService(args, serviceConnection)
            }
            val connected = awaitServiceConnection(waitMs)
            if (!connected) {
                val cost = System.currentTimeMillis() - bindStartedAt
                Logger.e(TAG, "bindUserService 未连上: 等待 ${cost}ms/${waitMs}ms 超时,保留连接请求等迟到回调")
                lastBindFailureAt = System.currentTimeMillis()
                false
            } else {
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.e(TAG, "bindUserService 失败: ${e.message}", e)
            lastBindFailureAt = System.currentTimeMillis()
            false
        }
    }

    /** UserService 身份参数。版本号随 app 走，升级后服务端会替换掉旧 AIDL 实现的进程。 */
    internal val shellServiceArgs: Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShellService::class.java.name),
        ).processNameSuffix("shell")
            .tag(SHELL_SERVICE_TAG)
            .version(BuildConfig.VERSION_CODE)
            .debuggable(BuildConfig.DEBUG)
            // 常驻：上面的 version() 已经保证升级后换掉旧进程，不需要再用 daemon(false) 去防复用。
            // 非常驻时每次解绑都会销毁进程，下次执行又要付一次冷启动。
            .daemon(true)

    /** 等连接回调，而不是轮询一个 volatile 字段。超时返回 false，但连接请求继续留着。 */
    internal suspend fun awaitServiceConnection(waitMs: Long): Boolean {
        if (shellService != null) return true
        val signal = connectionSignal
        withTimeoutOrNull(waitMs) { signal.await() }
        return shellService != null && serviceBound
    }

    /**
     * 检查 Shell 是否真正可用。
     *
     * `checkPermission()` 只代表 Shizuku 授权位为 GRANTED；授权位为 true 仍可能两条路都走不通，
     * 因此设置页和能力状态不能只看授权位。快路径能跑就算就绪，不必等 UserService 绑上。
     */
    suspend fun checkReady(): Boolean {
        if (!checkPermission()) return false
        if (remotePathReady()) return true
        return ensureServiceBound()
    }

    /**
     * 快路径自检：跑一条 shell 内建 no-op，不产生任何副作用。
     * 结果按 [REMOTE_PROBE_TTL_MS] 缓存，避免每轮提示词组装都 fork 一个进程。
     */
    private suspend fun remotePathReady(): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - remoteProbeAt < REMOTE_PROBE_TTL_MS) return@withContext remoteProbeOk
        val probed = runCatching { remoteExec.execute(":", REMOTE_PROBE_TIMEOUT_MS) }.getOrNull()
        remoteProbeOk = probed != null && probed.exitCode == 0
        remoteProbeAt = System.currentTimeMillis()
        Logger.i(TAG, "快路径自检 available=$remoteProbeOk")
        remoteProbeOk
    }

    /**
     * 用户手动重试：清掉绑定失败退避，让下一次 [checkReady] / [diagnose] 真正重新 bind。
     *
     * 退避只用于自动刷新（避免每次进页都阻塞 3 秒），不该挡住用户主动点的「刷新状态」。
     */
    fun resetBindBackoff() {
        lastBindFailureAt = 0L
        manualBindRequested = true
        Logger.i(TAG, "手动刷新：已清除绑定退避，下一次会真正重新 bind 并等待冷启动")
    }

    /** 解析 UserService 返回的 "exitCode\u0000stdout\u0000stderr" 格式字符串。 */
    private fun parseExecResult(raw: String): ShizukuExecResult {
        val parts = raw.split('\u0000', limit = 3)
        val exitCode = parts.getOrNull(0)?.toIntOrNull() ?: -1
        val stdout = parts.getOrNull(1) ?: ""
        val stderr = parts.getOrNull(2) ?: ""
        return ShizukuExecResult(exitCode, stdout, stderr)
    }

    /** Shizuku 服务的真实可用阶段。 */
    enum class ShizukuState {
        NOT_INSTALLED,
        NOT_RUNNING,
        VERSION_TOO_OLD,
        NOT_AUTHORIZED,
        USER_SERVICE_UNAVAILABLE,
        READY,
    }

    data class ShizukuStatus(
        val state: ShizukuState,
        val message: String,
    ) {
        val isReady: Boolean get() = state == ShizukuState.READY
    }

    /** Shizuku 命令执行结果。 */
    data class ShizukuExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val isSuccess: Boolean get() = exitCode == 0
    }
}
