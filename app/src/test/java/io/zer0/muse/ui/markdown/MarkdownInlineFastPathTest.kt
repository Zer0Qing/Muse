package io.zer0.muse.ui.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownInlineFastPathTest {
    @Test
    fun `plain paragraph has no inline syntax`() {
        assertFalse(hasInlineMarkdownSyntax("普通纯文本，没有任何格式标记。"))
        assertFalse(hasInlineMarkdownSyntax("Let's ship version 1.2 today."))
        assertTrue(hasInlineMarkdownSyntax("算术表达式 2 * 3 = 6"))
        assertTrue(hasInlineMarkdownSyntax("数组索引 array[0]"))
    }

    @Test
    fun `formatted links citations and urls keep the full inline pipeline`() {
        assertTrue(hasInlineMarkdownSyntax("hello **world**"))
        assertTrue(hasInlineMarkdownSyntax("hello `code`"))
        assertTrue(hasInlineMarkdownSyntax("hello *italic*"))
        assertTrue(hasInlineMarkdownSyntax("hello [link](https://example.com)"))
        assertTrue(hasInlineMarkdownSyntax("hello https://example.com"))
        assertTrue(hasInlineMarkdownSyntax("hello [1]", hasCitations = true))
    }

    @Test
    fun `only annotated urls require link gesture handling`() {
        assertFalse(hasUrlAnnotations(parseInline("plain paragraph")))
        assertFalse(hasUrlAnnotations(parseInline("bold **only**")))
        assertTrue(hasUrlAnnotations(parseInline("open https://example.com")))
        assertTrue(hasUrlAnnotations(parseInline("open [link](https://example.com)")))
    }
}
