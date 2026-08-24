package io.zer0.muse.ui.chat

import androidx.compose.ui.graphics.vector.ImageVector
import compose.icons.TablerIcons
import compose.icons.tablericons.Activity
import compose.icons.tablericons.Bell
import compose.icons.tablericons.Book
import compose.icons.tablericons.Braces
import compose.icons.tablericons.Browser
import compose.icons.tablericons.Bulb
import compose.icons.tablericons.Calculator
import compose.icons.tablericons.Calendar
import compose.icons.tablericons.Camera
import compose.icons.tablericons.Check
import compose.icons.tablericons.Clipboard
import compose.icons.tablericons.Clock
import compose.icons.tablericons.Cloud
import compose.icons.tablericons.Code
import compose.icons.tablericons.Database
import compose.icons.tablericons.Download
import compose.icons.tablericons.Edit
import compose.icons.tablericons.File
import compose.icons.tablericons.Folder
import compose.icons.tablericons.Globe
import compose.icons.tablericons.Hierarchy
import compose.icons.tablericons.InfoCircle
import compose.icons.tablericons.Language
import compose.icons.tablericons.Mail
import compose.icons.tablericons.MapPin
import compose.icons.tablericons.Message
import compose.icons.tablericons.Microphone
import compose.icons.tablericons.Note
import compose.icons.tablericons.Package
import compose.icons.tablericons.Phone
import compose.icons.tablericons.Photo
import compose.icons.tablericons.Plug
import compose.icons.tablericons.Puzzle
import compose.icons.tablericons.Rss
import compose.icons.tablericons.Search
import compose.icons.tablericons.Send
import compose.icons.tablericons.Server
import compose.icons.tablericons.Settings
import compose.icons.tablericons.Shield
import compose.icons.tablericons.Sitemap
import compose.icons.tablericons.Star
import compose.icons.tablericons.Sun
import compose.icons.tablericons.Terminal2
import compose.icons.tablericons.Tool
import compose.icons.tablericons.Users
import compose.icons.tablericons.Video
import compose.icons.tablericons.Volume
import compose.icons.tablericons.Wallet
import compose.icons.tablericons.Wand
import compose.icons.tablericons.Wifi
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.0.80: 工具调用的视觉元数据 — 图标 + 人摘要 + 中文标签。
 *
 * 把原来所有工具共用一个 Build/Check 图标的毛坯状态,换成每种工具自己的语义图标,
 * 并为折叠态生成一句"做了什么"的中文摘要,替代裸的工具名+前40字结果。
 *
 * 图标全部来自 Tabler Icons(项目已依赖);未命中的工具回退到通用扳手图标,不报错。
 */
internal object ToolCallVisuals {

    /** 取工具图标(未命中回退为通用扳手)。 */
    fun iconFor(toolName: String): ImageVector = mapping[toolName] ?: prefixIcon(toolName) ?: TablerIcons.Tool

    /** 折叠态一句话摘要。 */
    fun summaryFor(toolName: String, arguments: String, result: String, isSuccess: Boolean): String {
        val verb = successVerb[toolName] ?: prefixVerb(toolName) ?: defaultVerb(isSuccess)
        val obj = targetFor(toolName, arguments, result)
        return if (obj.isBlank()) verb else "$verb $obj"
    }

    /** 展开态工具的中文标签(原来直接显示英文 toolName)。 */
    fun labelFor(toolName: String): String = labels[toolName] ?: prettify(toolName)

    // ── 图标映射 ─────────────────────────────────────────────────────────

