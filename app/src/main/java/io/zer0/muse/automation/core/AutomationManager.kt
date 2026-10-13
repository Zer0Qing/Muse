package io.zer0.muse.automation.core

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.automation.executors.AccessibilityExecutor
import io.zer0.muse.automation.executors.RootExecutor
import io.zer0.muse.automation.executors.RootRequestResult
import io.zer0.muse.tools.system.ShizukuAuthorizer
import kotlinx.coroutines.CancellationException
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
    /**
     * 可注入的 Root 执行器(默认按 context 构造)。
     * 注入点供单测模拟「无 su / 授权超时」等无法在测试环境真实复现的路径。
     */
    private val rootExecutor: RootExecutor = RootExecutor(context),
    /**
     * 可注入的 Shell 执行器，供运行时 Shizuku 断连时验证 Root 兜底。
     * 生产默认仍使用与授权器共享的 ShellExecutor。
     */
    private val shellExecutor: io.zer0.muse.automation.executors.ShellExecutor =
        io.zer0.muse.automation.executors.ShellExecutor(context, shizukuAuthorizer),
) {
    val accessibility = AccessibilityExecutor(context)

    // UI 自动化 Shell 与状态检查共用同一个 Shizuku 授权器，避免“显示已授权、执行却走普通 sh”。
    val shell = shellExecutor
    val root: RootExecutor = rootExecutor

    private val mutex = Mutex()

    private val _permissionState = MutableStateFlow(PermissionState())

    /** 当前各层权限状态,设置页和工具执行时观察。 */
    val permissionState: StateFlow<PermissionState> = _permissionState.asStateFlow()

    /**
     * v2.7: 实时快照（同步、轻量）—— 每次读都重新探测无障碍通道。
     *
     * ## 为什么需要它
     *
     * [permissionState] 是缓存值，只在 [refreshPermissions] 被显式调用时更新
     * （App 启动、从设置页返回、请求 Root 后）。但无障碍服务是**异步连接**的：
     * 用户在系统设置里开启开关后，`MuseAccessibilityService.onServiceConnected`
     * 才把实例挂上；若此刻没有触发刷新，缓存会一直停在启动时的 false，
     * 导致工具预检误判“未授权”（用户实测：向导显示已开启、工具却报未打开）。
     *
     * 无障碍可用性是同步查询（`service?.isAccessibilityServiceEnabled()`），
     * 开销极小，因此工具预检这类需要“当下真实状态”的场景直接用它，
     * 不依赖缓存；同时把探测结果写回缓存，保持 UI 一致。
     *
     * Shell/Root 探测是 suspend 且可能阻塞（su 探针），不适合同步路径，
     * 因此本方法只实时刷新无障碍，其余沿用缓存值。
     */
    fun liveSnapshot(): PermissionState {
        val a11y = accessibility.isAvailableSync()
        val current = _permissionState.value
        return if (a11y == current.accessibilityEnabled) {
            current
        } else {
            val updated = current.copy(accessibilityEnabled = a11y)
            _permissionState.value = updated
            Logger.i(TAG, "无障碍状态实时刷新: a11y=$a11y")
            updated
        }
    }

    /** 所有执行器(从低到高)。 */
    private val executors: List<AutomationExecutor> = listOf(accessibility, shell, root)

    /**
     * 探测三层可用性。应在 App 启动时和从设置页返回时调用。
     */
    suspend fun refreshPermissions(): PermissionState = mutex.withLock {
        val a11y = probe("accessibility") { accessibility.isAvailable() }
        // 普通 `sh` 始终能在 Android 应用沙盒中启动，不能代表 adb/Shizuku 授权。
        // 第二层状态必须完成 UserService bind，不能只看 Shizuku 授权位。
        val shizukuStatus = try {
            shizukuAuthorizer.diagnose()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "shizuku diagnostic failed: ${e.message}", e)
            ShizukuAuthorizer.ShizukuStatus(
                ShizukuAuthorizer.ShizukuState.USER_SERVICE_UNAVAILABLE,
                "Shizuku 状态探测失败",
            )
        }
        val rt = probe("root") { root.isAvailable() }
        val state = PermissionState(
            accessibilityEnabled = a11y,
            shellEnabled = shizukuStatus.isReady,
            rootEnabled = rt,
            shizukuState = shizukuStatus.state,
            shizukuMessage = shizukuStatus.message,
        )
        _permissionState.value = state
        Logger.i(
            TAG,
            "permissions refreshed: a11y=$a11y shell=${shizukuStatus.state} root=$rt",
        )
        state
    }

    /** 权限探测属于 UI 状态刷新；单个通道异常时降级为不可用，不得击穿设置页。 */
    private suspend fun probe(name: String, block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(TAG, "$name permission probe failed: ${e.message}", e)
        false
    }

    /**
     * 显式请求 Root 授权(设置页第三层卡片的主行为)。
     *
     * 委托 [RootExecutor.requestRootAccess] 触发 root 管理器弹窗;无论成功与否都重新探测三层
     * 状态,让 [permissionState] 立刻反映真实结果。不持有 [mutex] —— su 探针可能等待用户操作,
     * 期间不应阻塞其他自动化调用。
     */
    suspend fun requestRoot(): RootRequestResult {
        val result = root.requestRootAccess()
        refreshPermissions()
        return result
    }

    // ── 统一动作接口 ────────────────────────────────────────

    /**
     * v2.x 自动化一期:设备命令统一执行 —— Shizuku 优先,Root 兜底。
     *
     * @return 档位名("Shizuku"/"Root")与详细结果;两档均不可用时返回 null。
     */
    suspend fun execTiered(command: String): Pair<String, io.zer0.muse.automation.executors.ShellExecutor.ExecDetail>? {
        var state = permissionState.value
        if (!state.shellEnabled && !state.rootEnabled) {
            // 缓存可能过期(刚授权/撤销),探一次再决定
            state = refreshPermissions()
        }
        return when {
            state.shellEnabled -> {
                val shizukuResult = shell.execDetailed(command)
                if (shizukuResult.exitCode == 0 || !state.rootEnabled) {
                    "Shizuku" to shizukuResult
                } else {
                    Logger.w(TAG, "Shizuku command failed at runtime, falling back to Root")
                    "Root" to root.execDetailed(command)
                }
            }
            state.rootEnabled -> "Root" to root.execDetailed(command)
            else -> null
        }
    }

    /** 截屏:优先已授权的 Shell/Root;API 34+ 最后降级到无障碍截图。 */
    suspend fun screenshot(): ByteArray? = mutex.withLock {
        firstNonEmptyScreenshot(
            { shell.screenshot() },
            { root.screenshot() },
            { accessibility.screenshot() },
        )
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
        if (shell.isAvailable()) return@withLock shell.readScreen()
        if (root.isAvailable()) return@withLock root.readScreen()
        // 无任何自动化通道时不能伪造“shell 读取成功”;设置页/工具据此展示明确的不可用状态。
        ScreenInfo(
            packageName = null,
            activityName = null,
            nodes = emptyList(),
            screenWidth = context.resources.displayMetrics.widthPixels,
            screenHeight = context.resources.displayMetrics.heightPixels,
            source = "unavailable",
        )
    }

    /** 读取后台虚拟屏的控件树；仅 Shell/Root 能力可以访问指定 display。 */
    suspend fun readScreenOnDisplay(displayId: Int): ScreenInfo? = mutex.withLock {
        when {
            shell.isAvailable() -> shell.readScreenOnDisplay(displayId)
            root.isAvailable() -> root.readScreenOnDisplay(displayId)
            else -> null
        }
    }

    /** 当前前台包名。 */
    suspend fun currentPackage(): String? = mutex.withLock {
        accessibility.currentPackage() ?: if (shell.isAvailable()) shell.currentPackage() else root.currentPackage()
    }

    /** 点击:优先无障碍,再按真实授权降级到 Shizuku/Root。 */
    suspend fun tap(x: Int, y: Int): Boolean = mutex.withLock {
        if (accessibility.isAvailable()) {
            accessibility.tap(x, y)
        } else if (shell.isAvailable()) shell.tap(x, y) else root.tap(x, y)
    }

    /** 长按。 */
    suspend fun longPress(x: Int, y: Int, durationMs: Long = 600): Boolean = mutex.withLock {
        if (accessibility.isAvailable()) {
            accessibility.longPress(x, y, durationMs)
        } else if (shell.isAvailable()) shell.longPress(x, y, durationMs) else root.longPress(x, y, durationMs)
    }

    /** 滑动。 */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 400): Boolean = mutex.withLock {
        if (accessibility.isAvailable()) {
            accessibility.swipe(x1, y1, x2, y2, durationMs)
        } else if (shell.isAvailable()) {
            shell.swipe(x1, y1, x2, y2, durationMs)
        } else {
            root.swipe(x1, y1, x2, y2, durationMs)
        }
    }

    /**
     * v2.x: 双指缩放 — 无障碍 dispatchGesture 双指实现;
     * Shell/Root 的 input 命令无多指注入能力,不可用时返回 false。
     */
    suspend fun pinch(centerX: Int, centerY: Int, startDistance: Int, endDistance: Int, durationMs: Long = 300): Boolean = mutex.withLock {
        if (accessibility.isAvailable()) {
            accessibility.pinch(centerX, centerY, startDistance, endDistance, durationMs)
        } else {
            false
        }
    }

    /** v2.x: 多段滑动 — 无障碍精确逐点;其余层降级首末两点直滑。 */
    suspend fun swipePath(points: List<Pair<Int, Int>>, durationMs: Long = 400): Boolean = mutex.withLock {
        if (points.size < 2) return@withLock false
        if (accessibility.isAvailable()) return@withLock accessibility.swipePath(points, durationMs)
        val first = points.first()
        val last = points.last()
        if (shell.isAvailable()) {
            shell.swipe(first.first, first.second, last.first, last.second, durationMs)
        } else {
            root.swipe(first.first, first.second, last.first, last.second, durationMs)
        }
    }

    /** 输入文本。 */
    suspend fun inputText(text: String): Boolean = mutex.withLock {
        if (accessibility.isAvailable()) {
            accessibility.inputText(text)
        } else if (shell.isAvailable()) shell.inputText(text) else root.inputText(text)
    }

    /** 按键。 */
    suspend fun pressKey(keyCode: Int): Boolean = mutex.withLock {
        if (accessibility.isAvailable()) {
            accessibility.pressKey(keyCode)
        } else if (shell.isAvailable()) shell.pressKey(keyCode) else root.pressKey(keyCode)
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
        val node = screen.findBestTextNode(text, exact) ?: return false
        return tap(node.centerX, node.centerY)
    }

    /**
     * 语义点击增强版：找不到目标时在当前屏幕有限次上滑，点击后可等待验证文字出现。
     *
     * 这是无障碍层最重要的长页面原语；Shell/Root 也能复用，因为 [readScreen] 与 [swipe]
     * 会按当前可用通道自动降级。默认不滚动，保持旧 [tapByText] 的一次性语义。
     */
    @Suppress("ReturnCount") // Bounded retry loop has explicit success, verification, and channel-failure exits.
    suspend fun tapByTextWithRetry(text: String, exact: Boolean = false, maxSwipes: Int = 0, verifyText: String? = null): Boolean {
        return tapByTextWithRetryDetailed(text, exact, maxSwipes, verifyText).success
    }

    /** 语义点击的结构化结果，供工具编排与 UI 审计使用。 */
    data class TextActionResult(
        val success: Boolean,
        val attempts: Int,
        val matched: Boolean,
        val verified: Boolean,
        val matchedLabel: String? = null,
    )

    /** 与 [tapByTextWithRetry] 相同的动作，但保留观察/验证细节。 */
    @Suppress("ReturnCount") // Each exit represents a distinct observable action outcome.
    suspend fun tapByTextWithRetryDetailed(
        text: String,
        exact: Boolean = false,
        maxSwipes: Int = 0,
        verifyText: String? = null,
    ): TextActionResult {
        val attempts = maxSwipes.coerceIn(0, 5)
        repeat(attempts + 1) { attempt ->
            val screen = readScreen()
            val node = screen.findBestTextNode(text, exact)
            if (node != null) {
                val matchedLabel = node.text ?: node.contentDescription
                // Once a tap is attempted, a false channel result cannot prove that no input
                // reached the device. Never scroll/retry and risk replaying the side effect.
                if (!tap(node.centerX, node.centerY)) {
                    return TextActionResult(false, attempt + 1, matched = true, verified = false, matchedLabel)
                }
                if (verifyText.isNullOrBlank()) {
                    return TextActionResult(true, attempt + 1, matched = true, verified = true, matchedLabel)
                }
                kotlinx.coroutines.delay(350L)
                val verified = readScreen().nodes.any { n ->
                    val label = n.text ?: n.contentDescription ?: return@any false
                    label.contains(verifyText, ignoreCase = true)
                }
                return TextActionResult(verified, attempt + 1, matched = true, verified = verified, matchedLabel)
            }
            if (attempt < attempts && screen.screenHeight > 0) {
                val centerX = screen.screenWidth / 2
                val bottom = (screen.screenHeight * 0.82f).toInt()
                val top = (screen.screenHeight * 0.28f).toInt()
                if (!swipe(centerX, bottom, centerX, top, 450L)) {
                    return TextActionResult(false, attempt + 1, matched = false, verified = false)
                }
                kotlinx.coroutines.delay(300L)
            }
        }
        return TextActionResult(false, attempts + 1, matched = false, verified = false)
    }

    /** Stable resource-id semantic click with bounded scrolling and optional post-click verification. */
    suspend fun tapByViewIdWithRetryDetailed(viewId: String, maxSwipes: Int = 0, verifyText: String? = null): TextActionResult {
        val attempts = maxSwipes.coerceIn(0, 5)
        var terminalResult: TextActionResult? = null
        for (attempt in 0..attempts) {
            val screen = readScreen()
            val node = screen.findBestViewIdNode(viewId)
            if (node != null) {
                terminalResult = tapViewIdNode(node, attempt + 1, verifyText)
                break
            }
            if (attempt < attempts && screen.screenHeight > 0) {
                val centerX = screen.screenWidth / 2
                if (!swipe(centerX, (screen.screenHeight * 0.82f).toInt(), centerX, (screen.screenHeight * 0.28f).toInt(), 450L)) {
                    terminalResult = TextActionResult(false, attempt + 1, matched = false, verified = false)
                    break
                }
                kotlinx.coroutines.delay(300L)
            }
        }
        return terminalResult ?: TextActionResult(false, attempts + 1, false, false)
    }

    private suspend fun tapViewIdNode(node: UiNode, attempt: Int, verifyText: String?): TextActionResult {
        val label = node.viewIdResourceName ?: node.text ?: node.contentDescription
        return when {
            !tap(node.centerX, node.centerY) ->
                TextActionResult(false, attempt, matched = true, verified = false, label)
            verifyText.isNullOrBlank() -> TextActionResult(true, attempt, matched = true, verified = true, label)
            else -> {
                kotlinx.coroutines.delay(350L)
                val verified = readScreen().nodes.any { candidate ->
                    val text = candidate.text ?: candidate.contentDescription ?: return@any false
                    text.contains(verifyText, ignoreCase = true)
                }
                TextActionResult(verified, attempt, matched = true, verified = verified, label)
            }
        }
    }

    /**
     * Double-tap at (x, y) for selection or special gestures.
     * Uses accessibility dispatchGesture when available, otherwise delegates to shell/root.
     */
    suspend fun doubleTap(x: Int, y: Int): Boolean = mutex.withLock {
        if (accessibility.isAvailable()) {
            // dispatchGesture 无法在一个手势里叠两次点击,用两次快速点击近似双击。
            if (accessibility.tap(x, y)) {
                kotlinx.coroutines.delay(100)
                return@withLock accessibility.tap(x, y)
            }
        }
        if (shell.isAvailable()) return@withLock shell.tap(x, y) && shell.tap(x, y)
        root.tap(x, y) && root.tap(x, y)
    }

    /**
     * Execute an action with automatic retry on failure (up to [maxRetries] additional attempts).
     * Used by tool executors to improve reliability of critical operations.
     *
     * @param action the action to execute
     * @param maxRetries number of additional attempts after first failure (default 1)
     * @return true if any attempt succeeded
     */
    suspend fun executeWithRetry(action: suspend () -> Boolean, maxRetries: Int = 1): Boolean {
        var lastSuccess = action()
        if (lastSuccess) return true
        for (i in 1..maxRetries) {
            Logger.i(TAG, "executeWithRetry attempt $i/$maxRetries")
            kotlinx.coroutines.delay(300L)
            lastSuccess = action()
            if (lastSuccess) return true
        }
        return false
    }

    /**
     * Capture a full [ScreenSnapshot] with version tracking.
     * Convenience wrapper over [screenshot] + [readScreen].
     */
    suspend fun captureSnapshot(version: Int = 0): ScreenSnapshot {
        val info = readScreen()
        val shot = screenshot()
        return ScreenSnapshot(info = info, screenshotPng = shot, version = version)
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
        val shizukuState: ShizukuAuthorizer.ShizukuState = ShizukuAuthorizer.ShizukuState.NOT_INSTALLED,
        val shizukuMessage: String = "未探测",
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

/** Try screenshot channels in priority order, treating empty or failed captures as unavailable. */
internal suspend fun firstNonEmptyScreenshot(vararg sources: suspend () -> ByteArray?): ByteArray? {
    for (source in sources) {
        val image = try {
            source()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (image != null && image.isNotEmpty()) return image
    }
    return null
}
