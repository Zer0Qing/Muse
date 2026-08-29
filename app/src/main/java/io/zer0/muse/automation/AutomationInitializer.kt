package io.zer0.muse.automation

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.tools.system.ShizukuAuthorizer
import io.zer0.muse.automation.tools.AutomationTools
import io.zer0.muse.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * UI 自动化模块初始化入口。
 *
 * 主 App 在 [android.app.Application.onCreate] 中调用 [initialize] 完成:
 * 1. 创建 [AutomationManager] 单例
 * 2. 注册 [AutomationTools] 到 [ToolRegistry],让 AI 可以调用
 * 3. 异步刷新三层权限状态
 *
 * 使用方式:
 * ```
 * class MuseApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         AutomationInitializer.initialize(this, toolRegistry)
 *     }
 * }
 * ```
 */
object AutomationInitializer {

    private const val TAG = "AutomationInit"

    @Volatile
    private var _manager: AutomationManager? = null

    /** 全局 AutomationManager 单例。未初始化时抛异常。 */
    val manager: AutomationManager
        get() = _manager ?: error("AutomationInitializer not initialized. Call initialize() first.")

    /** 是否已初始化。 */
    val isInitialized: Boolean get() = _manager != null

    /**
     * 初始化自动化模块。
     * @param context Application context
     * @param toolRegistry 全局工具注册器(AI 工具调用入口)
     */
    fun initialize(context: Context, toolRegistry: ToolRegistry) {
        if (_manager != null) return
        synchronized(this) {
            if (_manager != null) return
            val appContext = context.applicationContext
            // 与主 Shell 路由共用同一 ShizukuAuthorizer，避免“状态检查”和“实际执行”各走一套。
            val authorizer = runCatching {
                org.koin.java.KoinJavaComponent.get<ShizukuAuthorizer>(ShizukuAuthorizer::class.java)
            }.getOrElse { ShizukuAuthorizer(appContext) }
            val mgr = AutomationManager(
                context = appContext,
                shizukuAuthorizer = authorizer,
            )
            _manager = mgr

            // 注册 UI 自动化工具集
            val tools = AutomationTools(mgr)
            tools.register(toolRegistry)

            // 异步刷新权限状态(不阻塞 App 启动)
            @Suppress("DEPRECATION")
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    tools.initialize()
                }.onFailure { Logger.w(TAG, "permission refresh failed: ${it.message}") }
            }

            Logger.i(TAG, "UI automation module initialized (3-tier: a11y/shell/root)")
        }
    }
}
