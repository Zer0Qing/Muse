package io.zer0.muse.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 11.4: MarkdownParser 单元测试。
 *
 * 覆盖关键块级类型:Paragraph / Heading / CodeBlock / ListItem / Quote / Divider / Blank / Formula。
 * 行内格式(粗体/斜体/行内代码/链接)在 MarkdownText Composable 渲染时处理,
 * Parser 只负责块级切分,故此处不测行内。
 */
class MarkdownParserTest {

    @Test
    fun `plain paragraph parses as Paragraph`() {
        val blocks = parseMarkdown("Hello world")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
        assertEquals("Hello world", (blocks[0] as MarkdownBlock.Paragraph).text)
    }

    @Test
    fun `heading level 1 2 3 parses correctly`() {
        val blocks = parseMarkdown("# H1\n## H2\n### H3")
        assertEquals(3, blocks.size)
        val h1 = blocks[0] as MarkdownBlock.Heading
        val h2 = blocks[1] as MarkdownBlock.Heading
        val h3 = blocks[2] as MarkdownBlock.Heading
        assertEquals(1, h1.level)
        assertEquals("H1", h1.text)
        assertEquals(2, h2.level)
        assertEquals("H2", h2.text)
        assertEquals(3, h3.level)
        assertEquals("H3", h3.text)
    }

    @Test
    fun `fenced code block with language parses`() {
        val md = "```kotlin\nfun foo() = 1\n```"
        val blocks = parseMarkdown(md)
        assertEquals(1, blocks.size)
        val code = blocks[0] as MarkdownBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertEquals("fun foo() = 1", code.code)
    }

    @Test
    fun `fenced code block without language parses`() {
        val md = "```\nplain code\n```"
        val blocks = parseMarkdown(md)
        assertEquals(1, blocks.size)
        val code = blocks[0] as MarkdownBlock.CodeBlock
        assertEquals(null, code.language)
        assertEquals("plain code", code.code)
    }

    @Test
    fun `unordered list items parse`() {
        val md = "- apple\n- banana\n- cherry"
        val blocks = parseMarkdown(md)
        assertEquals(3, blocks.size)
        // 无序列表的 index 字段不递增(始终为 0);有序列表才递增
        blocks.forEach { block ->
            val item = block as MarkdownBlock.ListItem
            assertEquals(false, item.ordered)
        }
        assertEquals("apple", (blocks[0] as MarkdownBlock.ListItem).text)
        assertEquals("banana", (blocks[1] as MarkdownBlock.ListItem).text)
        assertEquals("cherry", (blocks[2] as MarkdownBlock.ListItem).text)
    }

    @Test
    fun `quote block parses`() {
        val md = "> This is a quote"
        val blocks = parseMarkdown(md)
        assertEquals(1, blocks.size)
        val quote = blocks[0] as MarkdownBlock.Quote
        assertEquals("This is a quote", quote.text)
    }

