package io.zer0.muse.conversation.render

import io.zer0.muse.conversation.core.MarkdownBlockParser
import io.zer0.muse.conversation.core.RenderBlock
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser

/**
 * Converts a GFM syntax tree into immutable render blocks. Block identity is
 * based on source offsets, so appending to a stream does not invalidate text
 * that has already been parsed.
 */
class MarkdownRenderParser : MarkdownBlockParser {
    private val parser =
        MarkdownParser(
            flavour = GFMFlavourDescriptor(),
            cancellationToken = CancellationToken {},
        )

    override fun parse(text: String, complete: Boolean): List<RenderBlock> {
        if (text.isEmpty()) return emptyList()
        val source = if (complete) text else closeOpenFence(text)
        val root = parser.buildMarkdownTreeFromString(source as CharSequence)
        return root.children.mapNotNull { node ->
            convert(node, source, complete)
        }
    }

    private fun convert(node: ASTNode, source: String, complete: Boolean): RenderBlock? {
        if (node.type == MarkdownTokenTypes.EOL || node.type == MarkdownTokenTypes.WHITE_SPACE) {
            return null
        }
        val blockText = source.substring(node.startOffset, node.endOffset)
        return when (node.type) {
            MarkdownElementTypes.CODE_FENCE -> {
                val language =
                    node.children
                        .firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }
                        ?.let { source.substring(it.startOffset, it.endOffset).trim() }
                        ?.substringBefore(' ')
                        ?.takeIf { it.isNotEmpty() }
                RenderBlock.Code(
                    blockId = "block:${node.startOffset}",
                    sourceStart = node.startOffset,
                    sourceEnd = node.endOffset,
                    text = blockText.trimEnd(),
                    language = language,
                    complete = complete,
                )
            }
            else ->
                RenderBlock.Paragraph(
                    blockId = "block:${node.startOffset}",
                    sourceStart = node.startOffset,
                    sourceEnd = node.endOffset,
                    text = blockText.trim(),
                )
        }
    }

    private fun closeOpenFence(source: String): String {
        val lines = source.split('\n')
        val openFence =
            lines.asSequence()
                .map { it.trimStart() }
                .filter { it.startsWith("```") }
                .lastOrNull()
        if (openFence == null) return source
        val marker = openFence.takeWhile { it == '`' }
        val fenceCount = lines.count { it.trimStart().startsWith(marker) }
        return if (fenceCount % 2 == 0) source else "$source\n$marker"
    }
}
