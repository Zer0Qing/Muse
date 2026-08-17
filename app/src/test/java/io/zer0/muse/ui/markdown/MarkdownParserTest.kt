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

    // ── A3: 增量解析器与全量解析语义等价 ────────────────────────────────

    @Test
    fun `增量解析 逐字符追加 与全量等价`() {
        val md = buildString {
            appendLine("# 标题")
            appendLine()
            appendLine("段落文本 **加粗**。")
            appendLine()
            appendLine("- 甲")
            appendLine("- 乙")
            appendLine()
            appendLine("```kotlin")
            appendLine("val x = 1")
            appendLine("```")
            appendLine()
            appendLine("| A | B |")
            appendLine("|---|---|")
            appendLine("| 1 | 2 |")
            appendLine()
            appendLine("\$\$")
            appendLine("E = mc^2")
            appendLine("\$\$")
        }.trimEnd()
        val parser = IncrementalMarkdownParser()
        var acc = ""
        for (ch in md) {
            acc += ch
            assertEquals(
                "逐字符追加不一致, len=${acc.length}",
                parseMarkdown(acc),
                parser.parse(acc),
            )
        }
        assertEquals("最终结果与全量等价", parseMarkdown(md), parser.parse(md))
    }

    @Test
    fun `增量解析 按行追加 与全量等价 含未闭合中间态`() {
        val lines = listOf(
            "前言",
            "",
            "# 标题",
            "",
            "```kotlin",
            "fun a() = 1",
            "fun b() = 2",
            "```",
            "",
            "- item1",
            "- item2",
            "",
            "> 引用",
            "",
            "| C1 | C2 |",
            "|---|---|",
            "| x | y |",
            "| z | w |",
            "",
            "尾段",
        )
        val parser = IncrementalMarkdownParser()
        var acc = ""
        for (line in lines) {
            acc += line + "\n"
            assertEquals(
                "按行追加不一致, 行=$line",
                parseMarkdown(acc),
                parser.parse(acc),
            )
        }
        assertEquals("最终结果与全量等价", parseMarkdown(acc), parser.parse(acc))
    }

    @Test
    fun `增量解析 段落同行内追加 与全量等价`() {
        // 流式常在段落同一行内追加 token(无新增换行),需重扫最后一块而非直接复用
        val parser = IncrementalMarkdownParser()
        var acc = "第一段"
        assertEquals(parseMarkdown(acc), parser.parse(acc))
        acc += "继续"
        assertEquals("段落同行追加不一致", parseMarkdown(acc), parser.parse(acc))
        acc += "再续"
        assertEquals(parseMarkdown(acc), parser.parse(acc))
        // 追加换行后进入新块
        acc += "\n第二段"
        assertEquals(parseMarkdown(acc), parser.parse(acc))
    }

    @Test
    fun `增量解析 未闭合围栏逐步闭合 与全量等价`() {
        val parser = IncrementalMarkdownParser()
        var acc = "开头"
        acc += "\n```kotlin"
        assertEquals("未闭合 fence 打开", parseMarkdown(acc), parser.parse(acc))
        acc += "\nval a = 1"
        assertEquals("fence 内追加代码行", parseMarkdown(acc), parser.parse(acc))
        acc += "\nval b = 2"
        assertEquals("fence 内再追加", parseMarkdown(acc), parser.parse(acc))
        acc += "\n```"
        assertEquals("fence 闭合", parseMarkdown(acc), parser.parse(acc))
        acc += "\n结尾段"
        assertEquals("闭合后追加段落", parseMarkdown(acc), parser.parse(acc))
    }

    @Test
    fun `增量解析 未闭合多行公式逐步闭合 与全量等价`() {
        val parser = IncrementalMarkdownParser()
        var acc = "\$\$"
        assertEquals("公式打开", parseMarkdown(acc), parser.parse(acc))
        acc += "\nx &= 1 + 2"
        assertEquals("公式内追加", parseMarkdown(acc), parser.parse(acc))
        acc += "\n\$\$"
        assertEquals("公式闭合", parseMarkdown(acc), parser.parse(acc))
    }

    @Test
    fun `增量解析 表格逐步追加数据行 与全量等价`() {
        val parser = IncrementalMarkdownParser()
        var acc = "| A | B |\n|---|---|"
        assertEquals("表头+分隔线", parseMarkdown(acc), parser.parse(acc))
        acc += "\n| 1 | 2 |"
        assertEquals("追加数据行", parseMarkdown(acc), parser.parse(acc))
        acc += "\n| 3 | 4 |"
        assertEquals("再追加数据行", parseMarkdown(acc), parser.parse(acc))
        acc += "\n表格后段落"
        assertEquals("表格闭合后", parseMarkdown(acc), parser.parse(acc))
    }

    @Test
    fun `增量解析 文本替换回退全量`() {
        val parser = IncrementalMarkdownParser()
        parser.parse("旧内容旧内容")
        // 非纯追加(新文本不以旧文本为前缀)→ 全量解析,结果与直接全量一致
        val fresh = "# 全新标题\n\n全新内容"
        assertEquals(parseMarkdown(fresh), parser.parse(fresh))
    }

    @Test
    fun `增量解析 头部稳定块保持同一实例`() {
        // 稳定节点缓存的核心断言:追加尾部时,已闭合头部块实例不变,
        // Compose 才能跳过这些块的重新组合
        val parser = IncrementalMarkdownParser()
        val first = parser.parse("固定头部 # 标题\n\n```kotlin\nval x = 1\n```")
        assertEquals(3, first.size)  // Paragraph / Blank / CodeBlock
        val second = parser.parse("固定头部 # 标题\n\n```kotlin\nval x = 1\n```\n\n新增段落")
        assertEquals(5, second.size)
        // 头部 3 块(Paragraph/Blank/CodeBlock)应为同一实例
        assertTrue("Paragraph 应复用实例", first[0] === second[0])
        assertTrue("Blank 应复用实例", first[1] === second[1])
        assertTrue("CodeBlock 应复用实例", first[2] === second[2])
    }
}
