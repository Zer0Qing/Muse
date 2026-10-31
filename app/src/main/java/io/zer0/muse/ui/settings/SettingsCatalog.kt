package io.zer0.muse.ui.settings

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.navigation.ArchivedChatsRoute
import io.zer0.muse.ui.navigation.AssistantsRoute
import io.zer0.muse.ui.navigation.AuditLogRoute
import io.zer0.muse.ui.navigation.ChannelSettingsRoute
import io.zer0.muse.ui.navigation.ConnectionCenterRoute
import io.zer0.muse.ui.navigation.DataManagementRoute
import io.zer0.muse.ui.navigation.DebugRoute
import io.zer0.muse.ui.navigation.KeepAliveSettingsRoute
import io.zer0.muse.ui.navigation.MiniPhoneRoute
import io.zer0.muse.ui.navigation.NotificationListenerRoute
import io.zer0.muse.ui.navigation.PluginManageRoute
import io.zer0.muse.ui.navigation.QuickNotesRoute
import io.zer0.muse.ui.navigation.ScheduledTasksRoute
import io.zer0.muse.ui.navigation.SettingsAboutRoute
import io.zer0.muse.ui.navigation.SettingsAgentRoute
import io.zer0.muse.ui.navigation.SettingsAppearanceRoute
import io.zer0.muse.ui.navigation.SettingsAsrRoute
import io.zer0.muse.ui.navigation.SettingsAssistantResourcesRoute
import io.zer0.muse.ui.navigation.SettingsAutomationRoute
import io.zer0.muse.ui.navigation.SettingsChatRoute
import io.zer0.muse.ui.navigation.SettingsDataImportRoute
import io.zer0.muse.ui.navigation.SettingsDataRoute
import io.zer0.muse.ui.navigation.SettingsCrashReportRoute
import io.zer0.muse.ui.navigation.SettingsExperimentsRoute
import io.zer0.muse.ui.navigation.SettingsMcpRoute
import io.zer0.muse.ui.navigation.SettingsMediaRoute
import io.zer0.muse.ui.navigation.SettingsMemoryRoute
import io.zer0.muse.ui.navigation.SettingsModelRoute
import io.zer0.muse.ui.navigation.SettingsPermissionWizardRoute
import io.zer0.muse.ui.navigation.SettingsProactiveRoute
import io.zer0.muse.ui.navigation.SettingsProxyRoute
import io.zer0.muse.ui.navigation.SettingsRagRoute
import io.zer0.muse.ui.navigation.SettingsSecurityRoute
import io.zer0.muse.ui.navigation.SettingsTaskRoutingRoute
import io.zer0.muse.ui.navigation.SettingsTutorialRoute
import io.zer0.muse.ui.navigation.SettingsVisionRoute
import io.zer0.muse.ui.navigation.SettingsWebAccessRoute
import io.zer0.muse.ui.navigation.SettingsWebSearchRoute
import io.zer0.muse.ui.navigation.StatsRoute
import io.zer0.muse.ui.navigation.StickerSettingsRoute
import io.zer0.muse.ui.navigation.ToolsScreenRoute
import io.zer0.muse.ui.navigation.TranslateRoute
import io.zer0.muse.ui.navigation.UserProfileEditRoute
import io.zer0.muse.ui.navigation.VideoGenerationRoute
import io.zer0.muse.ui.navigation.VoiceCloningRoute
import io.zer0.muse.ui.navigation.WorkspaceRoute
import kotlinx.coroutines.flow.Flow

/**
 * 设置一级分类。
 *
 * 设置首页只列这 14 类，每类的条目在分类页里；声明顺序即首页从上到下的顺序。
 */
enum class SettingsGroupKind {
    ASSISTANTS,
    CHAT,
    MODELS,
    TOOLS,
    VOICE,
    IMAGE,
    MEMORY,
    CONNECTION,
    AUTOMATION,
    APPEARANCE,
    DATA_STORAGE,
    PRIVACY_SECURITY,
    ABOUT,
    DEVELOPER,
}

/**
 * 设置条目的稳定标识。
 *
 * 只用于识别条目（渲染 key、测试断言、运行时状态的按条目覆盖），显示文案在
 * [SettingsEntrySpec.titleRes] 里。声明顺序与清单里的展示顺序一致。
 */
