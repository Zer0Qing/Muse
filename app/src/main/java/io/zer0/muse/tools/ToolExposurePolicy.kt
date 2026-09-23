package io.zer0.muse.tools

import io.zer0.ai.core.ToolDefinition

/**
 * 主对话工具暴露策略。
 *
 * 根据用户最新消息判断本轮是否关闭工具、是否把 tool_choice 设为 required;
 * 实际工具授权仍由 ToolPermissionResolver 决定。
 */
object ToolExposurePolicy {

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

    /**
     * v2.0: 判断用户是否明确要求执行动作(工具意图)。
     *
     * 与 [isSimpleToolRequest] 不同:后者只描述"短句",用于工具收窄;
     * 本函数供"是否压缩本轮思考/截断 maxTokens"判断,必须只在用户真正要动手时返回 true。
     * 否则用户开着深度思考发一句"你好",思考会被误当成"工具轮的重复推理"而吞掉。
     */
    fun isDirectToolRequest(userText: String, tools: List<ToolDefinition> = emptyList()): Boolean {
        val normalized = userText.trim().lowercase()
        if (normalized.isBlank()) return false
        if (normalized.containsAny(EXPLICIT_TOOL_KEYWORDS)) return true
        val hasMcpTools = tools.any { it.name.startsWith("mcp_") }
        return hasMcpTools &&
            !normalized.containsAny(QUESTION_KEYWORDS) &&
            normalized.containsAny(MCP_ACTION_KEYWORDS)
    }

    /**
     * v2.0: 简单请求下的工具收窄。
     *
     * 内置工具已过百,简单请求(短句、无复杂度关键词)不需要同时看到全部工具。
     * 按关键词命中工具族:命中时只暴露命中的族 + 核心工具 + MCP 工具;
     * 未命中、工具本来就不多、或请求本身复杂时返回原列表,行为完全不变。
     * 纯关键词规则、无副作用,避免向量检索可能带来的不确定误召回。
     */
    fun filterToolsForRequest(userText: String, tools: List<ToolDefinition>): List<ToolDefinition> {
        if (tools.size <= SIMPLE_TOOL_SUBSET_THRESHOLD) return tools
        if (!isSimpleToolRequest(userText)) return tools
        val normalized = userText.trim().lowercase()
        if (normalized.isBlank()) return tools
        val matched = TOOL_FAMILIES.filter { family -> family.keywords.any { normalized.contains(it) } }
        if (matched.isEmpty()) return tools
        val allowed = matched.flatMapTo(mutableSetOf()) { it.toolNames }
        allowed += CORE_TOOL_NAMES
        return tools.filter { it.name.startsWith("mcp_") || it.name in allowed }
    }

    /** 工具族:关键词 → 相关工具集合。 */
    private data class ToolFamily(val keywords: Set<String>, val toolNames: Set<String>)

    private val CORE_TOOL_NAMES = setOf("get_current_time", "calculator", "get_device_info")

    private const val SIMPLE_TOOL_SUBSET_THRESHOLD = 20

