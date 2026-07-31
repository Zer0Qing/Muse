package io.zer0.muse.ui.markdown

import io.zer0.common.AppJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * v1.0.53: 对话数据卡片解析(对标 Hana show_card)。
 *
 * 消息内容中的 ```card 代码块:
 * ```
 * ```card
 * {"type":"bar","title":"本周消息量","labels":["一","二","三"],"values":[12,30,18]}
 * ```
 * ```
 *
 * 解析失败返回 null(渲染层降级为原样显示代码块)。
 */
@Serializable
data class DataCard(
    val type: String,          // bar | line | donut
    val title: String = "",
    val labels: List<String> = emptyList(),
    val values: List<Float> = emptyList(),
) {
    val isValid: Boolean
        get() = type in SUPPORTED_TYPES && labels.size == values.size && values.isNotEmpty() && labels.isNotEmpty()

    companion object {
        val SUPPORTED_TYPES = setOf("bar", "line", "donut")
    }
}

object DataCardParser {

    private const val CARD_BLOCK_START = "```card"

    /**
     * 从 markdown 文本中提取第一个 ```card 块并解析。
     *
     * @return 解析成功返回 [DataCard];无卡片块/格式非法/数据不合法返回 null
     */
    fun parse(markdown: String): DataCard? {
        val startIdx = markdown.indexOf(CARD_BLOCK_START)
        if (startIdx < 0) return null
        val jsonStart = markdown.indexOf('\n', startIdx)
        if (jsonStart < 0) return null
        val jsonEnd = markdown.indexOf("```", jsonStart + 1)
        if (jsonEnd < 0) return null
        val jsonText = markdown.substring(jsonStart + 1, jsonEnd).trim()
        if (jsonText.isEmpty()) return null

        return parseJson(jsonText)
    }

    /** 从纯 JSON 解析(供测试与独立调用)。 */
    fun parseJson(jsonText: String): DataCard? {
        val card = runCatching {
            AppJson.decodeFromString(DataCard.serializer(), jsonText)
        }.getOrNull() ?: return null
        return if (card.isValid) card else null
    }

    /** 检测文本是否包含卡片块(渲染分流用)。 */
    fun containsCardBlock(markdown: String): Boolean = markdown.contains(CARD_BLOCK_START)
}
