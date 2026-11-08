package io.zer0.muse.permission

import io.zer0.common.Logger
import io.zer0.muse.accessibility.MuseAccessibilityService
import io.zer0.muse.terminal.TermuxChannel
import io.zer0.muse.tools.system.AccessibilityProviderInstaller
import io.zer0.muse.tools.system.RootAuthorizer
import io.zer0.muse.tools.system.ShizukuAuthorizer
import io.zer0.muse.tools.system.ShizukuInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 权限体系重设计: 统一权限协调层 — 四通道状态的单一事实源 + 事件驱动刷新。
 *
 * 解决问题:
 *  - X1 三套状态事实源（向导自有 state / AutomationManager 缓存 / 工具层 liveSnapshot）刷新时机各异。
 *    协调层产出 [PermissionSnapshot]，向导与 AutomationManager 都吃这一份。
 *  - X2 探测 ≠ 请求：[refresh] 全部被动零副作用（不弹 su、不唤醒 Termux、不请求授权），
 *    只有用户点按钮才触发外部打扰。Shizuku diagnose 会 bind UserService，但无用户弹窗。
 *  - X3 事件驱动：Shizuku binder received/dead、无障碍 connect/disconnect 自动触发刷新，
 *    不再「Shizuku 中途启动/被杀后状态停在旧值」。
 *
 * 启动时由 [start] 接线事件并做一次被动刷新；AutomationManager 把快照映射成既有
 * [io.zer0.muse.automation.core.AutomationManager.PermissionState] 公共契约（消费方不变）。
 */
class PermissionCoordinator(
    private val shizukuAuthorizer: ShizukuAuthorizer,
    private val shizukuInstaller: ShizukuInstaller,
    private val rootAuthorizer: RootAuthorizer,
    private val a11yInstaller: AccessibilityProviderInstaller,
    private val termuxChannel: TermuxChannel,
) {

    /** 四通道富状态快照（被动探测产出，供向导/管理器同源消费）。 */
    data class PermissionSnapshot(
        val accessibilityEnabled: Boolean = false,
        val providerInstalled: Boolean = false,
        val providerBundled: Boolean = false,
        val shizukuInstalled: Boolean = false,
        val shizukuState: ShizukuAuthorizer.ShizukuState = ShizukuAuthorizer.ShizukuState.NOT_INSTALLED,
        val shizukuMessage: String = "未探测",
        val rootGrantState: RootAuthorizer.GrantState = RootAuthorizer.GrantState.NoRoot,
        val rootManager: String? = null,
        val termuxAvailability: TermuxChannel.Availability = TermuxChannel.Availability.NotInstalled,
    ) {
        val shellEnabled: Boolean get() = shizukuState == ShizukuAuthorizer.ShizukuState.READY

        /**
         * 授权位是否已给出：READY 与 USER_SERVICE_UNAVAILABLE 都意味着 checkPermission 已通过。
         * 后者是「已授权但 Shell 服务没绑上」，不能再当成未授权去重复请求（请求会直接返回已授权，
         * 用户看到的就是「点了授权什么也没发生」）。
         */
        val shizukuPermissionGranted: Boolean
            get() = shizukuState == ShizukuAuthorizer.ShizukuState.READY ||
                shizukuState == ShizukuAuthorizer.ShizukuState.USER_SERVICE_UNAVAILABLE

        val rootEnabled: Boolean
            get() = rootGrantState == RootAuthorizer.GrantState.Granted ||
                rootGrantState == RootAuthorizer.GrantState.PreviouslyGranted

        val anyEnabled: Boolean get() = accessibilityEnabled || shellEnabled || rootEnabled
    }

    private val _snapshot = MutableStateFlow(PermissionSnapshot())
    val snapshot: StateFlow<PermissionSnapshot> = _snapshot.asStateFlow()

    private val mutex = Mutex()
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + io.zer0.muse.util.GlobalCoroutineExceptionHandler,
    )

    @Volatile
    private var started = false

    /**
     * 接线事件并启动刷新（幂等）。[onEventRefresh] 由 AutomationInitializer 传入,
     * 指向 AutomationManager.refreshPermissions，使事件同时刷新协调层快照与管理器缓存。
     */
    fun start(onEventRefresh: suspend () -> Unit) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            shizukuAuthorizer.addBinderStateListener {
                scope.launch { runCatching { onEventRefresh() } }
            }
            MuseAccessibilityService.addConnectionListener {
                scope.launch { runCatching { onEventRefresh() } }
            }
            scope.launch { runCatching { onEventRefresh() } }
        }
    }

    /**
     * 被动刷新四通道状态（零副作用：不弹 su、不唤醒 Termux、不请求授权）。
     * 单通道探针异常降级为不可用，不击穿整页（与向导旧 runCatching 同口径）。
     */
    @Suppress("TooGenericExceptionCaught") // 单通道探针异常必须降级为不可用，不能击穿整页刷新。
    suspend fun refresh(): PermissionSnapshot = mutex.withLock {
        val a11y = probeBool("a11y") { a11yInstaller.isEnabled() }
        val providerInstalled = probeBool("a11yProvider") { a11yInstaller.isProviderInstalled() }
        val providerBundled = probeBool("a11yProviderBundled") { a11yInstaller.isProviderApkBundled() }
        val shizukuInstalled = probeBool("shizukuInstalled") { shizukuInstaller.isInstalled() }
        val shizukuStatus = try {
            shizukuAuthorizer.diagnose()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "shizuku diagnose failed: ${e.message}")
            ShizukuAuthorizer.ShizukuStatus(ShizukuAuthorizer.ShizukuState.USER_SERVICE_UNAVAILABLE, "Shizuku 状态探测失败")
        }
        val rootState = try {
            rootAuthorizer.grantState()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "root state probe failed: ${e.message}")
            RootAuthorizer.GrantState.NoRoot
        }
        val rootManager = runCatching { rootAuthorizer.installedManager() }.getOrNull()
        val termuxAvail = try {
            termuxChannel.availability()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "termux probe failed: ${e.message}")
            TermuxChannel.Availability.NotInstalled
        }

        val snap = PermissionSnapshot(
            accessibilityEnabled = a11y,
            providerInstalled = providerInstalled,
            providerBundled = providerBundled,
            shizukuInstalled = shizukuInstalled,
            shizukuState = shizukuStatus.state,
            shizukuMessage = shizukuStatus.message,
            rootGrantState = rootState,
            rootManager = rootManager,
            termuxAvailability = termuxAvail,
        )
        _snapshot.value = snap
        snap
    }

    @Suppress("TooGenericExceptionCaught") // 布尔探针失败一律降级 false，不向上抛。
    private fun probeBool(name: String, block: () -> Boolean): Boolean = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(TAG, "$name probe failed: ${e.message}")
        false
    }

    companion object {
        private const val TAG = "PermissionCoordinator"
    }
}
