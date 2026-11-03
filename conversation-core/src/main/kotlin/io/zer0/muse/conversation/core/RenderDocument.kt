package io.zer0.muse.conversation.core

sealed interface RenderBlock {
    val blockId: String
    val sourceStart: Int
    val sourceEnd: Int
    val text: String

    data class Paragraph(
        override val blockId: String,
        override val sourceStart: Int,
        override val sourceEnd: Int,
        override val text: String,
    ) : RenderBlock

    data class Code(
        override val blockId: String,
        override val sourceStart: Int,
        override val sourceEnd: Int,
        override val text: String,
        val language: String?,
        val complete: Boolean,
    ) : RenderBlock
}

data class RenderDocument(
    val partId: String,
    val revision: Long,
    val blocks: List<RenderBlock>,
)

fun interface MarkdownBlockParser {
    fun parse(text: String, complete: Boolean): List<RenderBlock>
}

class RenderDocumentStore(
    private val parser: MarkdownBlockParser = LineBlockParser(),
) {
    private val previousBlocks = mutableMapOf<String, List<RenderBlock>>()
    private var revision = 0L

    fun update(partId: String, text: String, complete: Boolean): RenderDocument {
        revision++
        val old = previousBlocks[partId].orEmpty()
        val parsed = parser.parse(text, complete)
        val reused =
            parsed.mapIndexed { index, block ->
                val oldBlock = old.getOrNull(index)
                if (oldBlock != null && oldBlock.sourceStart == block.sourceStart &&
                    oldBlock.sourceEnd == block.sourceEnd && oldBlock.text == block.text
                ) {
                    oldBlock
                } else {
                    block
                }
            }
        previousBlocks[partId] = reused
        return RenderDocument(partId, revision, reused)
    }
}

private class LineBlockParser : MarkdownBlockParser {
    override fun parse(text: String, complete: Boolean): List<RenderBlock> {
        if (text.isEmpty()) return emptyList()
        val chunks = text.split("\n\n")
        var offset = 0
        return chunks.mapIndexed { index, chunk ->
            val start = offset
            val end = start + chunk.length
            offset = end + 2
            if (chunk.trimStart().startsWith("```")) {
                val firstLine = chunk.lineSequence().first()
                RenderBlock.Code(
                    blockId = "part:$start",
                    sourceStart = start,
                    sourceEnd = end,
                    text = chunk,
                    language = firstLine.removePrefix("```").trim().ifBlank { null },
                    complete = complete,
                )
            } else {
                RenderBlock.Paragraph(
                    blockId = "part:$start",
                    sourceStart = start,
                    sourceEnd = end,
                    text = chunk,
                )
            }
        }
    }
}
