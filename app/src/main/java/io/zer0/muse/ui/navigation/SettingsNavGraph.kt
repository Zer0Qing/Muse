package io.zer0.muse.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.zer0.muse.ui.DebugScreen
import io.zer0.muse.ui.MuseRoutes
import io.zer0.muse.ui.SettingsScreen
import io.zer0.muse.ui.WorkspaceScreen
import io.zer0.muse.ui.account.AccountScreen
import io.zer0.muse.ui.knowledge.KnowledgeBaseManagePage
import io.zer0.muse.license.LicensesScreen
import io.zer0.muse.ui.settings.AgentSettingsPage
import io.zer0.muse.ui.settings.AuditLogPage
import io.zer0.muse.ui.settings.ChatSettingsPage
import io.zer0.muse.ui.settings.CloudBackupPage
import io.zer0.muse.ui.settings.ExperimentsSettingsPage
import io.zer0.muse.ui.settings.MediaSettingsPage
import io.zer0.muse.ui.MemoryScreen
import io.zer0.muse.ui.settings.MemorySettingsPage
import io.zer0.muse.ui.settings.MultiAgentSettingsPage
import io.zer0.muse.ui.settings.PluginManagePage
import io.zer0.muse.ui.settings.ProxySettingsPage
import io.zer0.muse.ui.settings.RagSettingsPage
import io.zer0.muse.ui.settings.SecuritySettingsPage
import io.zer0.muse.ui.settings.SettingsAboutPage
import io.zer0.muse.ui.settings.SettingsAppearancePage
import io.zer0.muse.ui.settings.SettingsAsrPage
import io.zer0.muse.ui.settings.SettingsAssistantResourcesPage
import io.zer0.muse.ui.settings.SettingsDataImportPage
import io.zer0.muse.ui.settings.SettingsDataPage
import io.zer0.muse.ui.settings.SettingsImageGenPage
import io.zer0.muse.ui.settings.SettingsMcpPage
import io.zer0.muse.ui.settings.SettingsModelPage
import io.zer0.muse.ui.settings.SettingsTutorialPage
import io.zer0.muse.ui.settings.SettingsVideoGenPage
import io.zer0.muse.ui.settings.SettingsWebSearchPage
import io.zer0.muse.ui.settings.ToolsSettingsPage
import io.zer0.muse.ui.settings.TaskRoutingSettingsPage
import io.zer0.muse.ui.settings.UserProfileEditPage
import io.zer0.muse.ui.settings.PermissionWizardScreen
import io.zer0.muse.ui.settings.VisionSettingsPage
import androidx.navigation.toRoute

/**
 * 设置域 NavGraph — 包含设置主页 + 31 个二级/三级设置页(账户/模型/外观/代理/多 Agent/
 * 工作流/Agent/许可/调试/审计/工作区/Provider 插件等)共 32 个 composable。
 *
 * 从 MainActivity 抽取以解决后者过载问题(原 1804 行 → 目标 ≤ 800 行)。
 * 所有 composable 统一使用 [MuseTransitions.horizontalPushEnter] / [horizontalPushPopExit] 过渡。
 */
