package io.zer0.muse.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import io.zer0.muse.R
import io.zer0.muse.data.ProxyConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseSearchBar
import io.zer0.muse.ui.common.form.MuseSettingsIcon
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseLargeTitleHeader
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.MuseDialogWindowEffect
import io.zer0.muse.ui.common.surface.museModalScrim
import io.zer0.muse.ui.common.surface.museSafeTopInsetPadding
import io.zer0.muse.ui.navigation.ChannelSettingsRoute
import io.zer0.muse.ui.navigation.KeepAliveSettingsRoute
import io.zer0.muse.ui.navigation.PluginManageRoute
import io.zer0.muse.ui.navigation.QuickNotesRoute
import io.zer0.muse.ui.navigation.ScheduledTasksRoute
import io.zer0.muse.ui.navigation.SettingsCloudBackupRoute
import io.zer0.muse.ui.navigation.SettingsMiniPhoneRoute
import io.zer0.muse.ui.navigation.SettingsPermissionWizardRoute
import io.zer0.muse.ui.navigation.SettingsTaskRoutingRoute
import io.zer0.muse.ui.navigation.VideoGenerationRoute
import io.zer0.muse.ui.navigation.VoiceCloningRoute
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.update.UpdateCheckResult
import io.zer0.muse.update.UpdateNotifier
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v2.4 设置页 — 全量重写。
 *
 * 保持 v1.132 的搜索索引与分组结构不变,仅重写视觉层:
 *  - 暖白背景(background),白色卡片浮于其上
 *  - MuseTopBar 大标题,右侧搜索入口
 *  - 搜索态顶部切换为圆角搜索框,结果以独立卡片呈现
 *  - 所有设置项统一使用 MuseSettingsIcon + CardGroup
 *  - 分组标题使用次级文字色,营造清晰层级
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAssistants: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onOpenModelSettings: () -> Unit = {},
    onOpenMultiAgentSettings: () -> Unit = {},
    onOpenAgentSettings: () -> Unit = {},
    onOpenProactiveSettings: () -> Unit = {},
    onOpenDataSettings: () -> Unit = {},
    onOpenAppearanceSettings: () -> Unit = {},
    onOpenChatSettings: () -> Unit = {},
    onOpenMemorySettings: () -> Unit = {},
    onOpenMemoryConfig: () -> Unit = {},
    onOpenMediaSettings: () -> Unit = {},
    onOpenExperimentsSettings: () -> Unit = {},
    onOpenSecuritySettings: () -> Unit = {},
    onOpenProxySettings: () -> Unit = {},
    onOpenAboutSettings: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    onOpenRagSettings: () -> Unit = {},
    onOpenDataImport: () -> Unit = {},
    onOpenTutorial: () -> Unit = {},
    onOpenUserProfile: () -> Unit = {},
    onOpenTranslate: () -> Unit = {},
    onOpenVisionSettings: () -> Unit = {},
    onOpenDataManagement: () -> Unit = {},
    onOpenDebugLog: () -> Unit = {},
    onOpenCrashReport: () -> Unit = {},
    onOpenComponentGallery: () -> Unit = {},
    onOpenAuditLog: () -> Unit = {},
    onOpenWorkspace: () -> Unit = {},
    onOpenConnectionCenter: () -> Unit = {},
    onOpenArchivedChats: () -> Unit = {},
    onOpenProviderPlugins: () -> Unit = {},
    onOpenWebSearch: () -> Unit = {},
    onOpenAsr: () -> Unit = {},
    onOpenMcp: () -> Unit = {},
    onOpenAssistantResources: () -> Unit = {},
    onOpenNotificationListener: () -> Unit = {},
    onOpenTools: () -> Unit = {},
    onNavigate: (Any) -> Unit = {},
) {
    val settings: SettingsRepository = koinInject()
    val updateNotifier: UpdateNotifier = koinInject()
    val proxyConfig by settings.proxyConfigFlow.collectAsStateWithLifecycle(initialValue = ProxyConfig())
    val piiGuardEnabled by settings.piiGuardEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val proxyDisabled = stringResource(R.string.proxy_disabled)
    val proxyTitle = stringResource(R.string.proxy_title)
    val proxySubtitle = when {
        !proxyConfig.enabled -> proxyDisabled
        proxyConfig.host.isBlank() || proxyConfig.port <= 0 -> proxyDisabled
        else -> "${proxyConfig.type} ${proxyConfig.host}:${proxyConfig.port}"
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var checkingUpdate by remember { mutableStateOf(false) }
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // region 搜索索引(与 v1.132 保持一致)
    val appearanceTitle = stringResource(R.string.settings_screen_appearance_label)
    val appearanceDesc = stringResource(R.string.settings_screen_appearance_desc)
    val chatTitle = stringResource(R.string.settings_screen_chat)
    val chatDesc = stringResource(R.string.settings_screen_chat_desc)
    val mediaTitle = stringResource(R.string.settings_screen_media)
    // v2.6.10: 表情包入口标题先解析，link 的内容 lambda 不是 @Composable 上下文
    val stickerEntryTitle = stringResource(R.string.settings_chat_sticker_section)
    val mediaDesc = stringResource(R.string.settings_screen_media_desc)
    val translateTitle = stringResource(R.string.settings_screen_translate)
    val translateDesc = stringResource(R.string.settings_screen_translate_desc)
    val assistantTitle = stringResource(R.string.settings_screen_assistant)
    val assistantDesc = stringResource(R.string.settings_screen_assistant_desc)
    val agentTitle = stringResource(R.string.chat_agent_tab_title)
    val agentDesc = stringResource(R.string.settings_screen_agent_desc)
    val providerTitle = stringResource(R.string.settings_screen_provider)
    val providerDesc = stringResource(R.string.settings_screen_provider_desc)
    val taskRoutingTitle = stringResource(R.string.settings_task_routing_title)
    val taskRoutingDesc = stringResource(R.string.settings_screen_task_routing_desc)
    val providerPluginsTitle = stringResource(R.string.provider_plugins_title)
    val musePluginsTitle = stringResource(R.string.muse_plugins_external)
    val pluginManageTitle = stringResource(R.string.muse_plugins_manage)
    val memoryTitle = stringResource(R.string.settings_screen_memory_notification)
    val memoryConfigPageTitle = stringResource(R.string.settings_memory_page_title)
    val memoryDesc = stringResource(R.string.settings_screen_memory_notification_desc)
    val ragTitle = stringResource(R.string.settings_screen_rag)
    val ragDesc = stringResource(R.string.settings_screen_rag_desc)
    val dataManagementTitle = stringResource(R.string.data_management_entry)
    val dataManagementDesc = stringResource(R.string.data_management_entry_desc)
    val dataBackupTitle = stringResource(R.string.settings_screen_data_backup)
    val dataBackupDesc = stringResource(R.string.settings_screen_data_backup_desc)
    val dataImportTitle = stringResource(R.string.settings_screen_data_import)
    val dataImportDesc = stringResource(R.string.settings_screen_data_import_desc)
    val workspaceTitle = stringResource(R.string.workspace_title)
    val piiGuardTitle = stringResource(R.string.settings_screen_pii_guard)
    val piiGuardDesc = stringResource(R.string.settings_screen_pii_guard_desc)
    val securityTitle = stringResource(R.string.settings_screen_share)
    val securityDesc = stringResource(R.string.settings_screen_security_desc)
    val auditLogTitle = stringResource(R.string.settings_audit_log)
    val experimentsTitle = stringResource(R.string.settings_screen_experiments)
    val experimentsDesc = stringResource(R.string.settings_screen_experiments_desc)
    val statsTitle = stringResource(R.string.settings_screen_stats)
    val statsDesc = stringResource(R.string.settings_screen_stats_desc)
    val tutorialTitle = stringResource(R.string.settings_screen_tutorial)
    val tutorialDesc = stringResource(R.string.settings_screen_tutorial_desc)
    val aboutTitle = stringResource(R.string.settings_screen_about)
    val aboutDesc = stringResource(R.string.settings_screen_about_desc)
    val checkUpdateTitle = stringResource(R.string.settings_screen_check_update)
    val checkUpdateDesc = stringResource(R.string.settings_screen_check_update_desc)
    val debugLogTitle = stringResource(R.string.settings_screen_debug_log)
    val entryCrashReportTitle = stringResource(R.string.settings_crash_page_title)
    val diagnosticExportTitle = stringResource(R.string.diagnostic_export_title)
    val componentGalleryTitle = stringResource(R.string.settings_component_gallery)
    val componentGalleryDesc = stringResource(R.string.settings_component_gallery_desc)
    val componentGalleryKeywords = stringResource(R.string.settings_component_gallery_keywords)
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    val permissionWizardTitle = stringResource(R.string.permission_wizard_title)
    val webSearchEntryTitle = stringResource(R.string.settings_screen_web_search)
    val webSearchEntryDesc = stringResource(R.string.settings_screen_web_search_desc)
    val asrEntryTitle = stringResource(R.string.settings_screen_asr)
    val asrEntryDesc = stringResource(R.string.settings_screen_asr_desc)
    val mcpEntryTitle = stringResource(R.string.settings_screen_mcp)
    val mcpEntryDesc = stringResource(R.string.settings_screen_mcp_desc)
    val assistantResourcesTitle = stringResource(R.string.settings_screen_assistant_resources)
    val assistantResourcesDesc = stringResource(R.string.settings_screen_assistant_resources_desc)
    val notificationListenerTitle = stringResource(R.string.settings_screen_notification_listener)
    val notificationListenerDesc = stringResource(R.string.settings_screen_notification_listener_desc)
    val channelEntryTitle = stringResource(R.string.channel_page_title)
    val connectionCenterTitle = stringResource(R.string.connection_center_title)
    val connectorEntryTitle = stringResource(R.string.connector_page_title)
    val toolsTitle = stringResource(R.string.settings_screen_tools)
    val toolsDesc = stringResource(R.string.settings_screen_tools_desc)
    val automationTitle = stringResource(io.zer0.muse.R.string.automation_settings_title)
    val terminalEntryTitle = stringResource(io.zer0.muse.R.string.terminal_title)
    val automationSubtitle = stringResource(io.zer0.muse.R.string.automation_settings_subtitle)
    val quickNotesTitle = stringResource(R.string.settings_screen_quick_notes)
    val miniPhoneTitle = stringResource(R.string.settings_miniphone_title)
    // 前端修复 (i18n-3.x): 搜索索引 SettingsEntry 硬编码 title 统一走资源
    val entryThemeTitle = stringResource(R.string.section_theme)
    val entryTtsPlaybackTitle = stringResource(R.string.settings_screen_entry_tts_playback)
    val entryProactiveTitle = stringResource(R.string.settings_agent_proactive_title)
    val entryScheduledTasksTitle = stringResource(R.string.schedule_title)
    val entryApiKeyTitle = stringResource(R.string.settings_screen_entry_api_key)
    val entryKeepAwakeTitle = stringResource(R.string.settings_memory_keep_awake)
    val entryBootStartTitle = stringResource(R.string.settings_agent_boot_section)
    val entryArchivedTitle = stringResource(R.string.settings_screen_entry_archived)
    // 死物清理(第 9 项):entryBiometricTitle 已删 — 生物识别搜索项指向已下线功能(见下方 settingsIndex)
    val entryFontSizeTitle = stringResource(R.string.settings_screen_entry_font_size)
    val entryThemeModeTitle = stringResource(R.string.settings_screen_entry_theme_mode)
    val entryDynamicColorTitle = stringResource(R.string.settings_theme_dynamic_color)
    val entryScheduledThemeTitle = stringResource(R.string.settings_screen_entry_scheduled_theme)
    val entryStreamingTitle = stringResource(R.string.settings_chat_streaming)
    val entryEnterSendTitle = stringResource(R.string.settings_screen_entry_enter_send)
    val entryAutoScrollTitle = stringResource(R.string.settings_screen_entry_auto_scroll)
    val entryDeepThinkingTitle = stringResource(R.string.chat_deep_thinking_cd)
    val entryTimestampsTitle = stringResource(R.string.settings_screen_entry_timestamps)
    val entryTtsSpeedTitle = stringResource(R.string.settings_screen_entry_tts_speed)
    val entryTtsVoiceTitle = stringResource(R.string.settings_media_tts_voice_selector)
    val entryMemoryToggleTitle = stringResource(R.string.settings_screen_entry_memory_toggle)
    val entryCompressPromptTitle = stringResource(R.string.settings_memory_custom_compress_prompt)
    val entryProxySwitchTitle = stringResource(R.string.settings_screen_entry_proxy_switch)
    val entryRagModelTitle = stringResource(R.string.settings_screen_entry_rag_model)
    val entryChunkingTitle = stringResource(R.string.settings_screen_entry_chunking)
    val entrySearchEngineTitle = stringResource(R.string.settings_web_search_engine)
    val entryMcpServerTitle = stringResource(R.string.settings_mcp_title)
    val entryToolApprovalTitle = stringResource(R.string.settings_screen_entry_tool_approval)
    val entryCollabTitle = stringResource(R.string.settings_screen_entry_collab)
    val entryCloudBackupTitle = stringResource(R.string.settings_backup_cloud_title)

    val groupAssistants = stringResource(R.string.settings_screen_group_assistants)
    val groupModels = stringResource(R.string.settings_screen_group_models)
    val groupChatDisplay = stringResource(R.string.settings_screen_group_chat_display)
    val groupMemory = stringResource(R.string.settings_screen_group_memory)
    val groupDataPrivacy = stringResource(R.string.settings_screen_group_data_privacy)
    val groupStorage = stringResource(R.string.settings_screen_group_storage)
    val groupMigrate = stringResource(R.string.settings_screen_group_migrate)
    val groupTools = stringResource(R.string.settings_screen_group_tools)
    val groupAbout = stringResource(R.string.settings_screen_group_about)
    // v2.6.7: 按「用户带着什么问题来」重排，一级从 7 组扩到 11 组。
    val groupLook = stringResource(R.string.settings_screen_group_look)
    val groupVoiceMedia = stringResource(R.string.settings_screen_group_voice_media)
    val groupProactive = stringResource(R.string.settings_screen_group_proactive)
    val groupConnection = stringResource(R.string.settings_screen_group_connection)
    val userProfileTitle = stringResource(R.string.settings_user_profile_title)
    val visionSettingsTitle = stringResource(R.string.settings_vision_title)
    val videoGenEntryTitle = stringResource(R.string.video_gen_title)
    val voiceCloningEntryTitle = stringResource(R.string.voice_cloning_title)
    // v2.6.7: 常驻后台此前只挂在小手机设置下，实际无人可达。
    val keepAliveTitle = stringResource(R.string.settings_keep_alive_title)

    val checkUpdateAction: () -> Unit = {
        if (!checkingUpdate) {
            checkingUpdate = true
            scope.launch {
                val result = try {
                    updateNotifier.checkManually(context)
                } finally {
                    checkingUpdate = false
                }
                when (result) {
                    is UpdateCheckResult.NewVersion -> MuseToast.show(context.getString(R.string.update_found_new))
                    UpdateCheckResult.UpToDate -> MuseToast.show(context.getString(R.string.update_already_latest))
                    is UpdateCheckResult.Failed -> MuseToast.show(
                        context.getString(R.string.settings_about_update_check_failed, result.message),
                    )
                    UpdateCheckResult.Skipped -> Unit
                }
            }
        }
    }

    val settingsIndex by remember(piiGuardEnabled, proxySubtitle, checkingUpdate) {
        mutableStateOf(
            listOf(
                // 通用
                SettingsEntry(
                    chatTitle,
                    listOf(
                        "聊天", "对话", "消息", "输入", "发送", "liaotian", "duihua", "xiaoxi", "shuru",
                        "fasong", "lt", "dh", "xx", "全屏编辑", "展开", "气泡", "阴影", "模型", "MOOD", "思维链",
                        "快捷键", "引用回复",
                    ),
                    MuseRoutes.SETTINGS_CHAT,
                    groupChatDisplay,
                    MuseIcons.chat,
                    onOpenChatSettings,
                ),
                SettingsEntry(
                    appearanceTitle,
                    listOf(
                        "外观", "显示", "界面", "字号", "字体", "waiguan", "xianshi", "jiemian", "zihao",
                        "ziti", "wg", "xs", "jm", "zt", "主题", "圆角", "启动页", "引导", "壁纸",
                    ),
                    MuseRoutes.SETTINGS_APPEARANCE,
                    groupLook,
                    MuseIcons.colorSwatch,
                    onOpenAppearanceSettings,
                ),
                SettingsEntry(
                    entryThemeTitle,
                    listOf(
                        "主题", "配色", "深色", "浅色", "暗黑", "AMOLED", "颜色", "zhuti", "peise", "shense",
                        "qianse", "anhe", "yase", "zt", "ps", "ss", "qs",
                    ),
                    MuseRoutes.SETTINGS_APPEARANCE,
                    groupLook,
                    MuseIcons.colorSwatch,
                    onOpenAppearanceSettings,
                ),
                SettingsEntry(
                    mediaTitle,
                    listOf("语音播报", "媒体", "录音", "语音", "播报", "yuyinbobao", "meiti", "luyin", "yuyin", "bobao", "mt", "ly", "yy", "bb"),
                    MuseRoutes.SETTINGS_MEDIA,
                    groupVoiceMedia,
                    MuseIcons.microphone,
                    onOpenMediaSettings,
                ),
                SettingsEntry(
                    entryTtsPlaybackTitle,
                    listOf("TTS", "tts", "语音播报", "朗读", "文字转语音", "TextToSpeech", "yuyinbobao", "langdu", "wenzi", "yybb", "ld"),
                    MuseRoutes.SETTINGS_MEDIA,
                    groupVoiceMedia,
                    MuseIcons.microphone,
                    onOpenMediaSettings,
                ),
                SettingsEntry(
                    translateTitle,
                    listOf("翻译", "translate", "语言", "互译", "源语言", "目标语言", "fanyi", "yuyan", "huyi", "yuanyuyan", "mubiaoyuyan", "fy", "yy"),
                    MuseRoutes.TRANSLATE,
                    groupChatDisplay,
                    MuseIcons.languages,
                    onOpenTranslate,
                ),
                SettingsEntry(
                    quickNotesTitle,
                    listOf("快速记录", "速记", "笔记", "quick note", "note", "记录", "kuaisujilu", "suji", "biji", "jilu", "ksjl", "sj", "bj", "jl"),
                    MuseRoutes.QUICK_NOTES,
                    groupDataPrivacy,
                    MuseIcons.bulb,
                ) {
                    onNavigate(QuickNotesRoute)
                },

                // 助手与 Agent
                SettingsEntry(
                    assistantTitle,
                    listOf("助手", "assistant", "角色", "人设", "zhushou", "juese", "renshe", "zs", "js", "rs"),
                    MuseRoutes.ASSISTANTS,
                    groupAssistants,
                    MuseIcons.atom,
                    onOpenAssistants,
                ),
                SettingsEntry(
                    agentTitle,
                    listOf("Agent", "代理", "智能体", "自主", "daili", "zhinengti", "zizhu", "dl", "znt"),
                    MuseRoutes.SETTINGS_AGENT,
                    groupAssistants,
                    MuseIcons.users,
                    onOpenAgentSettings,
                ),
                SettingsEntry(
                    entryProactiveTitle,
                    listOf(
                        "主动消息", "主动", "推送", "定时发送", "proactive", "zhudongxiaoxi", "zhudong",
                        "tuisong", "dingshifasong", "zdxx", "zd", "ts",
                    ),
                    MuseRoutes.SETTINGS_PROACTIVE,
                    groupProactive,
                    MuseIcons.bell,
                    onOpenProactiveSettings,
                ),
                SettingsEntry(
                    entryScheduledTasksTitle,
                    listOf(
                        "定时任务", "定时", "计划任务", "scheduled", "task", "cron", "dingshirenwu",
                        "dingshi", "jihuarenwu", "dsrw", "ds", "jhrw",
                    ),
                    MuseRoutes.SCHEDULED_TASKS,
                    groupProactive,
                    MuseIcons.clock,
                ) {
                    onNavigate(ScheduledTasksRoute)
                },
                SettingsEntry(
                    assistantResourcesTitle,
                    listOf(
                        "助手资源", "收藏夹", "世界书", "快捷消息", "模式注入", "Skills", "技能", "zhushouziyuan",
                        "shoucangjia", "shijieshu", "kuaijiexiaoxi", "moshizhur", "jineng", "zszy",
                        "scj", "sjs", "kjxx", "mszr", "jn",
                    ),
                    MuseRoutes.SETTINGS_ASSISTANT_RESOURCES,
                    groupAssistants,
                    MuseIcons.stars,
                    onOpenAssistantResources,
                ),
                SettingsEntry(
                    notificationListenerTitle,
                    listOf(
                        "通知监听", "通知", "NotificationListener", "通知权限", "tongzhijianting", "tongzhi",
                        "tongzhiquanxian", "tzjl", "tz", "tzqx",
                    ),
                    MuseRoutes.NOTIFICATION_LISTENER,
                    groupConnection,
                    MuseIcons.bell,
                    onOpenNotificationListener,
                ),
                SettingsEntry(
                    channelEntryTitle,
                    listOf(
                        "消息渠道", "渠道", "平台互通", "桥接", "社交平台", "飞书", "QQ", "微信", "IM",
                        "channel", "feishu", "bridge", "xiaoxi", "qidao", "weixin", "xxqd",
                        "pingtaihutong", "qiaojie",
                    ),
                    MuseRoutes.CHANNELS,
                    groupConnection,
                    MuseIcons.send,
                ) { onNavigate(ChannelSettingsRoute) },
                SettingsEntry(
                    connectionCenterTitle,
                    listOf(
                        "连接中心", "连接", "渠道", "MCP", "插件", "权限",
                        "lianjie", "lianjiezhongxin", "qudao", "chajian", "quanxian", "mcp",
                    ),
                    "connection_center",
                    groupConnection,
                    MuseIcons.link,
                ) { onOpenConnectionCenter() },
                SettingsEntry(
                    connectorEntryTitle,
                    listOf("连接器", "授权", "OAuth", "connector", "oauth", "shouquan", "lianjieqi", "sjq", "lqq", "sq"),
                    MuseRoutes.CONNECTORS,
                    groupConnection,
                    MuseIcons.plug,
                ) {
                    onNavigate(io.zer0.muse.ui.navigation.ConnectorSettingsRoute)
                },
                SettingsEntry(
                    toolsTitle,
                    listOf("工具", "AI工具", "ToolRegistry", "tool", "插件", "gongju", "AIgongju", "chajian", "gj", "AIgj", "cj"),
                    MuseRoutes.TOOLS,
                    groupTools,
                    MuseIcons.wrench,
                    onOpenTools,
                ),
                SettingsEntry(
                    automationTitle,
                    listOf("UI自动化", "自动操作", "屏幕读取", "手势", "zidonghua", "zidongcaozuo", "pingmu", "shoushi"),
                    MuseRoutes.SETTINGS_AUTOMATION,
                    groupProactive,
                    MuseIcons.computer,
                ) {
                    onNavigate(io.zer0.muse.ui.navigation.SettingsAutomationRoute)
                },
                // ST-05: 权限配置向导 — 补入口(此前路由已注册但无任何入口)
                SettingsEntry(
                    permissionWizardTitle,
                    listOf("权限", "向导", "无障碍", "Shizuku", "Root", "quanxian", "xiangdao", "wuzhangai", "qx", "xd", "wza"),
                    MuseRoutes.SETTINGS_PERMISSION_WIZARD,
                    groupTools,
                    MuseIcons.shieldCheck,
                ) {
                    onNavigate(SettingsPermissionWizardRoute)
                },
                // v2.x 终端一期:应用沙盒终端
                SettingsEntry(
                    terminalEntryTitle,
                    listOf("终端", "命令行", "shell", "terminal", "zhongduan", "minglinghang"),
                    MuseRoutes.TERMINAL,
                    groupTools,
                    MuseIcons.terminal,
                ) {
                    onNavigate(io.zer0.muse.ui.navigation.TerminalRoute)
                },

                // AI 模型与能力(从原「助手与 Agent」拆分)
                SettingsEntry(
                    providerTitle,
                    listOf(
                        "供应商", "模型", "provider", "API", "密钥", "gongyingshang", "moxing", "miyao",
                        "gys", "mx", "my", "绘图", "Agnes", "DALL-E", "绘图供应商",
                    ),
                    MuseRoutes.SETTINGS_MODEL,
                    groupModels,
                    MuseIcons.provider,
                    onOpenModelSettings,
                ),
                SettingsEntry(
                    entryApiKeyTitle,
                    listOf("API Key", "密钥", "key", "token", "凭证", "apiKey", "miyao", "pingzheng"),
                    MuseRoutes.SETTINGS_MODEL,
                    groupModels,
                    MuseIcons.lock,
                    onOpenModelSettings,
                ),
                SettingsEntry(
                    taskRoutingTitle,
                    listOf(
                        "辅助模型", "任务路由", "路由", "小工具", "大工具", "视觉", "模型", "主对话模型", "默认模型",
                        "fuzhumoxing", "renwuluyou", "luyou", "xiaogongju", "dagongju", "shijue",
                        "moxing", "zhuduihua", "moren", "fzmx", "rwly", "ly", "xgj", "dgj", "sj",
                        "mx", "zdh", "mr",
                    ),
                    MuseRoutes.SETTINGS_TASK_ROUTING,
                    groupModels,
                    MuseIcons.taskRouting,
                ) {
                    onNavigate(SettingsTaskRoutingRoute)
                },
                SettingsEntry(
                    entryCompressPromptTitle,
                    listOf("压缩", "压缩提示词", "上下文压缩", "自定义提示词", "yasuo", "yasuotishici", "shangwen", "ystc", "ys"),
                    MuseRoutes.SETTINGS_TASK_ROUTING,
                    groupModels,
                    MuseIcons.edit,
                ) {
                    onNavigate(SettingsTaskRoutingRoute)
                },
                SettingsEntry(
                    pluginManageTitle,
                    listOf("插件管理", "外部插件", "muse-plugin", "插件包", "导入插件", "chajian", "plugin", "daoruchajian", "cjb", "cjgl"),
                    MuseRoutes.MUSE_PLUGINS,
                    groupTools,
                    MuseIcons.puzzle,
                ) { onNavigate(PluginManageRoute) },
                SettingsEntry(
                    webSearchEntryTitle,
                    listOf(
                        "联网搜索", "搜索", "web search", "网络搜索", "在线搜索", "lianwang", "sousuo",
                        "wangluosousuo", "zaixiansousuo", "lwss", "ss", "wlss", "zxss",
                    ),
                    MuseRoutes.SETTINGS_WEB_SEARCH,
                    groupTools,
                    MuseIcons.globe,
                    onOpenWebSearch,
                ),
                SettingsEntry(
                    asrEntryTitle,
                    listOf(
                        "ASR", "asr", "语音识别", "speech", "转文字", "识别语音", "yuyinshibie", "zhuanwenzi",
                        "shibieyuyin", "yysb", "zwz", "sbyy",
                    ),
                    MuseRoutes.SETTINGS_ASR,
                    groupVoiceMedia,
                    MuseIcons.microphone,
                    onOpenAsr,
                ),
                SettingsEntry(
                    mcpEntryTitle,
                    listOf("MCP", "mcp", "服务器", "Model Context Protocol", "工具协议", "fuwuqi", "gongjixieyi", "fwq", "gjxy"),
                    MuseRoutes.SETTINGS_MCP,
                    groupTools,
                    MuseIcons.affiliate,
                    onOpenMcp,
                ),

                // 记忆与知识库
                SettingsEntry(
                    memoryTitle,
                    listOf(
                        "记忆", "长期记忆", "memory", "遗忘", "回忆", "记忆空间", "jiyi", "changqijiyi", "yiwang",
                        "huiyi", "jiyikongjian", "jy", "cqjy", "yw", "hy", "kj",
                    ),
                    MuseRoutes.SETTINGS_MEMORY,
                    groupMemory,
                    MuseIcons.atom,
                    onOpenMemorySettings,
                ),
                SettingsEntry(
                    entryKeepAwakeTitle,
                    listOf(
                        "保持唤醒", "唤醒", "wakelock", "不休眠", "常亮", "keep awake", "baochihuanxing",
                        "huanxing", "buxiumian", "changliang", "bchx", "hx", "bxm", "cl",
                    ),
                    MuseRoutes.SETTINGS_AGENT,
                    groupAssistants,
                    MuseIcons.bolt,
                    onOpenAgentSettings,
                ),
                SettingsEntry(
                    entryBootStartTitle,
                    listOf(
                        "开机自启", "自启", "自启动", "开机", "boot", "auto launch", "BootReceiver",
                        "kaijiziqi", "ziqi", "zidong", "kaiji", "kjzq", "zq", "zdd", "kj",
                    ),
                    MuseRoutes.SETTINGS_AGENT,
                    groupAssistants,
                    MuseIcons.bolt,
                    onOpenAgentSettings,
                ),
                SettingsEntry(
                    ragTitle,
                    listOf("RAG", "知识库", "rag", "检索", "向量", "文档", "zhishiku", "jiansuo", "xiangliang", "wendang", "zsk", "js", "xl", "wd"),
                    MuseRoutes.SETTINGS_RAG,
                    groupMemory,
                    MuseIcons.book,
                    onOpenRagSettings,
                ),

                // 数据管理
                SettingsEntry(
                    dataManagementTitle,
                    listOf(
                        "数据管理", "数据", "存储", "清理", "缓存", "shujuguanli", "shuju", "cunchu", "qingli",
                        "huancun", "sjgl", "sj", "cc", "ql", "hc",
                    ),
                    MuseRoutes.DATA_MANAGEMENT,
                    groupDataPrivacy,
                    MuseIcons.database,
                    onOpenDataManagement,
                ),
                SettingsEntry(
                    dataBackupTitle,
                    listOf("备份", "云备份", "cloud", "backup", "S3", "WebDAV", "同步", "beifen", "yunbeifen", "tongbu", "bf", "ybf", "tb"),
                    MuseRoutes.SETTINGS_DATA,
                    groupDataPrivacy,
                    MuseIcons.cloud,
                    onOpenDataSettings,
                ),
                SettingsEntry(
                    dataImportTitle,
                    listOf("数据导入", "导入", "import", "恢复数据", "shujudaoru", "daoru", "huifushuju", "sjdr", "dr", "hfsj"),
                    MuseRoutes.SETTINGS_DATA_IMPORT,
                    groupDataPrivacy,
                    MuseIcons.cloudUpload,
                    onOpenDataImport,
                ),
                SettingsEntry(
                    workspaceTitle,
                    listOf(
                        "工作区", "文件管理", "workspace", "文件", "目录", "gongzuoqu", "wenjianguanli",
                        "wenjian", "mulu", "gzq", "wjgl", "wj", "ml",
                    ),
                    MuseRoutes.WORKSPACE,
                    groupDataPrivacy,
                    MuseIcons.folder,
                    onOpenWorkspace,
                ),
                // v1.0.72: 归档聊天 — 从设置-数据与隐私进入(原主页顶栏入口已移除)
                SettingsEntry(
                    entryArchivedTitle,
                    listOf("归档", "archive", "已归档", "guidang", "gd"),
                    MuseRoutes.WORKSPACE,
                    groupDataPrivacy,
                    MuseIcons.archive,
                    onOpenArchivedChats,
                ),

                // 隐私与安全
                SettingsEntry(
                    securityTitle,
                    listOf("分享", "导出", "分享模板", "share", "fenxiang", "daochu", "fx", "dc"),
                    MuseRoutes.SETTINGS_SECURITY,
                    groupChatDisplay,
                    MuseIcons.share,
                    onOpenSecuritySettings,
                ),
                // 死物清理(第 9 项):删除"生物识别/指纹/面容"搜索项 — 生物识别功能已随应用锁一起下线,
                // SecuritySettingsPage 里没有任何生物识别内容,该搜索项只会把用户带到无关页面。
                SettingsEntry(
                    proxyTitle,
                    listOf("代理", "proxy", "网络", "VPN", "HTTP代理", "daili", "wangluo", "dl", "wl"),
                    MuseRoutes.SETTINGS_PROXY,
                    groupConnection,
                    MuseIcons.proxy,
                    onOpenProxySettings,
                ),

                SettingsEntry(
                    keepAliveTitle,
                    listOf(
                        "常驻后台", "保活", "后台", "电池优化", "自启动", "通知文案",
                        "changzhu", "baohuo", "houtai", "dianchi", "zhiqidong",
                        "czy", "bh", "ht", "dc", "zqd",
                    ),
                    "keep_alive",
                    groupConnection,
                    MuseIcons.battery,
                ) { onNavigate(KeepAliveSettingsRoute) },
                SettingsEntry(
                    auditLogTitle,
                    listOf("审计", "日志", "audit", "操作记录", "审计日志", "shenji", "rizhi", "caozuojilu", "shenjirizhi", "sj", "rz", "czjl", "sjrz"),
                    MuseRoutes.AUDIT_LOG,
                    groupConnection,
                    MuseIcons.history,
                    onOpenAuditLog,
                ),

                // 关于
                SettingsEntry(
                    tutorialTitle,
                    listOf("教程", "新手", "引导", "tutorial", "帮助", "jiaocheng", "xinshou", "yindao", "bangzhu", "jc", "xs", "yd", "bz"),
                    MuseRoutes.SETTINGS_TUTORIAL,
                    groupAbout,
                    MuseIcons.school,
                    onOpenTutorial,
                ),
                SettingsEntry(
                    aboutTitle,
                    listOf("关于", "版本", "about", "信息", "guanyu", "banben", "xinxi", "gy", "bb", "xx"),
                    MuseRoutes.SETTINGS_ABOUT,
                    groupAbout,
                    MuseIcons.info,
                    onOpenAboutSettings,
                ),
                SettingsEntry(
                    checkUpdateTitle,
                    listOf("检查更新", "更新", "update", "版本", "升级", "jianchagengxin", "gengxin", "shengji", "jcgc", "gx", "sj"),
                    "",
                    groupAbout,
                    MuseIcons.refresh,
                ) {
                    checkUpdateAction()
                },
                SettingsEntry(
                    debugLogTitle,
                    listOf("调试", "日志", "debug", "log", "Logger", "tiaoshi", "rizhi", "ts", "rz"),
                    MuseRoutes.DEBUG,
                    groupAbout,
                    MuseIcons.bug,
                    onOpenDebugLog,
                ),
                SettingsEntry(
                    entryCrashReportTitle,
                    listOf("崩溃", "崩溃上报", "crash", "报告", "诊断", "bengkui", "baogao", "zhenduan", "bk", "bg", "zd"),
                    MuseRoutes.SETTINGS_CRASH_REPORT,
                    groupAbout,
                    MuseIcons.bug,
                    onOpenCrashReport,
                ),
                // v2.x（诊断导出项目 A）: 无路由——直接调起系统分享导出诊断 zip
                SettingsEntry(
                    diagnosticExportTitle,
                    listOf("诊断", "导出", "日志", "diagnostic", "export", "zhenduan", "daochu", "rizhi", "zd", "dc", "rz"),
                    "",
                    groupAbout,
                    MuseIcons.bug,
                ) {
                    io.zer0.muse.diagnostic.DiagnosticExporter.share(context)
                },
                SettingsEntry(
                    componentGalleryTitle,
                    componentGalleryKeywords,
                    MuseRoutes.COMPONENT_GALLERY,
                    groupAbout,
                    MuseIcons.colorSwatch,
                    onOpenComponentGallery,
                ),
                SettingsEntry(
                    experimentsTitle,
                    listOf("实验性", "实验", "experimental", "beta", "试验", "shiyanxing", "shiyan", "shiyan", "syx", "sy"),
                    MuseRoutes.SETTINGS_EXPERIMENTS,
                    groupAbout,
                    MuseIcons.flask,
                    onOpenExperimentsSettings,
                ),
                SettingsEntry(
                    statsTitle,
                    listOf("统计", "使用统计", "stats", "热力图", "数据", "tongji", "shiyongtongji", "relitu", "shuju", "tj", "sytj", "rlt", "sj"),
                    MuseRoutes.STATS,
                    groupAbout,
                    MuseIcons.chartBar,
                    onOpenStats,
                ),

                // 二级设置项
                SettingsEntry(
                    entryFontSizeTitle,
                    listOf("字号", "字体大小", "字体", "大小", "ziti", "zihao", "ztdx", "zt"),
                    MuseRoutes.SETTINGS_APPEARANCE,
                    groupLook,
                    MuseIcons.colorSwatch,
                    onOpenAppearanceSettings,
                ),
                SettingsEntry(
                    entryThemeModeTitle,
                    listOf("主题模式", "浅色", "深色", "跟随系统", "zhutimoshi", "qianse", "shense", "genshixitong", "ztms", "qs", "ss", "gsxt"),
                    MuseRoutes.SETTINGS_APPEARANCE,
                    groupLook,
                    MuseIcons.colorSwatch,
                    onOpenAppearanceSettings,
                ),
                SettingsEntry(
                    entryDynamicColorTitle,
                    listOf("动态取色", "取色", "壁纸", "dongtaiquse", "quse", "dtqs", "qs", "bz"),
                    MuseRoutes.SETTINGS_APPEARANCE,
                    groupLook,
                    MuseIcons.colorSwatch,
                    onOpenAppearanceSettings,
                ),
                SettingsEntry(
                    entryScheduledThemeTitle,
                    listOf("定时切换", "自动切换", "深色模式", "dingshiqiehuan", "zidongqiehuan", "shensemoshi", "dsqh", "zdqh", "ssms"),
                    MuseRoutes.SETTINGS_APPEARANCE,
                    groupLook,
                    MuseIcons.colorSwatch,
                    onOpenAppearanceSettings,
                ),

                SettingsEntry(
                    entryStreamingTitle,
                    listOf("流式", "流式响应", "实时输出", "liushi", "liushixiangying", "shishishuchu", "ls", "lsxy", "sssc"),
                    MuseRoutes.SETTINGS_CHAT,
                    groupChatDisplay,
                    MuseIcons.chat,
                    onOpenChatSettings,
                ),
                SettingsEntry(
                    entryEnterSendTitle,
                    listOf("回车发送", "回车", "发送", "huichefasong", "huiche", "fasong", "hcfs", "hc", "fs"),
                    MuseRoutes.SETTINGS_CHAT,
                    groupChatDisplay,
                    MuseIcons.chat,
                    onOpenChatSettings,
                ),
                SettingsEntry(
                    entryAutoScrollTitle,
                    listOf("自动滚动", "滚动", "zidonggundong", "gundong", "zdgd", "gd"),
                    MuseRoutes.SETTINGS_CHAT,
                    groupChatDisplay,
                    MuseIcons.chat,
                    onOpenChatSettings,
                ),
                SettingsEntry(
                    entryDeepThinkingTitle,
                    listOf("深度思考", "默认深度思考", "shendusikao", "morethorough", "sds", "sdsz"),
                    MuseRoutes.SETTINGS_CHAT,
                    groupChatDisplay,
                    MuseIcons.chat,
                    onOpenChatSettings,
                ),
                SettingsEntry(
                    entryTimestampsTitle,
                    listOf("时间戳", "24小时", "timestamp", "shijianchuo", "24xiaoshi", "sjc"),
                    MuseRoutes.SETTINGS_CHAT,
                    groupChatDisplay,
                    MuseIcons.chat,
                    onOpenChatSettings,
                ),

                SettingsEntry(
                    entryTtsSpeedTitle,
                    listOf("TTS", "语速", "音高", "yusu", "yingao", "ys", "yg"),
                    MuseRoutes.SETTINGS_MEDIA,
                    groupVoiceMedia,
                    MuseIcons.microphone,
                    onOpenMediaSettings,
                ),
                SettingsEntry(
                    entryTtsVoiceTitle,
                    listOf("声音", "语音", "voice", "shengyin", "yuyin", "sy", "yy"),
                    MuseRoutes.SETTINGS_MEDIA,
                    groupVoiceMedia,
                    MuseIcons.microphone,
                    onOpenMediaSettings,
                ),

                SettingsEntry(
                    entryMemoryToggleTitle,
                    listOf("记忆", "开关", "启用记忆系统", "jiyi", "kaiguan", "qiyong", "jy", "kg", "qyjy"),
                    MuseRoutes.SETTINGS_MEMORY_CONFIG,
                    groupMemory,
                    MuseIcons.atom,
                    onOpenMemoryConfig,
                ),

                SettingsEntry(
                    entryProxySwitchTitle,
                    listOf("代理", "开关", "Proxy", "daili", "kaiguan", "dl", "kg"),
                    MuseRoutes.SETTINGS_PROXY,
                    groupConnection,
                    MuseIcons.proxy,
                    onOpenProxySettings,
                ),

                SettingsEntry(
                    entryRagModelTitle,
                    listOf("检索模型", "RAG模型", "相似度", "jiansuomoxing", "ragmoxing", "xiangsidu", "jsmx", "ragmx", "xsd"),
                    MuseRoutes.SETTINGS_RAG,
                    groupMemory,
                    MuseIcons.book,
                    onOpenRagSettings,
                ),
                SettingsEntry(
                    entryChunkingTitle,
                    listOf("分段", "分块", "策略", "fenduan", "fenkuai", "celve", "fd", "fk", "cl"),
                    MuseRoutes.SETTINGS_RAG,
                    groupMemory,
                    MuseIcons.book,
                    onOpenRagSettings,
                ),

                SettingsEntry(
                    entrySearchEngineTitle,
                    listOf("搜索引擎", "Bing", "Jina", "SearXNG", "sousuoyinqing", "ssyq"),
                    MuseRoutes.SETTINGS_WEB_SEARCH,
                    groupTools,
                    MuseIcons.globe,
                    onOpenWebSearch,
                ),

                SettingsEntry(
                    entryMcpServerTitle,
                    listOf("MCP", "服务器", "ModelContextProtocol", "fuwuqi", "fwq"),
                    MuseRoutes.SETTINGS_MCP,
                    groupTools,
                    MuseIcons.affiliate,
                    onOpenMcp,
                ),
                SettingsEntry(
                    entryToolApprovalTitle,
                    listOf("工具批准", "批准模式", "自动批准", "gongjupizhun", "pizhunmoshi", "zidongpizhun", "gjpz", "pzms", "zdpz"),
                    MuseRoutes.TOOLS,
                    groupTools,
                    MuseIcons.wrench,
                    onOpenTools,
                ),
                // 「主动消息」已在上面声明一次（同一标题、同一路由、同一分组）。
                // 这里再声明一次会让 LazyColumn 拿到重复的 key，滚到「主动与自动化」
                // 或搜索该词时直接抛 IllegalArgumentException 崩掉。
                SettingsEntry(
                    entryCollabTitle,
                    listOf("协作", "多助手", "团队", "xiezhuo", "duozhushou", "tuandui", "xz", "dzs", "td"),
                    MuseRoutes.SETTINGS_AGENT,
                    groupAssistants,
                    MuseIcons.users,
                    onOpenAgentSettings,
                ),
                SettingsEntry(
                    entryCloudBackupTitle,
                    listOf("云备份", "备份", "S3", "WebDAV", "yunbeifen", "beifen", "ybf", "bf"),
                    MuseRoutes.SETTINGS_CLOUD_BACKUP,
                    groupDataPrivacy,
                    MuseIcons.cloud,
                ) {
                    onNavigate(io.zer0.muse.ui.navigation.SettingsCloudBackupRoute)
                },
            ),
        )
    }

    val filteredEntries by remember(searchQuery, settingsIndex) {
        mutableStateOf(
            if (searchQuery.isBlank()) {
                settingsIndex
            } else {
                val q = searchQuery.trim().lowercase()
                settingsIndex.filter { entry ->
                    entry.title.lowercase().contains(q) ||
                        entry.groupName.lowercase().contains(q) ||
                        entry.keywords.any { kw -> kw.lowercase().contains(q) }
                }
            },
        )
    }
    // endregion

    // v2.0.1: 设置页滚动头部 —「设置」大标题（item 0）随滚动推出，搜索栏（item 1）
    // 到达顶部后吸顶；吸顶瞬间内部实色搜索栏淡出、外层玻璃搜索栏（Haze 胶囊内背景模糊）接管。
    val settingsListState = rememberLazyListState()
    val isSearchBarStuck by remember {
        derivedStateOf { settingsListState.firstVisibleItemIndex >= 1 }
    }
    // 吸顶过渡进度（0 = 实色常态，1 = 玻璃吸顶）；内部占位与外层玻璃共用，保证交叉淡化同步。
    val searchStuckProgress by animateFloatAsState(
        targetValue = if (isSearchBarStuck) 1f else 0f,
        animationSpec = MuseMotion.tween(MuseAnimation.FAST_NORMAL_MS),
        label = "searchStuckProgress",
    )
    val surfaceHazeState = remember { HazeState() }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        // v2.0.1: 搜索已改为浮层（对齐主页命令面板），不做顶部工具栏切换。
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                state = settingsListState,
                modifier = Modifier
                    .fillMaxSize()
                    // v2.0.1: 列表自持状态栏内边距（大标题随列表滚动、搜索栏吸顶于状态栏下方）。
                    .museSafeTopInsetPadding()
                    // v2.0.1: 作为 Haze 背景模糊的内容源（吸顶玻璃搜索栏读取列表内容）。
                    .hazeSource(state = surfaceHazeState),
                contentPadding = PaddingValues(
                    // 吸顶后 stickyHeader 脱离内容流，占位栏不再撑开空间，
                    // 列表项会直接从搜索栏下方钻过去。这里按吸顶进度补一份
                    // 等高 padding，保证内容始终停留在搜索栏之下。
                    top = innerPadding.calculateTopPadding() +
                        (MusePaddings.searchBarHeight + MusePaddings.contentGap) * searchStuckProgress,
                    bottom = innerPadding.calculateBottomPadding() + MusePaddings.sectionGap,
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                ),
                // v2.0.1: 卡片组间距 20dp（原 16dp）
                verticalArrangement = Arrangement.spacedBy(MusePaddings.cardGap),
            ) {
                item(key = "settings_large_header") {
                    MuseLargeTitleHeader(
                        title = stringResource(R.string.settings_screen_title),
                        onBack = onBack,
                    )
                }

                stickyHeader(key = "settings_search_bar") {
                    // 占位搜索栏随内容滚动；吸顶后由外层玻璃搜索栏接管。
                    // 吸顶时这层会脱离内容流，下方条目从它下面穿过，因此需要一层背景遮住。
                    // 背景只铺胶囊本体高度（不含上下 6dp 呼吸位），避免顶栏白带过厚。
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = MusePaddings.searchBarHeight, max = MusePaddings.searchBarHeight)
                                .padding(vertical = 6.dp)
                                .background(
                                    MaterialTheme.colorScheme.background.copy(alpha = searchStuckProgress),
                                ),
                        )
                        MuseSearchBar(
                            text = stringResource(R.string.settings_search_hint),
                            onClick = { isSearching = true },
                            modifier = Modifier
                                .padding(horizontal = MusePaddings.screen, vertical = 6.dp)
                                .alpha(1f - searchStuckProgress),
                        )
                    }
                }
                item(key = "account") {
                    io.zer0.muse.ui.account.AccountCard(
                        onClick = onOpenAccount,
                        modifier = Modifier.padding(horizontal = MusePaddings.screen),
                    )
                }

                // ===== 1. 助手与人设：它是谁、我是谁、有几个它 =====
                item(key = "profile") {
                    SettingsCardGroup(title = groupAssistants) {
                        link(userProfileTitle, R.string.settings_screen_desc_user_profile, MuseIcons.user, onOpenUserProfile)
                        link(assistantTitle, R.string.settings_screen_assistant_desc, MuseIcons.atom, onOpenAssistants)
                        link(agentTitle, R.string.settings_screen_agent_desc, MuseIcons.users, onOpenAgentSettings)
                        link(
                            assistantResourcesTitle,
                            R.string.settings_screen_assistant_resources_desc,
                            MuseIcons.stars,
                            onOpenAssistantResources,
                        )
                    }
                }

                // ===== 2. 对话与表达：它怎么说话 =====
                item(key = "talk") {
                    SettingsCardGroup(title = groupChatDisplay) {
                        link(chatTitle, R.string.settings_screen_chat_desc, MuseIcons.chat, onOpenChatSettings)
                        link(translateTitle, R.string.settings_screen_translate_desc, MuseIcons.languages, onOpenTranslate)
                        // 分享是表达的一种形式，归到对话与表达。
                        link(securityTitle, R.string.settings_screen_security_desc, MuseIcons.lock, onOpenSecuritySettings)
                    }
                }

                // ===== 3. 界面与显示：我看到的样子 =====
                item(key = "look") {
                    SettingsCardGroup(title = groupLook) {
                        link(appearanceTitle, R.string.settings_screen_appearance_desc, MuseIcons.palette, onOpenAppearanceSettings)
                    }
                }

                // ===== 4. 模型与推理：它用什么脑子 =====
                item(key = "models") {
                    SettingsCardGroup(title = groupModels) {
                        link(providerTitle, R.string.settings_screen_provider_desc, MuseIcons.provider, onOpenModelSettings)
                        link(taskRoutingTitle, taskRoutingDesc, MuseIcons.taskRouting) { onNavigate(SettingsTaskRoutingRoute) }
                        link(visionSettingsTitle, R.string.settings_screen_desc_vision, MuseIcons.image, onOpenVisionSettings)
                    }
                }

                // ===== 5. 能力与工具：它能干什么 =====
                item(key = "tools") {
                    SettingsCardGroup(title = groupTools) {
                        link(webSearchEntryTitle, R.string.settings_screen_web_search_desc, MuseIcons.globe, onOpenWebSearch)
                        link(mcpEntryTitle, R.string.settings_screen_mcp_desc, MuseIcons.plug, onOpenMcp)
                        link(toolsTitle, R.string.settings_screen_tools_desc, MuseIcons.wrench, onOpenTools)
                        link(
                            providerPluginsTitle,
                            R.string.settings_screen_desc_provider_plugins,
                            MuseIcons.affiliate,
                            onOpenProviderPlugins,
                        )
                        // ST-05: 权限配置向导
                        link(
                            permissionWizardTitle,
                            R.string.permission_wizard_desc,
                            MuseIcons.shieldCheck,
                        ) { onNavigate(SettingsPermissionWizardRoute) }
                        // v2.6.7: 小手机本版不对外发布，设置入口一并隐藏（代码保留）。
                        if (io.zer0.muse.feature.FeatureFlags.MINI_PHONE) {
                            link(
                                miniPhoneTitle,
                                R.string.settings_screen_miniphone_desc,
                                MuseIcons.deviceMobile,
                            ) { onNavigate(SettingsMiniPhoneRoute) }
                        }
                    }
                }

                // ===== 6. 语音与多媒体：听、说、看、画都在这 =====
                item(key = "voice_media") {
                    SettingsCardGroup(title = groupVoiceMedia) {
                        link(mediaTitle, R.string.settings_screen_media_desc, MuseIcons.microphone, onOpenMediaSettings)
                        // v2.6.10: 表情包库单独开门 —— 它原来只藏在「媒体」页最底部，
                        // 而「媒体」的副标题只提语音与 TTS，等于没有入口。
                        link(
                            stickerEntryTitle,
                            R.string.settings_screen_sticker_desc,
                            MuseIcons.image,
                        ) { onNavigate(io.zer0.muse.ui.navigation.StickerSettingsRoute) }
                        link(asrEntryTitle, R.string.settings_screen_asr_desc, MuseIcons.microphone, onOpenAsr)
                        link(videoGenEntryTitle, R.string.settings_screen_desc_video_gen, MuseIcons.video) {
                            onNavigate(VideoGenerationRoute)
                        }
                        link(voiceCloningEntryTitle, R.string.settings_screen_desc_voice_cloning, MuseIcons.play) {
                            onNavigate(VoiceCloningRoute)
                        }
                    }
                }

                // ===== 7. 记忆与知识：它记得什么 =====
                item(key = "memory") {
                    SettingsCardGroup(title = groupMemory) {
                        link(memoryTitle, R.string.settings_screen_memory_notification_desc, MuseIcons.atom, onOpenMemorySettings)
                        link(memoryConfigPageTitle, R.string.settings_screen_desc_memory, MuseIcons.brain, onOpenMemoryConfig)
                        link(ragTitle, R.string.settings_screen_rag_desc, MuseIcons.book, onOpenRagSettings)
                    }
                }

                // ===== 8. 主动与自动化：它什么时候自己来 =====
                item(key = "proactive") {
                    SettingsCardGroup(title = groupProactive) {
                        link(entryProactiveTitle, R.string.settings_agent_proactive_subtitle, MuseIcons.bell, onOpenProactiveSettings)
                        link(entryScheduledTasksTitle, R.string.settings_screen_desc_schedule, MuseIcons.clock) {
                            onNavigate(ScheduledTasksRoute)
                        }
                        link(
                            automationTitle,
                            automationSubtitle,
                            MuseIcons.computer,
                        ) { onNavigate(io.zer0.muse.ui.navigation.SettingsAutomationRoute) }
                    }
                }

                // ===== 9. 连接、账号与安全：谁能用它、怎么连 =====
                item(key = "connection") {
                    SettingsCardGroup(title = groupConnection) {
                        link(connectionCenterTitle, R.string.connection_center_desc, MuseIcons.link, onOpenConnectionCenter)
                        link(channelEntryTitle, R.string.settings_screen_desc_channel, MuseIcons.wifi) {
                            onNavigate(ChannelSettingsRoute)
                        }
                        link(proxyTitle, proxySubtitle, MuseIcons.proxy, onOpenProxySettings)
                        link(
                            keepAliveTitle,
                            R.string.settings_keep_alive_subtitle,
                            MuseIcons.battery,
                        ) { onNavigate(KeepAliveSettingsRoute) }
                        switch(
                            piiGuardTitle,
                            R.string.settings_screen_pii_guard_desc,
                            MuseIcons.shield,
                            checked = piiGuardEnabled,
                            onCheckedChange = { v -> scope.launch { settings.savePiiGuardEnabled(v) } },
                        )
                        link(
                            notificationListenerTitle,
                            R.string.settings_screen_notification_listener_desc,
                            MuseIcons.bell,
                            onOpenNotificationListener,
                        )
                        link(auditLogTitle, R.string.settings_screen_desc_audit_log, MuseIcons.history, onOpenAuditLog)
                    }
                }

                // ===== 10a. 存储与空间：占了多少、放在哪 =====
                // 原来这一组把存储、备份迁移、历史归档三类挤在一张卡里，
                // 且前三项都以「数据」开头，用户分不清该点哪个。
                item(key = "storage") {
                    SettingsCardGroup(title = groupStorage) {
                        link(dataManagementTitle, R.string.data_management_entry_desc, MuseIcons.database, onOpenDataManagement)
                        link(workspaceTitle, R.string.workspace_desc, MuseIcons.folder, onOpenWorkspace)
                    }
                }

                // ===== 10b. 备份与迁移：带走或搬过来 =====
                item(key = "migrate") {
                    SettingsCardGroup(title = groupMigrate) {
                        link(dataBackupTitle, R.string.settings_screen_data_backup_desc, MuseIcons.cloud, onOpenDataSettings)
                        link(dataImportTitle, R.string.settings_screen_data_import_desc, MuseIcons.cloudUpload, onOpenDataImport)
                        link(entryArchivedTitle, R.string.settings_screen_archived_desc, MuseIcons.archive, onOpenArchivedChats)
                    }
                }

                // ===== 11. 关于与诊断：它本身怎么查 =====
                item(key = "about") {
                    SettingsCardGroup(title = groupAbout) {
                        link(tutorialTitle, R.string.settings_screen_tutorial_desc, MuseIcons.school, onOpenTutorial)
                        link(aboutTitle, R.string.settings_screen_about_desc, MuseIcons.info, onOpenAboutSettings)
                        checkUpdate(checkingUpdate, onCheck = checkUpdateAction)
                        link(statsTitle, R.string.settings_screen_stats_desc, MuseIcons.chartBar, onOpenStats)
                        link(experimentsTitle, R.string.settings_screen_experiments_desc, MuseIcons.flask, onOpenExperimentsSettings)
                        link(debugLogTitle, R.string.settings_screen_debug_log_desc, MuseIcons.bug, onOpenDebugLog)
                        link(entryCrashReportTitle, R.string.settings_screen_desc_crash_report, MuseIcons.shieldCheck, onOpenCrashReport)
                    }
                }
            }

            // v2.0.1: 吸顶玻璃搜索栏（覆盖层）——列表滚到顶后接管：
            // 只在胶囊形状内对下方内容做背景模糊（Haze），胶囊之外无遮罩；
            // 内部占位搜索栏同步淡出（见上方 stickyHeader）。
            if (!isSearching && searchStuckProgress > 0.01f) {
                MuseSearchBar(
                    text = stringResource(R.string.settings_search_hint),
                    onClick = { isSearching = true },
                    glass = true,
                    hazeState = surfaceHazeState,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .museSafeTopInsetPadding()
                        // 底部多留一份内容间距，避免胶囊下沿紧贴分隔线。
                        .padding(
                            start = MusePaddings.screen,
                            end = MusePaddings.screen,
                            top = 6.dp,
                            bottom = MusePaddings.contentGap,
                        )
                        .alpha(searchStuckProgress),
                )
            }
        }
    }

    // v2.0.1: 设置搜索浮层 — 对齐主页命令面板（点击搜索栏弹出胶囊 + 实时过滤设置项）。
    if (isSearching) {
        SettingsSearchPalette(
            entries = filteredEntries,
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onDismiss = {
                isSearching = false
                searchQuery = ""
            },
            onSelect = { entry ->
                isSearching = false
                searchQuery = ""
                entry.onClick()
            },
        )
    }
}

