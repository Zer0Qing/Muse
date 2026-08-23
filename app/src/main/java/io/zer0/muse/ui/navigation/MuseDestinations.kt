package io.zer0.muse.ui.navigation

import kotlinx.serialization.Serializable

/**
 * 类型安全路由目的地。
 *
 * 从 [MuseRoutes] 字符串路由逐步迁移；每迁移一个域就在此定义对应
 * @Serializable 目的地，并同步更新 NavGraph 与 navigate 调用。
 */
@Serializable
data object ToolsScreenRoute

@Serializable
data object VideoGenerationRoute

@Serializable
data object VoiceCloningRoute

@Serializable
data object StatsRoute

@Serializable
data object NotificationListenerRoute

@Serializable
data object DataManagementRoute

@Serializable
data object KnowledgeRoute

@Serializable
data object CoverManagerRoute

@Serializable
data object SettingsRoute
@Serializable
data object AccountRoute
@Serializable
data object SettingsModelRoute
@Serializable
data object SettingsWebSearchRoute
@Serializable
data object SettingsAsrRoute
@Serializable
data object SettingsImageGenRoute
@Serializable
data object SettingsVideoGenRoute
@Serializable
data object SettingsMcpRoute
@Serializable
data object SettingsAssistantResourcesRoute
@Serializable
data object UserProfileEditRoute
@Serializable
data object SettingsDataRoute
@Serializable
data object SettingsCloudBackupRoute
@Serializable
data object SettingsAppearanceRoute
@Serializable
data object SettingsAboutRoute
@Serializable
data object SettingsChatRoute
@Serializable
data object ToolsSettingsRoute
@Serializable
data object SettingsMemoryRoute
@Serializable
data object SettingsMemoryConfigRoute
@Serializable
data object SettingsMemorySpaceRoute
@Serializable
data object SettingsMediaRoute
@Serializable
data object SettingsExperimentsRoute
@Serializable
data object SettingsRagRoute
@Serializable
data object SettingsVisionRoute
@Serializable
data object SettingsDataImportRoute
@Serializable
data object SettingsTutorialRoute
@Serializable
data object SettingsSecurityRoute
@Serializable
data object SettingsProxyRoute
@Serializable
data object SettingsMultiAgentRoute
@Serializable
data object SettingsAgentRoute
 data object SettingsProactiveRoute
@Serializable
data object LicensesRoute
@Serializable
data object DebugRoute
@Serializable
data object AuditLogRoute
@Serializable
data object WorkspaceRoute
@Serializable
data object PluginManageRoute
@Serializable
data object KnowledgeBaseManageRoute

@Serializable
data object SettingsTaskRoutingRoute

@Serializable
data object SettingsPermissionWizardRoute




@Serializable
data object AssistantsRoute

@Serializable
data object MemoryRoute

@Serializable
data object FavoritesRoute

@Serializable
data object LorebooksRoute

@Serializable
data object WorldbookRoute

@Serializable
data object QuickMessagesRoute

@Serializable
data object PromptInjectionsRoute

@Serializable
data object SkillsRoute

@Serializable
data class AssistantDetailRoute(val assistantId: String)

@Serializable
data class AssistantBasicRoute(val assistantId: String)

@Serializable
data class AssistantPromptRoute(val assistantId: String)

@Serializable
data class AssistantExtensionsRoute(val assistantId: String)

@Serializable
data class AssistantMemoryRoute(val assistantId: String)

@Serializable
data class AssistantAdvancedRoute(val assistantId: String)

@Serializable
data object HomeRoute

@Serializable
data object SearchRoute

@Serializable
data object MiniPhoneRoute

/** v1.0.74: 小手机内子页 — AI 相册 / 天气 / 日记本。 */
@Serializable
data object MiniAlbumRoute

@Serializable
data object MiniWeatherRoute

@Serializable
data object MiniDiaryRoute

/** v1.0.74: 小手机设置页(总开关)。 */
@Serializable
data object SettingsMiniPhoneRoute

@Serializable
data object ChatDetailRoute

@Serializable
data object PromptTemplateManagerRoute

@Serializable
data class GroupChatDetailRoute(val chatId: String)

@Serializable
data object ScheduledTasksRoute

/** 通知点击后打开指定任务并自动展开执行历史。 */
@Serializable
data class ScheduledTaskRoute(val taskId: String)

@Serializable
data object QuickNotesRoute

/** 通知点击后打开指定快速记录并自动展开正文。 */
@Serializable
data class QuickNoteRoute(val noteId: String)

@Serializable
data object AgentDmRoute

@Serializable
data object MilestonesRoute

@Serializable
data object TranslateRoute

@Serializable
data object RecentlyDeletedRoute
@Serializable
data object ArchivedChatsRoute

@Serializable
data class HtmlPreviewRoute(val html: String)