fun NavGraphBuilder.settingsNavGraph(
    navController: NavHostController,
) {
    // 设置页(slide-in)
    composable<SettingsRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsScreen(
            onBack = { navController.popBackStack() },
            onOpenAssistants = { navController.navigate(AssistantsRoute) },
            onOpenAccount = { navController.navigate(AccountRoute) },
            onOpenModelSettings = { navController.navigate(SettingsModelRoute) },
            onOpenDataSettings = { navController.navigate(SettingsDataRoute) },
            onOpenAppearanceSettings = { navController.navigate(SettingsAppearanceRoute) },
            onOpenChatSettings = { navController.navigate(SettingsChatRoute) },
            onOpenMemorySettings = { navController.navigate(SettingsMemoryRoute) },
            onOpenMediaSettings = { navController.navigate(SettingsMediaRoute) },
            onOpenExperimentsSettings = { navController.navigate(SettingsExperimentsRoute) },
            onOpenSecuritySettings = { navController.navigate(SettingsSecurityRoute) },
            onOpenProxySettings = { navController.navigate(SettingsProxyRoute) },
            onOpenMultiAgentSettings = { navController.navigate(SettingsMultiAgentRoute) },
            onOpenAgentSettings = { navController.navigate(SettingsAgentRoute) },
            onOpenAboutSettings = { navController.navigate(SettingsAboutRoute) },
            onOpenStats = { navController.navigate(StatsRoute) },
            onOpenNotificationListener = { navController.navigate(NotificationListenerRoute) },
            onOpenTools = { navController.navigate(ToolsScreenRoute) },
            onOpenRagSettings = { navController.navigate(SettingsRagRoute) },
            onOpenVisionSettings = { navController.navigate(SettingsVisionRoute) },
            onOpenDataImport = { navController.navigate(SettingsDataImportRoute) },
            onOpenTutorial = { navController.navigate(SettingsTutorialRoute) },
            onOpenUserProfile = { navController.navigate(UserProfileEditRoute) },
            onOpenTranslate = { navController.navigate(TranslateRoute) },
            onOpenDataManagement = { navController.navigate(DataManagementRoute) },
            onOpenDebugLog = { navController.navigate(DebugRoute) },
            onOpenAuditLog = { navController.navigate(AuditLogRoute) },
            onOpenWorkspace = { navController.navigate(WorkspaceRoute) },
            onOpenVideoGeneration = { navController.navigate(VideoGenerationRoute) },
            onOpenProviderPlugins = { navController.navigate(PluginManageRoute) },
            // v1.133: 从 SettingsModelPage 拆出的 5 个独立二级页
            onOpenWebSearch = { navController.navigate(SettingsWebSearchRoute) },
            onOpenAsr = { navController.navigate(SettingsAsrRoute) },
            onOpenImageGen = { navController.navigate(SettingsImageGenRoute) },
            onOpenVideoGenSettings = { navController.navigate(SettingsVideoGenRoute) },
            onOpenMcp = { navController.navigate(SettingsMcpRoute) },
            onOpenAssistantResources = { navController.navigate(SettingsAssistantResourcesRoute) },
            onNavigate = { route -> navController.navigate(route) },
        )
    }
    // v0.25: 账户中心(占位登录页)
    composable<AccountRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        AccountScreen(
            onBack = { navController.popBackStack() },
            onOpenUserProfile = { navController.navigate(UserProfileEditRoute) },
        )
    }
    // v0.26: 设置二级页 — 模型与服务(v1.133: 仅供应商列表,其他拆为独立二级页)
    composable<SettingsModelRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsModelPage(
            onBack = { navController.popBackStack() },
            onOpenAsr = { navController.navigate(MuseRoutes.SETTINGS_ASR) },
        )
    }
    // v1.133: 设置二级页 — 联网搜索(从 SettingsModelPage 拆出)
    // B0-04: 设置二级页 — 任务模型路由
    composable<SettingsTaskRoutingRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        TaskRoutingSettingsPage(
            onBack = { navController.popBackStack() },
        )
    }
    composable<SettingsWebSearchRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsWebSearchPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.133: 设置二级页 — 语音识别 ASR(从 SettingsModelPage 拆出)
    composable<SettingsAsrRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsAsrPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.133: 设置二级页 — 图像生成(从 SettingsModelPage 拆出)
    composable<SettingsImageGenRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsImageGenPage(
            onBack = { navController.popBackStack() },
        )
    }
    // 设置二级页 — 视频生成(默认供应商/模型/时长/分辨率配置)
    composable<SettingsVideoGenRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsVideoGenPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.133: 设置二级页 — MCP 服务器(从 SettingsModelPage 拆出)
    composable<SettingsMcpRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsMcpPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.133: 设置二级页 — 助手资源(从 SettingsModelPage 拆出:收藏夹/世界书/快捷消息/模式注入/Skills/记忆开关)
    composable<SettingsAssistantResourcesRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsAssistantResourcesPage(
            onBack = { navController.popBackStack() },
            onOpenAssistants = { navController.navigate(AssistantsRoute) },
            onOpenFavorites = { navController.navigate(FavoritesRoute) },
            onOpenLorebooks = { navController.navigate(LorebooksRoute) },
            onOpenWorldbook = { navController.navigate(WorldbookRoute) },
            onOpenQuickMessages = { navController.navigate(QuickMessagesRoute) },
            onOpenPromptInjections = { navController.navigate(PromptInjectionsRoute) },
            onOpenSkills = { navController.navigate(SkillsRoute) },
        )
    }
    // P3-3: 权限配置向导(无障碍 / Shizuku / Root 三通道)
    composable<SettingsPermissionWizardRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        PermissionWizardScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // 用户画像编辑页(年龄/城市/MBTI 等)
    composable<UserProfileEditRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        UserProfileEditPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v0.26: 设置二级页 — 数据与备份
    composable<SettingsDataRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsDataPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.132: 设置二级页 — 云备份独立配置页(WebDAV/S3 表单 + 远端备份列表)
    composable<SettingsCloudBackupRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        CloudBackupPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v0.26: 设置二级页 — 外观
    composable<SettingsAppearanceRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsAppearancePage(
            onBack = { navController.popBackStack() },
        )
    }
    // v0.26: 设置二级页 — 关于
    composable<SettingsAboutRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsAboutPage(
            onBack = { navController.popBackStack() },
            onOpenLicenses = { navController.navigate(LicensesRoute) },
        )
    }
    // v0.31: 设置二级页 — 聊天行为
    composable<SettingsChatRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        ChatSettingsPage(
            onBack = { navController.popBackStack() },
            onOpenToolsSettings = { navController.navigate(ToolsSettingsRoute) },
        )
    }
    // v1.0.51: 记忆中心 — 4 Tab 查看+编辑(当下/短期/长期/事实)
    composable<SettingsMemoryRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        MemoryScreen(
            onBack = { navController.popBackStack() },
            onOpenSettings = { navController.navigate(SettingsMemoryConfigRoute) },
        )
    }
    // v1.0.51: 记忆参数配置页(原"记忆与通知",从记忆中心齿轮入口进入)
    composable<SettingsMemoryConfigRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        MemorySettingsPage(
            onBack = { navController.popBackStack() },
            onOpenMemorySpace = { navController.navigate(SettingsMemorySpaceRoute) },
        )
    }
    // v1.0.52 P2-2: 记忆空间管理页(Space CRUD)
    composable<SettingsMemorySpaceRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        io.zer0.muse.ui.memory.MemorySpaceManageScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // v0.32: 设置二级页 — 媒体
    composable<SettingsMediaRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        MediaSettingsPage(
            onBack = { navController.popBackStack() },
            onOpenVoiceCloning = { navController.navigate(VoiceCloningRoute) },
        )
    }
    // v0.32: 设置二级页 — 实验性
    composable<SettingsExperimentsRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        ExperimentsSettingsPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.56: 设置二级页 — RAG 知识库检索配置
    composable<SettingsRagRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        RagSettingsPage(
            onBack = { navController.popBackStack() },
            onManageKbs = { navController.navigate(KnowledgeBaseManageRoute) },
        )
    }
    // v1.133: 三级页 — 多知识库管理
    composable<KnowledgeBaseManageRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        KnowledgeBaseManagePage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.25: 设置二级页 — 视觉辅助
    composable<SettingsVisionRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        VisionSettingsPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.61: 设置二级页 — 数据导入
    composable<SettingsDataImportRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsDataImportPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.61: 设置二级页 — 使用教程(新手引导)
    composable<SettingsTutorialRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsTutorialPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v0.32: 设置二级页 — 安全与分享
    composable<SettingsSecurityRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SecuritySettingsPage(
            onBack = { navController.popBackStack() },
        )
    }
    // 设置二级页 — 网络代理
    composable<SettingsProxyRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        ProxySettingsPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.25: 设置二级页 — 多 Agent 协作
    composable<SettingsMultiAgentRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        MultiAgentSettingsPage(
            onBack = { navController.popBackStack() },

        )
    }

    // v1.27: 设置二级页 — Agent 配置(助手选择/协作/主动消息)
    composable<SettingsAgentRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        AgentSettingsPage(
            onBack = { navController.popBackStack() },
            onOpenMultiAgentSettings = { navController.navigate(SettingsMultiAgentRoute) },
            onOpenAgentDm = { navController.navigate(AgentDmRoute) },
        )
    }
    // v1.25: 开源许可页 — 修复 LICENSES 路由断链
    composable<LicensesRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        LicensesScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // 调试日志页 — 从设置 → 关于 → 调试日志 进入,展示最近 Logger 调用
    composable<DebugRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        DebugScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // P2-4: 审计日志页 — 从设置 → 数据与隐私 → 审计日志 进入
    composable<AuditLogRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        AuditLogPage(
            onBack = { navController.popBackStack() },
        )
    }
    // P2-7: 工作区页 — 从设置 → 数据与隐私 → 工作区 进入
    composable<WorkspaceRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        WorkspaceScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // 统一插件管理页（外部插件 + Provider 插件合并），旧入口已收敛到 PluginManageRoute
    composable<PluginManageRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        PluginManagePage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.0.20: 工具批准管理页 — 从设置 → 聊天 → 工具调用批准 进入
    // 按风险等级分组展示所有工具,每个工具可设置三档策略(ALWAYS_ALLOW / ASK_EVERY_TIME / ALWAYS_DENY)
    composable<ToolsSettingsRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        ToolsSettingsPage(
            onBack = { navController.popBackStack() },
        )
    }
}
