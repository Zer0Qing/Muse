package io.zer0.muse.ui.chat

import android.content.res.Resources
import androidx.compose.ui.graphics.vector.ImageVector
import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons
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
    fun iconFor(toolName: String): ImageVector = mapping[toolName] ?: prefixIcon(toolName) ?: MuseIcons.wrench

    /** 折叠态一句话摘要。I18N-01: 传入 res 走资源(zh+en 已入 strings_tools,其余语言分批)。 */
    fun summaryFor(toolName: String, arguments: String, result: String, isSuccess: Boolean, res: Resources? = null): String {
        // v2.0.1: 失败时用「操作名 + 失败」语义 — 不再复用成功动词
        // （用户反馈：审批超时的工具卡仍写着“写入了工作区 xxx”）。
        if (!isSuccess) {
            val failLabel = res?.let { r ->
                labelIds[toolName]?.let { r.getString(it) }
            } ?: labels[toolName] ?: prettify(toolName)
            // res==null 的兜底(非 Android 环境):仅返回标签,不带失败后缀。
            return res?.getString(R.string.tool_summary_failed, failLabel) ?: failLabel
        }
        val verb = res?.let { r ->
            successVerbIds[toolName]?.let { id -> r.getString(id) }
                ?: prefixVerbId(toolName)?.let { id -> r.getString(id) }
                ?: r.getString(R.string.tool_summary_default_success)
        } ?: (successVerb[toolName] ?: prefixVerb(toolName) ?: defaultVerb(true))
        val obj = targetFor(toolName, arguments, result)
        return if (obj.isBlank()) verb else "$verb $obj"
    }

    /** 展开态工具标签(原来直接显示英文 toolName)。I18N-01: 同 [summaryFor]。 */
    fun labelFor(toolName: String, res: Resources? = null): String {
        val localized = res?.let { r -> labelIds[toolName]?.let { id -> r.getString(id) } }
        return localized ?: labels[toolName] ?: prettify(toolName)
    }

    /**
     * 终态摘要：超时与中断在折叠态直接可见。
     *
     * 返回 null 表示结果不是编排器合成的终态，调用方应回退到 [summaryFor]。
     */
    fun terminalSummaryFor(result: String, res: Resources? = null): String? {
        val text = result.trimStart()
        return when {
            // I18N: 兜底只在 res==null(预览/纯函数)时触达,生产路径始终走资源
            text.startsWith("[超时]") -> res?.getString(R.string.tool_terminal_timeout) ?: ""
            text.startsWith("[中断]") -> res?.getString(R.string.tool_terminal_interrupted) ?: ""
            else -> null
        }
    }

    // ── 图标映射 ─────────────────────────────────────────────────────────

    private val mapping: Map<String, ImageVector> = mapOf(
        // 搜索 / 知识
        "web_search" to MuseIcons.globe,
        "search_memory" to MuseIcons.search,
        "pin_memory" to MuseIcons.star,
        "unpin_memory" to MuseIcons.star,
        "recall_experience" to MuseIcons.book,
        "record_experience" to MuseIcons.book,
        // 文件 / 工作区
        "read_file" to MuseIcons.file,
        "write_file" to MuseIcons.edit,
        "list_files" to MuseIcons.folder,
        "workspace_write" to MuseIcons.edit,
        "workspace_read" to MuseIcons.file,
        // 代码 / 终端
        "execute_code" to MuseIcons.code,
        "execute_javascript" to MuseIcons.braces,
        "execute_shell" to MuseIcons.terminal,
        "run_command" to MuseIcons.terminal,
        "json_pretty" to MuseIcons.braces,
        // 浏览器 / 网络
        "open_url" to MuseIcons.browser,
        "browser_navigate" to MuseIcons.browser,
        "browser_click" to MuseIcons.browser,
        "browser_type" to MuseIcons.browser,
        "browser_snapshot" to MuseIcons.browser,
        "ping_host" to MuseIcons.activity,
        "dns_lookup" to MuseIcons.activity,
        "get_public_ip" to MuseIcons.globe,
        "download" to MuseIcons.download,
        // 生成媒体
        "generate_image" to MuseIcons.image,
        "generate_video" to MuseIcons.video,
        "cover_generation" to MuseIcons.image,
        // 时间 / 日历 / 提醒
        "get_current_time" to MuseIcons.clock,
        "calendar_today" to MuseIcons.calendar,
        "add_calendar_event" to MuseIcons.calendar,
        "schedule_reminder" to MuseIcons.bell,
        "cancel_reminder" to MuseIcons.bell,
        "list_reminders" to MuseIcons.bell,
        "set_alarm" to MuseIcons.bell,
        "set_timer" to MuseIcons.clock,
        "scheduled_task_create" to MuseIcons.calendar,
        "scheduled_task_list" to MuseIcons.calendar,
        "scheduled_task_update" to MuseIcons.calendar,
        "scheduled_task_delete" to MuseIcons.calendar,
        "scheduled_task_execute" to MuseIcons.calendar,
        "scheduled_task_get_history" to MuseIcons.calendar,
        // 系统 / 设备
        "get_device_info" to MuseIcons.server,
        "get_battery_info" to MuseIcons.server,
        "get_storage_info" to MuseIcons.server,
        "get_memory_info" to MuseIcons.server,
        "get_cpu_info" to MuseIcons.server,
        "get_display_info" to MuseIcons.server,
        "get_network_info" to MuseIcons.server,
        "get_wifi_info" to MuseIcons.wifi,
        "toggle_wifi" to MuseIcons.wifi,
        "toggle_bluetooth" to MuseIcons.rss,
        "get_bluetooth_devices" to MuseIcons.rss,
        "list_installed_apps" to MuseIcons.packageIcon,
        "open_app" to MuseIcons.packageIcon,
        "open_system_setting" to MuseIcons.sliders,
        "get_foreground_app" to MuseIcons.packageIcon,
        "get_sensors_list" to MuseIcons.activity,
        "get_brightness" to MuseIcons.sun,
        "set_brightness" to MuseIcons.sun,
        "get_volume" to MuseIcons.volume,
        "set_volume" to MuseIcons.volume,
        "toggle_flashlight" to MuseIcons.bulb,
        "vibrate" to MuseIcons.phone,
        "screen_time" to MuseIcons.clock,
        // 电话 / 短信 / 联系人
        "make_phone_call" to MuseIcons.phone,
        "send_sms" to MuseIcons.chat,
        "get_contacts_count" to MuseIcons.users,
        "get_contacts_list" to MuseIcons.users,
        "add_contact" to MuseIcons.users,
        "get_location" to MuseIcons.mapPin,
        "open_maps" to MuseIcons.mapPin,
        "share_text" to MuseIcons.send,
        "send_email" to MuseIcons.mail,
        "get_recent_notifications" to MuseIcons.bell,
        // 剪贴板
        "clipboard_read" to MuseIcons.clipboard,
        "clipboard_write" to MuseIcons.clipboard,
        // 便签
        "quick_note_add" to MuseIcons.note,
        "quick_note_list" to MuseIcons.note,
        "quick_note_get" to MuseIcons.note,
        "quick_note_update" to MuseIcons.note,
        "quick_note_delete" to MuseIcons.note,
        "quick_note_pin" to MuseIcons.note,
        // 资源 / 知识库
        "resource_add" to MuseIcons.database,
        "resource_list" to MuseIcons.database,
        "resource_search" to MuseIcons.database,
        "resource_get" to MuseIcons.database,
        "resource_delete" to MuseIcons.database,
        // 多 Agent / 委派
        "delegate_agent" to MuseIcons.hierarchy,
        "subagent_task" to MuseIcons.hierarchy,
        "subagent_run" to MuseIcons.hierarchy,
        "subagent_close" to MuseIcons.hierarchy,
        "channel_pass" to MuseIcons.hierarchy,
        "channel_reply" to MuseIcons.hierarchy,
        "channel_read_context" to MuseIcons.hierarchy,
        // 技能 / 插件 / MCP
        "skill_import" to MuseIcons.puzzle,
        "skill_run" to MuseIcons.puzzle,
        "plugin_market_search" to MuseIcons.search,
        "plugin_market_install" to MuseIcons.puzzle,
        "mcp_tool" to MuseIcons.plug,
        // 通知 / 主动消息 / 卡片
        "notify" to MuseIcons.bell,
        "proactive_message_wish" to MuseIcons.wand,
        "show_card" to MuseIcons.wand,
        "current_status" to MuseIcons.info,
        // 杂项
        "calculator" to MuseIcons.calculator,
        "echo" to MuseIcons.chat,
        "translate" to MuseIcons.languages,
        "speak_text" to MuseIcons.microphone,
        "get_weather" to MuseIcons.cloud,
        "todo_write" to MuseIcons.check,
        "url_encode" to MuseIcons.code,
        "url_decode" to MuseIcons.code,
        "base64_encode" to MuseIcons.code,
        "base64_decode" to MuseIcons.code,
        "hash_text" to MuseIcons.shield,
        "generate_uuid" to MuseIcons.code,
        "generate_password" to MuseIcons.shield,
        "random_number" to MuseIcons.calculator,
        "wallet_balance" to MuseIcons.wallet,
        "take_photo" to MuseIcons.camera,
    )

    private fun prefixIcon(toolName: String): ImageVector? = when {
        toolName.startsWith("browser_") -> MuseIcons.browser
        toolName.startsWith("workspace_") -> MuseIcons.file
        toolName.startsWith("scheduled_task_") -> MuseIcons.calendar
        toolName.startsWith("quick_note_") -> MuseIcons.note
        toolName.startsWith("resource_") -> MuseIcons.database
        toolName.startsWith("subagent_") -> MuseIcons.hierarchy
        toolName.startsWith("channel_") -> MuseIcons.hierarchy
        toolName.startsWith("clipboard_") -> MuseIcons.clipboard
        toolName.startsWith("mcp_") -> MuseIcons.plug
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

    // I18N-01: 动词资源 ID(生产路径优先；successVerb 为 res==null 兜底的存量文案,新增工具只登记资源;es/ko/ja/pt/ru 翻译分批补齐)。
    private val successVerbIds: Map<String, Int> = mapOf(
        "web_search" to R.string.tool_summary_web_search,
        "search_memory" to R.string.tool_summary_search_memory,
        "pin_memory" to R.string.tool_summary_pin_memory,
        "unpin_memory" to R.string.tool_summary_unpin_memory,
        "read_file" to R.string.tool_summary_read_file,
        "write_file" to R.string.tool_summary_write_file,
        "list_files" to R.string.tool_summary_list_files,
        "workspace_write" to R.string.tool_summary_workspace_write,
        "execute_code" to R.string.tool_summary_execute_code,
        "execute_javascript" to R.string.tool_summary_execute_javascript,
        "execute_shell" to R.string.tool_summary_execute_shell,
        "run_command" to R.string.tool_summary_run_command,
        "open_url" to R.string.tool_summary_open_url,
        "generate_image" to R.string.tool_summary_generate_image,
        "generate_video" to R.string.tool_summary_generate_video,
        "get_current_time" to R.string.tool_summary_get_current_time,
        "calendar_today" to R.string.tool_summary_calendar_today,
        "add_calendar_event" to R.string.tool_summary_add_calendar_event,
        "schedule_reminder" to R.string.tool_summary_schedule_reminder,
        "cancel_reminder" to R.string.tool_summary_cancel_reminder,
        "list_reminders" to R.string.tool_summary_list_reminders,
        "set_alarm" to R.string.tool_summary_set_alarm,
        "set_timer" to R.string.tool_summary_set_timer,
        "get_device_info" to R.string.tool_summary_get_device_info,
        "get_battery_info" to R.string.tool_summary_get_battery_info,
        "get_storage_info" to R.string.tool_summary_get_storage_info,
        "get_memory_info" to R.string.tool_summary_get_memory_info,
        "get_cpu_info" to R.string.tool_summary_get_cpu_info,
        "get_network_info" to R.string.tool_summary_get_network_info,
        "list_installed_apps" to R.string.tool_summary_list_installed_apps,
        "open_app" to R.string.tool_summary_open_app,
        "make_phone_call" to R.string.tool_summary_make_phone_call,
        "send_sms" to R.string.tool_summary_send_sms,
        "get_contacts_list" to R.string.tool_summary_get_contacts_list,
        "add_contact" to R.string.tool_summary_add_contact,
        "get_location" to R.string.tool_summary_get_location,
        "open_maps" to R.string.tool_summary_open_maps,
        "share_text" to R.string.tool_summary_share_text,
        "send_email" to R.string.tool_summary_send_email,
        "get_recent_notifications" to R.string.tool_summary_get_recent_notifications,
        "clipboard_read" to R.string.tool_summary_clipboard_read,
        "clipboard_write" to R.string.tool_summary_clipboard_write,
        "quick_note_add" to R.string.tool_summary_quick_note_add,
        "quick_note_list" to R.string.tool_summary_quick_note_list,
        "quick_note_get" to R.string.tool_summary_quick_note_get,
        "quick_note_update" to R.string.tool_summary_quick_note_update,
        "quick_note_delete" to R.string.tool_summary_quick_note_delete,
        "quick_note_pin" to R.string.tool_summary_quick_note_pin,
        "resource_add" to R.string.tool_summary_resource_add,
        "resource_list" to R.string.tool_summary_resource_list,
        "resource_search" to R.string.tool_summary_resource_search,
        "resource_get" to R.string.tool_summary_resource_get,
        "resource_delete" to R.string.tool_summary_resource_delete,
        "delegate_agent" to R.string.tool_summary_delegate_agent,
        "subagent_task" to R.string.tool_summary_subagent_task,
        "subagent_run" to R.string.tool_summary_subagent_run,
        "subagent_close" to R.string.tool_summary_subagent_close,
        "notify" to R.string.tool_summary_notify,
        "proactive_message_wish" to R.string.tool_summary_proactive_message_wish,
        "show_card" to R.string.tool_summary_show_card,
        "current_status" to R.string.tool_summary_current_status,
        "calculator" to R.string.tool_summary_calculator,
        "translate" to R.string.tool_summary_translate,
        "speak_text" to R.string.tool_summary_speak_text,
        "get_weather" to R.string.tool_summary_get_weather,
        "todo_write" to R.string.tool_summary_todo_write,
        "ping_host" to R.string.tool_summary_ping_host,
        "dns_lookup" to R.string.tool_summary_dns_lookup,
        "get_public_ip" to R.string.tool_summary_get_public_ip,
        "download" to R.string.tool_summary_download,
        "json_pretty" to R.string.tool_summary_json_pretty,
        "hash_text" to R.string.tool_summary_hash_text,
        "generate_password" to R.string.tool_summary_generate_password,
        "record_experience" to R.string.tool_summary_record_experience,
        "recall_experience" to R.string.tool_summary_recall_experience,
        "take_photo" to R.string.tool_summary_take_photo,
        "cover_generation" to R.string.tool_summary_cover_generation,
        "plugin_market_search" to R.string.tool_summary_plugin_market_search,
        "plugin_market_install" to R.string.tool_summary_plugin_market_install,
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

    // I18N-01: 前缀动词资源 ID(与 prefixVerb 一一对应)。
    private fun prefixVerbId(toolName: String): Int? = when {
        toolName.startsWith("browser_") -> R.string.tool_summary_prefix_browser
        toolName.startsWith("workspace_") -> R.string.tool_summary_prefix_workspace
        toolName.startsWith("scheduled_task_") -> R.string.tool_summary_prefix_scheduled_task
        toolName.startsWith("quick_note_") -> R.string.tool_summary_prefix_quick_note
        toolName.startsWith("resource_") -> R.string.tool_summary_prefix_resource
        toolName.startsWith("subagent_") -> R.string.tool_summary_prefix_subagent
        toolName.startsWith("channel_") -> R.string.tool_summary_prefix_channel
        toolName.startsWith("clipboard_") -> R.string.tool_summary_prefix_clipboard
        toolName.startsWith("mcp_") -> R.string.tool_summary_prefix_mcp
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
            "plugin_market_search" -> argString(args, "query")
            "plugin_market_install" -> argString(args, "plugin_id")
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

    // I18N-01: 标签资源 ID(生产路径优先;labels 为 res==null 兜底的存量文案,新增工具只登记资源)。
    private val labelIds: Map<String, Int> = mapOf(
        "web_search" to R.string.tool_label_web_search,
        "search_memory" to R.string.tool_label_search_memory,
        "read_file" to R.string.tool_label_read_file,
        "write_file" to R.string.tool_label_write_file,
        "workspace_write" to R.string.tool_label_workspace_write,
        "list_files" to R.string.tool_label_list_files,
        "execute_code" to R.string.tool_label_execute_code,
        "execute_javascript" to R.string.tool_label_execute_javascript,
        "execute_shell" to R.string.tool_label_execute_shell,
        "open_url" to R.string.tool_label_open_url,
        "generate_image" to R.string.tool_label_generate_image,
        "generate_video" to R.string.tool_label_generate_video,
        "get_current_time" to R.string.tool_label_get_current_time,
        "calendar_today" to R.string.tool_label_calendar_today,
        "schedule_reminder" to R.string.tool_label_schedule_reminder,
        "set_alarm" to R.string.tool_label_set_alarm,
        "set_timer" to R.string.tool_label_set_timer,
        "get_device_info" to R.string.tool_label_get_device_info,
        "list_installed_apps" to R.string.tool_label_list_installed_apps,
        "open_app" to R.string.tool_label_open_app,
        "make_phone_call" to R.string.tool_label_make_phone_call,
        "send_sms" to R.string.tool_label_send_sms,
        "get_location" to R.string.tool_label_get_location,
        "clipboard_read" to R.string.tool_label_clipboard_read,
        "clipboard_write" to R.string.tool_label_clipboard_write,
        "quick_note_add" to R.string.tool_label_quick_note_add,
        "quick_note_list" to R.string.tool_label_quick_note_list,
        "delegate_agent" to R.string.tool_label_delegate_agent,
        "subagent_task" to R.string.tool_label_subagent_task,
        "notify" to R.string.tool_label_notify,
        "calculator" to R.string.tool_label_calculator,
        "translate" to R.string.tool_label_translate,
        "speak_text" to R.string.tool_label_speak_text,
        "get_weather" to R.string.tool_label_get_weather,
        "ping_host" to R.string.tool_label_ping_host,
        "dns_lookup" to R.string.tool_label_dns_lookup,
        "mcp_tool" to R.string.tool_label_mcp_tool,
        "show_card" to R.string.tool_label_show_card,
        "plugin_market_search" to R.string.tool_label_plugin_market_search,
        "plugin_market_install" to R.string.tool_label_plugin_market_install,
    )

    private fun prettify(name: String): String =
        name.split('_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
}

