package io.zer0.muse.conversation.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RenderDocumentTest {
    @Test
    fun `unchanged prefix keeps block identity across append`() {
        val store = RenderDocumentStore()
        val first = store.update("part-1", "paragraph one\n\nparagraph two", complete = false)

        val second =
            store.update(
                "part-1",
                "paragraph one\n\nparagraph two\n\nparagraph three",
                complete = false,
            )

        assertSame(first.blocks.first(), second.blocks.first())
        assertEquals("paragraph three", (second.blocks.last() as RenderBlock.Paragraph).text)
    }

    @Test
    fun `replace invalidates block identity`() {
        val store = RenderDocumentStore()
        val first = store.update("part-1", "old", complete = false)
        val replaced = store.update("part-1", "new", complete = false)

        assertEquals(false, first.blocks.first() === replaced.blocks.first())
    }
}
