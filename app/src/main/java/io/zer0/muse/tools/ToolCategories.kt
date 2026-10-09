package io.zer0.muse.tools

/**
 * v1.0.53: 内置工具分类注册表(既有实现 shared/tool-categories.ts)。
 *
 * CORE      — 移除会破坏模型能力,用户不可关闭,不显示在工具列表
 * STANDARD  — 常开内置工具,不显示开关(行为稳定)
 * OPTIONAL  — 用户可在 设置→工具 开关
 * GLOBAL    — 由全局权限设置页管理(网络/文件/执行类)
 * LEGACY    — 已废弃传输,仅保留兼容,不注册到工具面
 *
 * 启动断言:每个注册的内置工具必须且只能归属一个分类,
 * 否则 App 启动日志报错(debug 构建拒绝启动),强制新工具登记分类。
 */
enum class ToolCategory { CORE, STANDARD, OPTIONAL, GLOBAL, LEGACY }

object ToolCategories {
    /** 模型能力底座:移除会破坏对话/工具调用。 */
    val CORE: Set<String> =
        setOf(
            "get_current_time",
            "calculator",
            "get_device_info",
            // v2.x 阶段3:工具库按需检索 — 分层收窄的补全通道,必须恒发
            "find_tools",
        )

    /** 常开稳定工具。 */
    val STANDARD: Set<String> =
        setOf(
            "get_weather",
            "web_search",
            "web_fetch",
            "get_battery_info",
            "get_network_info",
            "get_storage_info",
            "get_memory_info",
            "get_display_info",
            "get_cpu_info",
            "get_sensors_list",
            "get_foreground_app",
            "clipboard_read",
            // v2.x: 通道状态自查 — 只读安全,提进 STANDARD 恒可见(否则"需要它时恰恰被 GLOBAL 收窄挡住")
            "screen_permission_status",
            // v2.x 扩展运行时(P0):内置 Node 沙盒自检 — 只读状态自查,恒可见
            "runtime_selfcheck",
        )

    /** 用户可在设置→工具 开关。 */
    val OPTIONAL: Set<String> =
        setOf(
            "open_url",
            "send_email",
            "send_sms",
            "add_contact",
            "set_alarm",
            "set_timer",
            "add_calendar_event",
            "toggle_wifi",
            "toggle_bluetooth",
            "toggle_flashlight",
            "set_brightness",
            "get_brightness",
            "set_volume",
            "get_volume",
            "vibrate",
            "open_app",
            "open_system_setting",
            "clipboard_write",
            "screen_time",
            "get_location",
            "get_contacts_list",
            "get_contacts_count",
            "get_recent_notifications",
            "list_installed_apps",
            "share_text",
            "echo",
            "get_calendar_today",
            "calendar_today",
            "get_wifi_info",
            "get_bluetooth_devices",
            "make_phone_call",
            "open_maps",
            "url_encode",
            "url_decode",
            "base64_encode",
            "base64_decode",
            "hash_text",
            "generate_uuid",
            "random_number",
            "schedule_reminder",
            "cancel_reminder",
            "list_reminders",
            "resource_add",
            "resource_list",
            "resource_search",
            "resource_get",
            "resource_delete",
            "quick_note_add",
            "quick_note_list",
            "quick_note_search",
            "quick_note_get",
            "quick_note_update",
            "quick_note_delete",
            "quick_note_pin",
            "scheduled_task_create",
            "scheduled_task_list",
            "scheduled_task_update",
            "scheduled_task_delete",
            "scheduled_task_execute",
            "scheduled_task_get_history",
            "translate",
            "ping_host",
            "dns_lookup",
            "get_public_ip",
            "json_pretty",
            "generate_password",
            "speak_text",
            // 文件 / 链接 / 文档(此前注册了但未登记分类)
            "read_file",
            "create_download",
            "parse_link",
            "parse_pdf",
            // 媒体生成
            "generate_image",
            "generate_video",
            "generate_qr_code",
            // 记忆 / 经验
            "pin_memory",
            "unpin_memory",
            "recall_experience",
            "record_experience",
            "search_memory",
            "search_conversation",
            "save_memory",
            "delete_memory",
            // 任务卡 / 通知 / 状态
            "todo_write",
            "show_card",
            "render_data",
            "update_card_data",
            "notify",
            "current_status",
            // 子 agent / 团队 / 群聊
            "subagent_task",
            "subagent_run",
            "subagent_close",
            "delegate_agent",
            "proactive_message_wish",
            "channel_pass",
            "channel_read_context",
            "channel_reply",
            "list_stickers",
            "send_sticker",
        )

