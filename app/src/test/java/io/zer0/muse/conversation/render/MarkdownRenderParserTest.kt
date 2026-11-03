package io.zer0.muse.conversation.render

import io.zer0.muse.conversation.core.RenderBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRenderParserTest {
    private val parser = MarkdownRenderParser()

    @Test
    fun `gfm headings paragraphs and tables become stable blocks`() {
        val blocks =
            parser.parse(
                "# Title\n\nParagraph text\n\n| Name | Value |\n| --- | --- |\n| A | 1 |",
                complete = true,
            )

        assertEquals(3, blocks.size)
        assertEquals("# Title", blocks[0].text)
        assertTrue(blocks[2].text.contains("| A | 1 |"))
    }

    @Test
    fun `unclosed code fence is exposed as code while streaming`() {
        val blocks = parser.parse("```kotlin\nval answer = 42", complete = false)
        val code = blocks.single() as RenderBlock.Code

        assertEquals("kotlin", code.language)
        assertTrue(code.text.contains("val answer = 42"))
        assertEquals(false, code.complete)
    }

    @Test
    fun `appending a completed paragraph keeps prior block identity`() {
        val store =
            io.zer0.muse.conversation.core.RenderDocumentStore(MarkdownRenderParser())
        val first = store.update("part-1", "First paragraph", complete = false)
        val second = store.update("part-1", "First paragraph\n\nSecond paragraph", complete = false)

        assertTrue(first.blocks.first() === second.blocks.first())
    }
}
