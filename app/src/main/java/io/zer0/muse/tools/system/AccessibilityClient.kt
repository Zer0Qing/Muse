package io.zer0.muse.tools.system

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.ServiceInfo
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import io.zer0.common.Logger
import io.zer0.muse.automation.executors.MuseAccessibilityService

/**
 * P3-3: 无障碍服务客户端 — 通过静态实例直接调用 [MuseAccessibilityService]。
 *
 * 设计(同进程静态实例方案,放弃 AIDL bindService):
 *  - [AccessibilityService] 由系统管理生命周期,onBind 已被 Framework 占用
 *  - 具体服务实现由 :accessibility 模块提供,主 App 组件继承该实现并保持稳定类名
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

        /** 主 App 清单中注册的稳定服务类名,用于匹配系统授权记录。 */
        val SERVICE_CLASS_NAME: String = MuseAccessibilityService::class.java.name

        /**
         * 判断系统返回的服务信息是否是 Muse 的主无障碍服务。
         * 部分 ROM 会把类名返回成相对名或不带包名前缀的短名,这里统一展开。
         */
        internal fun matchesServiceInfo(
            serviceInfo: ServiceInfo?,
            appPackageName: String,
            expectedClassName: String = SERVICE_CLASS_NAME,
        ): Boolean {
            if (serviceInfo == null || serviceInfo.packageName != appPackageName) return false
            return normalizeClassName(appPackageName, serviceInfo.name) ==
                normalizeClassName(appPackageName, expectedClassName)
        }

        /**
         * 兼容读取 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 的结果。
         * 使用纯字符串解析,不依赖 OEM 对 AccessibilityManager 列表实现的一致性。
         */
        internal fun containsEnabledService(
            rawValue: String?,
            appPackageName: String,
            expectedClassName: String = SERVICE_CLASS_NAME,
        ): Boolean {
            val expected = normalizeClassName(appPackageName, expectedClassName)
            return rawValue.orEmpty().split(':').any { raw ->
                val value = raw.trim()
                val separator = value.indexOf('/')
                if (separator <= 0 || separator == value.lastIndex) return@any false
                val packageName = value.substring(0, separator)
                val className = normalizeClassName(packageName, value.substring(separator + 1))
                packageName == appPackageName && className == expected
            }
        }

        private fun normalizeClassName(packageName: String, rawClassName: String?): String {
            val className = rawClassName?.trim().orEmpty()
            return when {
                className.isBlank() -> ""
                className.startsWith('.') -> packageName + className
                '.' !in className -> "$packageName.$className"
                else -> className
            }
        }
    }

    /**
     * 检查无障碍服务是否已在系统设置中启用。
     *
     * 实现方式:
     *  1. 优先用 [AccessibilityManager.getEnabledAccessibilityServiceList](系统 API,准确)
     *  2. 回退到解析 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES(兼容旧设备)
     */
    fun isEnabled(): Boolean {
        val appPkg = context.packageName
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val managerResult = runCatching {
            am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.any { info ->
                    matchesServiceInfo(info.resolveInfo?.serviceInfo, appPkg)
                } == true
        }.onFailure {
            // 部分定制 ROM 对无障碍服务列表访问会抛异常,继续走 Secure 设置回退。
            Logger.w(TAG, "读取系统无障碍服务列表失败: ${it.message}")
        }.getOrDefault(false)
        if (managerResult) return true

        val rawEnabledServices = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        }.onFailure {
            Logger.w(TAG, "读取已启用无障碍服务设置失败: ${it.message}")
        }.getOrNull()
        return containsEnabledService(rawEnabledServices, appPkg)
    }

    /** 当前是否已连接(静态实例可用)。 */
    fun isConnected(): Boolean = MuseAccessibilityService.instance?.isAccessibilityServiceEnabled() == true

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

    /** R-SVC-02: 截图能力反射是否失败(失败时提示用户使用系统截图)。 */
    suspend fun screenshotCapabilityFailed(): Boolean =
        MuseAccessibilityService.instance?.isScreenshotCapabilityFailed() ?: false

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