    /** 全局权限页管理(高风险执行/浏览器/工作区/UI 自动化)。 */
    val GLOBAL: Set<String> =
        setOf(
            "execute_javascript",
            "execute_node_script",
            "execute_shell",
            "workspace_write",
            "workspace_list",
            "workspace_read",
            "workspace_delete",
            "workspace_mkdir",
            "workspace_move",
            "browser_navigate",
            "browser_click",
            "browser_type",
            "browser_extract",
            "browser_scroll_bottom",
            "browser_get_html",
            "browser_snapshot",
            "browser_screenshot",
            // 消息渠道(v1.0.92)
            "send_channel_message",
            "channel_list",
            // OAuth 连接器(v2.0)
            "connector_list",
            "call_connector",
            // 插件市场(v2.0.1)——检索与安装外部插件均属扩展能力
            "plugin_market_search",
            "plugin_market_install",
            // v2.x: 卸载/启停插件
            "plugin_market_uninstall",
            "plugin_market_set_enabled",
            // v2.x 自动化一期:设备命令行通道(Shizuku/Root 分层执行)
            "device_shell",
            // v2.x 终端一期:应用沙盒终端命令
            "terminal_exec",
            // v2.2.1: Termux 通道(完整 Linux 环境命令,高风险)
            "termux_exec",
            // v2.2.1: GUI Agent 环(视觉驱动的多步屏幕操作,高风险)
            "ui_agent",
            // 虚拟屏(Shizuku/Root 通道;此前注册了但未登记分类)
            "virtual_screen", "virtual_screen_input",
            "automation_workflow",
            // UI 自动化(需无障碍权限,高风险)
            "ui_get_page_info",
            "ui_click",
            "ui_long_press",
            "ui_swipe",
            "ui_set_text",
            "ui_screenshot",
            "ui_back",
            "ui_home",
            "ui_global_action",
            "ui_get_current_app",
            // 屏幕自动化(需无障碍权限,高风险)
            "screen_read",
            "screen_current_app",
            "screen_back",
            "screen_home",
            "screen_tap",
            "screen_tap_text",
            "screen_swipe",
            "screen_input",
            "screen_launch_app",
            "screen_open_notifications",
            "screen_wait",
            // 屏幕自动化补充(此前注册了但未登记分类)
            "screen_pinch",
            "screen_swipe_path",
            // Root-level system tools (requires root)
            "settings_get",
            "settings_put",
            "am_start",
            "list_packages",
            "logcat_tail",
            "input_inject",
            // root 网络开关(此前注册了但未登记分类)
            "network_toggle",
        )

    /** 已废弃传输,仅兼容。 */
    val LEGACY: Set<String> = emptySet()

    /**
     * v2.x: 工具运行环境权限需求表(需 Shizuku/Root/无障碍/Termux 才能工作)。
     *
     * 值语义:集合内"任一就绪"即可用(如 screen_* 可走无障碍,也可降级 Shizuku/Root)。
     * 未出现的工具 = 无需额外授权。用途:find_tools 标注、系统提示标记、执行前预检。
     */
    val PERMISSIONS: Map<String, Set<ToolPermission>> =
        buildMap {
            // 设备命令通道(Shizuku 或 Root 任一)
            listOf(
                "device_shell", "settings_get", "settings_put", "am_start", "list_packages",
                "logcat_tail", "input_inject", "network_toggle", "virtual_screen", "virtual_screen_input",
            ).forEach { put(it, setOf(ToolPermission.SHELL_TIER)) }
            put("automation_workflow", setOf(ToolPermission.ACCESSIBILITY, ToolPermission.SHELL_TIER))
            // 屏幕自动化(无障碍为主,可降级设备通道;screen_pinch 仅支持无障碍)
            listOf(
                "screen_read", "screen_current_app", "screen_back", "screen_home", "screen_tap",
                "screen_tap_text", "screen_swipe", "screen_input", "screen_launch_app",
                "screen_open_notifications", "screen_wait", "screen_swipe_path",
            ).forEach { put(it, setOf(ToolPermission.ACCESSIBILITY, ToolPermission.SHELL_TIER)) }
            put("screen_pinch", setOf(ToolPermission.ACCESSIBILITY))
            // UI 工具(无障碍)
            listOf(
                "ui_get_page_info", "ui_click", "ui_long_press", "ui_swipe", "ui_set_text",
                "ui_screenshot", "ui_back", "ui_home", "ui_global_action", "ui_get_current_app",
            ).forEach { put(it, setOf(ToolPermission.ACCESSIBILITY)) }
            // GUI Agent 环:任一通道可截屏
            put("ui_agent", setOf(ToolPermission.SHELL_TIER, ToolPermission.ACCESSIBILITY))
            // Termux 通道
            put("termux_exec", setOf(ToolPermission.TERMUX))
        }

    /** 查询工具的权限需求(未登记 = 无需额外授权)。 */
    fun permissionOf(toolName: String): Set<ToolPermission> = PERMISSIONS[toolName].orEmpty()

    private val ALL: Map<String, ToolCategory> =
        buildMap {
            CORE.forEach { put(it, ToolCategory.CORE) }
            STANDARD.forEach { put(it, ToolCategory.STANDARD) }
            OPTIONAL.forEach { put(it, ToolCategory.OPTIONAL) }
            GLOBAL.forEach { put(it, ToolCategory.GLOBAL) }
            LEGACY.forEach { put(it, ToolCategory.LEGACY) }
        }

    /** 查询分类(未登记返回 null)。 */
    fun categoryOf(toolName: String): ToolCategory? = ALL[toolName]

    /**
     * 启动断言:检查所有已注册内置工具都有分类。
     *
     * @param registeredNames 已注册工具名集合
     * @return 未登记分类的工具名列表(空 = 全部覆盖)
     */
    fun assertCoverage(registeredNames: Set<String>): List<String> = registeredNames.filter { it !in ALL }.sorted()
}
