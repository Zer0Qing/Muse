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
        "mcp" to setOf(
            "mcp_server_list", "mcp_server_configure", "mcp_server_remove",
            "mcp_server_bind_assistant", "mcp_server_reconnect",
        ),
    )

    /**
     * 按用户意图筛选本轮工具定义。
     *
     * @param tools 当前可用工具定义
     * @param userText 用户最新消息
     * @param explicitSelection 是否由助手/会话显式配置工具白名单
     * @param alwaysExposeNames 助手明确绑定的外部工具,即使本轮文本没有命中能力关键词也保留
     */
    fun select(
        tools: List<ToolDefinition>,
        userText: String,
        explicitSelection: Boolean = false,
        alwaysExposeNames: Set<String> = emptySet(),
        /**
         * 生产默认 false：当前已授权工具全部进入本轮 schema，避免模型只能看到三个基础工具。
         * 需要压测意图裁剪时，测试/实验调用方显式传 true；执行权限仍由审批层控制。
         */
        applyIntentFilter: Boolean = false,
    ): List<ToolDefinition> {
        val distinct = tools.distinctBy { it.name }
        if (!applyIntentFilter || explicitSelection || distinct.size <= SMALL_TOOLSET_LIMIT) return distinct

        val normalized = userText.trim().lowercase()
        // 用户明确要求查看/测试全部工具时,必须把当前已授权工具全集发给模型。
        // 工具暴露不等于执行授权,高风险工具仍由 ToolPermissionResolver 审批。
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
        matchedNames += alwaysExposeNames

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
            distinct.filter { it.name in COMMON_TOOLS || it.name in alwaysExposeNames }
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
    /**
     * 用户明确要求只回答/列清单而不执行工具时，必须在请求层关闭工具。
     * 这条判断不能交给模型提示词，否则模型仍可能自行发出 tool call。
     */
    fun shouldDisableTools(userText: String): Boolean {
        val normalized = userText.trim().lowercase()
        if (normalized.isBlank()) return false
        return normalized.containsAny(NO_TOOL_KEYWORDS)
    }

    fun shouldRequireTool(userText: String, tools: List<ToolDefinition>): Boolean {
        if (tools.isEmpty() || shouldDisableTools(userText)) return false
        val normalized = userText.trim().lowercase()
        if (normalized.isBlank()) return false
        if (normalized.containsAny(EXPLICIT_TOOL_KEYWORDS)) return true
        val hasMcpTools = tools.any { it.name.startsWith("mcp_") }
        return hasMcpTools &&
            !normalized.containsAny(QUESTION_KEYWORDS) &&
            normalized.containsAny(MCP_ACTION_KEYWORDS)
    }

    private fun String.containsAny(values: Set<String>): Boolean =
        values.any { contains(it) }

    private const val SMALL_TOOLSET_LIMIT = 16
    private const val MIN_SELECTED_TOOLS = 2
    private const val SIMPLE_REQUEST_MAX_CHARS = 120

    private val ALL_TOOLS_KEYWORDS = setOf(
        "你能做什么", "有哪些工具", "工具列表", "全部工具", "所有工具", "可用工具",
        "测试所有工具", "测试所有的工具", "测试全部工具", "测试全部的工具", "测试当前全部工具", "测试工具", "把所有工具都测试一遍", "逐个测试工具",
        "列出全部能力", "列出所有能力", "完整工具清单", "完整能力清单", "当前能用的工具",
        "test all tools", "test every tool", "test tools", "all tools", "list all tools", "available tools",
    )
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
    private val NO_TOOL_KEYWORDS = setOf(
        "不要调用任何工具", "不要调用工具", "不要使用任何工具", "不要使用工具",
        "禁止调用工具", "禁止使用工具", "只列清单不要调用", "只列出清单不要调用",
        "仅列清单", "仅列出清单", "不要执行工具", "不要执行任何工具",
        "do not call any tools", "don't call any tools", "do not use tools", "without using tools",
        "no tool calls", "no tools",
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
    private val QUESTION_KEYWORDS = setOf("怎么", "如何", "什么是", "能不能", "是否", "为什么", "how", "what", "why")
    private val MCP_ACTION_KEYWORDS = setOf(
        "创建", "新建", "新增", "添加", "建立", "建一个", "查询", "查一下", "查找", "读取", "读一下",
        "列出", "获取", "更新", "修改", "编辑", "删除", "提交", "同步", "发送", "发布", "写入",
        "create", "list", "get", "read", "update", "delete", "send", "open", "add", "edit", "publish", "sync",
    )
}
