package io.zer0.muse.tools.system

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import io.zer0.common.Logger
import io.zer0.muse.accessibility.IAccessibilityProvider
import io.zer0.muse.automation.executors.MuseAccessibilityService

/**
 * P3-3: 无障碍服务客户端 — 双路绑定(独立 Provider 优先,应用内服务兜底)。
 *
 * v2.2.1 起无障碍服务支持两种承载方式:
 *  1. **独立 Provider APK**(io.zer0.muse.a11y,签名级 AIDL 桥):
 *     - 无障碍服务组件脱离主应用生命周期,主应用更新/重装不打断授权;
 *     - 通过 bindService 绑定其 [A11yBridgeService] 获取 [IAccessibilityProvider] 代理;
 *  2. **应用内服务**(MuseAccessibilityService,静态实例):
 *     - 旧版授权路径,未安装 Provider 时兜底;已授权用户无感继续使用。
 *
 * 调用路由([withProvider]):Provider 代理可用且已授权 → 走跨进程 AIDL;
 * 否则回落到应用内静态实例。任一来源失败均返回安全默认值(false / ""),不抛异常。
 *
 * 职责:
 *  1. 检查无障碍服务是否已在系统设置中启用([isEnabled],涵盖两份授权记录)
 *  2. 暴露 UI 操作 API(getPageInfo/click/swipe/setText/screenshot 等)
 *  3. 引导用户前往 [openAccessibilitySettings] 启用服务
 *
 * 调用前提:
 *  - 用户必须在「设置 → 无障碍」中授权启用对应服务(独立 Provider 或应用内服务)
 *  - 未授权或服务未运行时,所有 UI 操作返回安全默认值
 */
class AccessibilityClient(private val context: Context) {
    companion object {
        private const val TAG = "AccessibilityClient"

        /** 主 App 清单中注册的稳定服务类名,用于匹配系统授权记录。 */
        val SERVICE_CLASS_NAME: String = MuseAccessibilityService::class.java.name

        /** v2.2.1: 无障碍独立 Provider 的包名(发布/安装检测与包可见性共用)。 */
        const val PROVIDER_PACKAGE = "io.zer0.muse.a11y"

        /** v2.2.1: 独立 Provider 的 AIDL 桥接服务组件(签名级权限护栏)。 */
        const val PROVIDER_BRIDGE_CLASS = "io.zer0.muse.a11y.A11yBridgeService"

        /** v2.2.1: 独立 Provider 的无障碍服务组件(用于系统授权记录匹配)。 */
        const val PROVIDER_SERVICE_CLASS = "io.zer0.muse.a11y.A11yProviderAccessibilityService"

        /**
         * 判断系统返回的服务信息是否是目标无障碍服务。
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

    // ── 独立 Provider 绑定(v2.2.1) ────────────────────────────────────────────

    /** AIDL 代理(onServiceConnected 后置值;binder 死亡/断连时清空)。 */
    @Volatile private var providerProxy: IAccessibilityProvider? = null

    /** 是否已发起过绑定(避免重复 bindService;绑定失败后复位)。 */
    @Volatile private var providerBindRequested = false

    private val providerConnection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val proxy = IAccessibilityProvider.Stub.asInterface(service)
                providerProxy = proxy
                runCatching {
                    service?.linkToDeath(
                        android.os.IBinder.DeathRecipient {
                            providerProxy = null
                            providerBindRequested = false
                        },
                        0,
                    )
                }.onFailure { Logger.w(TAG, "监听 Provider binder 死亡失败: ${it.message}") }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                providerProxy = null
            }