    private val TOOL_FAMILIES = listOf(
        ToolFamily(
            keywords = setOf("搜索", "搜一下", "查一下", "查资料", "联网", "最新", "新闻", "search", "latest news"),
            toolNames = setOf("web_search", "web_fetch", "parse_link"),
        ),
        ToolFamily(
            keywords = setOf("天气", "气温", "下雨", "weather", "forecast"),
            toolNames = setOf("get_weather"),
        ),
        ToolFamily(
            keywords = setOf("闹钟", "提醒", "倒计时", "日历", "日程", "定时", "reminder", "alarm", "timer", "calendar", "schedule"),
            toolNames = setOf(
                "set_alarm", "set_timer", "add_calendar_event", "get_calendar_today", "calendar_today",
                "schedule_reminder", "cancel_reminder", "list_reminders",
                "scheduled_task_create", "scheduled_task_list", "scheduled_task_update",
                "scheduled_task_delete", "scheduled_task_execute", "scheduled_task_get_history",
            ),
        ),
        ToolFamily(
            keywords = setOf("电量", "电池", "内存", "存储", "亮度", "音量", "手电筒", "闪光灯", "蓝牙", "wifi", "无线", "震动", "battery", "volume", "brightness", "flashlight"),
            toolNames = setOf(
                "get_battery_info", "get_network_info", "get_storage_info", "get_memory_info",
                "get_display_info", "get_cpu_info", "get_wifi_info", "get_bluetooth_devices",
                "toggle_wifi", "toggle_bluetooth", "toggle_flashlight", "set_brightness", "get_brightness",
                "set_volume", "get_volume", "vibrate", "screen_time", "open_system_setting",
                "list_installed_apps", "get_recent_notifications",
            ),
        ),
        ToolFamily(
            keywords = setOf("打开应用", "打开", "发短信", "发邮件", "打电话", "导航", "地图", "联系人", "open app", "send sms", "send email", "call"),
            toolNames = setOf(
                "open_app", "open_url", "open_maps", "make_phone_call", "send_sms", "send_email",
                "add_contact", "get_contacts_list", "get_contacts_count", "share_text",
            ),
        ),
        ToolFamily(
            keywords = setOf("剪贴板", "复制", "粘贴", "clipboard", "前台应用", "foreground"),
            toolNames = setOf("clipboard_read", "clipboard_write", "get_foreground_app"),
        ),
        ToolFamily(
            keywords = setOf("记忆", "记住", "回忆", "经验", "memory"),
            toolNames = setOf("pin_memory", "unpin_memory", "recall_experience", "record_experience", "search_memory"),
        ),
        ToolFamily(
            keywords = setOf("笔记", "速记", "知识库", "资源", "note", "resource"),
            toolNames = setOf(
                "quick_note_add", "quick_note_list", "quick_note_search", "quick_note_get",
                "quick_note_update", "quick_note_delete", "quick_note_pin",
                "resource_add", "resource_list", "resource_search", "resource_get", "resource_delete",
            ),
        ),
        ToolFamily(
            keywords = setOf("通知", "卡片", "进度", "todo", "提醒我", "喊我"),
            toolNames = setOf("notify", "show_card", "update_card_data", "todo_write", "current_status"),
        ),
        ToolFamily(
            keywords = setOf("编码", "解码", "base64", "哈希", "uuid", "密码", "翻译", "translate", "encode", "decode", "hash"),
            toolNames = setOf(
                "url_encode", "url_decode", "base64_encode", "base64_decode", "hash_text",
                "generate_uuid", "random_number", "json_pretty", "generate_password", "translate",
            ),
        ),
        ToolFamily(
            keywords = setOf("画图", "图片", "生成图", "视频", "二维码", "朗读", "图像", "image", "video", "qr code"),
            toolNames = setOf("generate_image", "generate_video", "generate_qr_code", "speak_text"),
        ),
        ToolFamily(
            keywords = setOf("子代理", "群聊", "渠道", "表情包", "subagent", "channel"),
            toolNames = setOf(
                "subagent_task", "subagent_run", "subagent_close", "delegate_agent",
                "channel_pass", "channel_read_context", "channel_reply", "list_stickers", "send_sticker",
            ),
        ),
        ToolFamily(
            keywords = setOf("文件", "文档", "pdf", "链接", "下载", "file", "document", "download"),
            toolNames = setOf("read_file", "create_download", "parse_pdf", "parse_link"),
        ),
        ToolFamily(
            keywords = setOf("ping", "dns", "公网 ip", "public ip"),
            toolNames = setOf("ping_host", "dns_lookup", "get_public_ip"),
        ),
    )

    private fun String.containsAny(values: Set<String>): Boolean =
        values.any { contains(it) }

    private const val SIMPLE_REQUEST_MAX_CHARS = 120

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