    private val mapping: Map<String, ImageVector> = mapOf(
        // 搜索 / 知识
        "web_search" to TablerIcons.Globe,
        "search_memory" to TablerIcons.Search,
        "pin_memory" to TablerIcons.Star,
        "unpin_memory" to TablerIcons.Star,
        "recall_experience" to TablerIcons.Book,
        "record_experience" to TablerIcons.Book,
        // 文件 / 工作区
        "read_file" to TablerIcons.File,
        "write_file" to TablerIcons.Edit,
        "list_files" to TablerIcons.Folder,
        "workspace_write" to TablerIcons.Edit,
        "workspace_read" to TablerIcons.File,
        // 代码 / 终端
        "execute_code" to TablerIcons.Code,
        "execute_javascript" to TablerIcons.Braces,
        "execute_shell" to TablerIcons.Terminal2,
        "run_command" to TablerIcons.Terminal2,
        "json_pretty" to TablerIcons.Braces,
        // 浏览器 / 网络
        "open_url" to TablerIcons.Browser,
        "browser_navigate" to TablerIcons.Browser,
        "browser_click" to TablerIcons.Browser,
        "browser_type" to TablerIcons.Browser,
        "browser_snapshot" to TablerIcons.Browser,
        "ping_host" to TablerIcons.Activity,
        "dns_lookup" to TablerIcons.Activity,
        "get_public_ip" to TablerIcons.Globe,
        "download" to TablerIcons.Download,
        // 生成媒体
        "generate_image" to TablerIcons.Photo,
        "generate_video" to TablerIcons.Video,
        "cover_generation" to TablerIcons.Photo,
        // 时间 / 日历 / 提醒
        "get_current_time" to TablerIcons.Clock,
        "calendar_today" to TablerIcons.Calendar,
        "add_calendar_event" to TablerIcons.Calendar,
        "schedule_reminder" to TablerIcons.Bell,
        "cancel_reminder" to TablerIcons.Bell,
        "list_reminders" to TablerIcons.Bell,
        "set_alarm" to TablerIcons.Bell,
        "set_timer" to TablerIcons.Clock,
        "scheduled_task_create" to TablerIcons.Calendar,
        "scheduled_task_list" to TablerIcons.Calendar,
        "scheduled_task_update" to TablerIcons.Calendar,
        "scheduled_task_delete" to TablerIcons.Calendar,
        "scheduled_task_execute" to TablerIcons.Calendar,
        "scheduled_task_get_history" to TablerIcons.Calendar,
        // 系统 / 设备
        "get_device_info" to TablerIcons.Server,
        "get_battery_info" to TablerIcons.Server,
        "get_storage_info" to TablerIcons.Server,
        "get_memory_info" to TablerIcons.Server,
        "get_cpu_info" to TablerIcons.Server,
        "get_display_info" to TablerIcons.Server,
        "get_network_info" to TablerIcons.Server,
        "get_wifi_info" to TablerIcons.Wifi,
        "toggle_wifi" to TablerIcons.Wifi,
        "toggle_bluetooth" to TablerIcons.Rss,
        "get_bluetooth_devices" to TablerIcons.Rss,
        "list_installed_apps" to TablerIcons.Package,
        "open_app" to TablerIcons.Package,
        "open_system_setting" to TablerIcons.Settings,
        "get_foreground_app" to TablerIcons.Package,
        "get_sensors_list" to TablerIcons.Activity,
        "get_brightness" to TablerIcons.Sun,
        "set_brightness" to TablerIcons.Sun,
        "get_volume" to TablerIcons.Volume,
        "set_volume" to TablerIcons.Volume,
        "toggle_flashlight" to TablerIcons.Bulb,
        "vibrate" to TablerIcons.Phone,
        "screen_time" to TablerIcons.Clock,
        // 电话 / 短信 / 联系人
        "make_phone_call" to TablerIcons.Phone,
        "send_sms" to TablerIcons.Message,
        "get_contacts_count" to TablerIcons.Users,
        "get_contacts_list" to TablerIcons.Users,
        "add_contact" to TablerIcons.Users,
        "get_location" to TablerIcons.MapPin,
        "open_maps" to TablerIcons.MapPin,
        "share_text" to TablerIcons.Send,
        "send_email" to TablerIcons.Mail,
        "get_recent_notifications" to TablerIcons.Bell,
        // 剪贴板
        "clipboard_read" to TablerIcons.Clipboard,
        "clipboard_write" to TablerIcons.Clipboard,
        // 便签
        "quick_note_add" to TablerIcons.Note,
        "quick_note_list" to TablerIcons.Note,
        "quick_note_get" to TablerIcons.Note,
        "quick_note_update" to TablerIcons.Note,
        "quick_note_delete" to TablerIcons.Note,
        "quick_note_pin" to TablerIcons.Note,
        // 资源 / 知识库
        "resource_add" to TablerIcons.Database,
        "resource_list" to TablerIcons.Database,
        "resource_search" to TablerIcons.Database,
        "resource_get" to TablerIcons.Database,
        "resource_delete" to TablerIcons.Database,
        // 多 Agent / 委派
        "delegate_agent" to TablerIcons.Hierarchy,
        "subagent_task" to TablerIcons.Hierarchy,
        "subagent_run" to TablerIcons.Hierarchy,
        "subagent_close" to TablerIcons.Hierarchy,
        "channel_pass" to TablerIcons.Sitemap,
        "channel_reply" to TablerIcons.Sitemap,
        "channel_read_context" to TablerIcons.Sitemap,
        // 技能 / 插件 / MCP
        "skill_import" to TablerIcons.Puzzle,
        "skill_run" to TablerIcons.Puzzle,
        "mcp_tool" to TablerIcons.Plug,
        // 通知 / 主动消息 / 卡片
        "notify" to TablerIcons.Bell,
        "proactive_message_wish" to TablerIcons.Wand,
        "show_card" to TablerIcons.Wand,
        "current_status" to TablerIcons.InfoCircle,
        // 杂项
        "calculator" to TablerIcons.Calculator,
        "echo" to TablerIcons.Message,
        "translate" to TablerIcons.Language,
        "speak_text" to TablerIcons.Microphone,
        "get_weather" to TablerIcons.Cloud,
        "todo_write" to TablerIcons.Check,
        "url_encode" to TablerIcons.Code,
        "url_decode" to TablerIcons.Code,
        "base64_encode" to TablerIcons.Code,
        "base64_decode" to TablerIcons.Code,
        "hash_text" to TablerIcons.Shield,
        "generate_uuid" to TablerIcons.Code,
        "generate_password" to TablerIcons.Shield,
        "random_number" to TablerIcons.Calculator,
        "wallet_balance" to TablerIcons.Wallet,
        "take_photo" to TablerIcons.Camera,
    )

