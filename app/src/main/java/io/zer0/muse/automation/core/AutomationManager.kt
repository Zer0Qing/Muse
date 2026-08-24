package io.zer0.muse.automation.core

import android.content.Context
import io.zer0.muse.automation.executors.AccessibilityExecutor
import io.zer0.muse.automation.executors.RootExecutor
import io.zer0.muse.automation.executors.ShellExecutor
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
) {
    val accessibility = AccessibilityExecutor(context)
    val shell = ShellExecutor(context)
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
        val sh = shell.isAvailable()
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

    /** 截屏:优先 Shell/Root(无障碍不支持截屏)。 */
    suspend fun screenshot(): ByteArray? = mutex.withLock {
        root.screenshot() ?: shell.screenshot()
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
        shell.readScreen()
    }

    /** 当前前台包名。 */
    suspend fun currentPackage(): String? = mutex.withLock {
        accessibility.currentPackage() ?: shell.currentPackage()
    }

    /** 点击:优先无障碍,降级 Shell。 */
    suspend fun tap(x: Int, y: Int): Boolean = mutex.withLock {
        if (accessibility.isAvailable()) accessibility.tap(x, y)
        else shell.tap(x, y)
    }

    /** 长按。 */
    suspend fun longPress(x: Int, y: Int, durationMs: Long = 600): Boolean = mutex.withLock {
        if (accessibility.isAvailable()) accessibility.longPress(x, y, durationMs)
        else shell.longPress(x, y, durationMs)
    }

    /** 滑动。 */
    suspend fun swipe(
        x1: Int, y1: Int,
        x2: Int, y2: Int,
        durationMs: Long = 400,
    ): Boolean = mutex.withLock {
        if (accessibility.isAvailable()) accessibility.swipe(x1, y1, x2, y2, durationMs)
        else shell.swipe(x1, y1, x2, y2, durationMs)
    }

    /** 输入文本。 */
    suspend fun inputText(text: String): Boolean = mutex.withLock {
        if (accessibility.isAvailable()) accessibility.inputText(text)
        else shell.inputText(text)
    }

    /** 按键。 */
    suspend fun pressKey(keyCode: Int): Boolean = mutex.withLock {
        if (accessibility.isAvailable()) accessibility.pressKey(keyCode)
        else shell.pressKey(keyCode)
    }

    /** 启动 App。 */
    suspend fun launchApp(packageName: String): Boolean = mutex.withLock {
        accessibility.launchApp(packageName) || shell.launchApp(packageName)
    }

    /** 返回。 */
    suspend fun back(): Boolean = mutex.withLock {
        accessibility.back() || shell.back()
    }

    /** Home。 */
    suspend fun home(): Boolean = mutex.withLock {
        accessibility.home() || shell.home()
    }

    /** 打开通知栏。 */
    suspend fun openNotifications(): Boolean = mutex.withLock {
        accessibility.openNotifications() || shell.openNotifications()
    }

    /** 打开快速设置。 */
    suspend fun openQuickSettings(): Boolean = mutex.withLock {
        accessibility.openQuickSettings() || shell.openQuickSettings()
    }

    /**
     * 按文字找控件并点击。readScreen → 匹配 text/description → tap 中心点。
     * @return 是否找到并点击了
     */
    suspend fun tapByText(text: String, exact: Boolean = false): Boolean = mutex.withLock {
        val screen = readScreen()
        val node = screen.nodes.firstOrNull { n ->
            val label = n.text ?: n.contentDescription ?: return@firstOrNull false
            if (exact) label == text else label.contains(text, ignoreCase = true)
        } ?: return@withLock false
        tap(node.centerX, node.centerY)
    }

    /**
     * 执行高层任务:读屏 → 视觉/控件分析 → 返回屏幕摘要供 AI 决策。
     * 截屏 + 控件树合并,供 AI 同时拿到结构化数据和画面。
     */
    suspend fun captureForAi(): ScreenCapture = mutex.withLock {
        val info = readScreen()
        val shot = screenshot()
        ScreenCapture(info, shot)
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
