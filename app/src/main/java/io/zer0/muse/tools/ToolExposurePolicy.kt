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
     * 默认分层收窄(v2.x 工具瘦身阶段1,升级自 v2.0 简单请求收窄)。
     *
     * 工具面大于 [SIMPLE_TOOL_SUBSET_THRESHOLD] 时,按 [ToolCategories] 分层:
     *  - CORE / STANDARD:恒发(模型能力底座与常开稳定工具);
     *  - OPTIONAL:命中关键词族、本会话用过(粘性)、会话内已授权,或明确动作请求(宽松兜底)才发;
     *  - GLOBAL:命中关键词族、本会话用过、会话内已授权才发(未授权/未命中不发);
     *  - 未登记分类的工具(MCP / 插件 / 技能等)保持原可见性。
     *
     * 小工具面(≤阈值)完全不动 — 助手显式配置的小清单优先语义保留。
     * 纯关键词规则、无副作用,避免向量检索可能带来的不确定误召回。
     *
     * @param stickyToolNames 本会话历史中已调用过的工具名(用过的工具后续轮次持续可见)
     * @param authorizedToolNames 本会话内用户点过"本会话允许"的工具名集合
     * @param loadedToolNames find_tools 动态装载的工具名(命中后持续可见)
     * @param skillToolNames 本轮工具面里的技能名集合(技能未登记 ToolCategories 分类,
     *        与 OPTIONAL 同口径收窄:需命中/装载/粘性/授权才发;MCP/插件等其它
     *        未登记分类的动态工具仍保持既有可见性)
     */
    @Suppress("LongParameterList")
    fun filterToolsForRequest(
        userText: String,
        tools: List<ToolDefinition>,
        stickyToolNames: Set<String> = emptySet(),
        authorizedToolNames: Set<String> = emptySet(),
        loadedToolNames: Set<String> = emptySet(),
        skillToolNames: Set<String> = emptySet(),
    ): List<ToolDefinition> {
        return if (tools.size <= SIMPLE_TOOL_SUBSET_THRESHOLD) {
            tools
        } else {
            val normalized = userText.trim().lowercase()
            if (normalized.isBlank()) {
                tools
            } else {
                val matched = TOOL_FAMILIES.filter { family -> family.keywords.any { normalized.contains(it) } }
                val familyAllowed = matched.flatMapTo(mutableSetOf()) { it.toolNames }
                val dynamicAllowed = familyAllowed + stickyToolNames + authorizedToolNames + loadedToolNames
                // 宽松兜底:明确动作请求且非简单短句 → OPTIONAL 长尾全放,避免多步任务中途缺工具
                val optionalRelaxed = !isSimpleToolRequest(userText) && isDirectToolRequest(userText, tools)
                tools.filter { tool ->
                    when (ToolCategories.categoryOf(tool.name)) {
                        // 未登记分类(MCP / 插件 / 技能等动态工具):
                        //  - 技能:与 OPTIONAL 同口径收窄(此前无条件放行,导致每个已启用技能
                        //    每轮都随请求发出,是本项目工具 schema 占位的最大来源);
                        //  - 其余(MCP / 插件)保持既有可见性。
                        null -> tool.name !in skillToolNames || tool.name in dynamicAllowed || optionalRelaxed
                        ToolCategory.CORE, ToolCategory.STANDARD, ToolCategory.LEGACY -> true
                        ToolCategory.OPTIONAL -> tool.name in dynamicAllowed || optionalRelaxed
                        ToolCategory.GLOBAL -> tool.name in dynamicAllowed
                    }
                }
            }
        }
    }

    /** 工具族:关键词 → 相关工具集合。 */
    private data class ToolFamily(val keywords: Set<String>, val toolNames: Set<String>)

    private const val SIMPLE_TOOL_SUBSET_THRESHOLD = 20

    private val TOOL_FAMILIES =
        listOf(
            ToolFamily(
                keywords = setOf("搜索", "搜一下", "查一下", "查资料", "联网", "最新", "新闻", "search", "latest news"),
                toolNames = setOf("web_search", "web_fetch", "parse_link"),
            ),
            ToolFamily(
                keywords = setOf("天气", "气温", "下雨", "weather", "forecast"),
                toolNames = setOf("get_weather"),
            ),
            ToolFamily(
                keywords = setOf("闹钟", "提醒", "倒计时", "日历", "日程", "reminder", "alarm", "timer", "calendar"),
                toolNames =
                setOf(
                    "set_alarm",
                    "set_timer",
                    "add_calendar_event",
                    "get_calendar_today",
                    "calendar_today",
                    "schedule_reminder",
                    "cancel_reminder",
                    "list_reminders",
                ),
            ),
            ToolFamily(
                keywords = setOf("定时任务", "计划任务", "定期", "周期", "每天", "每周", "scheduled task", "schedule"),
                toolNames =
                setOf(
                    "scheduled_task_create",
                    "scheduled_task_list",
                    "scheduled_task_update",
                    "scheduled_task_delete",
                    "scheduled_task_execute",
                    "scheduled_task_get_history",
                ),
            ),
            ToolFamily(
                keywords = setOf("电量", "电池", "充电", "battery"),
                toolNames = setOf("get_battery_info", "screen_time"),
            ),
            ToolFamily(
                keywords = setOf("内存", "存储", "屏幕参数", "分辨率", "cpu", "处理器", "传感器", "设备信息", "手机型号", "memory", "storage"),
                toolNames =
                setOf(
                    "get_storage_info",
                    "get_memory_info",
                    "get_display_info",
                    "get_cpu_info",
                    "get_sensors_list",
                    "get_network_info",
                ),
            ),
            ToolFamily(
                keywords = setOf("蓝牙", "wifi", "无线网", "网络状态", "bluetooth"),
                toolNames = setOf("get_wifi_info", "get_bluetooth_devices", "toggle_wifi", "toggle_bluetooth"),
            ),
            ToolFamily(
                keywords = setOf("手电筒", "闪光灯", "亮度", "音量", "震动", "静音", "brightness", "volume", "flashlight", "vibrate"),
                toolNames =
                setOf(
                    "toggle_flashlight",
                    "set_brightness",
                    "get_brightness",
                    "set_volume",
                    "get_volume",
                    "vibrate",
                ),
            ),
            ToolFamily(
                keywords = setOf("装了哪些应用", "安装的应用", "应用列表", "最近通知", "通知栏", "系统设置", "installed apps"),
                toolNames = setOf("list_installed_apps", "get_recent_notifications", "open_system_setting"),
            ),
            ToolFamily(
                keywords = setOf("打开应用", "启动应用", "open app", "launch app"),
                toolNames = setOf("open_app"),
            ),
            ToolFamily(
                keywords = setOf("发短信", "发邮件", "打电话", "拨号", "send sms", "send email", "make a call"),
                toolNames = setOf("send_sms", "send_email", "make_phone_call"),
            ),
            ToolFamily(
                keywords = setOf("导航", "地图", "去哪", "路线", "maps", "navigate to"),
                toolNames = setOf("open_maps"),
            ),
            ToolFamily(
                keywords = setOf("联系人", "通讯录", "名片", "contact"),
                toolNames = setOf("add_contact", "get_contacts_list", "get_contacts_count"),
            ),
            ToolFamily(
                keywords = setOf("打开网址", "打开链接", "open url", "分享", "share"),
                toolNames = setOf("open_url", "share_text"),
            ),
            ToolFamily(
                keywords = setOf("剪贴板", "复制", "粘贴", "clipboard", "前台应用", "foreground"),
                toolNames = setOf("clipboard_read", "clipboard_write", "get_foreground_app"),
            ),
            ToolFamily(
                keywords = setOf("记忆", "记住", "记住这个", "回忆", "经验", "memory", "忘掉", "忘记", "forget"),
                toolNames =
                setOf(
                    "pin_memory",
                    "unpin_memory",
                    "recall_experience",
                    "record_experience",
                    "search_memory",
                    "search_conversation",
                    "save_memory",
                    "delete_memory",
                ),
            ),
            ToolFamily(
                keywords = setOf("笔记", "速记", "知识库", "记一下", "note", "note down"),
                toolNames =
                setOf(
                    "quick_note_add",
                    "quick_note_list",
                    "quick_note_search",
                    "quick_note_get",
                    "quick_note_update",
                    "quick_note_delete",
                    "quick_note_pin",
                ),
            ),
            ToolFamily(
                keywords = setOf("资源", "资源库", "资料库", "存个资源", "resource"),
                toolNames =
                setOf(
                    "resource_add",
                    "resource_list",
                    "resource_search",
                    "resource_get",
                    "resource_delete",
                ),
            ),
            ToolFamily(
                keywords = setOf("通知", "卡片", "进度", "todo", "提醒我", "喊我"),
                toolNames = setOf("notify", "show_card", "update_card_data", "todo_write", "current_status"),
            ),
            ToolFamily(
                keywords = setOf("编码", "解码", "base64", "哈希", "uuid", "密码", "翻译", "translate", "encode", "decode", "hash"),
                toolNames =
                setOf(
                    "url_encode", "url_decode", "base64_encode", "base64_decode", "hash_text",
                    "generate_uuid", "random_number", "json_pretty", "generate_password", "translate",
                ),
            ),
            ToolFamily(
                keywords = setOf("画图", "画", "图片", "生成图", "视频", "二维码", "朗读", "图像", "image", "video", "qr code"),
                toolNames = setOf("generate_image", "generate_video", "generate_qr_code", "speak_text"),
            ),
            ToolFamily(
                keywords = setOf("子代理", "群聊", "渠道", "表情包", "subagent", "channel"),
                toolNames =
                setOf(
                    "subagent_task", "subagent_run", "subagent_close", "delegate_agent",
                    "channel_pass", "channel_read_context", "channel_reply", "list_stickers", "send_sticker",
                    "send_channel_message", "channel_list",
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
            ToolFamily(
                keywords = setOf("工作区", "项目", "工程", "代码", "仓库", "脚本", "workspace"),
                toolNames =
                setOf(
                    "workspace_list",
                    "workspace_read",
                    "workspace_write",
                    "workspace_delete",
                    "workspace_mkdir",
                    "workspace_move",
                ),
            ),
            ToolFamily(
                keywords = setOf("浏览器", "网页", "打开网站", "网址", "browser", "webpage"),
                toolNames =
                setOf(
                    "browser_navigate",
                    "browser_click",
                    "browser_type",
                    "browser_extract",
                    "browser_scroll_bottom",
                    "browser_get_html",
                    "browser_snapshot",
                    "browser_screenshot",
                ),
            ),
            ToolFamily(
                keywords = setOf("插件", "plugin"),
                toolNames =
                setOf(
                    "plugin_market_search",
                    "plugin_market_install",
                    "plugin_market_uninstall",
                    "plugin_market_set_enabled",
                ),
            ),
            ToolFamily(
                keywords = setOf("终端", "命令行", "shell", "adb", "termux", "系统命令", "执行命令"),
                toolNames = setOf("device_shell", "terminal_exec", "termux_exec"),
            ),
            ToolFamily(
                keywords = setOf("脚本", "代码", "javascript", "node", "js 脚本", "执行代码", "script"),
                toolNames = setOf("execute_javascript", "execute_node_script", "execute_shell"),
            ),
            ToolFamily(
                keywords = setOf("系统设置", "settings", "日志", "logcat", "安装列表", "root 操作", "网络开关", "输入注入"),
                toolNames =
                setOf(
                    "settings_get",
                    "settings_put",
                    "am_start",
                    "list_packages",
                    "logcat_tail",
                    "input_inject",
                    "network_toggle",
                ),
            ),
            ToolFamily(
                keywords = setOf("屏幕", "界面", "当前应用", "前台应用", "读屏", "屏幕上", "screen content", "what's on screen"),
                toolNames =
                setOf(
                    "ui_get_page_info",
                    "ui_screenshot",
                    "ui_get_current_app",
                    "screen_read",
                    "screen_current_app",
                    "screen_permission_status",
                ),
            ),
            ToolFamily(
                keywords =
                setOf(
                    "自动点击", "点击屏幕", "点击按钮", "帮我点", "滑动屏幕",
                    "界面操作", "屏幕操作", "输入文字", "按键", "无障碍",
                    "auto click", "tap", "swipe", "accessibility",
                ),
                toolNames =
                setOf(
                    "ui_click", "ui_long_press", "ui_swipe", "ui_set_text", "ui_back", "ui_home", "ui_global_action",
                    "screen_back", "screen_home", "screen_tap", "screen_tap_text", "screen_swipe", "screen_input",
                    "screen_launch_app", "screen_open_notifications", "screen_wait", "screen_pinch", "screen_swipe_path",
                ),
            ),
            ToolFamily(
                keywords = setOf("自动操作", "自动化流程", "虚拟屏", "多步操作", "agent 操作", "virtual screen", "automation workflow"),
                toolNames = setOf("automation_workflow", "ui_agent", "virtual_screen", "virtual_screen_input"),
            ),
            ToolFamily(
                keywords = setOf("连接器", "oauth", "connector"),
                toolNames = setOf("connector_list", "call_connector"),
            ),
        )

    private fun String.containsAny(values: Set<String>): Boolean = values.any { contains(it) }

    private const val SIMPLE_REQUEST_MAX_CHARS = 120

    private val COMPLEXITY_KEYWORDS =
        setOf(
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
    private val NO_TOOL_KEYWORDS =
        setOf(
            "不要调用任何工具", "不要调用工具", "不要使用任何工具", "不要使用工具",
            "禁止调用工具", "禁止使用工具", "只列清单不要调用", "只列出清单不要调用",
            "仅列清单", "仅列出清单", "不要执行工具", "不要执行任何工具",
            "do not call any tools", "don't call any tools", "do not use tools", "without using tools",
            "no tool calls", "no tools",
        )
    private val EXPLICIT_TOOL_KEYWORDS =
        setOf(
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
    private val MCP_ACTION_KEYWORDS =
        setOf(
            "创建", "新建", "新增", "添加", "建立", "建一个", "查询", "查一下", "查找", "读取", "读一下",
            "列出", "获取", "更新", "修改", "编辑", "删除", "提交", "同步", "发送", "发布", "写入",
            "create", "list", "get", "read", "update", "delete", "send", "open", "add", "edit", "publish", "sync",
        )
}
