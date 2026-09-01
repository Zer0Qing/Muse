package io.zer0.muse.automation.executors

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityNodeInfo
import io.zer0.muse.automation.core.AutomationExecutor
import io.zer0.muse.automation.core.PermissionLevel
import io.zer0.muse.automation.core.ScreenInfo
import io.zer0.muse.automation.core.UiNode
import io.zer0.common.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 无障碍执行器 —— 第一层 UI 自动化。
 *
 * 通过 [MuseAccessibilityService] 读取控件树 + dispatchGesture 注入手势。
 * 这是三层里门槛最低的一层(用户只需在系统设置里开启无障碍开关)。
 *
 * 局限:
 * - 自定义 Canvas/WebView 内的控件读不到(此时上层会降级到截屏+视觉模型)
 * - 手势是坐标级的,需要调用方结合 [ScreenInfo] 算出坐标
 */
class AccessibilityExecutor(
    private val context: Context,
) : AutomationExecutor {

    override val level = PermissionLevel.ACCESSIBILITY

    private val service: MuseAccessibilityService? get() = MuseAccessibilityService.instance

    override suspend fun isAvailable(): Boolean = service?.isAccessibilityServiceEnabled() == true

    // ── 屏幕读取 ──────────────────────────────────────────────

    override suspend fun screenshot(): ByteArray? {
        // 无障碍层截屏需要 MediaProjection,这里交给 Shell 执行器做 screencap
        // 返回 null 让 ActionDispatcher 降级到 Shell 层
        Logger.d(TAG, "accessibility screenshot delegated to shell")
        return null
    }

    override suspend fun readScreen(): ScreenInfo {
        val svc = service ?: return ScreenInfo(source = "accessibility(unavailable)")
        val root = svc.rootInActiveWindow ?: return ScreenInfo(
            packageName = svc.rootInActiveWindow?.packageName?.toString(),
            source = "accessibility(no-root)",
        )
        val nodes = mutableListOf<UiNode>()
        collectNodes(root, nodes, depth = 0)

        val metrics = getScreenMetrics()
        val pkg = try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.appTasks.firstOrNull()?.taskInfo?.topActivity?.packageName?.toString()
                ?: svc.rootInActiveWindow?.packageName?.toString()
        } catch (_: Exception) {
            svc.rootInActiveWindow?.packageName?.toString()
        }

        return ScreenInfo(
            packageName = pkg,
            activityName = root.className?.toString(),
            nodes = nodes,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            source = "accessibility",
        )
    }

    override suspend fun currentPackage(): String? {
        val svc = service ?: return null
        return try {
            svc.rootInActiveWindow?.packageName?.toString()
        } catch (_: Exception) {
            null
        }
    }

    // ── 输入动作 ──────────────────────────────────────────────

    override suspend fun tap(x: Int, y: Int): Boolean = dispatchClick(x, y)

    override suspend fun longPress(x: Int, y: Int, durationMs: Long): Boolean =
        dispatchLongPress(x, y, durationMs)

    override suspend fun swipe(
        x1: Int, y1: Int,
        x2: Int, y2: Int,
        durationMs: Long,
    ): Boolean = dispatchSwipe(x1, y1, x2, y2, durationMs)

    override suspend fun inputText(text: String): Boolean {
        val svc = service ?: return false
        return try {
            // 找到聚焦的输入框
            val focused = svc.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) {
                val args = android.os.Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text,
                )
                focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                true
            } else {
                // 没有聚焦输入框,走剪贴板粘贴
                pasteText(text)
            }
        } catch (e: Exception) {
            Logger.w(TAG, "inputText failed: ${e.message}")
            false
        }
    }

    override suspend fun pressKey(keyCode: Int): Boolean {
        // 无障碍无法直接注入 KeyEvent,降级到 Shell
        // 但返回键/Home 键可以通过全局动作
        return when (keyCode) {
            4 -> service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) == true
            3 -> service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) == true
            else -> false
        }
    }

    override suspend fun launchApp(packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Logger.w(TAG, "launchApp failed: ${e.message}")
            false
        }
    }

    override suspend fun openNotifications(): Boolean =
        service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) == true

    override suspend fun openQuickSettings(): Boolean =
        service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) == true

    // ── 内部实现 ──────────────────────────────────────────────

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<UiNode>,
        depth: Int,
    ) {
        if (depth > MAX_TREE_DEPTH) return
        val hasUsefulInfo = !node.text.isNullOrBlank() ||
            !node.contentDescription.isNullOrBlank() ||
            node.isClickable || node.isEditable
        if (hasUsefulInfo) {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            out.add(
                UiNode(
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    className = node.className?.toString(),
                    viewIdResourceName = node.viewIdResourceName,
                    boundsLeft = rect.left,
                    boundsTop = rect.top,
                    boundsRight = rect.right,
                    boundsBottom = rect.bottom,
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    isScrollable = node.isScrollable,
                    isChecked = if (node.isCheckable) node.isChecked else null,
                    isEnabled = node.isEnabled,
                ),
            )
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectNodes(child, out, depth + 1)
            }
        }
    }

    private fun getScreenMetrics(): DisplayMetrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private suspend fun dispatchClick(x: Int, y: Int): Boolean {
        val svc = service ?: return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(svc, gesture)
    }

    private suspend fun dispatchLongPress(x: Int, y: Int, durationMs: Long): Boolean {
        val svc = service ?: return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(svc, gesture)
    }

    private suspend fun dispatchSwipe(
        x1: Int, y1: Int,
        x2: Int, y2: Int,
        durationMs: Long,
    ): Boolean {
        val svc = service ?: return false
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(svc, gesture)
    }

    private suspend fun dispatchGesture(
        svc: MuseAccessibilityService,
        gesture: GestureDescription,
    ): Boolean = suspendCancellableCoroutine { cont ->
        svc.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(g: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)
    }

    private fun pasteText(text: String): Boolean {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("auto_input", text))
            // 粘贴需要 Shell 层的 input keyevent 配合 KEYCODE_PASTE,
            // 这里只做剪贴板写入,返回 false 让上层降级
            false
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "AccessibilityExec"
        private const val MAX_TREE_DEPTH = 30
    }
}
