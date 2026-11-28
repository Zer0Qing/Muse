package io.zer0.muse.ui.artifact

import android.content.Context
import io.zer0.muse.R
import io.zer0.muse.data.agentbot.AgentSpaceEngine
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.ui.markdown.MarkdownBlock
import io.zer0.muse.ui.markdown.parseMarkdown
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/**
 * 富卡片（svg/html/chart/mermaid 围栏代码块）与产物库的同步。
 *
 * 渲染侧把这四类围栏块作为富内容呈现，在流式收尾时按同一判定规则自动入库，
 * 手动保存与自动入库共用 [id]，同一张卡不会变成两条产物。
 */
object RichCardArtifacts {

    private val CARD_LANGUAGES = setOf("svg", "html", "chart", "mermaid")
    private const val MAX_DERIVED_TITLE_CHARS = 60

    /**
     * 卡片产物的幂等 id。
     *
     * 含内容摘要：一条消息里多张同语言卡片各自独立，而同一份源码重复入库（自动 + 手动）
     * 落到同一行。只用 messageId+language 会让同语言的第二张卡覆盖第一张。
     */
    fun id(messageId: String, language: String, content: String): String {
        val lang = language.lowercase().trim()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$messageId:$lang:$content".toByteArray(Charsets.UTF_8))
        val suffix = digest.take(8).joinToString("") { "%02x".format(it) }
        return "card_${messageId}_${lang}_$suffix"
    }

    /** 按卡片源码派生有语义的标题，避免所有自动入库卡片都只显示纯英文占位符。 */
    fun deriveTitle(language: String, content: String, context: Context? = null): String {
        val lang = language.lowercase().trim()
        return when (lang) {
            "html", "svg" -> AgentSpaceEngine.deriveSpaceTitle(content, lang == "svg")
            "chart" -> deriveChartTitle(content, context) ?: "CHART"
            "mermaid" -> deriveMermaidTitle(content, context) ?: "MERMAID"
            else -> lang.uppercase().ifBlank { "CARD" }
        }
    }

    private fun deriveChartTitle(jsonText: String, context: Context?): String? = runCatching {
        val root = Json.parseToJsonElement(jsonText.trim()).jsonObject
        val options = root["options"] as? JsonObject
        val pluginsTitle = (options?.get("plugins") as? JsonObject)
            ?.let { it["title"] as? JsonObject }
            ?.get("text")?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull
        val legacyTitle = (options?.get("title") as? JsonObject)
            ?.get("text")?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull
        val datasetLabel = (root["data"] as? JsonObject)
            ?.get("datasets")?.jsonArray
            ?.firstOrNull()?.let { it as? JsonObject }
            ?.get("label")?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull
        val chartType = root["type"]?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
        val candidate = listOf(pluginsTitle, legacyTitle, datasetLabel)
            .firstOrNull { !it.isNullOrBlank() }
            ?.trim()
            ?.take(MAX_DERIVED_TITLE_CHARS)
        candidate ?: chartType?.let { type ->
            context?.getString(R.string.artifact_title_chart_with_type, type.uppercase())
                ?: "${type.uppercase()} Chart"
        }
    }.getOrNull()

    private fun deriveMermaidTitle(source: String, context: Context?): String? {
        val lines = source.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("%%") }
            .toList()
        val titleLine = lines.firstOrNull { it.startsWith("title ", ignoreCase = true) }
            ?.substringAfter(' ')
            ?.trim()
        if (!titleLine.isNullOrBlank()) return titleLine.take(MAX_DERIVED_TITLE_CHARS)
        val firstLine = lines.firstOrNull() ?: return null
        return when {
            firstLine.startsWith("sequenceDiagram", ignoreCase = true) ->
                context?.getString(R.string.artifact_mermaid_sequence) ?: "Sequence Diagram"
            firstLine.startsWith("classDiagram", ignoreCase = true) ->
                context?.getString(R.string.artifact_mermaid_class) ?: "Class Diagram"
            firstLine.startsWith("stateDiagram", ignoreCase = true) ->
                context?.getString(R.string.artifact_mermaid_state) ?: "State Diagram"
            firstLine.startsWith("erDiagram", ignoreCase = true) ->
                context?.getString(R.string.artifact_mermaid_er) ?: "ER Diagram"
            firstLine.startsWith("gantt", ignoreCase = true) ->
                context?.getString(R.string.artifact_mermaid_gantt) ?: "Gantt Chart"
            firstLine.startsWith("pie", ignoreCase = true) ->
                context?.getString(R.string.artifact_mermaid_pie) ?: "Pie Chart"
            firstLine.startsWith("mindmap", ignoreCase = true) ->
                context?.getString(R.string.artifact_mermaid_mindmap) ?: "Mindmap"
            else ->
                context?.getString(R.string.artifact_type_mermaid) ?: "Flowchart"
        }
    }

    /** 按渲染侧同一规则抽出消息里的富卡片；空白块不入库。 */
    fun extract(sessionId: String, messageId: String, content: String, context: Context? = null): List<ArtifactEntity> {
        val now = System.currentTimeMillis()
        return parseMarkdown(content)
            .filterIsInstance<MarkdownBlock.CodeBlock>()
            .mapNotNull { block ->
                val lang = block.language?.lowercase()?.trim().orEmpty()
                if (lang !in CARD_LANGUAGES || block.code.isBlank()) return@mapNotNull null
                ArtifactEntity(
                    id = id(messageId, lang, block.code),
                    sessionId = sessionId,
                    messageId = messageId,
                    title = deriveTitle(lang, block.code, context),
                    type = "rich_card",
                    content = block.code,
                    language = lang,
                    createdAt = now,
                    updatedAt = now,
                )
            }
    }
}
