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
