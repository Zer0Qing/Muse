package io.zer0.muse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import io.zer0.muse.ui.common.WindowWidthClass
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.GroupWork
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.zer0.muse.ui.common.IosSettingsIcon
import io.zer0.muse.ui.common.IosSwitch
import io.zer0.muse.ui.common.MuseToast
import io.zer0.muse.ui.common.rememberWindowWidthClass
import io.zer0.muse.ui.theme.MusePaddings
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.ProxyConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.components.CardGroup
import org.koin.compose.koinInject
import io.zer0.muse.update.UpdateNotifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.Refresh

/**
 * v0.34: 设置页 — 卡片分组 + DSL 架构。
 *
 * v1.132 重新整理分组:
 *  - 账户卡片(置顶)
 *  - 通用 — 外观 / 聊天 / 媒体 / 翻译 / 用户画像
 *  - 助手与 Agent — 助手 / Agent
 *  - 模型与服务 — 供应商 / 视觉 / 插件管理 / 视频生成
 *  - 记忆与知识 — 记忆通知 / RAG / 数据管理
 *  - 数据与备份 — 云备份 / 数据导入 / 工作区
 *  - 隐私与安全 — PII Guard / 安全 / 代理 / 审计日志
 *  - 关于 — 教程 / 关于 / 检查更新 / 调试日志 / 实验性 / 统计
 *
 * v1.132 新增:搜索功能(右上角搜索图标)。
 *  - 点击搜索图标 → 顶部栏切换为搜索输入框
 *  - 输入关键词后实时过滤所有设置项(标题 + 描述匹配)
 *  - 点击搜索结果项直接跳转到对应二级页
 *
 * v2.1 增强:搜索索引重构为 settingsIndex: List<SettingsEntry>。
 *  - 每项含 title(中文标题) / keywords(搜索关键词列表) / route(MuseRoutes 路由) / groupName(所属分组)
 *  - 过滤维度:title + keywords + groupName 三路匹配,中英文关键词均覆盖
 *  - 搜索结果展示:标题 + 所属分组(对标 iOS 设置 App)
 *  - 点击结果 → entry.onClick() 跳转(底层由调用方 navController.navigate(route) 执行)
 *  - 索引覆盖 46 项:备份/云备份/语音/TTS/ASR/外观/主题/模型/API Key/助手/记忆/知识库/
 *    工具/MCP/定时任务/主动消息/群聊/Web服务器/OCR/视觉辅助/安全/生物识别/开机自启/保持唤醒 等
 *
 * 每个 CardGroup 内的 item 共享圆角卡片(首项顶 20dp / 末项底 20dp / 中间 4dp),
 * 按下时圆角动画过渡(iOS 风格分组卡片的按压反馈)。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAssistants: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onOpenModelSettings: () -> Unit = {},
    onOpenMultiAgentSettings: () -> Unit = {},
    onOpenAgentSettings: () -> Unit = {},
    onOpenDataSettings: () -> Unit = {},
    onOpenAppearanceSettings: () -> Unit = {},
    onOpenChatSettings: () -> Unit = {},
    onOpenMemorySettings: () -> Unit = {},
    onOpenMediaSettings: () -> Unit = {},
    onOpenExperimentsSettings: () -> Unit = {},
    onOpenSecuritySettings: () -> Unit = {},
    onOpenProxySettings: () -> Unit = {},
    onOpenAboutSettings: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    /** v1.0.4: 打开我的报告页(周报/月报)。 */
    onOpenReports: () -> Unit = {},
    onOpenRagSettings: () -> Unit = {},
    onOpenDataImport: () -> Unit = {},
    /** v1.61-B: 打开使用教程页(新手引导)。 */
    onOpenTutorial: () -> Unit = {},
    /** v1.76: 打开用户画像编辑页(称呼 / 年龄 / 城市等)。 */
    onOpenUserProfile: () -> Unit = {},
    /** v1.97 gap8: 打开独立翻译页。 */
    onOpenTranslate: () -> Unit = {},
    /** v2.0: 打开数据管理页。 */
    onOpenVisionSettings: () -> Unit = {},
    onOpenDataManagement: () -> Unit = {},
    /** 打开调试日志页(从关于分组进入,展示最近 Logger 调用)。 */
    onOpenDebugLog: () -> Unit = {},
    /** P2-4: 打开审计日志页(从「数据与隐私」分组进入)。 */
    onOpenAuditLog: () -> Unit = {},
    /** P2-7: 打开工作区页(从「数据与隐私」分组进入,文件管理器)。 */
    onOpenWorkspace: () -> Unit = {},
    /** P2-8: 打开视频生成页(从「工具」分组进入)。 */
    onOpenVideoGeneration: () -> Unit = {},
    /** P2-10: 打开 Provider 插件管理页(从「模型与服务」分组进入)。 */
    onOpenProviderPlugins: () -> Unit = {},
    /** v1.133: 打开联网搜索页(从「模型与服务」分组进入,原 SettingsModelPage 内嵌)。 */
    onOpenWebSearch: () -> Unit = {},
    /** v1.133: 打开语音识别 ASR 页(从「模型与服务」分组进入,原 SettingsModelPage 内嵌)。 */
    onOpenAsr: () -> Unit = {},
    /** v1.133: 打开图像生成页(从「模型与服务」分组进入,原 SettingsModelPage 内嵌)。 */
    onOpenImageGen: () -> Unit = {},
    /** v1.133: 打开 MCP 服务器页(从「模型与服务」分组进入,原 SettingsModelPage 内嵌)。 */
    onOpenMcp: () -> Unit = {},
    /** v1.133: 打开助手资源页(从「助手与 Agent」分组进入,容纳收藏夹/世界书/快捷消息/模式注入/Skills/记忆开关)。 */
    onOpenAssistantResources: () -> Unit = {},
    /** v1.0.4: 打开通知监听页(从「助手与 Agent」分组进入,引导用户授权通知使用权)。 */
    onOpenNotificationListener: () -> Unit = {},
    /** v1.0.4: AI 工具管理页(从「助手与 Agent」分组进入,展示 ToolRegistry 中全部工具)。 */
    onOpenTools: () -> Unit = {},
    /**
     * 搜索结果跳转回调 — 传入 [MuseRoutes] 路由常量,由调用方执行 navController.navigate(route)。
     *
     * 用于搜索索引中无独立 onOpenXxx 回调的条目(如定时任务),默认空实现(向后兼容)。
     * 在 MainActivity 中接线为 `onNavigate = { route -> navController.navigate(route) }` 后生效。
     */
    onNavigate: (String) -> Unit = {},
) {
    val settings: SettingsRepository = koinInject()
    val updateNotifier: UpdateNotifier = koinInject()
    val proxyConfig by settings.proxyConfigFlow.collectAsStateWithLifecycle(initialValue = ProxyConfig())
    // PII Guard 开关(默认开启)
    val piiGuardEnabled by settings.piiGuardEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val proxyDisabled = stringResource(R.string.proxy_disabled)
    val proxyTitle = stringResource(R.string.proxy_title)
    val proxySubtitle = when {
        !proxyConfig.enabled -> proxyDisabled
        proxyConfig.host.isBlank() || proxyConfig.port <= 0 -> proxyDisabled
        else -> "${proxyConfig.type} ${proxyConfig.host}:${proxyConfig.port}"
    }
    // v1.133: 手动检查更新状态(checking=true 时禁用按钮并显示进度)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var checkingUpdate by remember { mutableStateOf(false) }
    // v1.132: 搜索状态(isSearching=true 时顶部栏切换为搜索框,LazyColumn 显示搜索结果)
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    // 进入搜索模式时自动聚焦输入框并弹出键盘
    androidx.compose.runtime.LaunchedEffect(isSearching) {
        if (isSearching) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    // v1.132: 预提取所有设置项的标题/描述字符串(供搜索过滤使用)
    val userProfileTitle = stringResource(R.string.settings_screen_user_profile)
    val userProfileDesc = stringResource(R.string.settings_screen_user_profile_desc)
    val appearanceTitle = stringResource(R.string.settings_screen_appearance_label)
    val appearanceDesc = stringResource(R.string.settings_screen_appearance_desc)
    val chatTitle = stringResource(R.string.settings_screen_chat)
    val chatDesc = stringResource(R.string.settings_screen_chat_desc)
    val mediaTitle = stringResource(R.string.settings_screen_media)
    val mediaDesc = stringResource(R.string.settings_screen_media_desc)
    val translateTitle = stringResource(R.string.settings_screen_translate)
    val translateDesc = stringResource(R.string.settings_screen_translate_desc)
    val assistantTitle = stringResource(R.string.settings_screen_assistant)
    val assistantDesc = stringResource(R.string.settings_screen_assistant_desc)
    val agentTitle = "Agent"
    val agentDesc = stringResource(R.string.settings_screen_agent_desc)
    val providerTitle = stringResource(R.string.settings_screen_provider)
    val providerDesc = stringResource(R.string.settings_screen_provider_desc)
    val visionTitle = stringResource(R.string.settings_screen_vision)
    val visionDesc = stringResource(R.string.settings_screen_vision_desc)
    val providerPluginsTitle = stringResource(R.string.provider_plugins_title)
    val videoGenTitle = stringResource(R.string.settings_screen_video_gen)
    val videoGenDesc = stringResource(R.string.settings_screen_video_gen_desc)
    val memoryTitle = stringResource(R.string.settings_screen_memory_notification)
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
    val securityTitle = stringResource(R.string.settings_screen_security)
    val securityDesc = stringResource(R.string.settings_screen_security_desc)
    val auditLogTitle = stringResource(R.string.settings_audit_log)
    val experimentsTitle = stringResource(R.string.settings_screen_experiments)
    val experimentsDesc = stringResource(R.string.settings_screen_experiments_desc)
    val statsTitle = stringResource(R.string.settings_screen_stats)
    val statsDesc = stringResource(R.string.settings_screen_stats_desc)
    val reportsTitle = stringResource(R.string.settings_screen_reports)
    val reportsDesc = stringResource(R.string.settings_screen_reports_desc)
    val tutorialTitle = stringResource(R.string.settings_screen_tutorial)
    val tutorialDesc = stringResource(R.string.settings_screen_tutorial_desc)
    val aboutTitle = stringResource(R.string.settings_screen_about)
    val aboutDesc = stringResource(R.string.settings_screen_about_desc)
    val checkUpdateTitle = stringResource(R.string.settings_screen_check_update)
    val checkUpdateDesc = stringResource(R.string.settings_screen_check_update_desc)
    val debugLogTitle = stringResource(R.string.settings_screen_debug_log)
    val debugLogDesc = stringResource(R.string.settings_screen_debug_log_desc)
    // v1.133: 5 个新拆分入口的标题/描述
    val webSearchEntryTitle = stringResource(R.string.settings_screen_web_search)
    val webSearchEntryDesc = stringResource(R.string.settings_screen_web_search_desc)
    val asrEntryTitle = stringResource(R.string.settings_screen_asr)
    val asrEntryDesc = stringResource(R.string.settings_screen_asr_desc)
    val imageGenEntryTitle = stringResource(R.string.settings_screen_image_gen)
    val imageGenEntryDesc = stringResource(R.string.settings_screen_image_gen_desc)
    val mcpEntryTitle = stringResource(R.string.settings_screen_mcp)
    val mcpEntryDesc = stringResource(R.string.settings_screen_mcp_desc)
    val assistantResourcesTitle = stringResource(R.string.settings_screen_assistant_resources)
    val assistantResourcesDesc = stringResource(R.string.settings_screen_assistant_resources_desc)
    // v1.0.4: 通知监听入口
    val notificationListenerTitle = stringResource(R.string.settings_screen_notification_listener)
    val notificationListenerDesc = stringResource(R.string.settings_screen_notification_listener_desc)
    // v1.0.4: AI 工具入口
    val toolsTitle = stringResource(R.string.settings_screen_tools)
    val toolsDesc = stringResource(R.string.settings_screen_tools_desc)
    val searchHint = stringResource(R.string.settings_search_hint)
    val noResults = stringResource(R.string.settings_search_no_results)

    // 设置项索引数据结构 — 每项包含标题、关键词列表、导航路由、所属分组、图标、跳转回调
    data class SettingsEntry(
        val title: String,               // 中文标题
        val keywords: List<String>,      // 搜索关键词列表(中英文均覆盖)
        val route: String,               // 导航路由(MuseRoutes 常量,空串表示无独立路由)
        val groupName: String,           // 所属分组(用于搜索结果展示)
        val icon: ImageVector,           // 列表项图标
        val onClick: () -> Unit,         // 跳转回调(实际由调用方 navController.navigate(route) 执行)
    )

    // 预置索引:覆盖所有设置子页面的关键设置项,内联定义便于维护。
    // 搜索时按 (title + keywords + groupName) 包含 query 过滤。
    val settingsIndex by remember(piiGuardEnabled, proxySubtitle, checkingUpdate) {
        mutableStateOf(
            listOf(
                // ── 通用分组 ──
                SettingsEntry("聊天行为", listOf("聊天", "对话", "消息", "输入", "发送"), MuseRoutes.SETTINGS_CHAT, "通用", Icons.Outlined.Forum, onOpenChatSettings),
                SettingsEntry("外观", listOf("外观", "显示", "界面", "字号", "字体"), MuseRoutes.SETTINGS_APPEARANCE, "通用", Icons.Outlined.Palette, onOpenAppearanceSettings),
                SettingsEntry("主题", listOf("主题", "配色", "深色", "浅色", "暗黑", "AMOLED", "颜色"), MuseRoutes.SETTINGS_APPEARANCE, "通用", Icons.Outlined.Palette, onOpenAppearanceSettings),
                SettingsEntry("媒体", listOf("媒体", "录音", "语音", "播报"), MuseRoutes.SETTINGS_MEDIA, "通用", Icons.Outlined.RecordVoiceOver, onOpenMediaSettings),
                SettingsEntry("TTS 语音播报", listOf("TTS", "tts", "语音播报", "朗读", "文字转语音", "TextToSpeech"), MuseRoutes.SETTINGS_MEDIA, "通用", Icons.Outlined.RecordVoiceOver, onOpenMediaSettings),
                SettingsEntry("AI 翻译", listOf("翻译", "translate", "语言", "互译", "源语言", "目标语言"), MuseRoutes.TRANSLATE, "通用", Icons.Outlined.Translate, onOpenTranslate),
                // v1.0.18: 快速记录入口(通用分组)
                SettingsEntry("快速记录", listOf("快速记录", "速记", "笔记", "quick note", "note", "记录"), MuseRoutes.QUICK_NOTES, "通用", Icons.Outlined.Lightbulb) { onNavigate(MuseRoutes.QUICK_NOTES) },
                SettingsEntry("群聊", listOf("群聊", "群组", "group", "多人", "启动页"), MuseRoutes.SETTINGS_APPEARANCE, "通用", Icons.Outlined.Forum, onOpenAppearanceSettings),
                SettingsEntry("用户画像", listOf("用户", "画像", "称呼", "年龄", "城市", "MBTI", "个性化"), MuseRoutes.USER_PROFILE_EDIT, "通用", Icons.Outlined.AccountCircle, onOpenUserProfile),

                // ── 助手与 Agent 分组 ──
                SettingsEntry("助手", listOf("助手", "assistant", "角色", "人设"), MuseRoutes.ASSISTANTS, "助手与 Agent", Icons.Outlined.Psychology, onOpenAssistants),
                SettingsEntry("Agent", listOf("Agent", "代理", "智能体", "自主"), MuseRoutes.SETTINGS_AGENT, "助手与 Agent", Icons.Outlined.GroupWork, onOpenAgentSettings),
                SettingsEntry("主动消息", listOf("主动消息", "主动", "推送", "定时发送", "proactive"), MuseRoutes.SETTINGS_AGENT, "助手与 Agent", Icons.Outlined.Notifications, onOpenAgentSettings),
                SettingsEntry("定时任务", listOf("定时任务", "定时", "计划任务", "scheduled", "task", "cron"), MuseRoutes.SCHEDULED_TASKS, "助手与 Agent", Icons.Outlined.Schedule) { onNavigate(MuseRoutes.SCHEDULED_TASKS) },
                SettingsEntry("助手资源", listOf("助手资源", "收藏夹", "世界书", "快捷消息", "模式注入", "Skills", "技能"), MuseRoutes.SETTINGS_ASSISTANT_RESOURCES, "助手与 Agent", Icons.Outlined.AutoAwesome, onOpenAssistantResources),
                SettingsEntry("通知监听", listOf("通知监听", "通知", "NotificationListener", "通知权限"), MuseRoutes.NOTIFICATION_LISTENER, "助手与 Agent", Icons.Outlined.Notifications, onOpenNotificationListener),
                SettingsEntry("AI 工具", listOf("工具", "AI工具", "ToolRegistry", "tool", "插件"), MuseRoutes.TOOLS, "助手与 Agent", Icons.Outlined.Build, onOpenTools),

                // ── 模型与服务分组 ──
                SettingsEntry("模型供应商", listOf("供应商", "模型", "provider", "API", "密钥"), MuseRoutes.SETTINGS_MODEL, "模型与服务", Icons.Outlined.SettingsEthernet, onOpenModelSettings),
                SettingsEntry("API Key", listOf("API Key", "密钥", "key", "token", "凭证", "apiKey"), MuseRoutes.SETTINGS_MODEL, "模型与服务", Icons.Outlined.Lock, onOpenModelSettings),
                SettingsEntry("视觉辅助", listOf("视觉辅助", "视觉", "vision", "看图", "图像理解"), MuseRoutes.SETTINGS_VISION, "模型与服务", Icons.Outlined.Visibility, onOpenVisionSettings),
                SettingsEntry("OCR 文字识别", listOf("OCR", "ocr", "文字识别", "图片文字", "识别"), MuseRoutes.SETTINGS_VISION, "模型与服务", Icons.Outlined.Visibility, onOpenVisionSettings),
                SettingsEntry("插件管理", listOf("插件", "plugin", "Provider插件", "导入"), MuseRoutes.PROVIDER_PLUGINS, "模型与服务", Icons.Outlined.Extension, onOpenProviderPlugins),
                SettingsEntry("视频生成", listOf("视频", "video", "生成视频"), MuseRoutes.VIDEO_GENERATION, "模型与服务", Icons.Outlined.Movie, onOpenVideoGeneration),
                SettingsEntry("联网搜索", listOf("联网搜索", "搜索", "web search", "网络搜索", "在线搜索"), MuseRoutes.SETTINGS_WEB_SEARCH, "模型与服务", Icons.Outlined.Language, onOpenWebSearch),
                SettingsEntry("语音识别 ASR", listOf("ASR", "asr", "语音识别", "speech", "转文字", "识别语音"), MuseRoutes.SETTINGS_ASR, "模型与服务", Icons.Outlined.Mic, onOpenAsr),
                SettingsEntry("图像生成", listOf("图像生成", "画图", "AI画", "image gen", "绘图"), MuseRoutes.SETTINGS_IMAGE_GEN, "模型与服务", Icons.Outlined.Image, onOpenImageGen),
                SettingsEntry("MCP 服务器", listOf("MCP", "mcp", "服务器", "Model Context Protocol", "工具协议"), MuseRoutes.SETTINGS_MCP, "模型与服务", Icons.Outlined.Hub, onOpenMcp),

                // ── 记忆与知识库分组 ──
                SettingsEntry("记忆与通知", listOf("记忆", "通知", "memory", "遗忘", "回忆"), MuseRoutes.SETTINGS_MEMORY, "记忆与知识库", Icons.Outlined.Psychology, onOpenMemorySettings),
                SettingsEntry("保持唤醒", listOf("保持唤醒", "唤醒", "wakelock", "不休眠", "常亮", "keep awake"), MuseRoutes.SETTINGS_MEMORY, "记忆与知识库", Icons.Outlined.Bolt, onOpenMemorySettings),
                SettingsEntry("开机自启", listOf("开机自启", "自启", "自启动", "开机", "boot", "auto launch", "BootReceiver"), MuseRoutes.SETTINGS_MEMORY, "记忆与知识库", Icons.Outlined.Bolt, onOpenMemorySettings),
                SettingsEntry("RAG 知识库", listOf("RAG", "知识库", "rag", "检索", "向量", "文档"), MuseRoutes.SETTINGS_RAG, "记忆与知识库", Icons.AutoMirrored.Outlined.MenuBook, onOpenRagSettings),

                // ── 数据管理分组 ──
                SettingsEntry("数据管理", listOf("数据管理", "数据", "存储", "清理", "缓存"), MuseRoutes.DATA_MANAGEMENT, "数据管理", Icons.Outlined.Storage, onOpenDataManagement),
                SettingsEntry("云备份", listOf("备份", "云备份", "cloud", "backup", "S3", "WebDAV", "同步"), MuseRoutes.SETTINGS_DATA, "数据管理", Icons.Outlined.Cloud, onOpenDataSettings),
                SettingsEntry("Web 服务器", listOf("Web服务器", "Web", "服务器", "webserver", "远程访问", "局域网", "PIN"), MuseRoutes.SETTINGS_DATA, "数据管理", Icons.Outlined.Language, onOpenDataSettings),
                SettingsEntry("数据导入", listOf("数据导入", "导入", "import", "恢复数据"), MuseRoutes.SETTINGS_DATA_IMPORT, "数据管理", Icons.Outlined.CloudUpload, onOpenDataImport),
                SettingsEntry("工作区", listOf("工作区", "文件管理", "workspace", "文件", "目录"), MuseRoutes.WORKSPACE, "数据管理", Icons.Outlined.Folder, onOpenWorkspace),

                // ── 隐私与安全分组 ──
                SettingsEntry("PII Guard", listOf("PII", "隐私", "脱敏", "pii guard", "信息保护"), "", "隐私与安全", Icons.Outlined.PrivacyTip) {},
                SettingsEntry("安全", listOf("安全", "锁屏", "PIN", "密码", "应用锁", "share"), MuseRoutes.SETTINGS_SECURITY, "隐私与安全", Icons.Outlined.Lock, onOpenSecuritySettings),
                SettingsEntry("生物识别", listOf("生物识别", "指纹", "biometric", "指纹解锁", "面容"), MuseRoutes.SETTINGS_SECURITY, "隐私与安全", Icons.Outlined.Lock, onOpenSecuritySettings),
                SettingsEntry("网络代理", listOf("代理", "proxy", "网络", "VPN", "HTTP代理"), MuseRoutes.SETTINGS_PROXY, "隐私与安全", Icons.Outlined.Tune, onOpenProxySettings),
                SettingsEntry("审计日志", listOf("审计", "日志", "audit", "操作记录", "审计日志"), MuseRoutes.AUDIT_LOG, "隐私与安全", Icons.Outlined.History, onOpenAuditLog),

                // ── 关于分组 ──
                SettingsEntry("使用教程", listOf("教程", "新手", "引导", "tutorial", "帮助"), MuseRoutes.SETTINGS_TUTORIAL, "关于", Icons.Outlined.School, onOpenTutorial),
                SettingsEntry("关于", listOf("关于", "版本", "about", "信息"), MuseRoutes.SETTINGS_ABOUT, "关于", Icons.Outlined.Info, onOpenAboutSettings),
                SettingsEntry("检查更新", listOf("检查更新", "更新", "update", "版本", "升级"), "", "关于", Icons.Outlined.Refresh) {},
                SettingsEntry("调试日志", listOf("调试", "日志", "debug", "log", "Logger"), MuseRoutes.DEBUG, "关于", Icons.Outlined.BugReport, onOpenDebugLog),

                SettingsEntry("实验性功能", listOf("实验性", "实验", "experimental", "beta", "试验"), MuseRoutes.SETTINGS_EXPERIMENTS, "关于", Icons.Outlined.Science, onOpenExperimentsSettings),
                SettingsEntry("统计", listOf("统计", "使用统计", "stats", "热力图", "数据"), MuseRoutes.STATS, "关于", Icons.Outlined.BarChart, onOpenStats),
                SettingsEntry("我的报告", listOf("报告", "周报", "月报", "年报", "report"), MuseRoutes.REPORTS, "关于", Icons.Outlined.AutoAwesome, onOpenReports),
            ),
        )
    }
    // 过滤后的搜索结果(query 为空时返回全部,否则按 title/keywords/groupName 包含查询)
    val filteredEntries by remember(searchQuery, settingsIndex) {
        mutableStateOf(
            if (searchQuery.isBlank()) settingsIndex
            else settingsIndex.filter { entry ->
                entry.title.contains(searchQuery, ignoreCase = true) ||
                    entry.groupName.contains(searchQuery, ignoreCase = true) ||
                    entry.keywords.any { it.contains(searchQuery, ignoreCase = true) }
            },
        )
    }

    // 预提取 semantics contentDescription 字符串
    val appearanceGroupCd = stringResource(R.string.settings_screen_appearance_group_cd)
    val userProfileCd = stringResource(R.string.settings_screen_user_profile_cd)
    val appearanceCd = stringResource(R.string.settings_screen_appearance_cd)
    val chatCd = stringResource(R.string.settings_screen_chat_cd)
    val mediaCd = stringResource(R.string.settings_screen_media_cd)
    val memoryDataGroupCd = stringResource(R.string.settings_screen_memory_data_group_cd)
    val memoryNotificationCd = stringResource(R.string.settings_screen_memory_notification_cd)
    val dataBackupCd = stringResource(R.string.settings_screen_data_backup_cd)
    val dataImportCd = stringResource(R.string.settings_screen_data_import_cd)
    val ragCd = stringResource(R.string.settings_screen_rag_cd)
    val piiGuardCd = stringResource(R.string.settings_screen_pii_guard_cd)
    val privacyGroupCd = stringResource(R.string.settings_screen_privacy_group_cd)
    val modelServiceGroupCd = stringResource(R.string.settings_screen_model_service_group_cd)
    val providerCd = stringResource(R.string.settings_screen_provider_cd)
    val assistantCd = stringResource(R.string.settings_screen_assistant_cd)
    val agentCd = stringResource(R.string.settings_screen_agent_cd)
    val advancedGroupCd = stringResource(R.string.settings_screen_advanced_group_cd)
    val securityCd = stringResource(R.string.settings_screen_security_cd)
    val proxyCd = stringResource(R.string.settings_screen_proxy_cd, proxyTitle, proxySubtitle)
    val experimentsCd = stringResource(R.string.settings_screen_experiments_cd)
    val statsCd = stringResource(R.string.settings_screen_stats_cd)
    val aboutGroupCd = stringResource(R.string.settings_screen_about_group_cd)
    val tutorialCd = stringResource(R.string.settings_screen_tutorial_cd)
    val aboutCd = stringResource(R.string.settings_screen_about_cd)
    val debugLogCd = stringResource(R.string.settings_screen_debug_log_cd)
    val toolsGroupCd = stringResource(R.string.settings_screen_tools_group_cd)
    val translateCd = stringResource(R.string.settings_screen_translate_cd)
    // v1.0.18: 快速记录入口 contentDescription
    val quickNotesCd = stringResource(R.string.settings_screen_quick_notes_cd)
    val videoGenCd = stringResource(R.string.settings_screen_video_gen_cd)
    val checkUpdateCd = stringResource(R.string.settings_screen_check_update_cd)
    // P2-1: 大屏(Expanded)下 LazyColumn 居中限宽 720dp,避免列表项过度拉伸
    val widthClass = rememberWindowWidthClass()

    Scaffold(
        topBar = {
            // v1.131: 显式状态栏遮罩,解决 enableEdgeToEdge 导致的透明状态栏问题
            Box(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .align(Alignment.TopCenter),
                )
                // iOS 风格 Large Title 顶部栏(替代 Material LargeTopAppBar)
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    // 返回按钮 + 搜索按钮行(v1.132: 右上角加搜索图标,与返回键同水平)
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        if (isSearching) {
                            // 搜索模式:返回按钮 = 退出搜索;中间是搜索输入框
                            IconButton(onClick = {
                                isSearching = false
                                searchQuery = ""
                                keyboard?.hide()
                            }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.settings_screen_back_cd),
                                )
                            }
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(focusRequester),
                                placeholder = { Text(searchHint) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Search,
                                ),
                                // 输入框右侧清空按钮
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(
                                                Icons.Outlined.Refresh,
                                                contentDescription = null,
                                            )
                                        }
                                    }
                                },
                            )
                        } else {
                            // 普通模式:返回按钮 + 右上角搜索按钮
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.settings_screen_back_cd),
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            // v1.132: 右上角搜索图标,与返回键同水平
                            IconButton(onClick = { isSearching = true }) {
                                Icon(
                                    Icons.Outlined.Search,
                                    contentDescription = stringResource(R.string.settings_search_cd),
                                )
                            }
                        }
                    }
                    // Large Title — 32dp 粗体(搜索模式隐藏,让出空间给搜索框)
                    if (!isSearching) {
                        Text(
                            text = stringResource(R.string.settings_screen_title),
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 32.sp,
                            ),
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        },
        // v1.0.27: 设置页背景改为 surfaceVariant(浅灰),让白色卡片区块从背景中浮出
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        // P2-1: Box 包裹 LazyColumn,Expanded 模式下用 TopCenter 居中限宽后的列表
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (widthClass == WindowWidthClass.Expanded) {
                            Modifier.widthIn(max = 720.dp)
                        } else {
                            Modifier
                        }
                    ),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + 16.dp,
                    // v1.99: 大R角/曲面屏 — 保留 innerPadding 的横向 safeDrawing,避免卡片贴边裁切
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
            // v1.132: 搜索模式 — 只显示搜索结果列表
            if (isSearching) {
                item(key = "search_status") {
                    Text(
                        text = if (searchQuery.isBlank()) stringResource(R.string.settings_search_prompt)
                        else "${filteredEntries.size} $noResults",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                if (filteredEntries.isEmpty()) {
                    item(key = "search_empty") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = noResults,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                } else {
                    items(filteredEntries, key = { it.title + it.route }) { entry ->
                        CardGroup(
                            modifier = Modifier.padding(horizontal = 16.dp),
                        ) {
                            item(
                                onClick = {
                                    keyboard?.hide()
                                    isSearching = false
                                    searchQuery = ""
                                    entry.onClick()
                                },
                                leadingContent = {
                                    IosSettingsIcon(entry.icon)
                                },
                                headlineContent = { Text(entry.title) },
                                supportingContent = { Text(entry.groupName) },
                                trailingContent = { ChevronRight() },
                            )
                        }
                    }
                }
            } else {
                // ── 账户卡片(置顶,未登录状态占位)──
                // v1.0.29: 增加顶部间距远离标题,减小底部间距靠近通用分组。
                item(key = "account") {
                    io.zer0.muse.ui.account.AccountCard(
                        onClick = onOpenAccount,
                        modifier = Modifier
                            .padding(horizontal = MusePaddings.screen)
                            .padding(top = MusePaddings.sectionGap),
                    )
                }

                // v1.0.29: 账户卡片与下方设置分组保持紧凑。
                item(key = "account_spacer") {
                    Spacer(Modifier.height(MusePaddings.contentGap))
                }

                // ── 通用(按使用频率排序:聊天/外观/媒体/翻译/用户画像)──
                item(key = "general") {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = {
                            Box(modifier = Modifier.semantics { contentDescription = appearanceGroupCd }) {
                                Text(stringResource(R.string.settings_screen_general))
                            }
                        },
                    ) {
                        item(
                            modifier = Modifier.semantics { contentDescription = chatCd },
                            onClick = onOpenChatSettings,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Forum) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_chat)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_chat_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        item(
                            modifier = Modifier.semantics { contentDescription = appearanceCd },
                            onClick = onOpenAppearanceSettings,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Palette) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_appearance_label)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_appearance_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        item(
                            modifier = Modifier.semantics { contentDescription = mediaCd },
                            onClick = onOpenMediaSettings,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.RecordVoiceOver) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_media)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_media_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        item(
                            modifier = Modifier.semantics { contentDescription = translateCd },
                            onClick = onOpenTranslate,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Translate) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_translate)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_translate_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        // v1.0.18: 快速记录入口
                        item(
                            modifier = Modifier.semantics { contentDescription = quickNotesCd },
                            onClick = { onNavigate(MuseRoutes.QUICK_NOTES) },
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Lightbulb) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_quick_notes)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_quick_notes_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        item(
                            modifier = Modifier.semantics { contentDescription = userProfileCd },
                            onClick = onOpenUserProfile,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.AccountCircle) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_user_profile)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_user_profile_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                    }
                }

                // ── 助手与 Agent ──
                item(key = "assistant_agent") {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = {
                            Box(modifier = Modifier.semantics { contentDescription = assistantCd }) {
                                Text(stringResource(R.string.settings_screen_assistant_agent))
                            }
                        },
                    ) {
                        item(
                            modifier = Modifier.semantics { contentDescription = assistantCd },
                            onClick = onOpenAssistants,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Psychology) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_assistant)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_assistant_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        item(
                            modifier = Modifier.semantics { contentDescription = agentCd },
                            onClick = onOpenAgentSettings,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.GroupWork) },
                            headlineContent = { Text("Agent") },
                            supportingContent = { Text(stringResource(R.string.settings_screen_agent_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        // v1.133: 助手资源(收藏夹/世界书/快捷消息/模式注入/Skills/记忆开关,原 SettingsModelPage 内嵌)
                        item(
                            onClick = onOpenAssistantResources,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.AutoAwesome) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_assistant_resources)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_assistant_resources_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        // v1.0.4: 通知监听(授权后 AI 可在聊天中查询其他 App 的通知)
                        item(
                            onClick = onOpenNotificationListener,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Notifications) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_notification_listener)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_notification_listener_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        // v1.0.4: AI 工具管理(展示 ToolRegistry 中全部工具 + 风险等级)
                        item(
                            onClick = onOpenTools,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Build) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_tools)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_tools_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                    }
                }

                // ── 模型与服务(供应商 / 视觉 / 插件 / 视频生成)──
                item(key = "model_service") {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = {
                            Box(modifier = Modifier.semantics { contentDescription = modelServiceGroupCd }) {
                                Text(stringResource(R.string.settings_screen_model_service))
                            }
                        },
                    ) {
                        item(
                            modifier = Modifier.semantics { contentDescription = providerCd },
                            onClick = onOpenModelSettings,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.SettingsEthernet) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_provider)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_provider_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        item(
                            onClick = onOpenVisionSettings,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Visibility) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_vision)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_vision_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        // P2-10: 插件管理入口(从 JSON 文件导入自定义 Provider)
                        item(
                            onClick = onOpenProviderPlugins,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Extension) },
                            headlineContent = { Text(stringResource(R.string.provider_plugins_title)) },
                            trailingContent = { ChevronRight() },
                        )
                        // P2-8: 视频生成入口
                        item(
                            modifier = Modifier.semantics { contentDescription = videoGenCd },
                            onClick = onOpenVideoGeneration,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Movie) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_video_gen)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_video_gen_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        // v1.133: 联网搜索(从 SettingsModelPage 拆出)
                        item(
                            onClick = onOpenWebSearch,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Language) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_web_search)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_web_search_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        // v1.133: 语音识别 ASR
                        item(
                            onClick = onOpenAsr,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Mic) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_asr)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_asr_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        // v1.133: 图像生成
                        item(
                            onClick = onOpenImageGen,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Image) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_image_gen)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_image_gen_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        // v1.133: MCP 服务器
                        item(
                            onClick = onOpenMcp,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Hub) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_mcp)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_mcp_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                    }
                }

                // ── 记忆与知识库(记忆通知 / RAG)──
                item(key = "memory_knowledge") {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = {
                            Box(modifier = Modifier.semantics { contentDescription = memoryDataGroupCd }) {
                                Text(stringResource(R.string.settings_screen_memory_knowledge))
                            }
                        },
                    ) {
                        item(
                            modifier = Modifier.semantics { contentDescription = memoryNotificationCd },
                            onClick = onOpenMemorySettings,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Psychology) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_memory_notification)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_memory_notification_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        item(
                            modifier = Modifier.semantics { contentDescription = ragCd },
                            onClick = onOpenRagSettings,
                            leadingContent = { IosSettingsIcon(Icons.AutoMirrored.Outlined.MenuBook) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_rag)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_rag_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                    }
                }

                // ── 数据管理(数据管理 / 云备份 / 数据导入 / 工作区)──
                item(key = "data_management") {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = {
                            Box(modifier = Modifier.semantics { contentDescription = dataBackupCd }) {
                                Text(stringResource(R.string.settings_screen_data_management_group))
                            }
                        },
                    ) {
                        item(
                            onClick = onOpenDataManagement,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Storage) },
                            headlineContent = { Text(stringResource(R.string.data_management_entry)) },
                            supportingContent = { Text(stringResource(R.string.data_management_entry_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        item(
                            modifier = Modifier.semantics { contentDescription = dataBackupCd },
                            onClick = onOpenDataSettings,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Cloud) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_data_backup)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_data_backup_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        item(
                            modifier = Modifier.semantics { contentDescription = dataImportCd },
                            onClick = onOpenDataImport,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.CloudUpload) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_data_import)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_data_import_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        // P2-7: 工作区入口(文件管理器)
                        item(
                            onClick = onOpenWorkspace,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Folder) },
                            headlineContent = { Text(stringResource(R.string.workspace_title)) },
                            supportingContent = { Text(stringResource(R.string.workspace_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                    }
                }

                // ── 隐私与安全(PII Guard / 安全 / 代理 / 审计日志)──
                item(key = "privacy_security") {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = {
                            Box(modifier = Modifier.semantics { contentDescription = privacyGroupCd }) {
                                Text(stringResource(R.string.settings_screen_privacy))
                            }
                        },
                    ) {
                        // PII Guard 开关
                        item(
                            modifier = Modifier.semantics { contentDescription = piiGuardCd },
                            leadingContent = { IosSettingsIcon(Icons.Outlined.PrivacyTip) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_pii_guard)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_pii_guard_desc)) },
                            trailingContent = {
                                IosSwitch(
                                    checked = piiGuardEnabled,
                                    onCheckedChange = { v -> scope.launch { settings.savePiiGuardEnabled(v) } },
                                )
                            },
                        )
                        item(
                            modifier = Modifier.semantics { contentDescription = securityCd },
                            onClick = onOpenSecuritySettings,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Lock) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_security)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_security_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        item(
                            modifier = Modifier.semantics { contentDescription = proxyCd },
                            onClick = onOpenProxySettings,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Tune) },
                            headlineContent = { Text(proxyTitle) },
                            supportingContent = { Text(proxySubtitle) },
                            trailingContent = { ChevronRight() },
                        )
                        // P2-4: 审计日志入口
                        item(
                            onClick = onOpenAuditLog,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.History) },
                            headlineContent = { Text(stringResource(R.string.settings_audit_log)) },
                            trailingContent = { ChevronRight() },
                        )
                    }
                }

                // ── 关于(教程 / 关于 / 检查更新 / 调试日志 / 实验性 / 统计)──
                item(key = "about") {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = {
                            Box(modifier = Modifier.semantics { contentDescription = aboutGroupCd }) {
                                Text(stringResource(R.string.settings_screen_about))
                            }
                        },
                    ) {
                        item(
                            modifier = Modifier.semantics { contentDescription = tutorialCd },
                            onClick = onOpenTutorial,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.School) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_tutorial)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_tutorial_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        item(
                            modifier = Modifier.semantics { contentDescription = aboutCd },
                            onClick = onOpenAboutSettings,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Info) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_about)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_about_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        // v1.133: 手动检查更新 — forceCheck=true,跳过 24h 间隔限制
                        item(
                            modifier = Modifier.semantics { contentDescription = checkUpdateCd },
                            onClick = {
                                if (checkingUpdate) return@item
                                checkingUpdate = true
                                scope.launch {
                                    val beforeJson = runCatching {
                                        settings.latestReleaseInfoFlow.first()
                                    }.getOrNull()
                                    updateNotifier.checkAndNotify(context, forceCheck = true)
                                    checkingUpdate = false
                                    val latest = runCatching {
                                        settings.latestReleaseInfoFlow.first()
                                    }.getOrNull()
                                    if (latest != null && latest != beforeJson) {
                                        MuseToast.show(context.getString(R.string.update_found_new))
                                    } else if (latest == null) {
                                        MuseToast.show(context.getString(R.string.update_already_latest))
                                    }
                                }
                            },
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Refresh) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_check_update)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_check_update_desc)) },
                            trailingContent = {
                                if (checkingUpdate) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    ChevronRight()
                                }
                            },
                        )
                        item(
                            modifier = Modifier.semantics { contentDescription = debugLogCd },
                            onClick = onOpenDebugLog,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.BugReport) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_debug_log)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_debug_log_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        item(
                            modifier = Modifier.semantics { contentDescription = experimentsCd },
                            onClick = onOpenExperimentsSettings,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.Science) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_experiments)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_experiments_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        item(
                            modifier = Modifier.semantics { contentDescription = statsCd },
                            onClick = onOpenStats,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.BarChart) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_stats)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_stats_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                        item(
                            onClick = onOpenReports,
                            leadingContent = { IosSettingsIcon(Icons.Outlined.AutoAwesome) },
                            headlineContent = { Text(stringResource(R.string.settings_screen_reports)) },
                            supportingContent = { Text(stringResource(R.string.settings_screen_reports_desc)) },
                            trailingContent = { ChevronRight() },
                        )
                    }
                }
            }
        }
        }
    }
}

/**
 * v0.34: 右箭头指示符 — 用于 CardGroup item 的 trailingContent,
 * 提示该行可点击进入下一级。对标 iOS SwiftUI Form 中的 ">" chevron。
 */
@Composable
private fun ChevronRight() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.outline,
    )
}