enum class SettingsEntryId {
    USER_PROFILE,
    ASSISTANTS,
    ASSISTANT_RESOURCES,
    AGENT,
    CHAT,
    STICKERS,
    QUICK_NOTES,
    SHARE,
    PROVIDER,
    TASK_ROUTING,
    PROVIDER_PLUGINS,
    TOOLS,
    PERMISSION_WIZARD,
    WEB_SEARCH,
    TTS,
    ASR,
    VOICE_CLONING,
    IMAGE_VIDEO_GEN,
    VISION,
    TRANSLATE,
    MEMORY,
    RAG,
    CONNECTION_CENTER,
    CHANNEL,
    WEB_ACCESS,
    MCP,
    PROACTIVE,
    SCHEDULED_TASKS,
    NOTIFICATION_LISTENER,
    UI_AUTOMATION,
    KEEP_ALIVE,
    APPEARANCE,
    MINIPHONE,
    DATA_MANAGEMENT,
    WORKSPACE,
    BACKUP,
    DATA_IMPORT,
    ARCHIVED_CHATS,
    PII_GUARD,
    PROXY,
    TUTORIAL,
    ABOUT,
    CHECK_UPDATE,
    STATS,
    EXPERIMENTS,
    DEBUG_LOG,
    CRASH_REPORT,
    AUDIT_LOG,
}

/** 条目在设置仓库里对应的开关键；写回逻辑集中在 [writeToggle]。 */
enum class SettingsToggleKey {
    PII_GUARD,
}

/** 不跳转、由界面直接执行的条目动作。 */
enum class SettingsActionId {
    CHECK_UPDATE,
    EXPORT_DIAGNOSTICS,
}

/**
 * 条目被点击时做什么。
 *
 * 三种形态覆盖设置页全部入口：进入某个页面、翻一个开关、执行一个动作。
 */
sealed interface SettingsEntryAction {
    /** 跳到目标路由（类型安全路由对象）。 */
    data class Navigate(val route: Any) : SettingsEntryAction

    /** 开关条目：读哪条流由 [flow] 声明，写回由 [SettingsToggleKey] 决定。 */
    data class Toggle(
        val key: SettingsToggleKey,
        val flow: (SettingsRepository) -> Flow<Boolean>,
    ) : SettingsEntryAction

    /** 页内动作（检查更新、导出诊断等），具体实现由界面传入的回调承接。 */
    data class Action(val id: SettingsActionId) : SettingsEntryAction
}

/** 设置条目声明。 */
data class SettingsEntrySpec(
    val id: SettingsEntryId,
    @StringRes val titleRes: Int,
    /** 说明文案；由运行时拼出的说明（如代理状态）为 null。 */
    @StringRes val descRes: Int? = null,
    val icon: ImageVector,
    val action: SettingsEntryAction,
    /** 搜索关键词（中文近义词 + 拼音首字母）；空表示只靠标题命中。 */
    val keywords: List<String> = emptyList(),
    /**
     * 是否在分类页列出来。
     *
     * 关掉后只是“不给入口”：分类页不渲染，搜索仍能命中，直接跳转仍可用。
     * 用于功能尚未打磨完、暂不主推但不想拆掉的情形。
     */
    val browseVisible: Boolean = true,
)

/**
 * 分类页里的一张卡片。
 *
 * 分类需要再分段时才给 [labelRes]；目前的 14 个分类都是整类一张不带标题的卡片。
 */
data class SettingsSectionSpec(
    @StringRes val labelRes: Int? = null,
    val entries: List<SettingsEntrySpec>,
)

/** 设置一级分类声明：标题、一句话说明、图标与其下的条目。 */
data class SettingsGroupSpec(
    val kind: SettingsGroupKind,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val icon: ImageVector,
    val sections: List<SettingsSectionSpec>,
) {
    /** 该分类的全部条目，按分区与声明顺序展开。 */
    val entries: List<SettingsEntrySpec>
        get() = sections.flatMap { it.entries }
}

/**
 * 设置入口的唯一声明清单。
 *
 * 首页（14 个分类）、分类页（该类条目）、搜索索引（全量条目）都从这里取数据，
 * 三者不会再各自维护一份顺序或标题。
 */
object SettingsCatalog {