    private fun prefixIcon(toolName: String): ImageVector? = when {
        toolName.startsWith("browser_") -> TablerIcons.Browser
        toolName.startsWith("workspace_") -> TablerIcons.File
        toolName.startsWith("scheduled_task_") -> TablerIcons.Calendar
        toolName.startsWith("quick_note_") -> TablerIcons.Note
        toolName.startsWith("resource_") -> TablerIcons.Database
        toolName.startsWith("subagent_") -> TablerIcons.Hierarchy
        toolName.startsWith("channel_") -> TablerIcons.Sitemap
        toolName.startsWith("clipboard_") -> TablerIcons.Clipboard
        toolName.startsWith("mcp_") -> TablerIcons.Plug
        else -> null
    }

    // ── 动词 ─────────────────────────────────────────────────────────────

    private val successVerb: Map<String, String> = mapOf(
        "web_search" to "搜索了网页",
        "search_memory" to "检索了记忆",
        "pin_memory" to "置顶了记忆",
        "unpin_memory" to "取消置顶记忆",
        "read_file" to "读取了文件",
        "write_file" to "写入了文件",
        "list_files" to "列出了文件",
        "workspace_write" to "写入了工作区",
        "execute_code" to "执行了代码",
        "execute_javascript" to "执行了脚本",
        "execute_shell" to "执行了命令",
        "run_command" to "运行了命令",
        "open_url" to "打开了网页",
        "generate_image" to "生成了图片",
        "generate_video" to "生成了视频",
        "get_current_time" to "查询了时间",
        "calendar_today" to "查看了日历",
        "add_calendar_event" to "添加了日历事件",
        "schedule_reminder" to "设置了提醒",
        "cancel_reminder" to "取消了提醒",
        "list_reminders" to "列出了提醒",
        "set_alarm" to "设置了闹钟",
        "set_timer" to "启动了计时器",
        "get_device_info" to "读取了设备信息",
        "get_battery_info" to "读取了电量",
        "get_storage_info" to "读取了存储",
        "get_memory_info" to "读取了内存",
        "get_cpu_info" to "读取了 CPU",
        "get_network_info" to "读取了网络",
        "list_installed_apps" to "列出了应用",
        "open_app" to "打开了应用",
        "make_phone_call" to "拨打电话",
        "send_sms" to "发送了短信",
        "get_contacts_list" to "读取了联系人",
        "add_contact" to "添加了联系人",
        "get_location" to "获取了位置",
        "open_maps" to "打开了地图",
        "share_text" to "分享了文本",
        "send_email" to "发送了邮件",
        "get_recent_notifications" to "读取了通知",
        "clipboard_read" to "读取了剪贴板",
        "clipboard_write" to "写入了剪贴板",
        "quick_note_add" to "新建了便签",
        "quick_note_list" to "列出了便签",
        "quick_note_get" to "读取了便签",
        "quick_note_update" to "更新了便签",
        "quick_note_delete" to "删除了便签",
        "quick_note_pin" to "置顶了便签",
        "resource_add" to "添加了资源",
        "resource_list" to "列出了资源",
        "resource_search" to "检索了资源",
        "resource_get" to "读取了资源",
        "resource_delete" to "删除了资源",
        "delegate_agent" to "委派了子助手",
        "subagent_task" to "启动了子任务",
        "subagent_run" to "运行了子任务",
        "subagent_close" to "关闭了子任务",
        "notify" to "发送了通知",
        "proactive_message_wish" to "记下了主动消息",
        "show_card" to "展示了卡片",
        "current_status" to "查询了状态",
        "calculator" to "计算",
        "translate" to "翻译",
        "speak_text" to "朗读了文本",
        "get_weather" to "查询了天气",
        "todo_write" to "更新了待办",
        "ping_host" to "Ping 了主机",
        "dns_lookup" to "DNS 查询",
        "get_public_ip" to "查询了公网 IP",
        "download" to "下载了文件",
        "json_pretty" to "格式化了 JSON",
        "hash_text" to "计算了哈希",
        "generate_password" to "生成了密码",
        "record_experience" to "记录了经历",
        "recall_experience" to "回想了经历",
        "take_photo" to "拍了照片",
        "cover_generation" to "生成了封面",
    )

