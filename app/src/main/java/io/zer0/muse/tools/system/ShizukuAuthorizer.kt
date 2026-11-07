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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * P3-3: Shizuku 授权器 — 集成 rikk.shizuku:api SDK。
 *
 * Shizuku 以 shell 权限(ADB 级别)执行命令,无需 root,是三通道路由中
 * 安全性与权限的平衡点(比 root 安全,比无障碍能执行 shell)。
 *
 * 命令执行采用 UserService 模式(Shizuku v13 推荐方案):
 *  1. 定义 [IShellService] AIDL 接口
 *  2. [ShellService] 在 Shizuku 管理的独立进程中运行,继承 shell 权限
 *  3. [Shizuku.bindUserService] 绑定服务,通过 AIDL IPC 执行命令
 *
 * 职责:
 *  1. [isAvailable]: Shizuku 服务是否运行(pingBinder)
 *  2. [checkPermission]: 应用是否已获 Shizuku 授权
 *  3. [requestPermission]: 弹出 Shizuku 授权请求(suspend,等待用户操作)
 *  4. [isSuiBackendAvailable]: Sui 后端兼容(root 用户无需装 Shizuku,Sui 以 root 提供 Shizuku 接口)
 *  5. [execute]: 以 shell 权限执行命令(通过 UserService)
 *
 * 生命周期:
 *  - Shizuku 服务可能在运行中被用户停止,每次执行前应 [checkPermission] 重新校验
 *  - UserService 在首次 [execute] 时惰性绑定,断开后自动重连
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
) {

    companion object {
        private const val TAG = "ShizukuAuthorizer"

        /** 授权请求码(任意,用于 OnRequestPermissionResultListener 回调匹配)。 */
        private const val PERMISSION_REQUEST_CODE = 0xC3A3

        /** Sui(Magisk 模块)的包名,提供 Shizuku 兼容接口。 */
        private const val SUI_PACKAGE = "moe.shizuku.privileged.sui"

        /** UserService 绑定超时(毫秒)。 */
        private const val SERVICE_BIND_TIMEOUT_MS = 3000L

        /** UserService 绑定轮询间隔(毫秒)。 */
        private const val SERVICE_BIND_POLL_MS = 50L

        /** UserService 稳定标识，避免 R8/混淆后以类名误判服务身份。 */
        private const val SHELL_SERVICE_TAG = "muse_shell"

        /** R-SVC-03: 授权弹窗等待超时(毫秒)。 */
        private const val PERMISSION_TIMEOUT_MS = 60_000L

        /** UserService 绑定失败后的短退避窗口。 */
        private const val BIND_FAILURE_BACKOFF_MS = 5_000L
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
            Logger.i(TAG, "Shell UserService 已连接")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            serviceBound = false
            boundArgs = null
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
     * 应直开 Shizuku 应用内的授权管理页让用户手动允许（AutoJs6 同口径）。
     */
    suspend fun requestPermission(): PermissionRequestResult {
        if (!isAvailable()) return PermissionRequestResult.Unavailable
        if (checkPermission()) return PermissionRequestResult.Granted
        if (runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)) {
            return PermissionRequestResult.PermanentlyDenied
        }
        return try {
            withTimeout(PERMISSION_TIMEOUT_MS) {
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
                    } catch (e: Throwable) {
                        Shizuku.removeRequestPermissionResultListener(listener)
                        Logger.w(TAG, "Shizuku.requestPermission 失败: ${e.message}")
                        if (cont.isActive) cont.resume(PermissionRequestResult.Unavailable)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Logger.w(TAG, "Shizuku 授权等待超时")
            PermissionRequestResult.Timeout
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
        if (!ensureServiceBound()) {
            return ShizukuStatus(ShizukuState.USER_SERVICE_UNAVAILABLE, "Muse Shell 服务暂不可用")
        }
        return ShizukuStatus(ShizukuState.READY, "Shizuku Shell 已就绪")
    }

    /**
     * 以 shell 权限执行命令(需已授权)。
     *
     * 实现:通过 Shizuku.bindUserService 绑定 [ShellService],在 shell 权限进程中执行。
     * 首次调用时惰性绑定,后续复用已绑定的服务。
     *
     * @return [ShizukuExecResult] 包含退出码与输出
     */
    suspend fun execute(command: String): ShizukuExecResult {
        return withContext(Dispatchers.IO) {
            if (!checkPermission()) return@withContext ShizukuExecResult(-1, "", "Shizuku 未授权")
            if (!ensureServiceBound()) return@withContext ShizukuExecResult(-1, "", "Shizuku shell service 绑定失败")
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
        if (!ensureServiceBound()) {
            return@withContext ShizukuExecResult(-1, "", "Shizuku shell service 绑定失败")
        }
        val service = shellService ?: return@withContext ShizukuExecResult(-1, "", "Shizuku shell service 不可用")
        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (error: Exception) {
            return@withContext ShizukuExecResult(-1, "", "创建 Shell 输出管道失败: ${error.message}")
        }
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        val capture = ToolOutputCapture(File(context.filesDir, TOOL_OUTPUTS_DIR), "shizuku_command")
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
                                    capture.append(String(buffer, 0, count))
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
            capture.close()
            ""
        } else {
            runCatching {
                capture.finish(
                    header = if (remoteFailure != null) {
                        "Shizuku 输出未能完整接收，以下内容可能不完整"
                    } else {
                        ""
                    },
                    emptyMessage = "(无输出)",
                )
            }
                .getOrElse {
                    capture.close()
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
    }

    /**
     * 确保 UserService 已绑定。
     * - 已绑定:直接返回 true
     * - 未绑定:调用 [Shizuku.bindUserService],轮询等待连接(最多 3 秒)
     */
    private suspend fun ensureServiceBound(): Boolean = serviceMutex.withLock {
        if (shellService != null && serviceBound) return@withLock true
        val now = System.currentTimeMillis()
        if (now - lastBindFailureAt < BIND_FAILURE_BACKOFF_MS) {
            return@withLock false
        }
        return@withLock try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, ShellService::class.java.name),
            ).processNameSuffix("shell")
                .tag(SHELL_SERVICE_TAG)
                // 随 app 版本强制替换旧 UserService 进程（AutoJs6 同口径）；
                // 固定版本号会让升级后的设备复用旧 AIDL 实现。
                .version(BuildConfig.VERSION_CODE)
                .debuggable(BuildConfig.DEBUG)
                // 不保留脱离当前绑定的常驻 UserService,避免升级后复用旧 AIDL 进程。
                .daemon(false)
            boundArgs = args
            // Shizuku 的连接回调与 Android ServiceConnection 统一在主线程注册,
            // 避免某些 ROM 从 IO 线程绑定时不派发 onServiceConnected。
            withContext(Dispatchers.Main.immediate) {
                Shizuku.bindUserService(args, serviceConnection)
            }
            // R-SVC-03: 用协程超时轮询替代 Thread.sleep,避免阻塞调用线程。
            val connected = withTimeoutOrNull(SERVICE_BIND_TIMEOUT_MS) {
                while (shellService == null) {
                    delay(SERVICE_BIND_POLL_MS)
                }
                true
            } == true
            if (!connected || shellService == null) {
                Logger.e(TAG, "bindUserService 失败: UserService 连接超时")
                lastBindFailureAt = System.currentTimeMillis()
                clearFailedBinding(args)
                false
            } else {
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.e(TAG, "bindUserService 失败: ${e.message}", e)
            lastBindFailureAt = System.currentTimeMillis()
            boundArgs?.let(::clearFailedBinding)
            false
        }
    }

    /**
     * 检查 Shell UserService 是否真正可用。
     *
     * `checkPermission()` 只代表 Shizuku 授权位为 GRANTED；新日志已证明授权位为 true
     * 仍可能在 bindUserService 阶段超时，因此设置页和能力状态不能只看授权位。
     */
    suspend fun checkReady(): Boolean {
        if (!checkPermission()) return false
        return ensureServiceBound()
    }

    /** 绑定失败时撤销待处理连接，避免下一次执行叠加旧 callback。 */
    private fun clearFailedBinding(args: Shizuku.UserServiceArgs) {
        runCatching { Shizuku.unbindUserService(args, serviceConnection, true) }
            .onFailure { Logger.w(TAG, "清理失败的 UserService 绑定失败: ${it.message}") }
        if (boundArgs === args) {
            boundArgs = null
            shellService = null
            serviceBound = false
        }
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
