package io.zer0.muse.automation.core

import android.content.Context
import io.zer0.muse.automation.executors.AccessibilityExecutor
import io.zer0.muse.automation.executors.RootExecutor
import io.zer0.muse.tools.system.ShizukuAuthorizer
import io.zer0.common.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * UI 自动化管理器 —— 三层执行器的统一入口。
 *
 * 职责:
 * 1. 持有 [AccessibilityExecutor] / [ShellExecutor] / [RootExecutor] 实例
 * 2. 启动时探测各层可用性,暴露 [permissionState]
 * 3. 动作分发:按动作所需最低层级选执行器,高一层失败时降级
 * 4. 截屏特殊处理:无障碍层不支持截屏,优先用 Shell/Root
 *
 * 单例(Koin 注入),生命周期跟随 App。
 */
class AutomationManager(
    private val context: Context,
    private val shizukuAuthorizer: ShizukuAuthorizer,
) {
    val accessibility = AccessibilityExecutor(context)
    // UI 自动化 Shell 与状态检查共用同一个 Shizuku 授权器，避免“显示已授权、执行却走普通 sh”。
    val shell = io.zer0.muse.automation.executors.ShellExecutor(context, shizukuAuthorizer)
    val root = RootExecutor(context)

    private val mutex = Mutex()

    private val _permissionState = MutableStateFlow(PermissionState())
    /** 当前各层权限状态,设置页和工具执行时观察。 */
    val permissionState: StateFlow<PermissionState> = _permissionState.asStateFlow()

    /** 所有执行器(从低到高)。 */
    private val executors: List<AutomationExecutor> = listOf(accessibility, shell, root)

    /**
     * 探测三层可用性。应在 App 启动时和从设置页返回时调用。
     */
    suspend fun refreshPermissions(): PermissionState = mutex.withLock {
        val a11y = accessibility.isAvailable()
        // 普通 `sh` 始终能在 Android 应用沙盒中启动，不能代表 adb/Shizuku 授权。
        // 第二层状态只认 Shizuku 服务运行且已授予本应用的真实授权。
        val sh = shizukuAuthorizer.checkPermission()
        val rt = root.isAvailable()
        val state = PermissionState(
            accessibilityEnabled = a11y,
            shellEnabled = sh,
            rootEnabled = rt,
        )
        _permissionState.value = state
        Logger.i(TAG, "permissions refreshed: a11y=$a11y shell=$sh root=$rt")
        state
    }

    // ── 统一动作接口 ────────────────────────────────────────

    /** 截屏:优先已授权的 Shizuku Shell，再降级 Root(无障碍不支持截屏)。 */
    suspend fun screenshot(): ByteArray? = mutex.withLock {
        shell.screenshot() ?: root.screenshot()
    }

    /** 读屏:优先无障碍(控件树最完整),降级 Shell(uiautomator dump)。 */
    suspend fun readScreen(): ScreenInfo = mutex.withLock {
        if (accessibility.isAvailable()) {
            try {
                val info = accessibility.readScreen()
                if (info.nodes.isNotEmpty()) return@withLock info
            } catch (e: Exception) {
                Logger.w(TAG, "a11y readScreen failed: ${e.message}")
            }
        }
        if (shell.isAvailable()) shell.readScreen() else root.readScreen()
    }

    /** 当前前台包名。 */
    suspend fun currentPackage(): String? = mutex.withLock {
        accessibility.currentPackage() ?: if (shell.isAvailable()) shell.currentPackage() else root.currentPackage()
    }

    /** 点击:优先无障碍,再按真实授权降级到 Shizuku/Root。 */
    suspend fun tap(x: Int, y: Int): Boolean = mutex.withLock {
        if (accessibility.isAvailable()) accessibility.tap(x, y)
        else if (shell.isAvailable()) shell.tap(x, y) else root.tap(x, y)
    }

    /** 长按。 */
    suspend fun longPress(x: Int, y: Int, durationMs: Long = 600): Boolean = mutex.withLock {
        if (accessibility.isAvailable()) accessibility.longPress(x, y, durationMs)
        else if (shell.isAvailable()) shell.longPress(x, y, durationMs) else root.longPress(x, y, durationMs)
    }

    /** 滑动。 */
    suspend fun swipe(
        x1: Int, y1: Int,
        x2: Int, y2: Int,
        durationMs: Long = 400,
    ): Boolean = mutex.withLock {
        if (accessibility.isAvailable()) accessibility.swipe(x1, y1, x2, y2, durationMs)
        else if (shell.isAvailable()) shell.swipe(x1, y1, x2, y2, durationMs)
        else root.swipe(x1, y1, x2, y2, durationMs)
    }

    /** 输入文本。 */
    suspend fun inputText(text: String): Boolean = mutex.withLock {
        if (accessibility.isAvailable()) accessibility.inputText(text)
        else if (shell.isAvailable()) shell.inputText(text) else root.inputText(text)
    }

    /** 按键。 */
    suspend fun pressKey(keyCode: Int): Boolean = mutex.withLock {
        if (accessibility.isAvailable()) accessibility.pressKey(keyCode)
        else if (shell.isAvailable()) shell.pressKey(keyCode) else root.pressKey(keyCode)
    }

    /** 启动 App。 */
    suspend fun launchApp(packageName: String): Boolean = mutex.withLock {
        accessibility.launchApp(packageName) ||
            if (shell.isAvailable()) shell.launchApp(packageName) else root.launchApp(packageName)
    }

    /** 返回。 */
    suspend fun back(): Boolean = mutex.withLock {
        accessibility.back() || if (shell.isAvailable()) shell.back() else root.back()
    }

    /** Home。 */
    suspend fun home(): Boolean = mutex.withLock {
        accessibility.home() || if (shell.isAvailable()) shell.home() else root.home()
    }

    /** 打开通知栏。 */
    suspend fun openNotifications(): Boolean = mutex.withLock {
        accessibility.openNotifications() || if (shell.isAvailable()) shell.openNotifications() else root.openNotifications()
    }

    /** 打开快速设置。 */
    suspend fun openQuickSettings(): Boolean = mutex.withLock {
        accessibility.openQuickSettings() || if (shell.isAvailable()) shell.openQuickSettings() else root.openQuickSettings()
    }

    /**
     * 按文字找控件并点击。readScreen → 匹配 text/description → tap 中心点。
     * @return 是否找到并点击了
     */
    suspend fun tapByText(text: String, exact: Boolean = false): Boolean {
        // readScreen/tap 各自负责锁；这里不能再包一层 Mutex，否则会发生不可重入死锁。
        val screen = readScreen()
        val node = screen.nodes.firstOrNull { n ->
            val label = n.text ?: n.contentDescription ?: return@firstOrNull false
            if (exact) label == text else label.contains(text, ignoreCase = true)
        } ?: return false
        return tap(node.centerX, node.centerY)
    }

    /**
     * 执行高层任务:读屏 → 视觉/控件分析 → 返回屏幕摘要供 AI 决策。
     * 截屏 + 控件树合并,供 AI 同时拿到结构化数据和画面。
     */
    suspend fun captureForAi(): ScreenCapture {
        // 同上，避免 readScreen()/screenshot() 重入同一把 Mutex。
        val info = readScreen()
        val shot = screenshot()
        return ScreenCapture(info, shot)
    }

    /** 最高可用层级(无权限返回 NONE)。 */
    suspend fun highestLevel(): PermissionLevel {
        val s = _permissionState.value
        return when {
            s.rootEnabled -> PermissionLevel.ROOT
            s.shellEnabled -> PermissionLevel.SHELL
            s.accessibilityEnabled -> PermissionLevel.ACCESSIBILITY
            else -> PermissionLevel.NONE
        }
    }

    data class PermissionState(
        val accessibilityEnabled: Boolean = false,
        val shellEnabled: Boolean = false,
        val rootEnabled: Boolean = false,
    ) {
        /** 是否至少有一层可用。 */
        val anyEnabled: Boolean get() = accessibilityEnabled || shellEnabled || rootEnabled
    }

    data class ScreenCapture(
        val info: ScreenInfo,
        val screenshotPng: ByteArray?,
    )

    companion object {
        private const val TAG = "AutomationMgr"
    }
}
