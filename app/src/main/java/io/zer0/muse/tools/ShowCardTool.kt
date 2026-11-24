package io.zer0.muse.tools
import io.zer0.muse.ui.chat.parseShowCardArgs

/**
 * show_card 工具。
 *
 * Agent 生成 HTML/SVG 卡片内容，在对话中内联渲染；实际渲染由 UI 侧的卡片渲染器处理。
 *
 * ## 卡片身份归属
 *
 * 工具不自造一次性 id。卡片的身份是**承载它的那条消息的 id**，由宿主在调用时注入，
 * 并在执行回执中作为 `card_id` 返回给模型；卡片脚本通过只读的 `window.muse.cardId` 读取。
 *
 * 这里曾有过一个自己生成的一次性 id（`c_<ts>_<seq>`）并把它当作数据绑定的 key，
 * 结果是两端各存一个 id：卡片按消息 id 去查、工具按 `c_xxx` 去写，
 * 读到的永远是 null，而且不报错。身份只能有一个来源。
 */
object ShowCardTool {

    /**
     * 解析 `update_card_data` 的目标卡片 id。
     *
     * 模型省略 card_id 时指向**当前这条助手消息**上的卡片：它刚 show_card 完，
     * 手上唯一确定的就是「这一条」，而不是一个它从未见过的 id。
     * 显式给了就以它为准（改之前某条消息里的卡片），校验交给调用方。
     *
     * 抽成纯函数是为了能单测：这段逻辑嵌在工具注册的 lambda 里时不可达。
     */
    internal fun resolveCardTarget(requested: String?, contextMessageId: String?): String =
        requested?.trim().orEmpty().ifBlank { contextMessageId?.trim().orEmpty() }

    fun toolDef() = ToolRegistry.ToolDef(
        name = "show_card",
        description = "展示可视化/交互式 HTML 或 SVG 卡片，在沙箱 WebView 内联渲染（适用于对比、图表、仪表盘、流程图、参数调节工具）。" +
            "【片段约束】只给内容片段，禁止 DOCTYPE/<html>/<head>/<body>、禁止外链资源、禁止只有 <style> 没有内容结构；" +
            "竖屏窄屏优先（flex/grid/百分比，触控热区≥44px），右上角 100×40 留空给宿主控件。" +
            "【样式词汇（宿主已注入，直接用类名与变量，勿写字面色或整段自定义 CSS）】" +
            "外框 <div class=\"m-shell\">；布局 .m-stack/.m-split/.m-flow/.m-panel/.m-section/.m-footer；" +
            "文字 .m-eyebrow/.m-title/.m-lead/.m-heading/.m-copy/.m-caption/.m-label；" +
            "数据 .m-metrics>.m-metric>(.m-metric-value+.m-metric-label)、.m-table-region>.m-table、.m-kv；" +
            "点缀 .m-badge(.m-badge--good/--warn/--danger)/.m-highlight/.m-disclosure；" +
            "控件 .m-button(.m-button--quiet)/.m-input/.m-textarea/.m-field/.m-check/.m-range/.m-select/.m-tabs>.m-tab；" +
            "媒体 .m-banner/.m-media-frame。" +
            "CSS 变量：--muse-fg/--muted/--faint、--muse-surface/-2/-3、--muse-border/--card-line-soft、" +
            "--muse-accent/--accent-soft、--muse-good/--warn/--danger、--muse-d1..--muse-d6、--muse-s1..--muse-s6、--muse-radius-sm/-lg。" +
            "【图表】勿手画 SVG 数据图，用 var c = window.MuseCharts.render(el, {kind:'line'|'area'|'column'|'stacked'|'bar'|'pie'|'donut', labels:[...], series:[{id,label,values}], unit})（最多 6 系列，勿用 NaN，重绘前调 c.destroy()）。" +
            "【交互与脚本】优先声明式 data-m-invoke=\"文字\"（可配 data-m-state='{\"k\":v}'）；" +
            "复杂逻辑写在 <script> 中用 addEventListener 绑定，禁止内联 JS 事件属性（如 onclick=\"...\"）。" +
            "渲染可提交控件前先调 var cap = window.muse.capabilities()，仅 cap.emit==='available' 时启用提交，否则降级静态展示。" +
            "必须由用户点击触发 window.muse.send(summary, state)（严禁自动发送），状态会以 <card_state> 附在用户消息后返回。" +
            "【身份与数据更新】卡片内通过只读 window.muse.cardId 读自身身份、window.muse.getData()/getJSON() 读绑定数据，" +
            "需动态刷新时注册 window.muse.data.onChange(fn)。成功时返回绑定消息的 card_id，后续可用 update_card_data(card_id, data_json) 更新（更新当前消息上的卡片可省略 card_id）。" +
            "【高度契约】简单内容用 height=\"auto\"（默认）；多行看板/表格传固定 dp 如 \"520\"（并给根容器 max-height:100%;overflow:auto）；固定比例传 \"16:9\"（不写 px）。",
        parameters = mapOf(
            "title" to "Required. Short snake_case identifier (e.g. 'q4_revenue_chart').",
            "code" to "Required. HTML or SVG fragment rooted in <div class=\"m-shell\"> using host .m-* classes and --muse-* CSS variables; no inline JS handlers (onclick etc.).",
            "data" to "Optional. JSON bound to this card; read via window.muse.getData() / window.muse.getJSON().",
            "height" to "Optional. Card height: \"auto\" (fit content, default), " +
                "\"520\" or \"520dp\" (fixed dp, content scrolls inside), or \"16:9\" (aspect ratio). " +
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
     *
     * @param messageId 承载这张卡的消息 id，由宿主注入。回执里会把它告诉模型：
     *   模型自己要更新这张卡时，需要一个能指名它的 id，而它无法从其他地方得知。
     */
    fun execute(args: Map<String, String>, messageId: String? = null): String {
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
        val idNote = messageId?.takeIf { it.isNotBlank() }
            ?.let { " Card bound to message $it; pass it as card_id to update this card later." }
            .orEmpty()
        return "Card '$title' rendered. Code length: ${code.length} chars.$dataNote$heightNote$idNote"
    }
}