    val groups: List<SettingsGroupSpec> = listOf(
        group(
            SettingsGroupKind.ASSISTANTS,
            R.string.settings_group_assistants,
            R.string.settings_group_assistants_desc,
            MuseIcons.users,
            entries(
                entry(
                    SettingsEntryId.USER_PROFILE,
                    R.string.settings_user_profile_title,
                    R.string.settings_screen_desc_user_profile,
                    MuseIcons.user,
                    SettingsEntryAction.Navigate(UserProfileEditRoute),
                    keywords = listOf(
                        "用户画像", "用户资料", "我的资料", "个人资料", "昵称", "头像", "profile",
                        "yonghuhuaxiang", "yonghuziliao", "ziliao", "nicheng", "touxiang", "yhhx", "yhzl", "zl", "nc", "tx",
                    ),
                ),
                entry(
                    SettingsEntryId.ASSISTANTS,
                    R.string.settings_screen_assistant,
                    R.string.settings_screen_assistant_desc,
                    MuseIcons.atom,
                    SettingsEntryAction.Navigate(AssistantsRoute),
                    keywords = listOf("助手", "assistant", "角色", "人设", "zhushou", "juese", "renshe", "zs", "js", "rs"),
                ),
                entry(
                    SettingsEntryId.ASSISTANT_RESOURCES,
                    R.string.settings_screen_assistant_resources,
                    R.string.settings_screen_assistant_resources_desc,
                    MuseIcons.stars,
                    SettingsEntryAction.Navigate(SettingsAssistantResourcesRoute),
                    keywords = listOf(
                        "助手资源", "收藏夹", "世界书", "快捷消息", "模式注入", "Skills", "技能", "zhushouziyuan",
                        "shoucangjia", "shijieshu", "kuaijiexiaoxi", "moshizhur", "jineng", "zszy",
                        "scj", "sjs", "kjxx", "mszr", "jn",
                    ),
                ),
                entry(
                    SettingsEntryId.AGENT,
                    R.string.chat_agent_tab_title,
                    R.string.settings_screen_agent_desc,
                    MuseIcons.users,
                    SettingsEntryAction.Navigate(SettingsAgentRoute),
                    keywords = listOf("Agent", "代理", "智能体", "自主", "daili", "zhinengti", "zizhu", "dl", "znt"),
                ),
            ),
        ),
        group(
            SettingsGroupKind.CHAT,
            R.string.settings_group_chat,
            R.string.settings_group_chat_desc,
            MuseIcons.chat,
            entries(
                entry(
                    SettingsEntryId.CHAT,
                    R.string.settings_screen_chat,
                    R.string.settings_screen_chat_desc,
                    MuseIcons.chat,
                    SettingsEntryAction.Navigate(SettingsChatRoute),
                    keywords = listOf(
                        "聊天", "对话", "消息", "输入", "发送", "liaotian", "duihua", "xiaoxi", "shuru",
                        "fasong", "lt", "dh", "xx", "全屏编辑", "展开", "气泡", "阴影", "模型", "MOOD", "思维链",
                        "快捷键", "引用回复",
                    ),
                ),
                entry(
                    SettingsEntryId.STICKERS,
                    R.string.settings_chat_sticker_section,
                    R.string.settings_screen_sticker_desc,
                    MuseIcons.image,
                    SettingsEntryAction.Navigate(StickerSettingsRoute),
                    keywords = listOf(
                        "表情包", "表情", "贴纸", "表情包库", "sticker", "emoji", "biaoqingbao",
                        "biaoqing", "tiezhi", "biaoqingbaoku", "bq", "tq", "bqbk",
                    ),
                ),
                entry(
                    SettingsEntryId.QUICK_NOTES,
                    R.string.settings_screen_quick_notes,
                    R.string.settings_screen_quick_notes_desc,
                    MuseIcons.bulb,
                    SettingsEntryAction.Navigate(QuickNotesRoute),
                    keywords = listOf(
                        "快速记录", "速记", "笔记", "quick note", "note", "记录", "kuaisujilu", "suji", "biji", "jilu", "ksjl", "sj", "bj", "jl",
                    ),
                ),
            ),
        ),
        group(
            SettingsGroupKind.MODELS,
            R.string.settings_group_models,
            R.string.settings_group_models_desc,
            MuseIcons.provider,
            entries(
                entry(
                    SettingsEntryId.PROVIDER,
                    R.string.settings_screen_provider,
                    R.string.settings_screen_provider_desc,
                    MuseIcons.provider,
                    SettingsEntryAction.Navigate(SettingsModelRoute),
                    keywords = listOf(
                        "供应商", "模型", "provider", "API", "密钥", "gongyingshang", "moxing", "miyao",
                        "gys", "mx", "my", "绘图", "Agnes", "DALL-E", "绘图供应商",
                    ),
                ),
                entry(
                    SettingsEntryId.TASK_ROUTING,
                    R.string.settings_task_routing_title,
                    R.string.settings_screen_task_routing_desc,
                    MuseIcons.taskRouting,
                    SettingsEntryAction.Navigate(SettingsTaskRoutingRoute),
                    keywords = listOf(
                        "辅助模型", "任务路由", "路由", "小工具", "大工具", "视觉", "模型", "主对话模型", "默认模型",
                        "fuzhumoxing", "renwuluyou", "luyou", "xiaogongju", "dagongju", "shijue",
                        "moxing", "zhuduihua", "moren", "fzmx", "rwly", "ly", "xgj", "dgj", "sj",
                        "mx", "zdh", "mr",
                    ),
                ),
                entry(
                    SettingsEntryId.PROVIDER_PLUGINS,
                    R.string.provider_plugins_title,
                    R.string.settings_screen_desc_provider_plugins,
                    MuseIcons.affiliate,
                    SettingsEntryAction.Navigate(PluginManageRoute),
                    keywords = listOf(
                        "插件管理", "供应商插件", "插件", "插件包", "provider plugin", "chajian",
                        "chajianbao", "gongyingshangchajian", "cj", "cjb",
                    ),
                ),
            ),
        ),
        group(
            SettingsGroupKind.TOOLS,
            R.string.settings_group_tools,
            R.string.settings_group_tools_desc,
            MuseIcons.wrench,
            entries(
                entry(
                    SettingsEntryId.TOOLS,
                    R.string.settings_screen_tools,
                    R.string.settings_screen_tools_desc,
                    MuseIcons.wrench,
                    SettingsEntryAction.Navigate(ToolsScreenRoute),
                    keywords = listOf("工具", "AI工具", "ToolRegistry", "tool", "插件", "gongju", "AIgongju", "chajian", "gj", "AIgj", "cj"),
                ),
                entry(
                    SettingsEntryId.PERMISSION_WIZARD,
                    R.string.permission_wizard_title,
                    R.string.permission_wizard_desc,
                    MuseIcons.shieldCheck,
                    SettingsEntryAction.Navigate(SettingsPermissionWizardRoute),
                    keywords = listOf("权限", "向导", "无障碍", "Shizuku", "Root", "quanxian", "xiangdao", "wuzhangai", "qx", "xd", "wza"),
                ),
                entry(
                    SettingsEntryId.WEB_SEARCH,
                    R.string.settings_screen_web_search,
                    R.string.settings_screen_web_search_desc,
                    MuseIcons.globe,
                    SettingsEntryAction.Navigate(SettingsWebSearchRoute),
                    keywords = listOf(
                        "联网搜索", "搜索", "web search", "网络搜索", "在线搜索", "lianwang", "sousuo",
                        "wangluosousuo", "zaixiansousuo", "lwss", "ss", "wlss", "zxss",
                    ),
                ),
            ),
        ),
        group(
            SettingsGroupKind.VOICE,
            R.string.settings_group_voice,
            R.string.settings_group_voice_desc,
            MuseIcons.microphone,
            entries(
                entry(
                    SettingsEntryId.TTS,
                    R.string.settings_screen_media,
                    R.string.settings_screen_media_desc,
                    MuseIcons.microphone,
                    SettingsEntryAction.Navigate(SettingsMediaRoute),
                    keywords = listOf(
                        "语音播报", "媒体", "录音", "语音", "播报", "yuyinbobao",
                        "meiti", "luyin", "yuyin", "bobao", "mt", "ly", "yy", "bb",
                    ),
                ),
                entry(
                    SettingsEntryId.ASR,
                    R.string.settings_screen_asr,
                    R.string.settings_screen_asr_desc,
                    MuseIcons.microphone,
                    SettingsEntryAction.Navigate(SettingsAsrRoute),
                    keywords = listOf(
                        "ASR", "asr", "语音识别", "speech", "转文字", "识别语音", "yuyinshibie", "zhuanwenzi",
                        "shibieyuyin", "yysb", "zwz", "sbyy",
                    ),
                ),
                entry(
                    SettingsEntryId.VOICE_CLONING,
                    R.string.voice_cloning_title,
                    R.string.settings_screen_desc_voice_cloning,
                    MuseIcons.play,
                    SettingsEntryAction.Navigate(VoiceCloningRoute),
                    keywords = listOf(
                        "语音克隆", "克隆", "音色", "声音复刻", "voice clone", "yuyinkelong",
                        "kelong", "yinse", "shengyinfuke", "yykl", "kl", "ys",
                    ),
                ),
            ),
        ),
        group(
            SettingsGroupKind.IMAGE,
            R.string.settings_group_image,
            R.string.settings_group_image_desc,
            MuseIcons.image,
            entries(
                entry(
                    SettingsEntryId.IMAGE_VIDEO_GEN,
                    R.string.video_gen_title,
                    R.string.settings_screen_desc_video_gen,
                    MuseIcons.video,
                    SettingsEntryAction.Navigate(VideoGenerationRoute),
                    keywords = listOf(
                        "视频生成", "图片生成", "绘图", "生成图片", "文生图", "生成视频", "video gen",
                        "image gen", "shipinshengcheng", "tupianshengcheng", "huitu", "shengchengtupian",
                        "wenshengtu", "spsc", "tpsc", "ht",
                    ),
                ),
                entry(
                    SettingsEntryId.VISION,
                    R.string.settings_vision_title,
                    R.string.settings_screen_desc_vision,
                    MuseIcons.image,
                    SettingsEntryAction.Navigate(SettingsVisionRoute),
                    keywords = listOf(
                        "视觉辅助", "视觉", "图片理解", "看图", "OCR", "视觉模型", "shijue",
                        "tupianlijie", "kantu", "shijuefuzhu", "shijuemoxing", "sj", "tplj", "kt",
                    ),
                ),
                entry(
                    SettingsEntryId.TRANSLATE,
                    R.string.settings_screen_translate,
                    R.string.settings_screen_translate_desc,
                    MuseIcons.languages,
                    SettingsEntryAction.Navigate(TranslateRoute),
                    keywords = listOf(
                        "翻译", "translate", "语言", "互译", "源语言", "目标语言", "fanyi",
                        "yuyan", "huyi", "yuanyuyan", "mubiaoyuyan", "fy", "yy",
                    ),
                ),
            ),
        ),
        group(
            SettingsGroupKind.MEMORY,
            R.string.settings_group_memory,
            R.string.settings_group_memory_desc,
            MuseIcons.brain,
            entries(
                entry(
                    SettingsEntryId.MEMORY,
                    R.string.settings_screen_memory_notification,
                    R.string.settings_screen_memory_notification_desc,
                    MuseIcons.atom,
                    SettingsEntryAction.Navigate(SettingsMemoryRoute),
                    keywords = listOf(
                        "记忆", "长期记忆", "memory", "遗忘", "回忆", "记忆空间", "jiyi", "changqijiyi", "yiwang",
                        "huiyi", "jiyikongjian", "jy", "cqjy", "yw", "hy", "kj",
                    ),
                ),
                entry(
                    SettingsEntryId.RAG,
                    R.string.settings_screen_rag,
                    R.string.settings_screen_rag_desc,
                    MuseIcons.book,
                    SettingsEntryAction.Navigate(SettingsRagRoute),
                    keywords = listOf(
                        "RAG", "知识库", "rag", "检索", "向量", "文档", "zhishiku", "jiansuo",
                        "xiangliang", "wendang", "zsk", "js", "xl", "wd",
                    ),
                ),
            ),
        ),
        group(
            SettingsGroupKind.CONNECTION,
            R.string.settings_group_connection,
            R.string.settings_group_connection_desc,
            MuseIcons.link,
            entries(
                entry(
                    SettingsEntryId.CONNECTION_CENTER,
                    R.string.connection_center_title,
                    R.string.connection_center_desc,
                    MuseIcons.link,
                    SettingsEntryAction.Navigate(ConnectionCenterRoute),
                    keywords = listOf(
                        "连接中心", "连接", "渠道", "MCP", "插件", "权限",
                        "lianjie", "lianjiezhongxin", "qudao", "chajian", "quanxian", "mcp",
                    ),
                ),
                entry(
                    SettingsEntryId.CHANNEL,
                    R.string.channel_page_title,
                    R.string.settings_screen_desc_channel,
                    MuseIcons.wifi,
                    SettingsEntryAction.Navigate(ChannelSettingsRoute),
                    keywords = listOf(
                        "消息渠道", "渠道", "平台互通", "桥接", "社交平台", "飞书", "QQ", "微信", "IM",
                        "channel", "feishu", "bridge", "xiaoxi", "qidao", "weixin", "xxqd",
                        "pingtaihutong", "qiaojie",
                    ),
                ),
                entry(
                    SettingsEntryId.WEB_ACCESS,
                    R.string.settings_screen_web_access,
                    R.string.settings_screen_web_access_desc,
                    MuseIcons.router,
                    SettingsEntryAction.Navigate(SettingsWebAccessRoute),
                    keywords = listOf(
                        "网页版", "网页", "Web 服务", "Web 服务器", "浏览器", "局域网", "二维码", "扫一扫",
                        "扫码", "手机访问", "PIN", "上网地址",
                        "wangyeban", "wangye", "liulanqi", "juyuwang", "erweima", "saoyisao", "sao",
                        "shoujifangwen", "pin", "web",
                    ),
                    // 本版暂不主推：服务端与页面都能用，只是不在分类页给入口，搜索仍能命中。
                    browseVisible = false,
                ),
                entry(
                    SettingsEntryId.MCP,
                    R.string.settings_screen_mcp,
                    R.string.settings_screen_mcp_desc,
                    MuseIcons.plug,
                    SettingsEntryAction.Navigate(SettingsMcpRoute),
                    keywords = listOf("MCP", "mcp", "服务器", "Model Context Protocol", "工具协议", "fuwuqi", "gongjixieyi", "fwq", "gjxy"),
                ),
                // 代理条目的说明由运行时按当前配置拼（未启用 / type host:port），清单里不给固定文案。
                entry(
                    SettingsEntryId.PROXY,
                    R.string.proxy_title,
                    null,
                    MuseIcons.proxy,
                    SettingsEntryAction.Navigate(SettingsProxyRoute),
                    keywords = listOf("代理", "proxy", "网络", "VPN", "HTTP代理", "daili", "wangluo", "dl", "wl"),
                ),
            ),
        ),
        group(
            SettingsGroupKind.AUTOMATION,
            R.string.settings_group_automation,
            R.string.settings_group_automation_desc,
            MuseIcons.bell,
            entries(
                entry(
                    SettingsEntryId.PROACTIVE,
                    R.string.settings_agent_proactive_title,
                    R.string.settings_agent_proactive_subtitle,
                    MuseIcons.bell,
                    SettingsEntryAction.Navigate(SettingsProactiveRoute),
                    keywords = listOf(
                        "主动消息", "主动", "推送", "定时发送", "proactive", "zhudongxiaoxi", "zhudong",
                        "tuisong", "dingshifasong", "zdxx", "zd", "ts",
                    ),
                ),
                entry(
                    SettingsEntryId.SCHEDULED_TASKS,
                    R.string.schedule_title,
                    R.string.settings_screen_desc_schedule,
                    MuseIcons.clock,
                    SettingsEntryAction.Navigate(ScheduledTasksRoute),
                    keywords = listOf(
                        "定时任务", "定时", "计划任务", "scheduled", "task", "cron", "dingshirenwu",
                        "dingshi", "jihuarenwu", "dsrw", "ds", "jhrw",
                    ),
                ),
                entry(
                    SettingsEntryId.NOTIFICATION_LISTENER,
                    R.string.settings_screen_notification_listener,
                    R.string.settings_screen_notification_listener_desc,
                    MuseIcons.bell,
                    SettingsEntryAction.Navigate(NotificationListenerRoute),
                    keywords = listOf(
                        "通知监听", "通知", "NotificationListener", "通知权限", "tongzhijianting", "tongzhi",
                        "tongzhiquanxian", "tzjl", "tz", "tzqx",
                    ),
                ),
                entry(
                    SettingsEntryId.UI_AUTOMATION,
                    R.string.automation_settings_title,
                    R.string.automation_settings_subtitle,
                    MuseIcons.computer,
                    SettingsEntryAction.Navigate(SettingsAutomationRoute),
                    keywords = listOf("UI自动化", "自动操作", "屏幕读取", "手势", "zidonghua", "zidongcaozuo", "pingmu", "shoushi"),
                ),
                entry(
                    SettingsEntryId.KEEP_ALIVE,
                    R.string.settings_keep_alive_title,
                    R.string.settings_keep_alive_subtitle,
                    MuseIcons.battery,
                    SettingsEntryAction.Navigate(KeepAliveSettingsRoute),
                    keywords = listOf(
                        "常驻后台", "保活", "后台", "电池优化", "自启动", "通知文案",
                        "changzhu", "baohuo", "houtai", "dianchi", "zhiqidong",
                        "czy", "bh", "ht", "dc", "zqd",
                    ),
                ),
            ),
        ),
        group(
            SettingsGroupKind.APPEARANCE,
            R.string.settings_group_appearance,
            R.string.settings_group_appearance_desc,
            MuseIcons.palette,
            entries(
                entry(
                    SettingsEntryId.APPEARANCE,
                    R.string.settings_screen_appearance_label,
                    R.string.settings_screen_appearance_desc,
                    MuseIcons.palette,
                    SettingsEntryAction.Navigate(SettingsAppearanceRoute),
                    keywords = listOf(
                        "外观", "显示", "界面", "字号", "字体", "waiguan", "xianshi", "jiemian", "zihao",
                        "ziti", "wg", "xs", "jm", "zt", "主题", "圆角", "启动页", "引导", "壁纸",
                    ),
                ),
                entry(
                    SettingsEntryId.MINIPHONE,
                    R.string.settings_miniphone_title,
                    R.string.settings_screen_miniphone_desc,
                    MuseIcons.deviceMobile,
                    SettingsEntryAction.Navigate(MiniPhoneRoute),
                    keywords = listOf(
                        "小手机", "内置手机", "手机", "miniphone", "mini phone", "xiaoshouji",
                        "neizhishouji", "shouji", "xsj", "sj",
                    ),
                    // 本版暂不主推：模块与路由完整保留，只是不在分类页与首页给入口，搜索仍能命中。
                    browseVisible = false,
                ),
            ),
        ),
        group(
            SettingsGroupKind.DATA_STORAGE,
            R.string.settings_group_data_storage,
            R.string.settings_group_data_storage_desc,
            MuseIcons.database,
            entries(
                entry(
                    SettingsEntryId.DATA_MANAGEMENT,
                    R.string.data_management_entry,
                    R.string.data_management_entry_desc,
                    MuseIcons.database,
                    SettingsEntryAction.Navigate(DataManagementRoute),
                    keywords = listOf(
                        "数据管理", "数据", "存储", "清理", "缓存", "shujuguanli", "shuju", "cunchu", "qingli",
                        "huancun", "sjgl", "sj", "cc", "ql", "hc",
                    ),
                ),
                entry(
                    SettingsEntryId.WORKSPACE,
                    R.string.workspace_title,
                    R.string.workspace_desc,
                    MuseIcons.folder,
                    SettingsEntryAction.Navigate(WorkspaceRoute),
                    keywords = listOf(
                        "工作区", "文件管理", "workspace", "文件", "目录", "gongzuoqu", "wenjianguanli",
                        "wenjian", "mulu", "gzq", "wjgl", "wj", "ml",
                    ),
                ),
                entry(
                    SettingsEntryId.BACKUP,
                    R.string.settings_screen_data_backup,
                    R.string.settings_screen_data_backup_desc,
                    MuseIcons.cloud,
                    SettingsEntryAction.Navigate(SettingsDataRoute),
                    keywords = listOf(
                        "备份", "云备份", "cloud", "backup", "S3", "WebDAV", "同步",
                        "beifen", "yunbeifen", "tongbu", "bf", "ybf", "tb",
                    ),
                ),
                entry(
                    SettingsEntryId.DATA_IMPORT,
                    R.string.settings_screen_data_import,
                    R.string.settings_screen_data_import_desc,
                    MuseIcons.cloudUpload,
                    SettingsEntryAction.Navigate(SettingsDataImportRoute),
                    keywords = listOf("数据导入", "导入", "import", "恢复数据", "shujudaoru", "daoru", "huifushuju", "sjdr", "dr", "hfsj"),
                ),
                entry(
                    SettingsEntryId.ARCHIVED_CHATS,
                    R.string.settings_screen_entry_archived,
                    R.string.settings_screen_archived_desc,
                    MuseIcons.archive,
                    SettingsEntryAction.Navigate(ArchivedChatsRoute),
                    keywords = listOf("归档", "archive", "已归档", "guidang", "gd"),
                ),
            ),
        ),
        group(
            SettingsGroupKind.PRIVACY_SECURITY,
            R.string.settings_group_privacy_security,
            R.string.settings_group_privacy_security_desc,
            MuseIcons.lock,
            entries(
                entry(
                    SettingsEntryId.PII_GUARD,
                    R.string.settings_screen_pii_guard,
                    R.string.settings_screen_pii_guard_desc,
                    MuseIcons.shield,
                    SettingsEntryAction.Toggle(
                        key = SettingsToggleKey.PII_GUARD,
                        flow = { repository -> repository.piiGuardEnabledFlow },
                    ),
                    keywords = listOf(
                        "PII", "隐私", "脱敏", "遮蔽", "手机号", "邮箱", "身份证", "pii", "yinsi", "tuomin", "zhebi", "shenfenzheng",
                    ),
                ),
                entry(
                    SettingsEntryId.SHARE,
                    R.string.settings_screen_share,
                    R.string.settings_screen_security_desc,
                    MuseIcons.lock,
                    SettingsEntryAction.Navigate(SettingsSecurityRoute),
                    keywords = listOf("分享", "导出", "分享模板", "share", "fenxiang", "daochu", "fx", "dc"),
                ),
            ),
        ),
        group(
            SettingsGroupKind.ABOUT,
            R.string.settings_group_about,
            R.string.settings_group_about_desc,
            MuseIcons.info,
            entries(
                entry(
                    SettingsEntryId.TUTORIAL,
                    R.string.settings_screen_tutorial,
                    R.string.settings_screen_tutorial_desc,
                    MuseIcons.school,
                    SettingsEntryAction.Navigate(SettingsTutorialRoute),
                    keywords = listOf(
                        "教程", "新手", "引导", "tutorial", "帮助", "jiaocheng", "xinshou", "yindao", "bangzhu", "jc", "xs", "yd", "bz",
                    ),
                ),
                entry(
                    SettingsEntryId.ABOUT,
                    R.string.settings_screen_about,
                    R.string.settings_screen_about_desc,
                    MuseIcons.info,
                    SettingsEntryAction.Navigate(SettingsAboutRoute),
                    keywords = listOf("关于", "版本", "about", "信息", "guanyu", "banben", "xinxi", "gy", "bb", "xx"),
                ),
                entry(
                    SettingsEntryId.CHECK_UPDATE,
                    R.string.settings_screen_check_update,
                    R.string.settings_screen_check_update_desc,
                    MuseIcons.refresh,
                    SettingsEntryAction.Action(SettingsActionId.CHECK_UPDATE),
                    keywords = listOf("检查更新", "更新", "update", "版本", "升级", "jianchagengxin", "gengxin", "shengji", "jcgc", "gx", "sj"),
                ),
            ),
        ),
        group(
            SettingsGroupKind.DEVELOPER,
            R.string.settings_group_developer,
            R.string.settings_group_developer_desc,
            MuseIcons.terminal,
            entries(
                entry(
                    SettingsEntryId.STATS,
                    R.string.settings_screen_stats,
                    R.string.settings_screen_stats_desc,
                    MuseIcons.chartBar,
                    SettingsEntryAction.Navigate(StatsRoute),
                    keywords = listOf(
                        "统计", "使用统计", "stats", "热力图", "数据", "tongji",
                        "shiyongtongji", "relitu", "shuju", "tj", "sytj", "rlt", "sj",
                    ),
                ),
                entry(
                    SettingsEntryId.EXPERIMENTS,
                    R.string.settings_screen_experiments,
                    R.string.settings_screen_experiments_desc,
                    MuseIcons.flask,
                    SettingsEntryAction.Navigate(SettingsExperimentsRoute),
                    keywords = listOf("实验性", "实验", "experimental", "beta", "试验", "shiyanxing", "shiyan", "syx", "sy"),
                ),
                entry(
                    SettingsEntryId.DEBUG_LOG,
                    R.string.settings_screen_debug_log,
                    R.string.settings_screen_debug_log_desc,
                    MuseIcons.bug,
                    SettingsEntryAction.Navigate(DebugRoute),
                    keywords = listOf("调试", "日志", "debug", "log", "Logger", "tiaoshi", "rizhi", "ts", "rz"),
                ),
                entry(
                    SettingsEntryId.CRASH_REPORT,
                    R.string.settings_crash_page_title,
                    R.string.settings_screen_desc_crash_report,
                    MuseIcons.shieldCheck,
                    SettingsEntryAction.Navigate(SettingsCrashReportRoute),
                    keywords = listOf("崩溃", "崩溃上报", "crash", "报告", "诊断", "bengkui", "baogao", "zhenduan", "bk", "bg", "zd"),
                ),
                entry(
                    SettingsEntryId.AUDIT_LOG,
                    R.string.settings_audit_log,
                    R.string.settings_screen_desc_audit_log,
                    MuseIcons.history,
                    SettingsEntryAction.Navigate(AuditLogRoute),
                    keywords = listOf(
                        "审计", "日志", "audit", "操作记录", "审计日志", "shenji",
                        "rizhi", "caozuojilu", "shenjirizhi", "sj", "rz", "czjl", "sjrz",
                    ),
                ),
            ),
        ),
    )

