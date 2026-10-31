package io.zer0.muse.data.miniphone

import io.zer0.ai.core.ToolDefinition
import io.zer0.muse.miniphone.api.MpActivityKind
import io.zer0.muse.tools.ToolCategories
import io.zer0.muse.tools.ToolCategory
import io.zer0.muse.tools.ToolPermissionResolver
import io.zer0.muse.tools.ToolRiskLevel

/**
 * 小手机里角色能用的工具面。
 *
 * 小手机私信是角色扮演场景,不是助手工作台:只有与「角色的生活」直接相关的只读/轻量工具允许出现,
 * 代码执行、终端、文件读写、浏览器、子智能体、UI 自动化、插件与 MCP 动态工具一律不给模型。
 *
 * 三道闸,按顺序:
 *  1. 名字白名单 [ALLOWED](记忆 / 日历提醒 / 天气与信息查询 / 笔记 / 资源库五族);
 *  2. 分类闸:必须在 [ALLOWED_CATEGORIES] 里 —— GLOBAL(执行类、浏览器、UI 自动化)、
 *     LEGACY,以及未登记分类的动态工具(插件 `plugin_*` / MCP `mcp_*__*`)在这里被挡掉;
 *  3. 风险闸 [ToolPermissionResolver.riskLevelFor] 不是 HIGH —— 与后台任务同一口径,
 *     注册台账里写低风险但名字语义高危的工具(如 `save_memory`)照旧拦下。
 */
internal object MiniPhoneToolPolicy {

    /** 允许的分类:核心、常开、可选。GLOBAL 与 LEGACY 不在其中;未登记(插件/MCP)返回 null 也挡掉。 */
    private val ALLOWED_CATEGORIES: Set<ToolCategory> = setOf(
        ToolCategory.CORE,
        ToolCategory.STANDARD,
        ToolCategory.OPTIONAL,
    )

    /** 记忆类。 */
    private val MEMORY_TOOLS: Set<String> = setOf(
        "save_memory",
        "search_memory",
        "delete_memory",
        "pin_memory",
        "unpin_memory",
        "recall_experience",
        "record_experience",
        "search_conversation",
        "remember_preference",
        "memory_config_get",
        "memory_config_set",
    )

    /** 日历 / 提醒类。 */
    private val CALENDAR_TOOLS: Set<String> = setOf(
        "get_calendar_today",
        "calendar_today",
        "add_calendar_event",
        "set_alarm",
        "set_timer",
        "schedule_reminder",
        "cancel_reminder",
        "list_reminders",
    )

    /** 天气。 */
    private val WEATHER_TOOLS: Set<String> = setOf("get_weather")

    /** 其余信息查询:外部检索与当前状态自查。 */
    private val QUERY_TOOLS: Set<String> = setOf(
        "web_search",
        "web_fetch",
        "get_current_time",
        "current_status",
        "translate",
    )

    /** 笔记类。 */
    private val NOTES_TOOLS: Set<String> = setOf(
        "quick_note_add",
        "quick_note_list",
        "quick_note_search",
        "quick_note_get",
        "quick_note_update",
        "quick_note_delete",
        "quick_note_pin",
    )

    /** 资源库类。 */
    private val RESOURCE_TOOLS: Set<String> = setOf(
        "resource_add",
        "resource_list",
        "resource_search",
        "resource_get",
        "resource_delete",
    )

    private val ALLOWED: Set<String> =
        MEMORY_TOOLS + CALENDAR_TOOLS + WEATHER_TOOLS + QUERY_TOOLS + NOTES_TOOLS + RESOURCE_TOOLS

    /**
     * 明确禁止的族与个别名字。
     *
     * 这些大部分本来就不在 [ALLOWED] 里,列出来是为了让边界写在代码里看得见:
     * 以后往白名单里加名字时,不会顺手把其中某个带回来。
     */
    private val DENIED_PREFIXES: List<String> = listOf(
        "execute_",
        "terminal_",
        "termux_",
        "device_shell",
        "workspace_",
        "browser_",
        "ui_",
        "screen_",
        "subagent_",
        "delegate_agent",
        "plugin_",
        "mcp_",
    )

    /** 单拎出来的禁止项:名字不像上面任何一族,但同属「接触文件 / 跨设备」边界。 */
    private val DENIED: Set<String> = setOf(
        "read_file",
        "read_user_file",
        "virtual_screen",
        "virtual_screen_input",
        "automation_workflow",
    )

    /** 只保留能发给模型的工具定义。 */
    fun filter(definitions: List<ToolDefinition>): List<ToolDefinition> = definitions.filter { isAllowed(it.name) }

    /** 三道闸都过才算允许。 */
    fun isAllowed(name: String): Boolean {
        if (isDenied(name) || name !in ALLOWED) return false
        val category = ToolCategories.categoryOf(name) ?: return false
        if (category !in ALLOWED_CATEGORIES) return false
        return ToolPermissionResolver.riskLevelFor(name) != ToolRiskLevel.HIGH
    }

    /** 工具名 → 界面那一行文案的种类。名字只在这里被读,不会离开宿主。 */
    fun activityKindOf(name: String): MpActivityKind = when (name) {
        in MEMORY_TOOLS -> MpActivityKind.REMEMBERING
        in CALENDAR_TOOLS -> MpActivityKind.CALENDAR
        in WEATHER_TOOLS -> MpActivityKind.WEATHER
        in QUERY_TOOLS -> MpActivityKind.SEARCHING
        in NOTES_TOOLS -> MpActivityKind.NOTES
        else -> MpActivityKind.OTHER
    }

    private fun isDenied(name: String): Boolean = name in DENIED || DENIED_PREFIXES.any { name.startsWith(it) }
}
