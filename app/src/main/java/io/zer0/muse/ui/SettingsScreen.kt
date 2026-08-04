package io.zer0.muse.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.ProxyConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.form.MuseSettingsIcon
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.media.WindowWidthClass
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.update.UpdateNotifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v2.4 设置页 — iOS / MANUS 风格全量重写。
 *
 * 保持 v1.132 的搜索索引与分组结构不变,仅重写视觉层:
 *  - 暖白背景(background),白色卡片浮于其上
 *  - MuseTopBar 大标题,右侧搜索入口
 *  - 搜索态顶部切换为圆角搜索框,结果以独立卡片呈现
 *  - 所有设置项统一使用 MuseSettingsIcon + CardGroup
 *  - 分组标题使用次级文字色,营造清晰层级
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
    onOpenRagSettings: () -> Unit = {},
    onOpenDataImport: () -> Unit = {},
    onOpenTutorial: () -> Unit = {},
    onOpenUserProfile: () -> Unit = {},
    onOpenTranslate: () -> Unit = {},
    onOpenVisionSettings: () -> Unit = {},
    onOpenDataManagement: () -> Unit = {},
    onOpenDebugLog: () -> Unit = {},
    onOpenAuditLog: () -> Unit = {},
    onOpenWorkspace: () -> Unit = {},
    onOpenVideoGeneration: () -> Unit = {},
    onOpenProviderPlugins: () -> Unit = {},
    onOpenWebSearch: () -> Unit = {},
    onOpenAsr: () -> Unit = {},
    onOpenImageGen: () -> Unit = {},
    onOpenVideoGenSettings: () -> Unit = {},
    onOpenMcp: () -> Unit = {},
    onOpenAssistantResources: () -> Unit = {},
    onOpenNotificationListener: () -> Unit = {},
    onOpenTools: () -> Unit = {},
    onNavigate: (String) -> Unit = {},
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
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearching) {
        if (isSearching) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    // region 搜索索引(与 v1.132 保持一致)
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
    val taskRoutingTitle = stringResource(R.string.settings_task_routing_title)
    val visionTitle = stringResource(R.string.settings_screen_vision)
    val visionDesc = stringResource(R.string.settings_screen_vision_desc)
    val providerPluginsTitle = stringResource(R.string.provider_plugins_title)
    val musePluginsTitle = stringResource(R.string.muse_plugins_external)
    val pluginManageTitle = stringResource(R.string.muse_plugins_manage)
    val videoGenTitle = stringResource(R.string.settings_screen_video_gen)
    val videoGenDesc = stringResource(R.string.settings_screen_video_gen_desc)
    val videoGenConfigTitle = stringResource(R.string.settings_screen_video_gen_config)
    val videoGenConfigDesc = stringResource(R.string.settings_screen_video_gen_config_desc)
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
    val tutorialTitle = stringResource(R.string.settings_screen_tutorial)
    val tutorialDesc = stringResource(R.string.settings_screen_tutorial_desc)
    val aboutTitle = stringResource(R.string.settings_screen_about)
    val aboutDesc = stringResource(R.string.settings_screen_about_desc)
    val checkUpdateTitle = stringResource(R.string.settings_screen_check_update)
    val checkUpdateDesc = stringResource(R.string.settings_screen_check_update_desc)
    val debugLogTitle = stringResource(R.string.settings_screen_debug_log)
    val debugLogDesc = stringResource(R.string.settings_screen_debug_log_desc)
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
    val notificationListenerTitle = stringResource(R.string.settings_screen_notification_listener)
    val notificationListenerDesc = stringResource(R.string.settings_screen_notification_listener_desc)
    val toolsTitle = stringResource(R.string.settings_screen_tools)
    val toolsDesc = stringResource(R.string.settings_screen_tools_desc)
    val quickNotesTitle = stringResource(R.string.settings_screen_quick_notes)
    val searchHint = stringResource(R.string.settings_search_hint)
    val noResults = stringResource(R.string.settings_search_no_results)

    val groupGeneral = stringResource(R.string.settings_screen_general)
    val groupAssistantAgent = stringResource(R.string.settings_screen_assistant_agent)
    val groupAiModels = stringResource(R.string.settings_screen_ai_models)
    val groupMemoryKnowledge = stringResource(R.string.settings_screen_memory_knowledge)
    val groupDataManagement = stringResource(R.string.settings_screen_data_management_group)
    val groupAbout = stringResource(R.string.settings_screen_about)

    data class SettingsEntry(
        val title: String,
        val keywords: List<String>,
        val route: String,
        val groupName: String,
        val icon: ImageVector,
        val onClick: () -> Unit,
    )

    val settingsIndex by remember(piiGuardEnabled, proxySubtitle, checkingUpdate) {
        mutableStateOf(
            listOf(
                // 通用
                SettingsEntry(chatTitle, listOf("聊天", "对话", "消息", "输入", "发送", "liaotian", "duihua", "xiaoxi", "shuru", "fasong", "lt", "dh", "xx", "全屏编辑", "展开", "气泡", "阴影", "模型", "MOOD", "思维链", "快捷键", "引用回复"), MuseRoutes.SETTINGS_CHAT, groupGeneral, TablerIcons.MessageCircle, onOpenChatSettings),
                SettingsEntry(appearanceTitle, listOf("外观", "显示", "界面", "字号", "字体", "waiguan", "xianshi", "jiemian", "zihao", "ziti", "wg", "xs", "jm", "zt", "主题", "圆角", "启动页", "引导", "壁纸"), MuseRoutes.SETTINGS_APPEARANCE, groupGeneral, TablerIcons.ColorSwatch, onOpenAppearanceSettings),
                SettingsEntry("主题", listOf("主题", "配色", "深色", "浅色", "暗黑", "AMOLED", "颜色", "zhuti", "peise", "shense", "qianse", "anhe", "yase", "zt", "ps", "ss", "qs"), MuseRoutes.SETTINGS_APPEARANCE, groupGeneral, TablerIcons.ColorSwatch, onOpenAppearanceSettings),
                SettingsEntry(mediaTitle, listOf("媒体", "录音", "语音", "播报", "meiti", "luyin", "yuyin", "bobao", "mt", "ly", "yy", "bb"), MuseRoutes.SETTINGS_MEDIA, groupGeneral, TablerIcons.Microphone, onOpenMediaSettings),
                SettingsEntry("TTS 语音播报", listOf("TTS", "tts", "语音播报", "朗读", "文字转语音", "TextToSpeech", "yuyinbobao", "langdu", "wenzi", "yybb", "ld"), MuseRoutes.SETTINGS_MEDIA, groupGeneral, TablerIcons.Microphone, onOpenMediaSettings),
                SettingsEntry(translateTitle, listOf("翻译", "translate", "语言", "互译", "源语言", "目标语言", "fanyi", "yuyan", "huyi", "yuanyuyan", "mubiaoyuyan", "fy", "yy"), MuseRoutes.TRANSLATE, groupGeneral, TablerIcons.Language, onOpenTranslate),
                SettingsEntry("快速记录", listOf("快速记录", "速记", "笔记", "quick note", "note", "记录", "kuaisujilu", "suji", "biji", "jilu", "ksjl", "sj", "bj", "jl"), MuseRoutes.QUICK_NOTES, groupGeneral, TablerIcons.Bulb) { onNavigate(MuseRoutes.QUICK_NOTES) },
                SettingsEntry("群聊", listOf("群聊", "群组", "group", "多人", "启动页", "qunliao", "qunzu", "duoren", "qidongye", "ql", "qz"), MuseRoutes.SETTINGS_APPEARANCE, groupGeneral, TablerIcons.MessageCircle, onOpenAppearanceSettings),

                // 助手与 Agent
                SettingsEntry(assistantTitle, listOf("助手", "assistant", "角色", "人设", "zhushou", "juese", "renshe", "zs", "js", "rs"), MuseRoutes.ASSISTANTS, groupAssistantAgent, TablerIcons.Atom, onOpenAssistants),
                SettingsEntry(agentTitle, listOf("Agent", "代理", "智能体", "自主", "daili", "zhinengti", "zizhu", "dl", "znt"), MuseRoutes.SETTINGS_AGENT, groupAssistantAgent, TablerIcons.Users, onOpenAgentSettings),
                SettingsEntry("主动消息", listOf("主动消息", "主动", "推送", "定时发送", "proactive", "zhudongxiaoxi", "zhudong", "tuisong", "dingshifasong", "zdxx", "zd", "ts"), MuseRoutes.SETTINGS_AGENT, groupAssistantAgent, TablerIcons.Bell, onOpenAgentSettings),
                SettingsEntry("定时任务", listOf("定时任务", "定时", "计划任务", "scheduled", "task", "cron", "dingshirenwu", "dingshi", "jihuarenwu", "dsrw", "ds", "jhrw"), MuseRoutes.SCHEDULED_TASKS, groupAssistantAgent, Icons.Outlined.Schedule) { onNavigate(MuseRoutes.SCHEDULED_TASKS) },
                SettingsEntry(assistantResourcesTitle, listOf("助手资源", "收藏夹", "世界书", "快捷消息", "模式注入", "Skills", "技能", "zhushouziyuan", "shoucangjia", "shijieshu", "kuaijiexiaoxi", "moshizhur", "jineng", "zszy", "scj", "sjs", "kjxx", "mszr", "jn"), MuseRoutes.SETTINGS_ASSISTANT_RESOURCES, groupAssistantAgent, TablerIcons.Stars, onOpenAssistantResources),
                SettingsEntry(notificationListenerTitle, listOf("通知监听", "通知", "NotificationListener", "通知权限", "tongzhijianting", "tongzhi", "tongzhiquanxian", "tzjl", "tz", "tzqx"), MuseRoutes.NOTIFICATION_LISTENER, groupAssistantAgent, TablerIcons.Bell, onOpenNotificationListener),
                SettingsEntry(toolsTitle, listOf("工具", "AI工具", "ToolRegistry", "tool", "插件", "gongju", "AIgongju", "chajian", "gj", "AIgj", "cj"), MuseRoutes.TOOLS, groupAssistantAgent, TablerIcons.Tools, onOpenTools),
                // P3-3: 权限配置向导(无障碍 / Shizuku / Root 三通道)
                SettingsEntry("权限配置向导", listOf("权限", "无障碍", "Shizuku", "Root", "UI自动化", "permission", "accessibility", "quanxian", "wuzhangai", "UIzidonghua", "qx", "wza"), MuseRoutes.SETTINGS_PERMISSION_WIZARD, groupAssistantAgent, TablerIcons.Lock) { onNavigate(MuseRoutes.SETTINGS_PERMISSION_WIZARD) },

                // AI 模型与能力(从原「助手与 Agent」拆分)
                SettingsEntry(providerTitle, listOf("供应商", "模型", "provider", "API", "密钥", "gongyingshang", "moxing", "miyao", "gys", "mx", "my", "绘图", "Agnes", "DALL-E", "绘图供应商"), MuseRoutes.SETTINGS_MODEL, groupAiModels, TablerIcons.Settings, onOpenModelSettings),
                SettingsEntry("API Key", listOf("API Key", "密钥", "key", "token", "凭证", "apiKey", "miyao", "pingzheng"), MuseRoutes.SETTINGS_MODEL, groupAiModels, TablerIcons.Lock, onOpenModelSettings),
                SettingsEntry(taskRoutingTitle, listOf("任务路由", "路由", "自动切换", "模型", "renwuluyou", "luyou", "zidongqiehuan", "moxing", "rwly", "ly", "zdqh", "mx"), MuseRoutes.SETTINGS_TASK_ROUTING, groupAiModels, TablerIcons.Adjustments) { onNavigate(MuseRoutes.SETTINGS_TASK_ROUTING) },
                SettingsEntry(visionTitle, listOf("视觉辅助", "视觉", "vision", "看图", "图像理解", "shijuefuzhu", "shijue", "kantu", "tuxianglijie", "sjfz", "sj", "kt", "txlj"), MuseRoutes.SETTINGS_VISION, groupAiModels, TablerIcons.Eye, onOpenVisionSettings),
                SettingsEntry("OCR 文字识别", listOf("OCR", "ocr", "文字识别", "图片文字", "识别", "wenzi", "shibie", "tupianwenzi", "wzsb", "tpwz", "sb"), MuseRoutes.SETTINGS_VISION, groupAiModels, TablerIcons.Eye, onOpenVisionSettings),
                SettingsEntry(
                    pluginManageTitle,
                    listOf("插件管理", "外部插件", "muse-plugin", "插件包", "导入插件", "chajian", "plugin", "daoruchajian", "cjb", "cjgl"),
                    MuseRoutes.MUSE_PLUGINS,
                    groupAiModels,
                    TablerIcons.Puzzle,
                ) { onNavigate(MuseRoutes.MUSE_PLUGINS) },
                SettingsEntry(videoGenTitle, listOf("视频", "video", "生成视频", "shipin", "shengchengshipin", "sp", "scsp"), MuseRoutes.VIDEO_GENERATION, groupAiModels, TablerIcons.Video, onOpenVideoGeneration),
                SettingsEntry(videoGenConfigTitle, listOf("视频配置", "视频生成配置", "video gen settings", "shipinpeizhi", "spsc", "spgenpeizhi", "sppeizhi"), MuseRoutes.SETTINGS_VIDEO_GEN, groupAiModels, TablerIcons.Settings, onOpenVideoGenSettings),
                SettingsEntry(webSearchEntryTitle, listOf("联网搜索", "搜索", "web search", "网络搜索", "在线搜索", "lianwang", "sousuo", "wangluosousuo", "zaixiansousuo", "lwss", "ss", "wlss", "zxss"), MuseRoutes.SETTINGS_WEB_SEARCH, groupAiModels, TablerIcons.World, onOpenWebSearch),
                SettingsEntry(asrEntryTitle, listOf("ASR", "asr", "语音识别", "speech", "转文字", "识别语音", "yuyinshibie", "zhuanwenzi", "shibieyuyin", "yysb", "zwz", "sbyy"), MuseRoutes.SETTINGS_ASR, groupAiModels, TablerIcons.Microphone, onOpenAsr),
                SettingsEntry(imageGenEntryTitle, listOf("图像生成", "画图", "AI画", "image gen", "绘图", "tuxiangshengcheng", "huatu", "AIhua", "huitu", "txsc", "ht", "AIht", "ht"), MuseRoutes.SETTINGS_IMAGE_GEN, groupAiModels, TablerIcons.Photo, onOpenImageGen),
                SettingsEntry(mcpEntryTitle, listOf("MCP", "mcp", "服务器", "Model Context Protocol", "工具协议", "fuwuqi", "gongjixieyi", "fwq", "gjxy"), MuseRoutes.SETTINGS_MCP, groupAiModels, TablerIcons.Affiliate, onOpenMcp),

                // 记忆与知识库
                SettingsEntry(memoryTitle, listOf("记忆", "通知", "memory", "遗忘", "回忆", "jiyi", "tongzhi", "yiwang", "huiyi", "jy", "tz", "yw", "hy"), MuseRoutes.SETTINGS_MEMORY, groupMemoryKnowledge, TablerIcons.Atom, onOpenMemorySettings),
                SettingsEntry("保持唤醒", listOf("保持唤醒", "唤醒", "wakelock", "不休眠", "常亮", "keep awake", "baochihuanxing", "huanxing", "buxiumian", "changliang", "bchx", "hx", "bxm", "cl"), MuseRoutes.SETTINGS_MEMORY, groupMemoryKnowledge, Icons.Outlined.Bolt, onOpenMemorySettings),
                SettingsEntry("开机自启", listOf("开机自启", "自启", "自启动", "开机", "boot", "auto launch", "BootReceiver", "kaijiziqi", "ziqi", "zidong", "kaiji", "kjzq", "zq", "zdd", "kj"), MuseRoutes.SETTINGS_MEMORY, groupMemoryKnowledge, Icons.Outlined.Bolt, onOpenMemorySettings),
                SettingsEntry(ragTitle, listOf("RAG", "知识库", "rag", "检索", "向量", "文档", "zhishiku", "jiansuo", "xiangliang", "wendang", "zsk", "js", "xl", "wd"), MuseRoutes.SETTINGS_RAG, groupMemoryKnowledge, TablerIcons.Book, onOpenRagSettings),

                // 数据管理
                SettingsEntry(dataManagementTitle, listOf("数据管理", "数据", "存储", "清理", "缓存", "shujuguanli", "shuju", "cunchu", "qingli", "huancun", "sjgl", "sj", "cc", "ql", "hc"), MuseRoutes.DATA_MANAGEMENT, groupDataManagement, TablerIcons.Database, onOpenDataManagement),
                SettingsEntry(dataBackupTitle, listOf("备份", "云备份", "cloud", "backup", "S3", "WebDAV", "同步", "beifen", "yunbeifen", "tongbu", "bf", "ybf", "tb"), MuseRoutes.SETTINGS_DATA, groupDataManagement, TablerIcons.Cloud, onOpenDataSettings),
                SettingsEntry(dataImportTitle, listOf("数据导入", "导入", "import", "恢复数据", "shujudaoru", "daoru", "huifushuju", "sjdr", "dr", "hfsj"), MuseRoutes.SETTINGS_DATA_IMPORT, groupDataManagement, TablerIcons.CloudUpload, onOpenDataImport),
                SettingsEntry(workspaceTitle, listOf("工作区", "文件管理", "workspace", "文件", "目录", "gongzuoqu", "wenjianguanli", "wenjian", "mulu", "gzq", "wjgl", "wj", "ml"), MuseRoutes.WORKSPACE, groupDataManagement, TablerIcons.Folder, onOpenWorkspace),

                // 隐私与安全
                SettingsEntry("PII Guard", listOf("PII", "隐私", "脱敏", "pii guard", "信息保护", "yinsi", "tuomin", "xinxi", "xinxi baohu", "ys", "tm", "xx", "xxbh"), "", groupDataManagement, TablerIcons.ShieldCheck) {},
                SettingsEntry(securityTitle, listOf("安全", "锁屏", "PIN", "密码", "应用锁", "share", "anquan", "suoping", "mima", "yingyongsuo", "aq", "sp", "mm", "yys"), MuseRoutes.SETTINGS_SECURITY, groupDataManagement, TablerIcons.Lock, onOpenSecuritySettings),
                SettingsEntry("生物识别", listOf("生物识别", "指纹", "biometric", "指纹解锁", "面容", "shengwushibie", "zhiwen", "zhiwenjiesuo", "mianrong", "swsb", "zw", "zwjs", "mr"), MuseRoutes.SETTINGS_SECURITY, groupDataManagement, TablerIcons.Lock, onOpenSecuritySettings),
                SettingsEntry("网络代理", listOf("代理", "proxy", "网络", "VPN", "HTTP代理", "daili", "wangluo", "dl", "wl"), MuseRoutes.SETTINGS_PROXY, groupDataManagement, TablerIcons.Adjustments, onOpenProxySettings),
                SettingsEntry(auditLogTitle, listOf("审计", "日志", "audit", "操作记录", "审计日志", "shenji", "rizhi", "caozuojilu", "shenjirizhi", "sj", "rz", "czjl", "sjrz"), MuseRoutes.AUDIT_LOG, groupDataManagement, TablerIcons.History, onOpenAuditLog),

                // 关于
                SettingsEntry(tutorialTitle, listOf("教程", "新手", "引导", "tutorial", "帮助", "jiaocheng", "xinshou", "yindao", "bangzhu", "jc", "xs", "yd", "bz"), MuseRoutes.SETTINGS_TUTORIAL, groupAbout, TablerIcons.School, onOpenTutorial),
                SettingsEntry(aboutTitle, listOf("关于", "版本", "about", "信息", "guanyu", "banben", "xinxi", "gy", "bb", "xx"), MuseRoutes.SETTINGS_ABOUT, groupAbout, TablerIcons.InfoCircle, onOpenAboutSettings),
                SettingsEntry(checkUpdateTitle, listOf("检查更新", "更新", "update", "版本", "升级", "jianchagengxin", "gengxin", "shengji", "jcgc", "gx", "sj"), "", groupAbout, TablerIcons.Refresh) {},
                SettingsEntry(debugLogTitle, listOf("调试", "日志", "debug", "log", "Logger", "tiaoshi", "rizhi", "ts", "rz"), MuseRoutes.DEBUG, groupAbout, TablerIcons.Bug, onOpenDebugLog),
                SettingsEntry(experimentsTitle, listOf("实验性", "实验", "experimental", "beta", "试验", "shiyanxing", "shiyan", "shiyan", "syx", "sy"), MuseRoutes.SETTINGS_EXPERIMENTS, groupAbout, TablerIcons.Flask, onOpenExperimentsSettings),
                SettingsEntry(statsTitle, listOf("统计", "使用统计", "stats", "热力图", "数据", "tongji", "shiyongtongji", "relitu", "shuju", "tj", "sytj", "rlt", "sj"), MuseRoutes.STATS, groupAbout, TablerIcons.ChartBar, onOpenStats),
                SettingsEntry("里程碑", listOf("里程碑", "成就", "纪念日", "milestone", "liangcheng", "chengjiu", "jinianri", "lc", "cj", "jnr"), MuseRoutes.MILESTONES, groupAbout, TablerIcons.Stars) { onNavigate(MuseRoutes.MILESTONES) },

                // 二级设置项
                SettingsEntry("字号", listOf("字号", "字体大小", "字体", "大小", "ziti", "zihao", "ztdx", "zt"), MuseRoutes.SETTINGS_APPEARANCE, appearanceTitle, TablerIcons.ColorSwatch, onOpenAppearanceSettings),
                SettingsEntry("主题模式", listOf("主题模式", "浅色", "深色", "跟随系统", "zhutimoshi", "qianse", "shense", "genshixitong", "ztms", "qs", "ss", "gsxt"), MuseRoutes.SETTINGS_APPEARANCE, appearanceTitle, TablerIcons.ColorSwatch, onOpenAppearanceSettings),
                SettingsEntry("动态取色", listOf("动态取色", "取色", "壁纸", "dongtaiquse", "quse", "dtqs", "qs", "bz"), MuseRoutes.SETTINGS_APPEARANCE, appearanceTitle, TablerIcons.ColorSwatch, onOpenAppearanceSettings),
                SettingsEntry("定时切换主题", listOf("定时切换", "自动切换", "深色模式", "dingshiqiehuan", "zidongqiehuan", "shensemoshi", "dsqh", "zdqh", "ssms"), MuseRoutes.SETTINGS_APPEARANCE, appearanceTitle, TablerIcons.ColorSwatch, onOpenAppearanceSettings),

                SettingsEntry("流式响应", listOf("流式", "流式响应", "实时输出", "liushi", "liushixiangying", "shishishuchu", "ls", "lsxy", "sssc"), MuseRoutes.SETTINGS_CHAT, chatTitle, TablerIcons.MessageCircle, onOpenChatSettings),
                SettingsEntry("回车发送", listOf("回车发送", "回车", "发送", "huichefasong", "huiche", "fasong", "hcfs", "hc", "fs"), MuseRoutes.SETTINGS_CHAT, chatTitle, TablerIcons.MessageCircle, onOpenChatSettings),
                SettingsEntry("自动滚动", listOf("自动滚动", "滚动", "zidonggundong", "gundong", "zdgd", "gd"), MuseRoutes.SETTINGS_CHAT, chatTitle, TablerIcons.MessageCircle, onOpenChatSettings),
                SettingsEntry("深度思考", listOf("深度思考", "默认深度思考", "shendusikao", "morethorough", "sds", "sdsz"), MuseRoutes.SETTINGS_CHAT, chatTitle, TablerIcons.MessageCircle, onOpenChatSettings),
                SettingsEntry("消息时间戳", listOf("时间戳", "24小时", "timestamp", "shijianchuo", "24xiaoshi", "sjc"), MuseRoutes.SETTINGS_CHAT, chatTitle, TablerIcons.MessageCircle, onOpenChatSettings),

                SettingsEntry("TTS 语速", listOf("TTS", "语速", "音高", "yusu", "yingao", "ys", "yg"), MuseRoutes.SETTINGS_MEDIA, mediaTitle, TablerIcons.Microphone, onOpenMediaSettings),
                SettingsEntry("TTS 声音", listOf("声音", "语音", "voice", "shengyin", "yuyin", "sy", "yy"), MuseRoutes.SETTINGS_MEDIA, mediaTitle, TablerIcons.Microphone, onOpenMediaSettings),
                SettingsEntry("音频输出", listOf("音频输出", "扬声器", "听筒", "蓝牙", "yinpingshuchu", "yangshengqi", "tingtong", "lanya", "ypsc", "ysq", "tt", "ly"), MuseRoutes.SETTINGS_MEDIA, mediaTitle, TablerIcons.Microphone, onOpenMediaSettings),

                SettingsEntry("记忆开关", listOf("记忆", "开关", "jiyi", "kaiguan", "jy", "kg"), MuseRoutes.SETTINGS_MEMORY, memoryTitle, TablerIcons.Atom, onOpenMemorySettings),
                SettingsEntry("保持唤醒", listOf("保持唤醒", "唤醒", "wakelock", "baochihuanxing", "huanxing", "bchx", "hx"), MuseRoutes.SETTINGS_MEMORY, memoryTitle, Icons.Outlined.Bolt, onOpenMemorySettings),
                SettingsEntry("开机自启", listOf("开机自启", "自启", "自启动", "kaijiziqi", "ziqi", "zidong", "kaiji", "kjzq", "zq", "zd", "kj"), MuseRoutes.SETTINGS_MEMORY, memoryTitle, Icons.Outlined.Bolt, onOpenMemorySettings),

                SettingsEntry("PIN 锁", listOf("PIN", "锁屏", "密码锁", "suoping", "mimasuo", "sp", "mms"), MuseRoutes.SETTINGS_SECURITY, securityTitle, TablerIcons.Lock, onOpenSecuritySettings),
                SettingsEntry("生物识别", listOf("生物识别", "指纹", "面容", "shengwushibie", "zhiwen", "mianrong", "swsb", "zw", "mr"), MuseRoutes.SETTINGS_SECURITY, securityTitle, TablerIcons.Lock, onOpenSecuritySettings),

                SettingsEntry("代理开关", listOf("代理", "开关", "Proxy", "daili", "kaiguan", "dl", "kg"), MuseRoutes.SETTINGS_PROXY, "网络代理", TablerIcons.Adjustments, onOpenProxySettings),

                SettingsEntry("检索模型", listOf("检索模型", "RAG模型", "相似度", "jiansuomoxing", "ragmoxing", "xiangsidu", "jsmx", "ragmx", "xsd"), MuseRoutes.SETTINGS_RAG, ragTitle, TablerIcons.Book, onOpenRagSettings),
                SettingsEntry("分段策略", listOf("分段", "分块", "策略", "fenduan", "fenkuai", "celve", "fd", "fk", "cl"), MuseRoutes.SETTINGS_RAG, ragTitle, TablerIcons.Book, onOpenRagSettings),

                SettingsEntry("搜索引擎", listOf("搜索引擎", "Bing", "Jina", "SearXNG", "sousuoyinqing", "ssyq"), MuseRoutes.SETTINGS_WEB_SEARCH, webSearchEntryTitle, TablerIcons.World, onOpenWebSearch),
                SettingsEntry("ASR 引擎", listOf("ASR", "语音识别引擎", "模型", "asryinqing", "yuyinshibieyinqing", "asryq", "yysbyq"), MuseRoutes.SETTINGS_ASR, asrEntryTitle, TablerIcons.Microphone, onOpenAsr),
                SettingsEntry("视觉模型", listOf("视觉模型", "看图模型", "shijuemoxing", "kantumoxing", "sjmx", "ktmx"), MuseRoutes.SETTINGS_VISION, visionTitle, TablerIcons.Eye, onOpenVisionSettings),
                SettingsEntry("图像分辨率", listOf("分辨率", "图像尺寸", "fenbianlv", "tuxiangchicun", "fbl", "txcc"), MuseRoutes.SETTINGS_IMAGE_GEN, imageGenEntryTitle, TablerIcons.Photo, onOpenImageGen),
                SettingsEntry("MCP 服务器", listOf("MCP", "服务器", "ModelContextProtocol", "fuwuqi", "fwq"), MuseRoutes.SETTINGS_MCP, mcpEntryTitle, TablerIcons.Affiliate, onOpenMcp),
                SettingsEntry("工具批准模式", listOf("工具批准", "批准模式", "自动批准", "gongjupizhun", "pizhunmoshi", "zidongpizhun", "gjpz", "pzms", "zdpz"), MuseRoutes.TOOLS, toolsTitle, TablerIcons.Tools, onOpenTools),
                SettingsEntry("主动消息", listOf("主动消息", "推送", "定时", "zhudongxiaoxi", "tuisong", "dingshi", "zdxx", "ts", "ds"), MuseRoutes.SETTINGS_AGENT, agentTitle, TablerIcons.Bell, onOpenAgentSettings),
                SettingsEntry("协作助手", listOf("协作", "多助手", "团队", "xiezhuo", "duozhushou", "tuandui", "xz", "dzs", "td"), MuseRoutes.SETTINGS_AGENT, agentTitle, TablerIcons.Users, onOpenAgentSettings),
                SettingsEntry("云备份", listOf("云备份", "备份", "S3", "WebDAV", "yunbeifen", "beifen", "ybf", "bf"), MuseRoutes.SETTINGS_DATA, dataBackupTitle, TablerIcons.Cloud, onOpenDataSettings),
                SettingsEntry("Web 服务器", listOf("Web服务器", "端口", "PIN", "远程访问", "webfuwuqi", "duankou", "yuanchengfangwen", "webfwq", "dk"), MuseRoutes.SETTINGS_DATA, dataBackupTitle, TablerIcons.World, onOpenDataSettings),
            ),
        )
    }

    val filteredEntries by remember(searchQuery, settingsIndex) {
        mutableStateOf(
            if (searchQuery.isBlank()) settingsIndex
            else {
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

    val widthClass = rememberWindowWidthClass()

    Scaffold(
        topBar = {
            if (isSearching) {
                SearchTopBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onClear = { searchQuery = "" },
                    onClose = {
                        isSearching = false
                        searchQuery = ""
                        keyboard?.hide()
                    },
                    focusRequester = focusRequester,
                    searchHint = searchHint,
                )
            } else {
                MuseTopBar(
                    title = stringResource(R.string.settings_screen_title),
                    onBack = onBack,
                    largeTitle = true,
                    actions = {
                        IconButton(
                            onClick = { isSearching = true },
                            modifier = Modifier.size(MuseIconSizes.touchTarget),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = stringResource(R.string.settings_search_cd),
                                modifier = Modifier.size(MuseIconSizes.iconMedium),
                            )
                        }
                    },
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
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
                    bottom = innerPadding.calculateBottomPadding() + MusePaddings.sectionGap,
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                ),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap),
            ) {
                if (isSearching) {
                    item(key = "search_status") {
                        Text(
                            text = if (searchQuery.isBlank()) {
                                stringResource(R.string.settings_search_prompt)
                            } else {
                                "${filteredEntries.size} $noResults"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = MusePaddings.screen),
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
                        items(filteredEntries, key = { it.title + it.route + it.groupName }) { entry ->
                            CardGroup(
                                modifier = Modifier.padding(horizontal = MusePaddings.screen),
                            ) {
                                item(
                                    onClick = {
                                        keyboard?.hide()
                                        isSearching = false
                                        searchQuery = ""
                                        entry.onClick()
                                    },
                                    leadingContent = { MuseSettingsIcon(entry.icon) },
                                    headlineContent = { Text(entry.title) },
                                    supportingContent = { Text(entry.groupName) },
                                    trailingContent = { ChevronRight() },
                                )
                            }
                        }
                    }
                } else {
                    item(key = "account") {
                        io.zer0.muse.ui.account.AccountCard(
                            onClick = onOpenAccount,
                            modifier = Modifier
                                .padding(horizontal = MusePaddings.screen)
                                .padding(top = MusePaddings.sectionGap),
                        )
                    }

                    item(key = "general") {
                        SettingsCardGroup(title = groupGeneral) {
                            link(chatTitle, R.string.settings_screen_chat_desc, TablerIcons.MessageCircle, onOpenChatSettings)
                            link(appearanceTitle, R.string.settings_screen_appearance_desc, TablerIcons.ColorSwatch, onOpenAppearanceSettings)
                            link(mediaTitle, R.string.settings_screen_media_desc, TablerIcons.Microphone, onOpenMediaSettings)
                            link(translateTitle, R.string.settings_screen_translate_desc, TablerIcons.Language, onOpenTranslate)
                            link(quickNotesTitle, R.string.settings_screen_quick_notes_desc, TablerIcons.Bulb) { onNavigate(MuseRoutes.QUICK_NOTES) }
                        }
                    }

                    item(key = "assistant_agent") {
                        SettingsCardGroup(title = groupAssistantAgent) {
                            link(assistantTitle, R.string.settings_screen_assistant_desc, TablerIcons.Atom, onOpenAssistants)
                            link(agentTitle, R.string.settings_screen_agent_desc, TablerIcons.Users, onOpenAgentSettings)
                            link(assistantResourcesTitle, R.string.settings_screen_assistant_resources_desc, TablerIcons.Stars, onOpenAssistantResources)
                            link(notificationListenerTitle, R.string.settings_screen_notification_listener_desc, TablerIcons.Bell, onOpenNotificationListener)
                            link(toolsTitle, R.string.settings_screen_tools_desc, TablerIcons.Tools, onOpenTools)
                        }
                    }

                    item(key = "ai_models") {
                        SettingsCardGroup(title = groupAiModels) {
                            link(providerTitle, R.string.settings_screen_provider_desc, TablerIcons.Settings, onOpenModelSettings)
                            link(visionTitle, R.string.settings_screen_vision_desc, TablerIcons.Eye, onOpenVisionSettings)
                            link(providerPluginsTitle, TablerIcons.Puzzle, onOpenProviderPlugins)
                            link(videoGenTitle, R.string.settings_screen_video_gen_desc, TablerIcons.Video, onOpenVideoGeneration)
                            link(videoGenConfigTitle, R.string.settings_screen_video_gen_config_desc, TablerIcons.Settings, onOpenVideoGenSettings)
                            link(webSearchEntryTitle, R.string.settings_screen_web_search_desc, TablerIcons.World, onOpenWebSearch)
                            link(asrEntryTitle, R.string.settings_screen_asr_desc, TablerIcons.Microphone, onOpenAsr)
                            link(imageGenEntryTitle, R.string.settings_screen_image_gen_desc, TablerIcons.Photo, onOpenImageGen)
                            link(mcpEntryTitle, R.string.settings_screen_mcp_desc, TablerIcons.Affiliate, onOpenMcp)
                        }
                    }

                    item(key = "memory_knowledge") {
                        SettingsCardGroup(title = groupMemoryKnowledge) {
                            link(memoryTitle, R.string.settings_screen_memory_notification_desc, TablerIcons.Atom, onOpenMemorySettings)
                            link(ragTitle, R.string.settings_screen_rag_desc, TablerIcons.Book, onOpenRagSettings)
                        }
                    }

                    item(key = "data_management") {
                        SettingsCardGroup(title = groupDataManagement) {
                            link(dataManagementTitle, R.string.data_management_entry_desc, TablerIcons.Database, onOpenDataManagement)
                            link(dataBackupTitle, R.string.settings_screen_data_backup_desc, TablerIcons.Cloud, onOpenDataSettings)
                            link(dataImportTitle, R.string.settings_screen_data_import_desc, TablerIcons.CloudUpload, onOpenDataImport)
                            link(workspaceTitle, R.string.workspace_desc, TablerIcons.Folder, onOpenWorkspace)
                            switch(
                                piiGuardTitle,
                                R.string.settings_screen_pii_guard_desc,
                                TablerIcons.ShieldCheck,
                                checked = piiGuardEnabled,
                                onCheckedChange = { v -> scope.launch { settings.savePiiGuardEnabled(v) } },
                            )
                            link(securityTitle, R.string.settings_screen_security_desc, TablerIcons.Lock, onOpenSecuritySettings)
                            link(proxyTitle, proxySubtitle, TablerIcons.Adjustments, onOpenProxySettings)
                            link(auditLogTitle, TablerIcons.History, onOpenAuditLog)
                        }
                    }

                    item(key = "about") {
                        SettingsCardGroup(title = groupAbout) {
                            link(tutorialTitle, R.string.settings_screen_tutorial_desc, TablerIcons.School, onOpenTutorial)
                            link(aboutTitle, R.string.settings_screen_about_desc, TablerIcons.InfoCircle, onOpenAboutSettings)
                            checkUpdate(checkingUpdate, onCheck = {
                                if (checkingUpdate) return@checkUpdate
                                checkingUpdate = true
                                scope.launch {
                                    val beforeJson = runCatching { settings.latestReleaseInfoFlow.first() }.getOrNull()
                                    updateNotifier.checkAndNotify(context, forceCheck = true)
                                    checkingUpdate = false
                                    val latest = runCatching { settings.latestReleaseInfoFlow.first() }.getOrNull()
                                    if (latest != null && latest != beforeJson) {
                                        MuseToast.show(context.getString(R.string.update_found_new))
                                    } else if (latest == null) {
                                        MuseToast.show(context.getString(R.string.update_already_latest))
                                    }
                                }
                            })
                            link(debugLogTitle, R.string.settings_screen_debug_log_desc, TablerIcons.Bug, onOpenDebugLog)
                            link(experimentsTitle, R.string.settings_screen_experiments_desc, TablerIcons.Flask, onOpenExperimentsSettings)
                            link(statsTitle, R.string.settings_screen_stats_desc, TablerIcons.ChartBar, onOpenStats)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 搜索态顶部栏:简洁输入框 + 关闭按钮。
 */
@Composable
private fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
    focusRequester: FocusRequester,
    searchHint: String,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        color = MaterialTheme.colorScheme.background,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.screen, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.auxGap),
        ) {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                placeholder = { Text(searchHint) },
                singleLine = true,
                shape = MuseShapes.pill,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = onClear) {
                            Icon(
                                imageVector = TablerIcons.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(MuseIconSizes.iconMedium),
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search,
                ),
            )
            val cancelInteractionSource = remember { MutableInteractionSource() }
            Text(
                text = stringResource(R.string.settings_screen_cancel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(
                    interactionSource = cancelInteractionSource,
                    indication = null,
                    onClick = onClose,
                ),
            )
        }
    }
}

/**
 * 设置分组卡片:统一样式,带分组标题。
 */
@Composable
private fun SettingsCardGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: CardGroupContentScope.() -> Unit,
) {
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
    override fun link(
        headline: String,
        descRes: Int,
        icon: ImageVector,
        onClick: () -> Unit,
    ) {
        cardGroupScope.item(
            onClick = onClick,
            leadingContent = { MuseSettingsIcon(icon) },
            headlineContent = { Text(headline) },
            supportingContent = { Text(stringResource(descRes)) },
            trailingContent = { ChevronRight() },
        )
    }

    override fun link(
        headline: String,
        desc: String,
        icon: ImageVector,
        onClick: () -> Unit,
    ) {
        cardGroupScope.item(
            onClick = onClick,
            leadingContent = { MuseSettingsIcon(icon) },
            headlineContent = { Text(headline) },
            supportingContent = { Text(desc) },
            trailingContent = { ChevronRight() },
        )
    }

    override fun link(
        headline: String,
        icon: ImageVector,
        onClick: () -> Unit,
    ) {
        cardGroupScope.item(
            onClick = onClick,
            leadingContent = { MuseSettingsIcon(icon) },
            headlineContent = { Text(headline) },
            trailingContent = { ChevronRight() },
        )
    }

    override fun switch(
        headline: String,
        descRes: Int,
        icon: ImageVector,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
    ) {
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

    override fun checkUpdate(
        checking: Boolean,
        onCheck: () -> Unit,
    ) {
        cardGroupScope.item(
            onClick = onCheck,
            leadingContent = { MuseSettingsIcon(TablerIcons.Refresh) },
            headlineContent = { Text(stringResource(R.string.settings_screen_check_update)) },
            supportingContent = { Text(stringResource(R.string.settings_screen_check_update_desc)) },
            trailingContent = {
                if (checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
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
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.outline,
        modifier = Modifier.size(MuseIconSizes.iconMedium),
    )
}