    private fun prefixVerb(toolName: String): String? = when {
        toolName.startsWith("browser_") -> "操作了浏览器"
        toolName.startsWith("workspace_") -> "操作了工作区"
        toolName.startsWith("scheduled_task_") -> "管理了定时任务"
        toolName.startsWith("quick_note_") -> "管理了便签"
        toolName.startsWith("resource_") -> "管理了资源"
        toolName.startsWith("subagent_") -> "管理了子任务"
        toolName.startsWith("channel_") -> "管理了频道"
        toolName.startsWith("clipboard_") -> "操作了剪贴板"
        toolName.startsWith("mcp_") -> "调用了 MCP 工具"
        else -> null
    }

    private fun defaultVerb(isSuccess: Boolean) = if (isSuccess) "调用了工具" else "工具调用失败"

    // ── 目标对象(从参数或结果里抠出关键词) ───────────────────────────────

    private fun targetFor(toolName: String, arguments: String, result: String): String {
        val args = parseArgs(arguments)
        val raw: String? = when (toolName) {
            "web_search" -> argString(args, "query")
            "search_memory" -> argString(args, "query")
            "read_file", "write_file", "workspace_write", "workspace_read" ->
                argString(args, "path") ?: argString(args, "file_path")
            "list_files" -> argString(args, "path") ?: argString(args, "directory")
            "open_url" -> argString(args, "url")
            "generate_image" -> argString(args, "prompt")
            "generate_video" -> argString(args, "prompt")
            "execute_shell", "run_command" -> argString(args, "command")
            "execute_code" -> argString(args, "language")
            "translate" -> argString(args, "text")
            "get_weather" -> argString(args, "city") ?: argString(args, "location")
            "open_app" -> argString(args, "package_name") ?: argString(args, "app")
            "make_phone_call" -> argString(args, "phone_number") ?: argString(args, "number")
            "send_sms" -> argString(args, "phone_number") ?: argString(args, "to")
            "send_email" -> argString(args, "to")
            "add_calendar_event", "scheduled_task_create" -> argString(args, "title")
            "schedule_reminder" -> argString(args, "content") ?: argString(args, "text")
            "set_alarm" -> argString(args, "label")
            "share_text" -> argString(args, "text")
            "notify" -> argString(args, "title")
            "quick_note_add", "quick_note_update" -> argString(args, "content") ?: argString(args, "title")
            "delegate_agent", "subagent_task" -> argString(args, "task") ?: argString(args, "prompt")
            "calculator" -> argString(args, "expression")
            "speak_text" -> argString(args, "text")
            "ping_host" -> argString(args, "host")
            "dns_lookup" -> argString(args, "domain")
            "download" -> argString(args, "url")
            "take_photo" -> ""
            "pin_memory", "unpin_memory" -> argString(args, "query") ?: result.take(30)
            else -> genericTarget(args)
        }
        return raw?.let { clean(it) }.orEmpty()
    }

