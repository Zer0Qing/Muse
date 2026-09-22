package io.zer0.muse.tools

/**
 * show_card 工具(既有实现 show-card-tool.ts 实现)。
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
            "data" to "Optional. JSON text with structured data bound to this card. " +
                "The card script can read it via window.muse.getData(cardId); " +
                "later updates go through update_card_data.",
        ),
        required = setOf("title", "code"),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    fun execute(args: Map<String, String>, dataStore: io.zer0.muse.data.card.CardDataStore? = null): String {
        val title = args["title"]?.trim()
            ?: return "Error: title parameter is required."
        val code = args["code"]?.trim()
            ?: return "Error: code parameter is required."
        if (title.isEmpty() || code.isEmpty()) {
            return "Error: title and code cannot be empty."
        }
        val cardId = generateCardId()
        val data = args["data"]
        val bound = if (!data.isNullOrBlank() && dataStore != null) {
            dataStore.put(cardId, data)
            " Data bound: card script can read it via window.muse.getData(\"$cardId\")."
        } else {
            ""
        }
        return "Card '$title' rendered (id: $cardId). Code length: ${code.length} chars.$bound"
    }
}
