package io.zer0.muse.ui.artifact

import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.ui.markdown.MarkdownBlock
import io.zer0.muse.ui.markdown.parseMarkdown
import java.security.MessageDigest

/**
 * 富卡片（svg/html/chart 围栏代码块）与产物中心的同步。
 *
 * 渲染侧只把这三类围栏块当卡片显示，但产物中心过去只在用户手动点「保存」时才入库，
 * 于是生成出来的卡片绝大多数没有出现在产物中心。这里在流式收尾时按同一判定规则补一次
 * 自动入库，手动保存与自动入库共用 [id]，同一张卡不会变成两条产物。
 */
object RichCardArtifacts {

    private val CARD_LANGUAGES = setOf("svg", "html", "chart")

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

    /** 按渲染侧同一规则抽出消息里的富卡片；空白块不入库。 */
    fun extract(sessionId: String, messageId: String, content: String): List<ArtifactEntity> {
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
                    title = lang.uppercase(),
                    type = "rich_card",
                    content = block.code,
                    language = lang,
                    createdAt = now,
                    updatedAt = now,
                )
            }
    }
}
