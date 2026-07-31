package io.zer0.muse.tools.system

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import io.zer0.common.Logger
import io.zer0.muse.accessibility.MuseAccessibilityService

/**
 * P3-3: 无障碍服务客户端 — 通过静态实例直接调用 [MuseAccessibilityService]。
 *
 * 设计(同进程静态实例方案,放弃 AIDL bindService):
 *  - [AccessibilityService] 由系统管理生命周期,onBind 已被 Framework 占用
 *  - Kotlin 不允许同时继承 AccessibilityService 和 IAccessibilityProvider.Stub
 *  - 同 APK 内(同进程)用 [MuseAccessibilityService.instance] 静态引用直接调用,避免 bindService 冲突
 *  - AIDL 接口 IAccessibilityProvider 保留作为方法契约 + 未来跨进程扩展的基础
 *
 * 职责:
 *  1. 检查无障碍服务是否已在系统设置中启用([isEnabled])
 *  2. 通过静态实例暴露 UI 操作 API(getPageInfo/click/swipe/setText/screenshot 等)
 *  3. 引导用户前往 [openAccessibilitySettings] 启用服务
 *
 * 调用前提:
 *  - 用户必须在「设置 → 无障碍 → Muse UI 自动化」中授权启用服务
 *  - 服务由系统在授权后激活,[MuseAccessibilityService.instance] 才会被赋值
 *  - 未授权或服务未运行时,所有 UI 操作返回安全默认值(false / "")
 */
class AccessibilityClient(private val context: Context) {

    companion object {
        private const val TAG = "AccessibilityClient"

        /**
         * 无障碍服务完整类名(accessibility 模块定义)。
         *
         * 注意:library 模块合并进 app 后,服务运行时的 packageName 是 app 的包名
         * (io.zer0.muse),而非模块 namespace。因此 ComponentName 的 packageName
         * 必须用 [Context.getPackageName] 运行时获取,不能用模块 namespace。
         */
        val SERVICE_CLASS_NAME: String = MuseAccessibilityService::class.java.name
    }

    /**
     * 检查无障碍服务是否已在系统设置中启用。
     *
     * 实现方式:
     *  1. 优先用 [AccessibilityManager.getEnabledAccessibilityServiceList](系统 API,准确)
     *  2. 回退到解析 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES(兼容旧设备)
     */
    fun isEnabled(): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC,
        )
        if (enabledServices.isNullOrEmpty()) return false
        // library 模块合并后,服务运行时 packageName 为 app 包名,用 context.packageName 比对
        val appPkg = context.packageName
        return enabledServices.any { it.resolveInfo.serviceInfo.let { si ->
            si.packageName == appPkg && si.name == SERVICE_CLASS_NAME
        }}
    }

    /** 当前是否已连接(静态实例可用)。 */
    fun isConnected(): Boolean = MuseAccessibilityService.instance != null

    // ── UI 操作 API(直接调用静态实例,失败返回安全默认值) ───────────────────

    suspend fun getPageInfo(): String =
        withProvider(defaultOnError = "") { it.getUiHierarchy() }

    suspend fun click(x: Int, y: Int): Boolean =
        withProvider(defaultOnError = false) { it.performClick(x, y) }

    suspend fun longPress(x: Int, y: Int): Boolean =
        withProvider(defaultOnError = false) { it.performLongPress(x, y) }

    suspend fun globalAction(actionId: Int): Boolean =
        withProvider(defaultOnError = false) { it.execGlobalAction(actionId) }

    suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long): Boolean =
        withProvider(defaultOnError = false) { it.performSwipe(startX, startY, endX, endY, duration) }

    suspend fun findFocusedNodeId(): String =
        withProvider(defaultOnError = "") { it.findFocusedNodeId() }

    suspend fun setText(nodeId: String, text: String): Boolean =
        withProvider(defaultOnError = false) { it.setTextOnNode(nodeId, text) }

    suspend fun screenshot(path: String, format: String = "PNG"): Boolean =
        withProvider(defaultOnError = false) { it.takeScreenshot(path, format) }

    suspend fun currentActivityName(): String =
        withProvider(defaultOnError = "") { it.getCurrentActivityName() }

    // ── 内部工具 ──────────────────────────────────────────────────────────────

    /**
     * 通过静态实例调用 [block]。
     * - 服务未启用/未运行时返回 [defaultOnError]
     * - 调用异常时记录日志并返回 [defaultOnError](不抛出,保证工具链稳定)
     */
    private suspend fun <T> withProvider(
        defaultOnError: T,
        block: (MuseAccessibilityService) -> T,
    ): T {
        val service = MuseAccessibilityService.instance ?: return defaultOnError
        return runCatching { block(service) }.getOrElse { e ->
            Logger.w(TAG, "服务调用失败: ${e.message}")
            defaultOnError
        }
    }

    /** 打开系统无障碍设置页(引导用户启用服务)。 */
    fun openAccessibilitySettings() {
        val intent = android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
