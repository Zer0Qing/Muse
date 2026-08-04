package io.zer0.muse.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.zer0.common.Logger
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * P3-3: Muse 无障碍服务 — UI 自动化能力底座。
 *
 * 职责:
 *  1. 作为 [AccessibilityService] 接收系统无障碍事件,记录当前前台 Activity
 *  2. 通过 [companion object] 静态实例向 [io.zer0.muse.tools.system.AccessibilityClient] 暴露 UI 操作能力:
 *     - 读取 UI 层级([getUiHierarchy])
 *     - 点击/长按/滑动手势([performClick]/[performLongPress]/[performSwipe])
 *     - 全局动作([execGlobalAction]:返回/HOME/最近任务等)
 *     - 节点文本输入([setTextOnNode])
 *     - 截图([takeScreenshot],API 34+)
 *     - 当前 Activity 查询([getCurrentActivityName])
 *
 * 设计说明(为何不用 AIDL bindService):
 *  - [AccessibilityService] 由系统管理生命周期,onBind 已被 Framework 占用用于无障碍绑定
 *  - Kotlin 不允许同时继承 AccessibilityService 和 IAccessibilityProvider.Stub(两者都是类)
 *  - 同 APK 内(同进程)用静态实例直接调用更高效,避免 bindService 与系统绑定冲突
 *  - AIDL 接口 [IAccessibilityProvider] 保留作为方法契约 + 未来跨进程扩展的基础
 *
 * 节点路径标识:
 *  - getUiHierarchy 为每个节点分配路径 "父路径.子索引",如根="0",根的第2个子节点="0.1"
 *  - findFocusedNodeId 返回焦点节点的路径
 *  - setTextOnNode 按路径遍历树定位节点
 *
 * 安全:
 *  - exported=false:仅本应用可绑定(同 APK 签名)
 *  - 系统要求 BIND_ACCESSIBILITY_SERVICE 权限才能绑定
 *  - 所有操作仅响应客户端主动调用,不后台监控(事件仅用于更新当前 Activity 名)
 */
class MuseAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MuseA11yService"

        /** UI 层级遍历最大节点数(防止超大树拖垮 LLM 上下文)。 */
        private const val MAX_NODES = 500

        /** 手势回调等待超时(毫秒)。 */
        private const val GESTURE_TIMEOUT_MS = 2000L

        /** 截图回调等待超时(毫秒)。 */
        private const val SCREENSHOT_TIMEOUT_MS = 3000L

        /**
         * 当前服务实例(onServiceConnected 后设置,onDestroy 清除)。
         * AccessibilityClient 通过此静态引用直接调用服务方法(同进程,无需 bindService)。
         */
        @Volatile
        var instance: MuseAccessibilityService? = null
    }

    /** 最近一次窗口状态变化事件的包名/类名(用于 getCurrentActivityName)。 */
    @Volatile private var lastPackage: String = ""
    @Volatile private var lastClassName: String = ""

    /** 服务是否已连接(onServiceConnected 后置 true)。 */
    @Volatile private var connected = false

    // ── AccessibilityService 生命周期 ──────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        instance = this
        enableScreenshotCapability()
        Logger.i(TAG, "无障碍服务已连接")
    }

    /**
     * 动态启用截图能力(API 34+)。
     *
     * android:canTakeScreenshots XML 属性在当前 AAPT 版本中无法识别(报 "attribute not found"),
     * 改为在 onServiceConnected 中通过反射设置 AccessibilityServiceInfo.mCanTakeScreenshots = true。
     * 此字段控制 AccessibilityService.takeScreenshot() 是否可用,默认为 false。
     */
    private fun enableScreenshotCapability() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        try {
            val info = serviceInfo ?: return
            val field = AccessibilityServiceInfo::class.java.getDeclaredField("mCanTakeScreenshots")
            field.isAccessible = true
            field.setBoolean(info, true)
            serviceInfo = info
            Logger.d(TAG, "已启用截图能力(mCanTakeScreenshots=true)")
        } catch (e: NoSuchFieldException) {
            Logger.w(TAG, "mCanTakeScreenshots 字段不存在: ${e.message}")
        } catch (e: Exception) {
            Logger.w(TAG, "启用截图能力失败: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 仅记录当前前台 Activity 信息,不进行其他处理(不后台监控用户行为)
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            val cls = event.className?.toString()
            if (!pkg.isNullOrBlank()) {
                lastPackage = pkg
                lastClassName = cls ?: ""
            }
        }
    }

    override fun onInterrupt() {
        Logger.w(TAG, "无障碍服务被系统中断")
    }

    override fun onDestroy() {
        connected = false
        instance = null
        Logger.i(TAG, "无障碍服务已销毁")
        super.onDestroy()
    }

    // ── IAccessibilityProvider 契约方法(直接实现,不继承 Stub) ─────────────────

    fun isAccessibilityServiceEnabled(): Boolean = connected

    fun getCurrentActivityName(): String {
        if (lastPackage.isBlank()) return ""
        return if (lastClassName.isNotBlank()) "$lastPackage/$lastClassName" else lastPackage
    }

    fun getUiHierarchy(): String {
        val root = rootInActiveWindow ?: return "[error] 无活动窗口(无障碍服务未授权或无前台窗口)"
        val sb = StringBuilder()
        sb.append("[activity] ").append(getCurrentActivityName().ifBlank { "unknown" }).append('\n')
        val counter = intArrayOf(0)
        dumpNode(root, "0", sb, counter)
        return if (counter[0] >= MAX_NODES) {
            sb.append("\n[truncated] 已达到最大节点数 $MAX_NODES")
            sb.toString()
        } else {
            sb.toString()
        }
    }

    fun performClick(x: Int, y: Int): Boolean = dispatchClick(x, y, longPress = false)

    fun performLongPress(x: Int, y: Int): Boolean = dispatchClick(x, y, longPress = true)

    /**
     * 执行全局无障碍动作(返回/HOME/最近任务等)。
     * 注意:命名为 execGlobalAction 而非 performGlobalAction,因为
     * AccessibilityService.performGlobalAction 是 final,不能 override;
     * 此方法直接调用父类的 final 方法。
     */
    fun execGlobalAction(actionId: Int): Boolean {
        val ok = performGlobalAction(actionId)
        Logger.d(TAG, "execGlobalAction($actionId) -> $ok")
        return ok
    }

    fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long): Boolean {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration.coerceAtLeast(50L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return awaitGesture(gesture)
    }

    fun findFocusedNodeId(): String {
        val root = rootInActiveWindow ?: return ""
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return ""
        return findNodePath(root, focus) ?: ""
    }

    fun setTextOnNode(nodeId: String, text: String): Boolean {
        if (nodeId.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        val target = findNodeByPath(root, nodeId) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Logger.d(TAG, "setTextOnNode($nodeId) -> $ok")
        return ok
    }

    fun takeScreenshot(path: String, format: String): Boolean {
        // AccessibilityService.takeScreenshot() 需 API 34+,低版本不支持
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Logger.w(TAG, "takeScreenshot 需 Android 14+(API 34),当前 API ${Build.VERSION.SDK_INT}")
            return false
        }
        if (path.isBlank()) return false
        val latch = CountDownLatch(1)
        var result = false
        takeScreenshot(
            DEFAULT_DISPLAY,
            Runnable::run,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    result = saveScreenshot(screenshot, path, format)
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    Logger.w(TAG, "takeScreenshot 失败 errorCode=$errorCode")
                    latch.countDown()
                }
            },
        )
        if (!latch.await(SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Logger.w(TAG, "takeScreenshot 超时")
            return false
        }
        return result
    }

    // ── 内部实现 ────────────────────────────────────────────────────────────────

    /** 递归输出节点信息到 [sb],[path] 为当前节点路径(如 "0.1.2")。 */
    private fun dumpNode(node: AccessibilityNodeInfo, path: String, sb: StringBuilder, counter: IntArray) {
        if (counter[0] >= MAX_NODES) return
        counter[0]++
        sb.append('[').append(path).append("] ")
        sb.append("class=").append(node.className ?: "")
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
        if (!text.isNullOrBlank()) sb.append(" text=").append(AccessibilityPathUtils.escapeText(text))
        val desc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        if (!desc.isNullOrBlank()) sb.append(" desc=").append(AccessibilityPathUtils.escapeText(desc))
        val r = node.rect
        sb.append(" bounds=[").append(r.left).append(',').append(r.top)
            .append("][").append(r.right).append(',').append(r.bottom).append(']')
        if (node.isClickable) sb.append(" clickable=true")
        if (node.isEditable) sb.append(" editable=true")
        if (node.isFocused) sb.append(" focused=true")
        sb.append('\n')
        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (counter[0] >= MAX_NODES) return
            val child = node.getChild(i) ?: continue
            dumpNode(child, "$path.$i", sb, counter)
        }
    }

    /** 转义文本中的换行/制表符,保证单行输出。 */

    private val AccessibilityNodeInfo.rect: android.graphics.Rect
        get() = android.graphics.Rect().also { getBoundsInScreen(it) }

    /** 点击/长按手势分发。 */
    private fun dispatchClick(x: Int, y: Int, longPress: Boolean): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val duration = if (longPress) 500L else 50L
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = awaitGesture(gesture)
        Logger.d(TAG, "dispatchClick($x,$y,longPress=$longPress) -> $ok")
        return ok
    }

    /** 同步等待手势分发结果(CountDownLatch + 超时)。 */
    private fun awaitGesture(gesture: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        var result = false
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                result = true
                latch.countDown()
            }

            override fun onCancelled(g: GestureDescription?) {
                latch.countDown()
            }
        }, null)
        if (!dispatched) return false
        return latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS) && result
    }

    /**
     * 在 [root] 子树中查找 [target] 节点的路径标识。
     * @return 路径字符串(如 "0.1.2");未找到返回 null
     */
    private fun findNodePath(root: AccessibilityNodeInfo, target: AccessibilityNodeInfo): String? {
        val targetHash = System.identityHashCode(target)
        return findNodePathRec(root, "0", targetHash)
    }

    private fun findNodePathRec(node: AccessibilityNodeInfo, path: String, targetHash: Int): String? {
        if (System.identityHashCode(node) == targetHash) return path
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodePathRec(child, "$path.$i", targetHash)
            if (found != null) return found
        }
        return null
    }

    /** 按路径标识(如 "0.1.2")从 [root] 遍历定位节点。 */
    private fun findNodeByPath(root: AccessibilityNodeInfo, path: String): AccessibilityNodeInfo? {
        val indices = AccessibilityPathUtils.parseNodePath(path)
        if (indices.isEmpty()) return null
        var current: AccessibilityNodeInfo = root
        for (i in 1 until indices.size) {
            val idx = indices[i]
            val child = current.getChild(idx) ?: return null
            current = child
        }
        return current
    }

    /** 将 [ScreenshotResult] 的 bitmap 保存到文件。ScreenshotResult 的 hardwareBuffer 是 API 30+。 */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.R)
    private fun saveScreenshot(screenshot: ScreenshotResult, path: String, format: String): Boolean {
        val hardwareBuffer = screenshot.hardwareBuffer ?: return false
        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, null) ?: run {
            hardwareBuffer.close()
            return false
        }
        return try {
            val fmt = if (format.equals("JPEG", ignoreCase = true)) {
                Bitmap.CompressFormat.JPEG
            } else {
                Bitmap.CompressFormat.PNG
            }
            FileOutputStream(path).use { fos -> bitmap.compress(fmt, 90, fos) }
            true
        } catch (e: Exception) {
            // 必要容错:文件 IO 可能失败(磁盘满/权限),记录日志而非吞异常
            Logger.e(TAG, "saveScreenshot 失败: ${e.message}", e)
            false
        } finally {
            hardwareBuffer.close()
        }
    }
}

/** 默认显示器 ID(main display = 0)。 */
private const val DEFAULT_DISPLAY = 0