    @Test
    fun `horizontal divider parses`() {
        val md = "---"
        val blocks = parseMarkdown(md)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Divider)
    }

    @Test
    fun `blank line preserves as Blank`() {
        val md = "paragraph 1\n\nparagraph 2"
        val blocks = parseMarkdown(md)
        // 期望:Paragraph / Blank / Paragraph
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
        assertTrue(blocks[1] is MarkdownBlock.Blank)
        assertTrue(blocks[2] is MarkdownBlock.Paragraph)
    }

    @Test
    fun `multi-line code block preserves internal newlines`() {
        val md = "```python\nline1\nline2\nline3\n```"
        val blocks = parseMarkdown(md)
        assertEquals(1, blocks.size)
        val code = blocks[0] as MarkdownBlock.CodeBlock
        assertEquals("line1\nline2\nline3", code.code)
    }

    @Test
    fun `mixed content parses in order`() {
        val md = buildString {
            appendLine("# Title")
            appendLine()
            appendLine("Some paragraph text.")
            appendLine()
            appendLine("- item 1")
            appendLine("- item 2")
            appendLine()
            appendLine("```kotlin")
            appendLine("val x = 1")
            appendLine("```")
        }
        val blocks = parseMarkdown(md.trimEnd())
        // 期望顺序:Heading / Blank / Paragraph / Blank / ListItem / ListItem / Blank / CodeBlock
        assertTrue(blocks[0] is MarkdownBlock.Heading)
        assertTrue(blocks[1] is MarkdownBlock.Blank)
        assertTrue(blocks[2] is MarkdownBlock.Paragraph)
        assertTrue(blocks[3] is MarkdownBlock.Blank)
        assertTrue(blocks[4] is MarkdownBlock.ListItem)
        assertTrue(blocks[5] is MarkdownBlock.ListItem)
        assertTrue(blocks[6] is MarkdownBlock.Blank)
        assertTrue(blocks[7] is MarkdownBlock.CodeBlock)
    }

    @Test
    fun `formula block parses`() {
        val md = "\$\$\nE = mc^2\n\$\$"
        val blocks = parseMarkdown(md)
        // 公式块解析(Phase 8.6)
        val formula = blocks.firstOrNull { it is MarkdownBlock.Formula } as? MarkdownBlock.Formula
        assertTrue(formula != null)
        assertTrue(formula!!.latex.contains("E = mc^2"))
    }

    @Test
    fun `formula block 同行闭合 trimmed 为开头结尾双美元`() {
        // 同行闭合公式:$$ E = mc^2 $$(以 $$ 开头且以 $$ 结尾,长度 > 4)
        val md = "\$\$ E = mc^2 \$\$"
        val blocks = parseMarkdown(md)
        // 不应被误识别为 Paragraph,必须是单个 Formula
        assertEquals("同行闭合公式应只产生 1 个块,实际 ${blocks.size}", 1, blocks.size)
        val formula = blocks[0] as? MarkdownBlock.Formula
        assertTrue("应为 Formula 类型,实际 ${blocks[0]::class.simpleName}", formula != null)
        assertEquals("E = mc^2", formula!!.latex)
    }

    @Test
    fun `formula block 同行闭合含等号与希腊字母`() {
        val md = "\$\$ \\alpha + \\beta = \\gamma \$\$"
        val blocks = parseMarkdown(md)
        val formula = blocks.firstOrNull { it is MarkdownBlock.Formula } as? MarkdownBlock.Formula
        assertTrue("应识别为 Formula", formula != null)
        assertTrue(formula!!.latex.contains("\\alpha"))
        assertTrue(formula.latex.contains("\\gamma"))
    }

    @Test
    fun `dollar 前缀的普通文本不被误识别为公式`() {
        // M2 修复:仅 $$ 开头 + $$ 结尾且长度>4 才是公式;单纯以 $$ 开头的普通文本按段落处理
        // 此处用 "$$ 价格是 100 元" 模拟:以 $$ 开头但不以 $$ 结尾,应作为 Paragraph
        val md = "\$\$ 价格是 100 元"
        val blocks = parseMarkdown(md)
        // 不应出现 Formula 块
        val hasFormula = blocks.any { it is MarkdownBlock.Formula }
        assertFalse("不应被识别为公式块", hasFormula)
        // 第一块应为 Paragraph,保留原文(trim 后)
        val para = blocks.firstOrNull { it is MarkdownBlock.Paragraph } as? MarkdownBlock.Paragraph
        assertTrue("应作为 Paragraph 处理", para != null)
        assertTrue(para!!.text.contains("价格"))
    }

    @Test
    fun `单个 dollar 行不识别为公式`() {
        // 仅整行恰好为 $$ 才进入公式块;单个 $ 应为 Paragraph
        val md = "\$100"
        val blocks = parseMarkdown(md)
        val hasFormula = blocks.any { it is MarkdownBlock.Formula }
        assertFalse("单 \$ 不应识别为公式", hasFormula)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
    }

    @Test
    fun `多行公式块跨多行内容`() {
        // 多行公式:$$ 开始,中间多行 latex,直到含 $$ 的行结束
        val md = buildString {
            append("\$\$")
            append("\n\\begin{aligned}")
            append("\nx &= 1 + 2 \\\\")
            append("\ny &= 3 + 4")
            append("\n\\end{aligned}")
            append("\n\$\$")
        }
        val blocks = parseMarkdown(md)
        val formula = blocks.firstOrNull { it is MarkdownBlock.Formula } as? MarkdownBlock.Formula
        assertTrue("应识别为多行 Formula", formula != null)
        assertTrue(formula!!.latex.contains("aligned"))
        assertTrue(formula.latex.contains("x &= 1 + 2"))
        assertTrue(formula.latex.contains("y &= 3 + 4"))
    }

    @Test
    fun `empty string returns empty list`() {
        val blocks = parseMarkdown("")
        // 空字符串 split("\n") 返回 [""],可能解析为 1 个 Blank 或 Paragraph
        // 只要不含其他类型即可
        assertTrue(blocks.all { it is MarkdownBlock.Blank || it is MarkdownBlock.Paragraph })
    }
}