            override fun onBindingDied(name: ComponentName?) {
                providerProxy = null
                providerBindRequested = false
            }
        }

    /** 独立 Provider APK 是否已安装(未装时仍可用应用内服务)。 */
    fun isProviderInstalled(): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(PROVIDER_PACKAGE, 0)
    }.isSuccess

    /** 发起一次 Provider 绑定(幂等;仅当 APK 已安装)。 */
    private fun ensureProviderBound() {
        if (providerBindRequested) return
        if (!isProviderInstalled()) return
        synchronized(this) {
            if (providerBindRequested) return
            val intent = Intent().apply { setClassName(PROVIDER_PACKAGE, PROVIDER_BRIDGE_CLASS) }
            val ok =
                runCatching {
                    context.bindService(intent, providerConnection, Context.BIND_AUTO_CREATE)
                }.onFailure {
                    Logger.w(TAG, "绑定独立 Provider 失败: ${it.message}")
                }.getOrDefault(false)
            if (ok) providerBindRequested = true
        }
    }

    // ── 状态查询 ──────────────────────────────────────────────────────────────

    /**
     * 无障碍服务是否已在系统设置中启用。
     * 独立 Provider 或应用内服务任一被授权即返回 true(迁移期两套授权并存)。
     */
    fun isEnabled(): Boolean = isServiceEnabledInSystem(context.packageName, SERVICE_CLASS_NAME) ||
        isServiceEnabledInSystem(PROVIDER_PACKAGE, PROVIDER_SERVICE_CLASS)

    private fun isServiceEnabledInSystem(packageName: String, className: String): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val managerResult =
            runCatching {
                am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    ?.any { info ->
                        matchesServiceInfo(info.resolveInfo?.serviceInfo, packageName, className)
                    } == true
            }.onFailure {
                // 部分定制 ROM 对无障碍服务列表访问会抛异常,继续走 Secure 设置回退。
                Logger.w(TAG, "读取系统无障碍服务列表失败: ${it.message}")
            }.getOrDefault(false)
        if (managerResult) return true

        val rawEnabledServices =
            runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )
            }.onFailure {
                Logger.w(TAG, "读取已启用无障碍服务设置失败: ${it.message}")
            }.getOrNull()
        return containsEnabledService(rawEnabledServices, packageName, className)
    }

    /** 当前是否已连接(独立 Provider 或应用内服务的任一实例可用)。 */
    fun isConnected(): Boolean {
        ensureProviderBound()
        val remoteConnected =
            providerProxy?.let { proxy ->
                runCatching { proxy.isAccessibilityServiceEnabled() }.getOrDefault(false)
            } == true
        return remoteConnected || MuseAccessibilityService.instance?.isAccessibilityServiceEnabled() == true
    }

    // ── UI 操作 API(双路路由,失败返回安全默认值) ────────────────────────────

    suspend fun getPageInfo(): String = withProvider(defaultOnError = "") { it.getUiHierarchy() }

    suspend fun click(x: Int, y: Int): Boolean = withProvider(defaultOnError = false) { it.performClick(x, y) }

    suspend fun longPress(x: Int, y: Int): Boolean = withProvider(defaultOnError = false) { it.performLongPress(x, y) }

    suspend fun globalAction(actionId: Int): Boolean = withProvider(defaultOnError = false) { it.execGlobalAction(actionId) }

    suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long): Boolean =
        withProvider(defaultOnError = false) { it.performSwipe(startX, startY, endX, endY, duration) }

    suspend fun findFocusedNodeId(): String = withProvider(defaultOnError = "") { it.findFocusedNodeId() }

    suspend fun setText(nodeId: String, text: String): Boolean = withProvider(defaultOnError = false) { it.setTextOnNode(nodeId, text) }

    suspend fun screenshot(path: String, format: String = "PNG"): Boolean =
        withProvider(defaultOnError = false) { it.takeScreenshot(path, format) }

    /**
     * v2.6.13: 当前服务是否真的拿到了截图能力。
     *
     * 旧实现叫 screenshotCapabilityFailed()，靠“反射写隐藏字段是否抛错”反推；
     * 那个字段在 AOSP 里不存在，所以它永远返回 true，而工具层看到的就是“无法截屏”。
     * 现在直接问系统上报的 capability 位。
     *
     * Provider 路径（独立进程）拿不到本地 serviceInfo，按可用处理；真调用失败会返回错误码。
     */
    suspend fun canTakeScreenshot(): Boolean = MuseAccessibilityService.instance?.canTakeScreenshot() ?: true

    suspend fun currentActivityName(): String = withProvider(defaultOnError = "") { it.getCurrentActivityName() }

    // ── 内部工具 ──────────────────────────────────────────────────────────────

    /**
     * 双路路由调用:
     *  1. 独立 Provider 代理可用且其服务已连接 → 跨进程 AIDL 调用;
     *  2. 否则回落应用内静态实例;
     *  3. 均不可用或调用异常时返回 [defaultOnError](不抛出,保证工具链稳定)。
     */
    private suspend fun <T> withProvider(defaultOnError: T, block: (A11yOps) -> T): T {
        ensureProviderBound()
        providerProxy?.let { proxy ->
            val remoteEnabled = runCatching { proxy.isAccessibilityServiceEnabled() }.getOrDefault(false)
            if (remoteEnabled) {
                val result = runCatching { block(RemoteOps(proxy)) }
                result.getOrNull()?.let { return it }
                // binder 死亡/远程异常:清空以便下次重绑,回落应用内服务
                providerProxy = null
                providerBindRequested = false
                Logger.w(TAG, "Provider 调用失败,回落应用内服务: ${result.exceptionOrNull()?.message}")
            }
        }

        val service = MuseAccessibilityService.instance ?: return defaultOnError
        return runCatching { block(LocalOps(service)) }.getOrElse { e ->
            Logger.w(TAG, "服务调用失败: ${e.message}")
            defaultOnError
        }
    }

    /** 内部操作面 — 屏蔽「独立 Provider(AIDL 代理)」与「应用内服务(静态实例)」差异。 */
    internal interface A11yOps {
        fun isAccessibilityServiceEnabled(): Boolean

        fun getCurrentActivityName(): String

        fun getUiHierarchy(): String

        fun performClick(x: Int, y: Int): Boolean

        fun performLongPress(x: Int, y: Int): Boolean

        fun execGlobalAction(actionId: Int): Boolean

        fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long): Boolean

        fun findFocusedNodeId(): String

        fun setTextOnNode(nodeId: String, text: String): Boolean

        fun takeScreenshot(path: String, format: String): Boolean
    }

    /** 应用内服务实现(同进程静态实例)。 */
    private class LocalOps(private val service: MuseAccessibilityService) : A11yOps {
        override fun isAccessibilityServiceEnabled(): Boolean = service.isAccessibilityServiceEnabled()

        override fun getCurrentActivityName(): String = service.getCurrentActivityName()

        override fun getUiHierarchy(): String = service.getUiHierarchy()

        override fun performClick(x: Int, y: Int): Boolean = service.performClick(x, y)

        override fun performLongPress(x: Int, y: Int): Boolean = service.performLongPress(x, y)

        override fun execGlobalAction(actionId: Int): Boolean = service.execGlobalAction(actionId)

        override fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long): Boolean =
            service.performSwipe(startX, startY, endX, endY, duration)

        override fun findFocusedNodeId(): String = service.findFocusedNodeId()

        override fun setTextOnNode(nodeId: String, text: String): Boolean = service.setTextOnNode(nodeId, text)

        override fun takeScreenshot(path: String, format: String): Boolean = service.takeScreenshot(path, format)
    }

    /** 独立 Provider 实现(跨进程 AIDL;performGlobalAction ←→ execGlobalAction 名称映射)。 */
    private class RemoteOps(private val provider: IAccessibilityProvider) : A11yOps {
        override fun isAccessibilityServiceEnabled(): Boolean = provider.isAccessibilityServiceEnabled

        override fun getCurrentActivityName(): String = provider.currentActivityName

        override fun getUiHierarchy(): String = provider.uiHierarchy

        override fun performClick(x: Int, y: Int): Boolean = provider.performClick(x, y)

        override fun performLongPress(x: Int, y: Int): Boolean = provider.performLongPress(x, y)

        override fun execGlobalAction(actionId: Int): Boolean = provider.performGlobalAction(actionId)

        override fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long): Boolean =
            provider.performSwipe(startX, startY, endX, endY, duration)

        override fun findFocusedNodeId(): String = provider.findFocusedNodeId()

        override fun setTextOnNode(nodeId: String, text: String): Boolean = provider.setTextOnNode(nodeId, text)

        override fun takeScreenshot(path: String, format: String): Boolean = provider.takeScreenshot(path, format)
    }

    /** 打开系统无障碍设置页(引导用户启用服务)。 */
    fun openAccessibilitySettings() {
        val intent =
            android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
    }
}