/** v1.132 设置搜索索引条目（v2.0.1: 提升为文件级可见，供搜索浮层使用）。 */
private data class SettingsEntry(
    val title: String,
    val keywords: List<String>,
    val route: String,
    val groupName: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/**
 * v2.0.1: 设置搜索浮层 — 对齐主页命令面板（点击搜索栏弹出胶囊 + 实时过滤设置项）。
 *
 * 视觉与交互抄齐 [CommandPalette]：scrim + pill 搜索胶囊（surface 0.86）+ 实色结果卡；
 * 输入前保持干净（只显示胶囊），有输入才显示结果（空态兜底）。
 */
@Composable
private fun SettingsSearchPalette(
    entries: List<SettingsEntry>,
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSelect: (SettingsEntry) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Dialog 是独立窗口，可能在一帧内尚未 attach，此时 requestFocus 会静默失效。
    // 改为「等帧 + 重试」，焦点真正落下后再弹键盘。
    LaunchedEffect(Unit) {
        repeat(5) { attempt ->
            withFrameNanos { }
            val focused = runCatching { focusRequester.requestFocus() }.isSuccess
            if (focused) {
                keyboard?.show()
                return@LaunchedEffect
            }
            if (attempt > 0) kotlinx.coroutines.delay(40L)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        MuseDialogWindowEffect(forceFullScreen = true)
        Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .museModalScrim()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.largeGap),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.pill,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                    tonalElevation = 0.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                    ) {
                        Icon(
                            imageVector = MuseIcons.search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                        BasicTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (query.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.settings_search_hint),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester)
                                .padding(vertical = MusePaddings.tightGap),
                        )
                        if (query.isNotEmpty()) {
                            MuseTactileButton(
                                icon = MuseIcons.x,
                                onClick = { onQueryChange("") },
                                contentDescription = stringResource(R.string.command_palette_clear),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                iconSize = MuseIconSizes.iconSmall,
                            )
                        }
                    }
                }
                if (query.isNotBlank()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        shape = MuseShapes.semiLarge,
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp,
                    ) {
                        if (entries.isEmpty()) {
                            Text(
                                text = stringResource(R.string.settings_search_no_results),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(MusePaddings.contentGap),
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp),
                            ) {
                                item(key = "count") {
                                    Text(
                                        text = stringResource(
                                            R.string.settings_search_result_count,
                                            entries.size,
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(
                                            horizontal = MusePaddings.contentGap,
                                            vertical = MusePaddings.tightGap,
                                        ),
                                    )
                                }
                                // key 里带下标：设置项由一张扁平表生成，标题 + 路由 + 分组
                                // 理论上应当唯一，但历史上真出现过重复（「主动消息」被声明两次），
                                // 而 Compose 对重复 key 是直接抛异常崩溃，不是忽略。
                                // 带上下标后即使将来又出现重复，也只是渲染两行，不会把应用带走。
                                itemsIndexed(
                                    items = entries,
                                    key = { index, entry ->
                                        index.toString() + entry.title + entry.route + entry.groupName
                                    },
                                ) { _, entry ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onSelect(entry) }
                                            .padding(
                                                horizontal = MusePaddings.contentGap,
                                                vertical = MusePaddings.itemGap,
                                            ),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                                    ) {
                                        MuseSettingsIcon(entry.icon)
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = entry.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = entry.groupName,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 设置分组卡片:统一样式,带分组标题。
 */
@Composable
private fun SettingsCardGroup(title: String, modifier: Modifier = Modifier, content: CardGroupContentScope.() -> Unit) {
    CardGroup(
        modifier = modifier.padding(horizontal = MusePaddings.screen),
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        val scope = CardGroupContentScopeImpl(this)
        scope.content()
    }
}

/**
 * DSL 作用域,用于在 SettingsCardGroup 内快速声明链接项/开关项。
 */
private class CardGroupContentScopeImpl(
    private val cardGroupScope: io.zer0.muse.ui.common.surface.CardGroupScope,
) : CardGroupContentScope {
    override fun link(headline: String, descRes: Int, icon: ImageVector, onClick: () -> Unit) {
        cardGroupScope.item(
            onClick = onClick,
            leadingContent = { MuseSettingsIcon(icon) },
            headlineContent = { Text(headline) },
            supportingContent = { Text(stringResource(descRes)) },
            trailingContent = { ChevronRight() },
        )
    }

    override fun link(headline: String, desc: String, icon: ImageVector, onClick: () -> Unit) {
        cardGroupScope.item(
            onClick = onClick,
            leadingContent = { MuseSettingsIcon(icon) },
            headlineContent = { Text(headline) },
            supportingContent = { Text(desc) },
            trailingContent = { ChevronRight() },
        )
    }

    override fun link(headline: String, icon: ImageVector, onClick: () -> Unit) {
        cardGroupScope.item(
            onClick = onClick,
            leadingContent = { MuseSettingsIcon(icon) },
            headlineContent = { Text(headline) },
            trailingContent = { ChevronRight() },
        )
    }

    override fun switch(headline: String, descRes: Int, icon: ImageVector, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        cardGroupScope.item(
            leadingContent = { MuseSettingsIcon(icon) },
            headlineContent = { Text(headline) },
            supportingContent = { Text(stringResource(descRes)) },
            trailingContent = {
                MuseSwitch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                )
            },
        )
    }

    override fun checkUpdate(checking: Boolean, onCheck: () -> Unit) {
        cardGroupScope.item(
            onClick = onCheck,
            leadingContent = { MuseSettingsIcon(MuseIcons.refresh) },
            headlineContent = { Text(stringResource(R.string.settings_screen_check_update)) },
            supportingContent = { Text(stringResource(R.string.settings_screen_check_update_desc)) },
            trailingContent = {
                if (checking) {
                    MuseSpinner(
                        size = 20.dp,
                    )
                } else {
                    ChevronRight()
                }
            },
        )
    }
}

private interface CardGroupContentScope {
    fun link(headline: String, descRes: Int, icon: ImageVector, onClick: () -> Unit)
    fun link(headline: String, desc: String, icon: ImageVector, onClick: () -> Unit)
    fun link(headline: String, icon: ImageVector, onClick: () -> Unit)
    fun switch(headline: String, descRes: Int, icon: ImageVector, checked: Boolean, onCheckedChange: (Boolean) -> Unit)
    fun checkUpdate(checking: Boolean, onCheck: () -> Unit)
}

@Composable
private fun ChevronRight() {
    Icon(
        imageVector = MuseIcons.chevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.outline,
        modifier = Modifier.size(MuseIconSizes.iconMedium),
    )
}
