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
import io.zer0.muse.ui.settings.MemorySettingsPage
import io.zer0.muse.ui.settings.MultiAgentSettingsPage
import io.zer0.muse.ui.settings.ProviderPluginPage
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
import io.zer0.muse.ui.settings.UserProfileEditPage
import io.zer0.muse.ui.settings.VisionSettingsPage
import io.zer0.muse.ui.workflow.WorkflowEditorScreen

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
    composable(
        route = MuseRoutes.SETTINGS,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsScreen(
            onBack = { navController.popBackStack() },
            onOpenAssistants = { navController.navigate(MuseRoutes.ASSISTANTS) },
            onOpenAccount = { navController.navigate(MuseRoutes.ACCOUNT) },
            onOpenModelSettings = { navController.navigate(MuseRoutes.SETTINGS_MODEL) },
            onOpenDataSettings = { navController.navigate(MuseRoutes.SETTINGS_DATA) },
            onOpenAppearanceSettings = { navController.navigate(MuseRoutes.SETTINGS_APPEARANCE) },
            onOpenChatSettings = { navController.navigate(MuseRoutes.SETTINGS_CHAT) },
            onOpenMemorySettings = { navController.navigate(MuseRoutes.SETTINGS_MEMORY) },
            onOpenMediaSettings = { navController.navigate(MuseRoutes.SETTINGS_MEDIA) },
            onOpenExperimentsSettings = { navController.navigate(MuseRoutes.SETTINGS_EXPERIMENTS) },
            onOpenSecuritySettings = { navController.navigate(MuseRoutes.SETTINGS_SECURITY) },
            onOpenProxySettings = { navController.navigate(MuseRoutes.SETTINGS_PROXY) },
            onOpenMultiAgentSettings = { navController.navigate(MuseRoutes.SETTINGS_MULTI_AGENT) },
            onOpenAgentSettings = { navController.navigate(MuseRoutes.SETTINGS_AGENT) },
            onOpenAboutSettings = { navController.navigate(MuseRoutes.SETTINGS_ABOUT) },
            onOpenStats = { navController.navigate(MuseRoutes.STATS) },
            onOpenNotificationListener = { navController.navigate(MuseRoutes.NOTIFICATION_LISTENER) },
            onOpenTools = { navController.navigate(MuseRoutes.TOOLS) },
            onOpenRagSettings = { navController.navigate(MuseRoutes.SETTINGS_RAG) },
            onOpenVisionSettings = { navController.navigate(MuseRoutes.SETTINGS_VISION) },
            onOpenDataImport = { navController.navigate(MuseRoutes.SETTINGS_DATA_IMPORT) },
            onOpenTutorial = { navController.navigate(MuseRoutes.SETTINGS_TUTORIAL) },
            onOpenUserProfile = { navController.navigate(MuseRoutes.USER_PROFILE_EDIT) },
            onOpenTranslate = { navController.navigate(MuseRoutes.TRANSLATE) },
            onOpenDataManagement = { navController.navigate(MuseRoutes.DATA_MANAGEMENT) },
            onOpenDebugLog = { navController.navigate(MuseRoutes.DEBUG) },
            onOpenAuditLog = { navController.navigate(MuseRoutes.AUDIT_LOG) },
            onOpenWorkspace = { navController.navigate(MuseRoutes.WORKSPACE) },
            onOpenVideoGeneration = { navController.navigate(MuseRoutes.VIDEO_GENERATION) },
            onOpenProviderPlugins = { navController.navigate(MuseRoutes.PROVIDER_PLUGINS) },
            // v1.133: 从 SettingsModelPage 拆出的 5 个独立二级页
            onOpenWebSearch = { navController.navigate(MuseRoutes.SETTINGS_WEB_SEARCH) },
            onOpenAsr = { navController.navigate(MuseRoutes.SETTINGS_ASR) },
            onOpenImageGen = { navController.navigate(MuseRoutes.SETTINGS_IMAGE_GEN) },
            onOpenVideoGenSettings = { navController.navigate(MuseRoutes.SETTINGS_VIDEO_GEN) },
            onOpenMcp = { navController.navigate(MuseRoutes.SETTINGS_MCP) },
            onOpenAssistantResources = { navController.navigate(MuseRoutes.SETTINGS_ASSISTANT_RESOURCES) },
            onNavigate = { route -> navController.navigate(route) },
        )
    }
    // v0.25: 账户中心(占位登录页)
    composable(
        route = MuseRoutes.ACCOUNT,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        AccountScreen(
            onBack = { navController.popBackStack() },
            onOpenUserProfile = { navController.navigate(MuseRoutes.USER_PROFILE_EDIT) },
        )
    }
    // v0.26: 设置二级页 — 模型与服务(v1.133: 仅供应商列表,其他拆为独立二级页)
    composable(
        route = MuseRoutes.SETTINGS_MODEL,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsModelPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.133: 设置二级页 — 联网搜索(从 SettingsModelPage 拆出)
    composable(
        route = MuseRoutes.SETTINGS_WEB_SEARCH,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsWebSearchPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.133: 设置二级页 — 语音识别 ASR(从 SettingsModelPage 拆出)
    composable(
        route = MuseRoutes.SETTINGS_ASR,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsAsrPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.133: 设置二级页 — 图像生成(从 SettingsModelPage 拆出)
    composable(
        route = MuseRoutes.SETTINGS_IMAGE_GEN,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsImageGenPage(
            onBack = { navController.popBackStack() },
        )
    }
    // 设置二级页 — 视频生成(默认供应商/模型/时长/分辨率配置)
    composable(
        route = MuseRoutes.SETTINGS_VIDEO_GEN,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsVideoGenPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.133: 设置二级页 — MCP 服务器(从 SettingsModelPage 拆出)
    composable(
        route = MuseRoutes.SETTINGS_MCP,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsMcpPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.133: 设置二级页 — 助手资源(从 SettingsModelPage 拆出:收藏夹/世界书/快捷消息/模式注入/Skills/记忆开关)
    composable(
        route = MuseRoutes.SETTINGS_ASSISTANT_RESOURCES,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsAssistantResourcesPage(
            onBack = { navController.popBackStack() },
            onOpenAssistants = { navController.navigate(MuseRoutes.ASSISTANTS) },
            onOpenFavorites = { navController.navigate(MuseRoutes.FAVORITES) },
            onOpenLorebooks = { navController.navigate(MuseRoutes.LOREBOOKS) },
            onOpenQuickMessages = { navController.navigate(MuseRoutes.QUICK_MESSAGES) },
            onOpenPromptInjections = { navController.navigate(MuseRoutes.PROMPT_INJECTIONS) },
            onOpenSkills = { navController.navigate(MuseRoutes.SKILLS) },
        )
    }
    // 用户画像编辑页(年龄/城市/MBTI 等)
    composable(
        route = MuseRoutes.USER_PROFILE_EDIT,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        UserProfileEditPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v0.26: 设置二级页 — 数据与备份
    composable(
        route = MuseRoutes.SETTINGS_DATA,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsDataPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.132: 设置二级页 — 云备份独立配置页(WebDAV/S3 表单 + 远端备份列表)
    composable(
        route = MuseRoutes.SETTINGS_CLOUD_BACKUP,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        CloudBackupPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v0.26: 设置二级页 — 外观
    composable(
        route = MuseRoutes.SETTINGS_APPEARANCE,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsAppearancePage(
            onBack = { navController.popBackStack() },
        )
    }
    // v0.26: 设置二级页 — 关于
    composable(
        route = MuseRoutes.SETTINGS_ABOUT,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsAboutPage(
            onBack = { navController.popBackStack() },
            onOpenLicenses = { navController.navigate(MuseRoutes.LICENSES) },
        )
    }
    // v0.31: 设置二级页 — 聊天行为
    composable(
        route = MuseRoutes.SETTINGS_CHAT,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        ChatSettingsPage(
            onBack = { navController.popBackStack() },
            onOpenToolsSettings = { navController.navigate(MuseRoutes.TOOLS_SETTINGS) },
        )
    }
    // v0.32: 设置二级页 — 记忆与通知
    composable(
        route = MuseRoutes.SETTINGS_MEMORY,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        MemorySettingsPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v0.32: 设置二级页 — 媒体
    composable(
        route = MuseRoutes.SETTINGS_MEDIA,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        MediaSettingsPage(
            onBack = { navController.popBackStack() },
            onOpenVoiceCloning = { navController.navigate(MuseRoutes.VOICE_CLONING) },
        )
    }
    // v0.32: 设置二级页 — 实验性
    composable(
        route = MuseRoutes.SETTINGS_EXPERIMENTS,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        ExperimentsSettingsPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.56: 设置二级页 — RAG 知识库检索配置
    composable(
        route = MuseRoutes.SETTINGS_RAG,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        RagSettingsPage(
            onBack = { navController.popBackStack() },
            onManageKbs = { navController.navigate(MuseRoutes.KB_MANAGE) },
        )
    }
    // v1.133: 三级页 — 多知识库管理
    composable(
        route = MuseRoutes.KB_MANAGE,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        KnowledgeBaseManagePage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.25: 设置二级页 — 视觉辅助
    composable(
        route = MuseRoutes.SETTINGS_VISION,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        VisionSettingsPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.61: 设置二级页 — 数据导入
    composable(
        route = MuseRoutes.SETTINGS_DATA_IMPORT,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsDataImportPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.61: 设置二级页 — 使用教程(新手引导)
    composable(
        route = MuseRoutes.SETTINGS_TUTORIAL,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsTutorialPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v0.32: 设置二级页 — 安全与分享
    composable(
        route = MuseRoutes.SETTINGS_SECURITY,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SecuritySettingsPage(
            onBack = { navController.popBackStack() },
        )
    }
    // 设置二级页 — 网络代理
    composable(
        route = MuseRoutes.SETTINGS_PROXY,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        ProxySettingsPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.25: 设置二级页 — 多 Agent 协作
    composable(
        route = MuseRoutes.SETTINGS_MULTI_AGENT,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        MultiAgentSettingsPage(
            onBack = { navController.popBackStack() },
            onOpenWorkflowEditor = { teamId ->
                navController.navigate(MuseRoutes.workflowEditorRoute(teamId))
            },
        )
    }
    // Multi-Agent 工作流可视化编排页(带 teamId 参数)
    composable(
        route = MuseRoutes.WORKFLOW_EDITOR + "/{teamId}",
        arguments = listOf(navArgument("teamId") { type = NavType.StringType }),
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val teamId = backStackEntry.arguments?.getString("teamId").orEmpty()
        WorkflowEditorScreen(
            teamId = teamId,
            onBack = { navController.popBackStack() },
        )
    }
    // v1.27: 设置二级页 — Agent 配置(助手选择/协作/主动消息)
    composable(
        route = MuseRoutes.SETTINGS_AGENT,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        AgentSettingsPage(
            onBack = { navController.popBackStack() },
            onOpenMultiAgentSettings = { navController.navigate(MuseRoutes.SETTINGS_MULTI_AGENT) },
            onOpenAgentDm = { navController.navigate(MuseRoutes.AGENT_DM) },
        )
    }
    // v1.25: 开源许可页 — 修复 LICENSES 路由断链
    composable(
        route = MuseRoutes.LICENSES,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        LicensesScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // 调试日志页 — 从设置 → 关于 → 调试日志 进入,展示最近 Logger 调用
    composable(
        route = MuseRoutes.DEBUG,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        DebugScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // P2-4: 审计日志页 — 从设置 → 数据与隐私 → 审计日志 进入
    composable(
        route = MuseRoutes.AUDIT_LOG,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        AuditLogPage(
            onBack = { navController.popBackStack() },
        )
    }
    // P2-7: 工作区页 — 从设置 → 数据与隐私 → 工作区 进入
    composable(
        route = MuseRoutes.WORKSPACE,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        WorkspaceScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // P2-10: Provider 插件管理页 — 从设置 → 模型与服务 → 插件管理 进入
    composable(
        route = MuseRoutes.PROVIDER_PLUGINS,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        ProviderPluginPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.0.20: 工具批准管理页 — 从设置 → 聊天 → 工具调用批准 进入
    // 按风险等级分组展示所有工具,每个工具可设置三档策略(ALWAYS_ALLOW / ASK_EVERY_TIME / ALWAYS_DENY)
    composable(
        route = MuseRoutes.TOOLS_SETTINGS,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        ToolsSettingsPage(
            onBack = { navController.popBackStack() },
        )
    }
}
