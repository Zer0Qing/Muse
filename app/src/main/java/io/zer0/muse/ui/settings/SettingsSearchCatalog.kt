package io.zer0.muse.ui.settings

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import io.zer0.muse.R
import io.zer0.muse.ui.navigation.ComponentGalleryRoute
import io.zer0.muse.ui.navigation.ConnectorSettingsRoute
import io.zer0.muse.ui.navigation.PluginManageRoute
import io.zer0.muse.ui.navigation.SettingsAppearanceRoute
import io.zer0.muse.ui.navigation.SettingsAgentRoute
import io.zer0.muse.ui.navigation.SettingsChatRoute
import io.zer0.muse.ui.navigation.SettingsCloudBackupRoute
import io.zer0.muse.ui.navigation.SettingsMcpRoute
import io.zer0.muse.ui.navigation.SettingsMediaRoute
import io.zer0.muse.ui.navigation.SettingsMemoryConfigRoute
import io.zer0.muse.ui.navigation.SettingsModelRoute
import io.zer0.muse.ui.navigation.SettingsProxyRoute
import io.zer0.muse.ui.navigation.SettingsRagRoute
import io.zer0.muse.ui.navigation.SettingsTaskRoutingRoute
import io.zer0.muse.ui.navigation.SettingsWebSearchRoute
import io.zer0.muse.ui.navigation.TerminalRoute
import io.zer0.muse.ui.navigation.ToolsScreenRoute

/**
 * 搜索索引里的一条记录。
 *
 * 索引覆盖两类入口：分类页里能看到的条目（来自 [SettingsCatalog]），以及只做搜索直达、
 * 不出现在分类页更深一层的页内设置在 [SettingsSearchExtras] 里的条目。
 * [group] 永远是 14 个一级分类之一，界面上用它拼面包屑。
 */
data class SettingsSearchRecord(
    @StringRes val titleRes: Int,
    val keywords: List<String>,
    val group: SettingsGroupKind,
    val action: SettingsEntryAction,
    /** 结果行的图标：条目自带图标，只做搜索直达的页内设置用所属分类的图标。 */
    val icon: ImageVector,
)

/** 面包屑分隔符：分类 › 条目。 */
const val SETTINGS_BREADCRUMB_SEPARATOR: String = " › "

/** 拼搜索结果里的面包屑（分类在前，条目在后）。 */
fun settingsBreadcrumb(groupTitle: String, entryTitle: String): String =
    groupTitle + SETTINGS_BREADCRUMB_SEPARATOR + entryTitle

/**
 * 按关键词过滤搜索索引。
 *
 * 标题、分类名、关键词三处任一命中即可；[titleOf] / [groupTitleOf] 把资源解析交给调用方，
 * 这样同一份过滤逻辑在界面（stringResource）与测试（真资源）里都能跑。
 */
fun filterSettingsSearch(
    records: List<SettingsSearchRecord>,
    query: String,
    titleOf: (Int) -> String,
    groupTitleOf: (SettingsGroupKind) -> String,
): List<SettingsSearchRecord> {
    if (query.isBlank()) return records
    val needle = query.trim().lowercase()
    return records.filter { record ->
        titleOf(record.titleRes).lowercase().contains(needle) ||
            groupTitleOf(record.group).lowercase().contains(needle) ||
            record.keywords.any { keyword -> keyword.lowercase().contains(needle) }
    }
}

/** 搜索索引：分类清单里的条目 + 只做搜索直达的页内设置。 */
object SettingsSearchCatalog {

    val records: List<SettingsSearchRecord> =
        SettingsCatalog.groups.flatMap { group ->
            group.entries.map { entry ->
                SettingsSearchRecord(
                    titleRes = entry.titleRes,
                    keywords = entry.keywords,
                    group = group.kind,
                    action = entry.action,
                    icon = entry.icon,
                )
            }
        } + SettingsSearchExtras.records
}

/**
 * 只出现在搜索索引里的设置。
 *
 * 这些是某个页面里的具体设置项（字号、流式响应、检索模型等），用户会直接搜它们，
 * 但它们不该在分类页里跟「能点进去的页面」平级，所以不进入 [SettingsCatalog]。
 */
internal object SettingsSearchExtras {

