package io.zer0.muse.tools

/**
 * show_card 工具(openhanako show-card-tool.ts 移植)。
 *
 * Agent 生成 HTML/SVG 卡片内容,在对话中内联渲染。
 * 实际渲染由 UI 侧的 CardRenderer 处理。
 */
object ShowCardTool {

    private var cardSeq = 0

    private fun generateCardId(): String {
        cardSeq += 1
        val ts = System.currentTimeMillis().toString(36)
        val seq = cardSeq.toString(36)
        return "c_${ts}_${seq}"
    }

    fun toolDef() = ToolRegistry.ToolDef(
        name = "show_card",
        description = "Show visual content (SVG graphics, diagrams, charts, or interactive HTML) " +
            "that renders inline in the conversation as an interactive card. " +
            "Use for flowcharts, architecture diagrams, dashboards, data tables, calculators, " +
            "timelines, or any visual content that benefits from spatial layout. " +
            "The code is rendered inside a sandboxed WebView. " +
            "Do NOT include DOCTYPE, <html>, <head>, or <body> tags - just content fragments.",
        parameters = mapOf(
            "title" to "Required. Short snake_case identifier for this visual (e.g. 'q4_revenue_chart').",
            "code" to "Required. HTML or SVG fragment to render. Use CSS variables for theming.",
        ),
        required = setOf("title", "code"),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    fun execute(args: Map<String, String>): String {
        val title = args["title"]?.trim()
            ?: return "Error: title parameter is required."
        val code = args["code"]?.trim()
            ?: return "Error: code parameter is required."
        if (title.isEmpty() || code.isEmpty()) {
            return "Error: title and code cannot be empty."
        }
        val cardId = generateCardId()
        return "Card '$title' rendered (id: $cardId). Code length: ${code.length} chars."
    }
}
