package io.zer0.muse.tools.system

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import io.zer0.common.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
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
    }

    /** 已绑定的 shell 服务代理(可能为 null,表示未绑定)。 */
    @Volatile
    private var shellService: IShellService? = null

    /** UserService 绑定状态。 */
    @Volatile
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shellService = IShellService.Stub.asInterface(service)
            serviceBound = true
            Logger.i(TAG, "Shell UserService 已连接")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            serviceBound = false
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
        return suspendCancellableCoroutine { cont ->
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
     * 以 shell 权限执行命令(需已授权)。
     *
     * 实现:通过 Shizuku.bindUserService 绑定 [ShellService],在 shell 权限进程中执行。
     * 首次调用时惰性绑定,后续复用已绑定的服务。
     *
     * @return [ShizukuExecResult] 包含退出码与输出
     */
    fun execute(command: String): ShizukuExecResult {
        if (!checkPermission()) return ShizukuExecResult(-1, "", "Shizuku 未授权")
        if (!ensureServiceBound()) return ShizukuExecResult(-1, "", "Shizuku shell service 绑定失败")
        val service = shellService ?: return ShizukuExecResult(-1, "", "Shizuku shell service 不可用")
        return try {
            val raw = service.execute(command)
            parseExecResult(raw)
        } catch (e: android.os.RemoteException) {
            Logger.e(TAG, "Shizuku 远程调用失败: ${e.message}", e)
            // 远程调用失败可能是因为服务进程崩溃,清除引用以便下次重连
            shellService = null
            serviceBound = false
            ShizukuExecResult(-1, "", e.message ?: "远程调用异常")
        } catch (e: Throwable) {
            Logger.e(TAG, "Shizuku 执行异常: ${e.message}", e)
            ShizukuExecResult(-1, "", e.message ?: "执行异常")
        }
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────────

    /**
     * 确保 UserService 已绑定。
     * - 已绑定:直接返回 true
     * - 未绑定:调用 [Shizuku.bindUserService],轮询等待连接(最多 3 秒)
     */
    private fun ensureServiceBound(): Boolean {
        if (shellService != null && serviceBound) return true
        if (!checkPermission()) return false
        return try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, ShellService::class.java.name)
            ).processNameSuffix("shell")
                .version(1)
                .debuggable(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            Shizuku.bindUserService(args, serviceConnection)
            // 轮询等待绑定完成(bindUserService 是异步的)
            val deadline = System.currentTimeMillis() + SERVICE_BIND_TIMEOUT_MS
            while (shellService == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(SERVICE_BIND_POLL_MS)
            }
            shellService != null
        } catch (e: Throwable) {
            Logger.e(TAG, "bindUserService 失败: ${e.message}", e)
            false
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

    /** Shizuku 命令执行结果。 */
    data class ShizukuExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val isSuccess: Boolean get() = exitCode == 0
    }
}
