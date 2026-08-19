package io.zer0.muse.tools

import io.zer0.ai.core.ToolDefinition

/**
 * 主对话工具暴露策略。
 *
 * 工具注册表可以包含大量本地工具、插件工具和 MCP 工具,但每轮把全部 schema
 * 发给模型会增加输入 token、工具选择歧义和首 token 延迟。这里按用户最新消息
 * 做轻量意图筛选;实际工具授权仍由 ToolPermissionResolver 决定。
 */
object ToolExposurePolicy {

    private val COMMON_TOOLS = setOf(
        "calculator",
        "echo",
        "get_current_time",
    )

    private val FAMILY_KEYWORDS = mapOf(
        "web" to setOf("联网", "网页", "网站", "搜索", "查资料", "最新", "新闻", "资讯", "天气", "web", "search", "latest", "weather"),
        "file" to setOf("文件", "文档", "目录", "路径", "读取", "写入", "保存", "导出", "workspace", "file", "document"),
        "memory" to setOf("记忆", "记住", "回忆", "知识库", "资料库", "快速记录", "笔记", "待办", "memory", "note", "knowledge"),
        "schedule" to setOf("提醒", "闹钟", "倒计时", "定时", "日程", "每天", "每周", "schedule", "alarm", "timer"),
        "agent" to setOf("委托", "助手", "子任务", "团队", "协作", "agent", "delegate", "subagent", "team"),
        "phone" to setOf("短信", "电话", "联系人", "通讯录", "剪贴板", "手机", "电量", "位置", "打开应用", "send sms", "contact"),
        "media" to setOf("画", "画图", "图片", "头像", "海报", "视频", "二维码", "生成图", "image", "video", "qr"),
        "translate" to setOf("翻译", "translate", "translation", "日文", "英文", "韩文"),
        "mcp" to setOf("mcp", "飞书", "feishu", "github", "notion", "cli", "连接器"),
    )

    private val FAMILY_TOOL_NAMES = mapOf(
        "web" to setOf("web_search", "web_fetch", "arxiv_search", "http_get", "http_post"),
        "file" to setOf("read_file", "write_file", "list_dir", "delete_file", "file_exists", "workspace_list", "workspace_read", "workspace_write"),
        "memory" to setOf(
            "knowledge_search",
            "search_memory",
            "pin_memory",
            "unpin_memory",
            "quick_note_add",
            "quick_note_list",
            "quick_note_get",
            "quick_note_update",
            "quick_note_delete",
            "quick_note_pin",
            "todo_write",
        ),
        "schedule" to setOf(
            "set_alarm",
            "set_timer",
            "calendar_today",
            "scheduled_task_create",
            "scheduled_task_list",
            "scheduled_task_update",
            "scheduled_task_delete",
            "scheduled_task_execute",
            "scheduled_task_get_history",
            "schedule_reminder",
            "cancel_reminder",
            "list_reminders",
        ),
        "agent" to setOf(
            "delegate_agent",
            "subagent_task",
            "subagent_run",
            "subagent_close",
            "task_plan",
            "update_plan_step",
        ),
        "phone" to setOf(
            "send_sms",
            "make_phone_call",
            "send_email",
            "share_text",
            "clipboard_read",
            "clipboard_write",
            "open_app",
            "get_device_info",
            "get_location",
            "get_contacts_count",
            "get_contacts_list",
        ),
        "media" to setOf("generate_image", "generate_video", "generate_qr_code"),
        "translate" to setOf("translate"),
    )

    /**
     * 按用户意图筛选本轮工具定义。
     *
     * @param tools 当前可用工具定义
     * @param userText 用户最新消息
     * @param explicitSelection 是否由助手/会话显式配置工具白名单
     */
    fun select(
        tools: List<ToolDefinition>,
        userText: String,
        explicitSelection: Boolean = false,
    ): List<ToolDefinition> {
        val distinct = tools.distinctBy { it.name }
        if (explicitSelection || distinct.size <= SMALL_TOOLSET_LIMIT) return distinct

        val normalized = userText.trim().lowercase()
        if (normalized.isBlank() || normalized.containsAny(ALL_TOOLS_KEYWORDS)) {
            return distinct
        }

        val matchedFamilies = FAMILY_KEYWORDS
            .filterValues { keywords -> normalized.containsAny(keywords) }
            .keys
        val matchedNames = matchedFamilies
            .flatMap { FAMILY_TOOL_NAMES[it].orEmpty() }
            .toMutableSet()
        matchedNames += COMMON_TOOLS

        // MCP 工具名由 McpRegistry 统一使用 mcp_{serverId}__{toolName} 前缀。
        if ("mcp" in matchedFamilies) {
            distinct.filter { it.name.startsWith("mcp_") }.forEach { matchedNames += it.name }
        }

        val selected = distinct.filter { it.name in matchedNames }
        return if (selected.size >= MIN_SELECTED_TOOLS) {
            selected
        } else {
            // 未识别到明确意图时只保留本地无副作用工具,让模型仍能完成计算/时间查询,
            // 同时避免把 100+ 个工具 schema 全量发给模型。
            distinct.filter { it.name in COMMON_TOOLS }
        }
    }

    /**
     * 判断当前请求是否适合关闭工具轮的重复推理。
     *
     * 简单、明确的工具请求不需要先写长篇思考或规划。
     */
    fun isSimpleToolRequest(userText: String): Boolean {
        val text = userText.trim()
        if (text.isBlank() || text.length > SIMPLE_REQUEST_MAX_CHARS) return false
        return !text.containsAny(COMPLEXITY_KEYWORDS)
    }

    /**
     * 判断用户是否明确要求执行动作。
     *
     * 只有出现工具/动作意图时才把 OpenAI tool_choice 设为 required。
     * 普通闲聊仍保持模型自由选择,避免为了“有工具”而调用无关工具。
     */
    fun shouldRequireTool(userText: String, tools: List<ToolDefinition>): Boolean {
        if (tools.isEmpty()) return false
        val normalized = userText.trim().lowercase()
        if (normalized.isBlank()) return false
        return normalized.containsAny(EXPLICIT_TOOL_KEYWORDS)
    }

    private fun String.containsAny(values: Set<String>): Boolean =
        values.any { contains(it) }

    private const val SMALL_TOOLSET_LIMIT = 16
    private const val MIN_SELECTED_TOOLS = 2
    private const val SIMPLE_REQUEST_MAX_CHARS = 120

    private val ALL_TOOLS_KEYWORDS = setOf("你能做什么", "有哪些工具", "工具列表", "全部工具")
    private val COMPLEXITY_KEYWORDS = setOf(
        "为什么",
        "分析",
        "比较",
        "方案",
        "设计",
        "解释",
        "详细",
        "规划",
        "how",
        "why",
        "analyze",
        "compare",
        "design",
        "explain",
    )
    private val EXPLICIT_TOOL_KEYWORDS = setOf(
        "调用",
        "使用工具",
        "执行",
        "搜索",
        "查资料",
        "查一下",
        "联网",
        "最新",
        "新闻",
        "天气",
        "记住",
        "保存到记忆",
        "设闹钟",
        "设置提醒",
        "倒计时",
        "发短信",
        "发邮件",
        "打开应用",
        "翻译",
        "画图",
        "生成图片",
        "回显",
        "echo",
        "calculator",
        "calculate",
        "current time",
        "what time",
    )
}