    val records: List<SettingsSearchRecord> = listOf(
        record(
            R.string.section_theme,
            SettingsGroupKind.APPEARANCE,
            SettingsEntryAction.Navigate(SettingsAppearanceRoute),
            listOf(
                "主题", "配色", "深色", "浅色", "暗黑", "AMOLED", "颜色", "zhuti", "peise", "shense",
                "qianse", "anhe", "yase", "zt", "ps", "ss", "qs",
            ),
        ),
        record(
            R.string.settings_screen_entry_font_size,
            SettingsGroupKind.APPEARANCE,
            SettingsEntryAction.Navigate(SettingsAppearanceRoute),
            listOf("字号", "字体大小", "字体", "大小", "ziti", "zihao", "ztdx", "zt"),
        ),
        record(
            R.string.settings_screen_entry_theme_mode,
            SettingsGroupKind.APPEARANCE,
            SettingsEntryAction.Navigate(SettingsAppearanceRoute),
            listOf("主题模式", "浅色", "深色", "跟随系统", "zhutimoshi", "qianse", "shense", "genshixitong", "ztms", "qs", "ss", "gsxt"),
        ),
        record(
            R.string.settings_theme_dynamic_color,
            SettingsGroupKind.APPEARANCE,
            SettingsEntryAction.Navigate(SettingsAppearanceRoute),
            listOf("动态取色", "取色", "壁纸", "dongtaiquse", "quse", "dtqs", "qs", "bz"),
        ),
        record(
            R.string.settings_screen_entry_scheduled_theme,
            SettingsGroupKind.APPEARANCE,
            SettingsEntryAction.Navigate(SettingsAppearanceRoute),
            listOf("定时切换", "自动切换", "深色模式", "dingshiqiehuan", "zidongqiehuan", "shensemoshi", "dsqh", "zdqh", "ssms"),
        ),
        record(
            R.string.settings_screen_entry_tts_playback,
            SettingsGroupKind.VOICE,
            SettingsEntryAction.Navigate(SettingsMediaRoute),
            listOf("TTS", "tts", "语音播报", "朗读", "文字转语音", "TextToSpeech", "yuyinbobao", "langdu", "wenzi", "yybb", "ld"),
        ),
        record(
            R.string.settings_screen_entry_tts_speed,
            SettingsGroupKind.VOICE,
            SettingsEntryAction.Navigate(SettingsMediaRoute),
            listOf("TTS", "语速", "音高", "yusu", "yingao", "ys", "yg"),
        ),
        record(
            R.string.settings_media_tts_voice_selector,
            SettingsGroupKind.VOICE,
            SettingsEntryAction.Navigate(SettingsMediaRoute),
            listOf("声音", "语音", "voice", "shengyin", "yuyin", "sy", "yy"),
        ),
        record(
            R.string.settings_screen_entry_api_key,
            SettingsGroupKind.MODELS,
            SettingsEntryAction.Navigate(SettingsModelRoute),
            listOf("API Key", "密钥", "key", "token", "凭证", "apiKey", "miyao", "pingzheng"),
        ),
        record(
            R.string.muse_plugins_manage,
            SettingsGroupKind.MODELS,
            SettingsEntryAction.Navigate(PluginManageRoute),
            listOf("插件管理", "外部插件", "muse-plugin", "插件包", "导入插件", "chajian", "plugin", "daoruchajian", "cjb", "cjgl"),
        ),
        record(
            R.string.settings_memory_page_title,
            SettingsGroupKind.MEMORY,
            SettingsEntryAction.Navigate(SettingsMemoryConfigRoute),
            listOf(
                "记忆", "记忆配置", "记忆设置", "记忆系统", "memory", "jiyi", "jiyipeizhi",
                "jiyishezhi", "jiyixitong", "jy", "jypz", "jysz",
            ),
        ),
        record(
            R.string.settings_memory_custom_compress_prompt,
            SettingsGroupKind.MEMORY,
            SettingsEntryAction.Navigate(SettingsTaskRoutingRoute),
            listOf("压缩", "压缩提示词", "上下文压缩", "自定义提示词", "yasuo", "yasuotishici", "shangwen", "ystc", "ys"),
        ),
        record(
            R.string.settings_screen_entry_memory_toggle,
            SettingsGroupKind.MEMORY,
            SettingsEntryAction.Navigate(SettingsMemoryConfigRoute),
            listOf("记忆", "开关", "启用记忆系统", "jiyi", "kaiguan", "qiyong", "jy", "kg", "qyjy"),
        ),
        record(
            R.string.settings_screen_entry_rag_model,
            SettingsGroupKind.MEMORY,
            SettingsEntryAction.Navigate(SettingsRagRoute),
            listOf("检索模型", "RAG模型", "相似度", "jiansuomoxing", "ragmoxing", "xiangsidu", "jsmx", "ragmx", "xsd"),
        ),
        record(
            R.string.settings_screen_entry_chunking,
            SettingsGroupKind.MEMORY,
            SettingsEntryAction.Navigate(SettingsRagRoute),
            listOf("分段", "分块", "策略", "fenduan", "fenkuai", "celve", "fd", "fk", "cl"),
        ),
        record(
            R.string.connector_page_title,
            SettingsGroupKind.CONNECTION,
            SettingsEntryAction.Navigate(ConnectorSettingsRoute),
            listOf("连接器", "授权", "OAuth", "connector", "oauth", "shouquan", "lianjieqi", "sjq", "lqq", "sq"),
        ),
        record(
            R.string.settings_mcp_title,
            SettingsGroupKind.CONNECTION,
            SettingsEntryAction.Navigate(SettingsMcpRoute),
            listOf("MCP", "服务器", "ModelContextProtocol", "fuwuqi", "fwq"),
        ),
        record(
            R.string.terminal_title,
            SettingsGroupKind.TOOLS,
            SettingsEntryAction.Navigate(TerminalRoute),
            listOf("终端", "命令行", "shell", "terminal", "zhongduan", "minglinghang"),
        ),
        record(
            R.string.settings_web_search_engine,
            SettingsGroupKind.TOOLS,
            SettingsEntryAction.Navigate(SettingsWebSearchRoute),
            listOf("搜索引擎", "Bing", "Jina", "SearXNG", "sousuoyinqing", "ssyq"),
        ),
        record(
            R.string.settings_screen_entry_tool_approval,
            SettingsGroupKind.TOOLS,
            SettingsEntryAction.Navigate(ToolsScreenRoute),
            listOf("工具批准", "批准模式", "自动批准", "gongjupizhun", "pizhunmoshi", "zidongpizhun", "gjpz", "pzms", "zdpz"),
        ),
        record(
            R.string.settings_memory_keep_awake,
            SettingsGroupKind.ASSISTANTS,
            SettingsEntryAction.Navigate(SettingsAgentRoute),
            listOf(
                "保持唤醒", "唤醒", "wakelock", "不休眠", "常亮", "keep awake", "baochihuanxing",
                "huanxing", "buxiumian", "changliang", "bchx", "hx", "bxm", "cl",
            ),
        ),
        record(
            R.string.settings_agent_boot_section,
            SettingsGroupKind.ASSISTANTS,
            SettingsEntryAction.Navigate(SettingsAgentRoute),
            listOf(
                "开机自启", "自启", "自启动", "开机", "boot", "auto launch", "BootReceiver",
                "kaijiziqi", "ziqi", "zidong", "kaiji", "kjzq", "zq", "zdd", "kj",
            ),
        ),
        record(
            R.string.settings_screen_entry_collab,
            SettingsGroupKind.ASSISTANTS,
            SettingsEntryAction.Navigate(SettingsAgentRoute),
            listOf("协作", "多助手", "团队", "xiezhuo", "duozhushou", "tuandui", "xz", "dzs", "td"),
        ),
        record(
            R.string.settings_chat_streaming,
            SettingsGroupKind.CHAT,
            SettingsEntryAction.Navigate(SettingsChatRoute),
            listOf("流式", "流式响应", "实时输出", "liushi", "liushixiangying", "shishishuchu", "ls", "lsxy", "sssc"),
        ),
        record(
            R.string.settings_screen_entry_enter_send,
            SettingsGroupKind.CHAT,
            SettingsEntryAction.Navigate(SettingsChatRoute),
            listOf("回车发送", "回车", "发送", "huichefasong", "huiche", "fasong", "hcfs", "hc", "fs"),
        ),
        record(
            R.string.settings_screen_entry_auto_scroll,
            SettingsGroupKind.CHAT,
            SettingsEntryAction.Navigate(SettingsChatRoute),
            listOf("自动滚动", "滚动", "zidonggundong", "gundong", "zdgd", "gd"),
        ),
        record(
            R.string.chat_deep_thinking_cd,
            SettingsGroupKind.CHAT,
            SettingsEntryAction.Navigate(SettingsChatRoute),
            listOf("深度思考", "默认深度思考", "shendusikao", "morethorough", "sds", "sdsz"),
        ),
        record(
            R.string.settings_screen_entry_timestamps,
            SettingsGroupKind.CHAT,
            SettingsEntryAction.Navigate(SettingsChatRoute),
            listOf("时间戳", "24小时", "timestamp", "shijianchuo", "24xiaoshi", "sjc"),
        ),
        record(
            R.string.settings_screen_entry_proxy_switch,
            SettingsGroupKind.CONNECTION,
            SettingsEntryAction.Navigate(SettingsProxyRoute),
            listOf("代理", "开关", "Proxy", "daili", "kaiguan", "dl", "kg"),
        ),
        record(
            R.string.settings_backup_cloud_title,
            SettingsGroupKind.DATA_STORAGE,
            SettingsEntryAction.Navigate(SettingsCloudBackupRoute),
            listOf("云备份", "备份", "S3", "WebDAV", "yunbeifen", "beifen", "ybf", "bf"),
        ),
        record(
            R.string.diagnostic_export_title,
            SettingsGroupKind.DEVELOPER,
            SettingsEntryAction.Action(SettingsActionId.EXPORT_DIAGNOSTICS),
            listOf("诊断", "导出", "日志", "diagnostic", "export", "zhenduan", "daochu", "rizhi", "zd", "dc", "rz"),
        ),
        record(
            R.string.settings_component_gallery,
            SettingsGroupKind.DEVELOPER,
            SettingsEntryAction.Navigate(ComponentGalleryRoute),
            listOf(
                "组件", "画廊", "component gallery", "组件演示", "预览", "设计系统",
                "zujian", "hualang", "zujiandemo", "yulan", "shejixitong",
            ),
        ),
    )

    private fun record(
        @StringRes titleRes: Int,
        group: SettingsGroupKind,
        action: SettingsEntryAction,
        keywords: List<String>,
    ): SettingsSearchRecord = SettingsSearchRecord(
        titleRes = titleRes,
        keywords = keywords,
        group = group,
        action = action,
        icon = SettingsCatalog.group(group).icon,
    )
}
