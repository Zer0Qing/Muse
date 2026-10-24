package io.zer0.muse.tools

/**
 * show_card 工具。
 *
 * Agent 生成 HTML/SVG 卡片内容，在对话中内联渲染；实际渲染由 UI 侧的卡片渲染器处理。
 *
 * ## 卡片身份归属
 *
 * 工具不产生、也不返回卡片的身份标识。卡片的身份是**承载它的那条消息的 id**，
 * 只有渲染层知道。卡片脚本通过 `window.muse.cardId` 读到它。
 *
 * 这里曾有过一个自己生成的一次性 id（`c_<ts>_<seq>`）并把它当作数据绑定的 key，
 * 结果是两端各存一个 id：卡片按消息 id 去查、工具按 `c_xxx` 去写，
 * 读到的永远是 null，而且不报错。身份只能有一个来源。
 */
object ShowCardTool {

    fun toolDef() = ToolRegistry.ToolDef(
        name = "show_card",
        description = "Show visual content (SVG graphics, diagrams, charts, or interactive HTML) " +
            "rendered inline as an interactive card in a sandboxed WebView. " +
            "Use for flowcharts, dashboards, data tables, timelines, or any spatial layout. " +
            "Do NOT include DOCTYPE, <html>, <head> or <body> tags — content fragments only. " +
            "The card reads its own identity via window.muse.cardId; this tool returns no id.",
        parameters = mapOf(
            "title" to "Required. Short snake_case identifier (e.g. 'q4_revenue_chart').",
            "code" to "Required. HTML or SVG fragment. Use CSS variables for theming.",
            "data" to "Optional. JSON bound to this card; the card reads it via window.muse.getData().",
        ),
        required = setOf("title", "code"),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    /**
     * 校验参数并给出一句执行摘要。
     *
     * 卡片的 `data` 参数不需要在这里落盘：渲染层会从工具参数里把它原样取出并内联注入
     * （见 `parseShowCardArgs` → `inlineCardData`），那条路径不依赖任何 id 约定。
     * 后续更新由 `update_card_data` 按消息 id 写入，卡片用 `window.muse.data.onChange`
     * 订阅即可。
     */
    fun execute(args: Map<String, String>): String {
        val title = args["title"]?.trim()
            ?: return "Error: title parameter is required."
        val code = args["code"]?.trim()
            ?: return "Error: code parameter is required."
        if (title.isEmpty() || code.isEmpty()) {
            return "Error: title and code cannot be empty."
        }
        val data = args["data"]
        val dataNote = if (data.isNullOrBlank()) "" else " Bound data: ${data.length} chars."
        return "Card '$title' rendered. Code length: ${code.length} chars.$dataNote"
    }
}
