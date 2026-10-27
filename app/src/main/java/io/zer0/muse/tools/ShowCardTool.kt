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
        description = "展示可视化内容(SVG 图形、图表或交互式 HTML),在沙箱 WebView 里内联渲染成可交互卡片。" +
            "适用于流程图、仪表盘、数据表、时间线,或任何需要空间布局的内容。" +
            "不要包含 DOCTYPE、<html>、<head>、<body> 标签,只给内容片段。" +
            "卡片通过 window.muse.cardId 读取自身身份;本工具不返回 id。",
        parameters = mapOf(
            "title" to "Required. Short snake_case identifier (e.g. 'q4_revenue_chart').",
            "code" to "Required. HTML or SVG fragment. Use CSS variables for theming.",
            "data" to "Optional. JSON bound to this card; the card reads it via window.muse.getData().",
            "height" to "Optional. Card height: \"auto\" (fit content, default), " +
                "\"520\" or \"520dp\" (fixed, content scrolls inside), or \"16:9\" (aspect ratio). " +
                "Prefer giving an explicit size for dashboards and multi-row layouts — " +
                "content-fit measurement underestimates async content.",
        ),
        required = setOf("title", "code"),
        category = "built-in",
        // v2.6.10: 卡片是纯展示动作,渲染在沙盒 WebView 里,无设备副作用。
        // 归 SAFE 后不再逐次审批 —— 否则 ASK 模式下每张卡都等用户点同意,
        // 30 秒超时即自动拒绝,用户感知就是"卡片生成不了"。
        riskLevel = ToolRiskLevel.SAFE,
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
        val height = args["height"]?.trim()
        val heightNote = if (height.isNullOrBlank() || height == "auto") "" else " Height: $height."
        return "Card '$title' rendered. Code length: ${code.length} chars.$dataNote$heightNote"
    }
}