    /** 全部条目，按首页分类顺序与分类内顺序展开。 */
    val entries: List<SettingsEntrySpec> = groups.flatMap { it.entries }

    /** 按分类取声明；分类是枚举，必然存在。 */
    fun group(kind: SettingsGroupKind): SettingsGroupSpec = groups.first { it.kind == kind }

    private fun group(
        kind: SettingsGroupKind,
        @StringRes titleRes: Int,
        @StringRes subtitleRes: Int,
        icon: ImageVector,
        entries: List<SettingsEntrySpec>,
    ): SettingsGroupSpec = SettingsGroupSpec(
        kind = kind,
        titleRes = titleRes,
        subtitleRes = subtitleRes,
        icon = icon,
        sections = listOf(SettingsSectionSpec(entries = entries)),
    )

    private fun entries(vararg entries: SettingsEntrySpec): List<SettingsEntrySpec> = entries.toList()

    private fun entry(
        id: SettingsEntryId,
        @StringRes titleRes: Int,
        @StringRes descRes: Int?,
        icon: ImageVector,
        action: SettingsEntryAction,
        keywords: List<String> = emptyList(),
        browseVisible: Boolean = true,
    ): SettingsEntrySpec = SettingsEntrySpec(
        id = id,
        titleRes = titleRes,
        descRes = descRes,
        icon = icon,
        action = action,
        keywords = keywords,
        browseVisible = browseVisible,
    )
}
