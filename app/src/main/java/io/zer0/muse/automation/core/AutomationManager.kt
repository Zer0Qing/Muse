package io.zer0.muse.automation.core

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.automation.executors.AccessibilityExecutor
import io.zer0.muse.automation.executors.RootExecutor
import io.zer0.muse.automation.executors.RootRequestResult
import io.zer0.muse.permission.PermissionCoordinator
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
    /**
     * 权限体系重设计: 统一协调层（单一事实源 + 事件驱动刷新）。
     * 生产由 AutomationInitializer 注入；为 null 时退回管理器自探测（单测路径）。
     */
    private val coordinator: PermissionCoordinator? = null,
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
        // 权限体系重设计: 有协调层时以它的被动快照为单一事实源（事件驱动同源刷新）。
        if (coordinator != null) {
            val snap = coordinator.refresh()
            val state = PermissionState(
                accessibilityEnabled = snap.accessibilityEnabled,
                shellEnabled = snap.shellEnabled,
                rootEnabled = snap.rootEnabled,
                shizukuState = snap.shizukuState,
                shizukuMessage = snap.shizukuMessage,
            )
            _permissionState.value = state
            Logger.i(
                TAG,
                "permissions refreshed via coordinator: " +
                    "a11y=${state.accessibilityEnabled} shell=${state.shizukuState} root=${state.rootEnabled}",
            )
            return@withLock state
        }
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

    /**
     * 最近一次动作**实际**走的通道。
     *
     * 之前没人知道一次 tap 是无障碍做的还是 root 做的 —— 出问题时只能靠猜。
     * 现在每次动作都记一行日志，并把这个值留在实例上供状态页/诊断读取。
     */
    @Volatile
    var lastActionTier: PermissionLevel = PermissionLevel.NONE
        private set

    /**
     * 按**当前可用性**选一层执行，**只执行一次**。
     *
     * 为什么不是「失败了再换一层」：
     * `back()` / `home()` / `openNotifications()` 之前写的是 `a11y() || shell() || root()` ——
     * 如果无障碍其实已经把动作做成了、却返回了 false，后面那一层会**再做一次**
     * （返回两次、回到两个页面、通知栏拉开又收起）。这类动作不可重放，所以只选一层。
     * 选中的那层失败就返回 false，由模型自己看屏幕决定要不要重试 —— 它本来就在环里。
     */
    private suspend fun runOnTier(
        name: String,
        onAccessibility: suspend () -> Boolean,
        onShell: suspend () -> Boolean,
        onRoot: suspend () -> Boolean,
    ): Boolean {
        val tier = when {
            accessibility.isAvailable() -> PermissionLevel.ACCESSIBILITY
            shell.isAvailable() -> PermissionLevel.SHELL
            else -> PermissionLevel.ROOT
        }
        lastActionTier = tier
        val ok = runCatching {
            when (tier) {
                PermissionLevel.ACCESSIBILITY -> onAccessibility()
                PermissionLevel.SHELL -> onShell()
                else -> onRoot()
            }
        }.getOrElse { error ->
            Logger.w(TAG, "动作 $name 在 ${tier.name} 上抛错: ${error.message}")
            false
        }
        Logger.d(TAG, "动作 $name | tier=${tier.name} | ok=$ok")
        return ok
    }

    /**
     * 屏幕尺寸（像素）：走 `resources.displayMetrics`，**不读控件树**，
     * 所以比 `readScreen()` 便宜得多，适合每次动作前都查一遍坐标。
     * 拿不到时是 0x0 —— 那种情况调用方必须放行（见 `boundsIssue`）。
     */
    fun screenSize(): Pair<Int, Int> = context.resources.displayMetrics.let {
        it.widthPixels to it.heightPixels
    }

    /** 单点越界的确定原因；`null` = 在界内或尺寸未知。 */
    fun coordinateIssue(x: Int, y: Int): String? {
        val (w, h) = screenSize()
        return boundsIssue(x, y, w, h)
    }

    /** 多点动作（滑动/缩放/路径）的越界原因。 */
    fun coordinatesIssue(points: List<Pair<Int, Int>>): String? {
        val (w, h) = screenSize()
        return pointsBoundsIssue(points, w, h)
    }

    /** 点击:优先无障碍,再按真实授权降级到 Shizuku/Root。 */
    suspend fun tap(x: Int, y: Int): Boolean = mutex.withLock {
        runOnTier("tap", { accessibility.tap(x, y) }, { shell.tap(x, y) }, { root.tap(x, y) })
    }

    /** 长按。 */
    suspend fun longPress(x: Int, y: Int, durationMs: Long = 600): Boolean = mutex.withLock {
        runOnTier(
            "longPress",
            { accessibility.longPress(x, y, durationMs) },
            { shell.longPress(x, y, durationMs) },
            { root.longPress(x, y, durationMs) },
        )
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

    /**
     * 多段滑动：**只有无障碍**能逐点注入。
     *
     * Shell/Root 的 input 命令只能直线滑动 —— 旧实现会把多段路径悄悄降级成「首点→末点」，
     * 那是**另一个手势**（图案解锁会变成一条直线），比直接失败危险。现在 >2 点时明确拒绝。
     */
    suspend fun swipePath(points: List<Pair<Int, Int>>, durationMs: Long = 400): Boolean = mutex.withLock {
        if (points.size < 2) return@withLock false
        if (accessibility.isAvailable()) return@withLock accessibility.swipePath(points, durationMs)
        if (points.size > 2) return@withLock false
        val first = points.first()
        val last = points.last()
        if (shell.isAvailable()) {
            shell.swipe(first.first, first.second, last.first, last.second, durationMs)
        } else {
            root.swipe(first.first, first.second, last.first, last.second, durationMs)
        }
    }

    /** 多段路径手势是否可用（只有无障碍能注入多段；Shell/Root 只能直线）。 */
    suspend fun supportsMultiPointGesture(): Boolean = accessibility.isAvailable()

    /** 输入文本。 */
    suspend fun inputText(text: String): Boolean = mutex.withLock {
        runOnTier(
            "inputText",
            { accessibility.inputText(text) },
            { shell.inputText(text) },
            { root.inputText(text) },
        )
    }

    /** 按键。 */
    suspend fun pressKey(keyCode: Int): Boolean = mutex.withLock {
        runOnTier(
            "pressKey",
            { accessibility.pressKey(keyCode) },
            { shell.pressKey(keyCode) },
            { root.pressKey(keyCode) },
        )
    }

    /** 启动 App。 */
    suspend fun launchApp(packageName: String): Boolean = mutex.withLock {
        // 启动是幂等的（同一个 App 拉两次只是再拉到前台），所以这里保留「失败再换一层」；
        // 但仍然记下实际生效的层级，免得日志里看不出是谁启动的。
        val viaAccessibility = accessibility.launchApp(packageName)
        if (viaAccessibility) {
            lastActionTier = PermissionLevel.ACCESSIBILITY
            Logger.d(TAG, "动作 launchApp | tier=ACCESSIBILITY | ok=true")
            return@withLock true
        }
        val tier = if (shell.isAvailable()) PermissionLevel.SHELL else PermissionLevel.ROOT
        lastActionTier = tier
        val ok = if (tier == PermissionLevel.SHELL) {
            shell.launchApp(packageName)
        } else {
            root.launchApp(packageName)
        }
        Logger.d(TAG, "动作 launchApp | tier=${tier.name} | ok=$ok")
        ok
    }

    /** 返回。 */
    suspend fun back(): Boolean = mutex.withLock {
        runOnTier("back", { accessibility.back() }, { shell.back() }, { root.back() })
    }

    /** Home。 */
    suspend fun home(): Boolean = mutex.withLock {
        runOnTier("home", { accessibility.home() }, { shell.home() }, { root.home() })
    }

    /** 打开通知栏。 */
    suspend fun openNotifications(): Boolean = mutex.withLock {
        runOnTier(
            "openNotifications",
            { accessibility.openNotifications() },
            { shell.openNotifications() },
            { root.openNotifications() },
        )
    }

    /** 打开快速设置。 */
    suspend fun openQuickSettings(): Boolean = mutex.withLock {
        runOnTier(
            "openQuickSettings",
            { accessibility.openQuickSettings() },
            { shell.openQuickSettings() },
            { root.openQuickSettings() },
        )
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
    /**
     * 自动化需要用到的系统设置页。
     *
     * 存在的意义：模型发现某层没开时，只能告诉用户「去设置里开一下」——
     * 用户得自己在十几层菜单里找。有了它，Agent 可以直接把用户送到正确的那一页。
     */
    enum class SystemSettingsTarget(val wireName: String) {
        ACCESSIBILITY("accessibility"),
        NOTIFICATION_ACCESS("notification_access"),
        OVERLAY("overlay"),
        BATTERY_OPTIMIZATION("battery"),
        APP_DETAILS("app_details"),
        ;

        companion object {
            fun fromWire(value: String?): SystemSettingsTarget? =
                entries.firstOrNull { it.wireName == value?.trim()?.lowercase() }
        }
    }

    /**
     * 打开对应系统设置页。返回是否成功启动（厂商可能不实现某个 action，逐个降级）。
     *
     * 走 [io.zer0.muse.automation.ui.openAutomationSettings]：它逐个候选 Intent 检查可解析性并捕获
     * ActivityNotFoundException —— 厂商系统缺实现时不会把宿主 Activity 弄崩。
     */
    fun openSystemSettings(target: SystemSettingsTarget): Boolean {
        val pkgUri = android.net.Uri.parse("package:" + context.packageName)
        val candidates = when (target) {
            SystemSettingsTarget.ACCESSIBILITY -> listOf(
                android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
                // OEM 常不实现专项 action：补一条通用设置兜底，配合 attempt-and-catch 不再静默 no-op。
                android.content.Intent(android.provider.Settings.ACTION_SETTINGS),
            )
            SystemSettingsTarget.NOTIFICATION_ACCESS -> listOf(
                // 用 SDK 常量（= "android.settings.NOTIFICATION_LISTENER_SETTINGS"）。
                // 旧实现写了 "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"——这个 action 根本不存在，
                // 只会退到通用设置却照样报成功；与 NotificationListenerScreen 同一常量口径。
                android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                android.content.Intent(android.provider.Settings.ACTION_SETTINGS),
            )
            SystemSettingsTarget.OVERLAY -> listOf(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    pkgUri,
                ),
                android.content.Intent(android.provider.Settings.ACTION_SETTINGS),
            )
            SystemSettingsTarget.BATTERY_OPTIMIZATION -> listOf(
                android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                android.content.Intent(android.provider.Settings.ACTION_SETTINGS),
            )
            SystemSettingsTarget.APP_DETAILS -> listOf(
                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri),
                android.content.Intent(android.provider.Settings.ACTION_SETTINGS),
            )
        }
        val started = io.zer0.muse.automation.ui.openAutomationSettings(context, candidates)
        Logger.i(TAG, "打开系统设置页 | target=${target.wireName} | started=$started")
        return started
    }

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

        /** v2.6.7: 面向自检输出的层级名（比 enum 名可读）。 */
        fun highestLevelName(): String = when {
            rootEnabled -> "Root"
            shellEnabled -> "Shell(Shizuku/adb)"
            accessibilityEnabled -> "无障碍"
            else -> "无（仅应用内操作）"
        }
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
