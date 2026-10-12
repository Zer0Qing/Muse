package io.zer0.muse.tools

import io.zer0.common.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Structured chart/table renderer for tool results.
 *
 * Charts are emitted as the existing ```card format and tables as bounded
 * Markdown. The chat tool-result renderer owns the visual presentation.
 */
object RenderDataTool {

    const val NAME = "render_data"

    // Keep only structural guardrails; the orchestrator can spill large results to a complete
    // file reference, so this renderer must not reject or truncate otherwise valid user data.
    private const val MAX_COLUMNS = 64
    private const val MAX_ROWS = 2_000

    private val CHART_TYPES = setOf("bar", "line", "donut")

    fun toolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME,
        description =
        "在聊天中直接展示结构化数据。type=bar/line/donut 时 data 需为 " +
            "{\"labels\":[...],\"values\":[...]}；type=table 时 data 需为 " +
            "{\"columns\":[...],\"rows\":[[...]]}。数据仅在本地渲染，不访问网络。",
        parameters = mapOf(
            "type" to "必填:bar | line | donut | table",
            "title" to "必填,图表或表格标题",
            "data" to "必填 JSON 数据:图表使用 labels/values,表格使用 columns/rows",
        ),
        required = setOf("type", "title", "data"),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
        // data 是嵌套对象（图表 labels/values、表格 columns/rows）。
        // 旧实现未声明类型 → schema 默认标成 string，模型便把它按转义 JSON 字符串传递，
        // 叠加参数提取的转义还原缺陷会解析失败。这里显式声明 object。
        parameterTypes = mapOf("data" to "object"),
    )

    fun execute(args: Map<String, String>): String {
        val type = args["type"]?.trim()?.lowercase()
        val title = args["title"]?.let(::cleanText)
        val rawData = args["data"]?.trim()
        val data = rawData?.let { runCatching { AppJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val rendered =
            when {
                type == null -> error("缺少 type")
                title == null -> error("缺少 title")
                title.isBlank() -> error("title 不能为空")
                rawData == null -> error("缺少 data")
                data == null -> error("data 必须是 JSON 对象")
                type in CHART_TYPES -> renderChart(type, title, data)
                type == "table" -> renderTable(title, data)
                else -> error("type 只支持 bar、line、donut 或 table")
            }
        return rendered
    }

    private fun renderChart(type: String, title: String, data: JsonObject): String {
        val labels = stringArray(data["labels"])
        val values = numberArray(data["values"])
        // 校验分支各自产出错误文案；只有全部通过才产出卡片 JSON。两侧都是 String，
        // 因此不需要额外的类型判断，也不会误把错误文案包进 ```card 代码块。
        return when {
            labels == null -> error("图表 labels 必须是字符串数组")
            values == null -> error("图表 values 必须是有限数字数组")
            labels.isEmpty() || values.isEmpty() -> error("图表数据不能为空")
            labels.size != values.size -> error("labels 与 values 长度必须一致")
            labels.size > MAX_ROWS -> error("图表数据最多支持 $MAX_ROWS 个点")
            else -> {
                val cardJson =
                    buildJsonObject {
                        put("type", JsonPrimitive(type))
                        put("title", JsonPrimitive(title))
                        put("labels", buildJsonArray { labels.forEach { add(JsonPrimitive(it)) } })
                        put("values", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
                    }
                "```card\n$cardJson\n```"
            }
        }
    }

    private fun renderTable(title: String, data: JsonObject): String {
        val columns = stringArray(data["columns"])
        val rows = data["rows"] as? JsonArray
        val shapeError = validateTableShape(columns, rows)
        val normalizedRows = if (shapeError == null && columns != null && rows != null) {
            rows.map { stringArray(it) }
        } else {
            null
        }
        val rowsError = if (columns != null && normalizedRows != null) {
            normalizedRows.withIndex()
                .firstOrNull { (_, cells) -> cells == null || cells.size != columns.size }
                ?.let { (index, cells) ->
                    if (cells == null) {
                        error("第 ${index + 1} 行必须是字符串/数字数组")
                    } else {
                        error("第 ${index + 1} 行列数与 columns 不一致")
                    }
                }
        } else {
            null
        }
        return when {
            shapeError != null -> shapeError
            rowsError != null -> rowsError
            columns != null && normalizedRows != null -> renderTableBody(title, columns, normalizedRows)
            else -> error("表格数据不完整")
        }
    }

    /** 表格"形状"层面的校验（列本身、行数），返回 null 表示通过。 */
    private fun validateTableShape(columns: List<String>?, rows: JsonArray?): String? = when {
        columns == null -> error("表格 columns 必须是字符串数组")
        columns.isEmpty() -> error("表格至少需要一列")
        columns.size > MAX_COLUMNS -> error("表格最多支持 $MAX_COLUMNS 列")
        rows == null -> error("表格 rows 必须是二维数组")
        rows.size > MAX_ROWS -> error("表格最多支持 $MAX_ROWS 行")
        else -> null
    }

    private fun renderTableBody(title: String, columns: List<String>, rows: List<List<String>?>): String = buildString {
        appendLine("### $title")
        appendLine("| ${columns.joinToString(" | ")} |")
        appendLine("| ${columns.joinToString(" | ") { "---" }} |")
        rows.forEach { row ->
            appendLine("| ${row.orEmpty().joinToString(" | ")} |")
        }
    }.trimEnd()

    private fun stringArray(element: kotlinx.serialization.json.JsonElement?): List<String>? {
        val array = element as? JsonArray ?: return null
        val cleaned = array.map { primitive ->
            (primitive as? JsonPrimitive)?.contentOrNull()?.let(::cleanText)?.takeIf { it.isNotBlank() }
        }
        return cleaned.takeIf { values -> values.all { it != null } }?.mapNotNull { it }
    }

    private fun numberArray(element: kotlinx.serialization.json.JsonElement?): List<Double>? {
        val array = element as? JsonArray ?: return null
        val numbers = array.map { primitive ->
            (primitive as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it.isFinite() }
        }
        return numbers.takeIf { values -> values.all { it != null } }?.mapNotNull { it }
    }

    private fun cleanText(value: String): String =
        value.replace('|', ' ').replace('\r', ' ').replace('\n', ' ').trim()

    private fun error(message: String): String = "Error: $message"

    private fun JsonPrimitive.contentOrNull(): String? = content.takeIf { it.isNotBlank() }
}
