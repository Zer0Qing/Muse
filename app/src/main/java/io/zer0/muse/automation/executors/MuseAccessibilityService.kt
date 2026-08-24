package io.zer0.muse.automation.executors

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import io.zer0.common.Logger

/**
 * Muse 无障碍服务 — UI 自动化的第一层。
 *
 * 单例持有,供 [AccessibilityExecutor] 调用:
 * - 读取控件树(rootInActiveWindow)
 * - dispatchGesture 注入点击/滑动
 * - performGlobalAction 模拟返回/Home/通知栏
 *
 * 用户在系统设置 → 无障碍中开启此服务后,实例会被系统创建并赋值到 [instance]。
 */
class MuseAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // 配置:监听所有窗口变化,获取控件树
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.DEFAULT
            notificationTimeout = 100L
        }
        serviceInfo = info
        Logger.i(TAG, "MuseAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要逐事件处理,Executor 在需要时主动拉取 rootInActiveWindow
    }

    override fun onInterrupt() {
        Logger.w(TAG, "MuseAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Logger.i(TAG, "MuseAccessibilityService destroyed")
    }

    companion object {
        private const val TAG = "MuseA11ySvc"

        /** 当前服务实例,系统创建后赋值;服务关闭时置 null。 */
        @Volatile
        var instance: MuseAccessibilityService? = null
            private set

        /** 服务是否已连接(供设置页快速判断)。 */
        fun isConnected(): Boolean = instance != null
    }
}
