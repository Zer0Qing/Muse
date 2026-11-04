package io.zer0.muse.ui

import io.zer0.muse.transformer.InternalMarkupSanitizer
import io.zer0.muse.transformer.MoodSkinParser

enum class ChatRenderRole {
    User,
    Assistant,
}

data class ChatRenderChunk(
    val key: String,
    val text: String,
    val index: Int,
    val count: Int,
    val sourceStart: Int,
    val sourceEnd: Int,
)

/**
 * Splits only completed, non-interactive assistant bodies into bounded display
 * units. Each unit can be measured independently by the message LazyColumn.
 */
object ChatRenderChunks {
    val maxChunkCharacters: Int
        get() = 8_000

    fun project(
        messageId: String,
        content: String,
        role: ChatRenderRole,
        isStreaming: Boolean,
        hasRichContent: Boolean,
    ): List<ChatRenderChunk> {
        require(messageId.isNotBlank()) { "message id must not be blank" }
        if (
            role != ChatRenderRole.Assistant ||
            isStreaming ||
            hasRichContent ||
            !isPlainProse(content) ||
            content.length <= maxChunkCharacters
        ) {
            return listOf(
                ChatRenderChunk(
                    key = "$messageId:body:0",
                    text = content,
                    index = 0,
                    count = 1,
                    sourceStart = 0,
                    sourceEnd = content.length,
                ),
            )
        }
        val parts = split(content)
        var sourceStart = 0
        return parts.mapIndexed { index, text ->
            ChatRenderChunk(
                key = "$messageId:body:$index",
                text = text,
                index = index,
                count = parts.size,
                sourceStart = sourceStart,
                sourceEnd = sourceStart + text.length,
            )
                .also { sourceStart += text.length }
        }
    }

    /**
     * Markdown blocks, internal protocol tags, citations and URLs rely on the
     * complete source. Hard-splitting them changes meaning or drops rendering
     * context, so only plain prose may use the bounded lazy-row path.
     */
    // 守卫链：每个提前返回都带一条**不同的**人类可读原因（策略/谓词的共同形状）。
    // 拧成单一返回会把原因塞进可空值再层层解包，读的人反而看不出谁在挡 ——
    // 这是规则的已知误报（阈值 4），逐点豁免并把理由留在这里。
    @Suppress("ReturnCount")
    private fun isPlainProse(content: String): Boolean {
        if (content.isBlank()) return true
        val display = InternalMarkupSanitizer.displayContent(content)
        if (display.trim() != content.trim()) return false
        if (MoodSkinParser.containsInlineEffect(display)) return false
        if ("http://" in display || "https://" in display) return false
        if (CITATION_MARKER.containsMatchIn(display)) return false
        if (display.contains("```") || display.contains("~~~")) return false
        if (display.contains("<svg", ignoreCase = true)) return false
        if (display.contains("<html", ignoreCase = true)) return false
        if (display.contains("<script", ignoreCase = true)) return false
        if (HTML_STRUCTURE_TAG.containsMatchIn(display)) return false
        if (display.contains("$$") || display.contains("\\[")) return false
        if (STRUCTURAL_LINE.containsMatchIn(display)) return false
        return true
    }

    private val CITATION_MARKER = Regex("\\[\\d+]")
    private val HTML_STRUCTURE_TAG = Regex(
        "<\\/?(?:table|thead|tbody|tr|td|th|div|section|article|details|summary|style|form|iframe|canvas)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val STRUCTURAL_LINE = Regex(
        "(?m)^\\s*(?:#{1,6}\\s|>|\\||!\\[|\\[\\[sticker:|(?:[-*+] |\\d+\\. )).*",
    )

    private fun split(content: String): List<String> {
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < content.length) {
            val hardEnd = (start + maxChunkCharacters).coerceAtMost(content.length)
            val end =
                if (hardEnd == content.length) {
                    hardEnd
                } else {
                    val paragraphBoundary = content.lastIndexOf("\n\n", hardEnd - 1)
                    val lineBoundary = content.lastIndexOf('\n', hardEnd - 1)
                    when {
                        paragraphBoundary >= start + maxChunkCharacters / 2 -> paragraphBoundary + 2
                        lineBoundary >= start + maxChunkCharacters / 2 -> lineBoundary + 1
                        else -> hardEnd
                    }
                }
            chunks += content.substring(start, end)
            start = end
        }
        return chunks
    }
}
