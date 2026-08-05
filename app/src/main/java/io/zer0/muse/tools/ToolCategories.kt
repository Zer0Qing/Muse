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
    val CORE: Set<String> = setOf(
        "get_current_time",
        "calculator",
        "get_device_info",
    )

    /** 常开稳定工具。 */
    val STANDARD: Set<String> = setOf(
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
    )

    /** 用户可在设置→工具 开关。 */
    val OPTIONAL: Set<String> = setOf(
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
    )

    /** 全局权限页管理(高风险执行/浏览器/工作区)。 */
    val GLOBAL: Set<String> = setOf(
        "execute_javascript",
        "workspace_write",
        "browser_navigate",
        "browser_click",
        "browser_type",
        "browser_extract",
        "browser_scroll_bottom",
        "browser_get_html",
    )

    /** 已废弃传输,仅兼容。 */
    val LEGACY: Set<String> = emptySet()

    private val ALL: Map<String, ToolCategory> = buildMap {
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
    fun assertCoverage(registeredNames: Set<String>): List<String> =
        registeredNames.filter { it !in ALL }.sorted()
}