    private fun genericTarget(args: JSONObject?): String? {
        if (args == null) return null
        val keys = listOf(
            "query", "path", "url", "title", "name", "text", "content",
            "prompt", "command", "keyword", "id",
        )
        for (k in keys) {
            if (args.has(k)) {
                val v = args.opt(k)
                if (v is String && v.isNotBlank()) return v
            }
        }
        return null
    }

    private fun argString(args: JSONObject?, key: String): String? {
        if (args == null || !args.has(key)) return null
        return when (val v = args.opt(key)) {
            is String -> v
            is Number, is Boolean -> v.toString()
            is JSONArray -> {
                val sb = StringBuilder("[")
                val n = minOf(v.length(), 3)
                for (i in 0 until n) {
                    if (i > 0) sb.append(", ")
                    sb.append(v.opt(i))
                }
                if (v.length() > 3) sb.append("…")
                sb.append("]").toString()
            }
            is JSONObject -> v.toString()
            null, JSONObject.NULL -> null
            else -> v.toString()
        }
    }

    private fun parseArgs(arguments: String): JSONObject? {
        val t = arguments.trim()
        if (t.isEmpty() || !t.startsWith("{")) return null
        return runCatching { JSONObject(t) }.getOrNull()
    }

    private fun clean(s: String): String {
        val one = s.replace('\n', ' ').trim()
        return if (one.length > 40) one.take(40) + "…" else one
    }

    // ── 中文标签 ─────────────────────────────────────────────────────────

    private val labels: Map<String, String> = mapOf(
        "web_search" to "网页搜索",
        "search_memory" to "记忆检索",
        "read_file" to "读取文件",
        "write_file" to "写入文件",
        "list_files" to "列出文件",
        "execute_code" to "代码执行",
        "execute_javascript" to "脚本执行",
        "execute_shell" to "Shell 命令",
        "open_url" to "打开网页",
        "generate_image" to "图像生成",
        "generate_video" to "视频生成",
        "get_current_time" to "当前时间",
        "calendar_today" to "查看日历",
        "schedule_reminder" to "提醒",
        "set_alarm" to "闹钟",
        "set_timer" to "计时器",
        "get_device_info" to "设备信息",
        "list_installed_apps" to "应用列表",
        "open_app" to "打开应用",
        "make_phone_call" to "拨打电话",
        "send_sms" to "发送短信",
        "get_location" to "获取位置",
        "clipboard_read" to "读取剪贴板",
        "clipboard_write" to "写入剪贴板",
        "quick_note_add" to "新建便签",
        "quick_note_list" to "便签列表",
        "delegate_agent" to "委派子助手",
        "subagent_task" to "子任务",
        "notify" to "发送通知",
        "calculator" to "计算器",
        "translate" to "翻译",
        "speak_text" to "语音朗读",
        "get_weather" to "天气查询",
        "ping_host" to "Ping",
        "dns_lookup" to "DNS 查询",
        "mcp_tool" to "MCP 工具",
        "show_card" to "展示卡片",
    )

    private fun prettify(name: String): String =
        name.split('_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
}

