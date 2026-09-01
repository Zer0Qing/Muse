package io.zer0.muse.tools.system

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import io.zer0.common.Logger
import io.zer0.muse.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku
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
class ShizukuAuthorizer(private val context: Context) {

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

        /** UserService 协议版本；AIDL/服务实现变化时强制替换旧进程。 */
        private const val SHELL_SERVICE_VERSION = 3

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
     * @return true 已授权;false 被拒绝或服务不可用
     */
    suspend fun requestPermission(): Boolean {
        if (!isAvailable()) return false
        if (checkPermission()) return true
        return try {
            withTimeout(PERMISSION_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode == PERMISSION_REQUEST_CODE) {
                        val granted = grantResult == PackageManager.PERMISSION_GRANTED
                        // 必须移除监听器,避免泄漏与重复回调
                        Shizuku.removeRequestPermissionResultListener(this)
                        if (cont.isActive) cont.resume(granted)
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
                if (cont.isActive) cont.resume(false)
            }
        }
        }
    } catch (e: TimeoutCancellationException) {
        Logger.w(TAG, "Shizuku 授权等待超时")
        false
    }
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
        // Sui 需要 root 才能工作
        return suiInstalled && RootAuthorizer().checkPermission()
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
                .version(SHELL_SERVICE_VERSION)
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
