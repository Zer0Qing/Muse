package io.zer0.muse.data.artifact

import android.content.Context
import kotlin.uuid.Uuid
import io.zer0.muse.R

/**
 * 从 AI 回复内容中抽取 `<artifact>` 标签,生成 [ArtifactEntity] 列表,
 * 并把原标签替换为占位符 `[artifact:{id}]` 供 UI 渲染卡片。
 *
 * - 标签名大小写不敏感
 * - type 缺省时默认 "document"
 * - language 可选
 * - 支持内容跨多行、属性间任意空白
 */
object ArtifactExtractor {

    /**
     * 匹配 `<artifact ...>...</artifact>`,忽略大小写且内容可跨行。
     *
     * L13 已知限制: 属性段用 `[^>]*` 匹配,当属性值含 > 时(如 title="a>b")正则会提前截断,
     * 实际 LLM 输出罕见;如需精确解析应改用 XML/HTML 解析器,但项目不引入额外依赖,暂保持正则方案。
     */
    private val artifactTagRegex = Regex(
        """<artifact\b([^>]*)>(.*?)</artifact>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** 匹配单个属性 `name="value"` 或 `name='value'`,忽略大小写。 */
    private val attrRegex = Regex(
        """\b(title|type|language)\s*=\s*["']([^"']*)["']""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 抽取产物并替换占位符。
     *
     * @return Pair(替换后的内容, 产物实体列表)
     */
    fun extractArtifacts(
        sessionId: String,
        messageId: String,
        content: String,
        context: Context? = null,
    ): Pair<String, List<ArtifactEntity>> {
        val artifacts = mutableListOf<ArtifactEntity>()
        var replaced = content

        // 从后往前替换,避免前面替换改变后续 match 的索引
        val matches = artifactTagRegex.findAll(content).toList()
        if (matches.isEmpty()) return content to emptyList()

        matches.reversed().forEach { match ->
            val attrs = parseAttributes(match.groupValues[1])
            val artifactContent = match.groupValues[2]
            val id = Uuid.random().toString()
            val now = System.currentTimeMillis()
            val artifact = ArtifactEntity(
                id = id,
                sessionId = sessionId,
                messageId = messageId,
                title = attrs["title"] ?: context?.getString(R.string.artifact_default_title) ?: "Untitled",
                type = attrs["type"] ?: "document",
                content = artifactContent,
                language = attrs["language"],
                createdAt = now,
                updatedAt = now,
            )
            artifacts.add(artifact)
            replaced = replaced.replaceRange(match.range, "[artifact:$id]")
        }

        return replaced to artifacts.reversed()
    }

    private fun parseAttributes(tagOpening: String): Map<String, String> {
        return attrRegex.findAll(tagOpening).associate { result ->
            val name = result.groupValues[1].lowercase()
            val value = result.groupValues[2]
            name to value
        }
    }
}
