package io.zer0.muse.automation

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.automation.tools.AutomationTools
import io.zer0.muse.terminal.TermuxChannel
import io.zer0.muse.tools.ToolPermissionStatus
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.system.ShizukuAuthorizer
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
            val authorizer =
                runCatching {
                    org.koin.java.KoinJavaComponent.get<ShizukuAuthorizer>(ShizukuAuthorizer::class.java)
                }.getOrElse { ShizukuAuthorizer(appContext) }
            val mgr =
                AutomationManager(
                    context = appContext,
                    shizukuAuthorizer = authorizer,
                )
            _manager = mgr

            // v2.x: 工具权限分层 — 注入运行环境授权状态提供器(find_tools 状态展示 + 执行前预检)
            toolRegistry.permissionStatusProvider = {
                // v2.7: 用实时快照（每次预检重新探测无障碍）—— 无障碍服务异步连接，
                // 缓存快照会停在启动时的 false，导致工具误判“未授权”。
                val state = mgr.liveSnapshot()
                ToolPermissionStatus(
                    accessibility = state.accessibilityEnabled,
                    shellTier = state.shellEnabled || state.rootEnabled,
                    termux = TermuxChannel.get(appContext).availability() is TermuxChannel.Availability.Ready,
                )
            }

            // 注册 UI 自动化工具集
            val workflowJournal = runCatching {
                org.koin.java.KoinJavaComponent.get<io.zer0.muse.tools.WorkflowJournal>(
                    io.zer0.muse.tools.WorkflowJournal::class.java,
                )
            }.getOrNull()
            val virtualDisplayManager = runCatching {
                io.zer0.muse.automation.vdisplay.VirtualDisplayServerManager(appContext, mgr)
            }.onFailure { Logger.w(TAG, "VirtualDisplay manager init failed: ${it.message}") }
                .getOrNull()
            val virtualDisplayClient = virtualDisplayManager?.let { manager ->
                io.zer0.muse.automation.vdisplay.VirtualDisplayClient(appContext, manager)
            }
            val nodeScriptExecutor: suspend (String, Long) -> io.zer0.muse.tools.script.SkillEngineResult = { code, timeoutMs ->
                io.zer0.muse.tools.NodeScriptTool.evaluate(appContext, code, timeoutMs)
            }
            val tools = AutomationTools(mgr, workflowJournal, virtualDisplayClient, virtualDisplayManager, nodeScriptExecutor)
            tools.register(toolRegistry)

            // 注册 Root 级别工具(分层执行:Shizuku 优先、Root 兜底,注册本身无副作用)
            try {
                io.zer0.muse.tools.RootToolsRegistrar(toolRegistry, mgr, appContext)
            } catch (e: Exception) {
                Logger.w(TAG, "RootToolsRegistrar failed: ${e.message}")
            }

            // v2.2.1: GUI Agent 环工具(视觉驱动多步操作;VisionBridge 由 Koin 提供,缺失则跳过注册)
            try {
                val visionBridge =
                    org.koin.java.KoinJavaComponent.get<io.zer0.muse.vision.VisionBridge>(
                        io.zer0.muse.vision.VisionBridge::class.java,
                    )
                io.zer0.muse.automation.agent.UiAgentTool(
                    io.zer0.muse.automation.agent.UiAgentRunner(mgr, visionBridge, virtualDisplayClient, virtualDisplayManager),
                ).register(toolRegistry)
            } catch (e: Exception) {
                Logger.w(TAG, "UiAgent tool registration failed: ${e.message}")
            }

            // v2.2.1: 虚拟屏工具(后台隐藏屏;Shizuku 或 Root 通道,注册本身无副作用)
            if (virtualDisplayManager != null && virtualDisplayClient != null) {
                try {
                    io.zer0.muse.automation.vdisplay.VirtualDisplayTool(
                        appContext,
                        virtualDisplayClient,
                        virtualDisplayManager,
                        mgr,
                    ).register(toolRegistry)
                } catch (e: Exception) {
                    Logger.w(TAG, "VirtualDisplay tool registration failed: ${e.message}")
                }
            }

            // v2.x Agent 化: 应用控制工具集(app_list/launch/settings/force_stop/clear_data/uninstall)。
            // "列出/启动/打开设置页"只需应用自身权限(无自动化授权也能用),破坏性动作统一走
            // execTiered(Shizuku 优先、Root 兜底);缺通道时如实回报需要哪一项能力。
            try {
                io.zer0.muse.automation.appcontrol.AppControlTools(
                    context = appContext,
                    controller = io.zer0.muse.automation.appcontrol.AndroidAppController(appContext, mgr),
                ).register(toolRegistry)
            } catch (e: Exception) {
                Logger.w(TAG, "AppControl tools registration failed: ${e.message}")
            }

            // 异步刷新权限状态(不阻塞 App 启动)
            @Suppress("DEPRECATION")
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    tools.initialize()
                }.onFailure { Logger.w(TAG, "permission refresh failed: ${it.message}") }
            }

            // v2.7: 无障碍服务连接/断开时主动刷新权限快照。
            // 无障碍是异步连接的：用户开启开关后服务才 onServiceConnected；
            // 若不刷新，启动时探测的缓存会一直停在 false，
            // 导致工具预检误判“未授权”（向导读系统设置却能正确显示已开启）。
            io.zer0.muse.accessibility.MuseAccessibilityService.addConnectionListener { connected ->
                @Suppress("DEPRECATION")
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { mgr.refreshPermissions() }
                        .onFailure { Logger.w(TAG, "a11y 连接($connected)后刷新权限失败: ${it.message}") }
                }
            }

            Logger.i(TAG, "UI automation module initialized (3-tier: a11y/shell/root)")
        }
    }
}
