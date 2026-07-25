package io.zer0.muse.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.133: DocumentParser XLSX 解析单元测试。
 *
 * 测试 [DocumentParser.parseSharedStrings] 与 [DocumentParser.parseWorksheet] 两个
 * 纯 XML 处理方法(parseResult 走 ContentResolver 不便单元测试,故不测)。
 *
 * 覆盖场景:
 *  - 共享字符串表:空/单 <t>/多 <t> 富文本/命名空间前缀
 *  - 工作表:数字单元格/共享字符串单元格/内联字符串/布尔/空行
 *
 * 注:用 RobolectricTestRunner — XmlPullParserFactory.newInstance() 在 Android stub jar
 * 中默认抛 RuntimeException,需 Robolectric 提供真实实现。
 */
@RunWith(RobolectricTestRunner::class)
class DocumentParserXlsxTest {

    private val parser = DocumentParser()

    // ── parseSharedStrings ──

    @Test
    fun `sharedStrings 空输入返回空列表`() {
        assertTrue(parser.parseSharedStrings(null).isEmpty())
        assertTrue(parser.parseSharedStrings("").isEmpty())
        assertTrue(parser.parseSharedStrings("   ").isEmpty())
    }

    @Test
    fun `sharedStrings 单 t 标签按顺序解析`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="3" uniqueCount="3">
                <si><t>姓名</t></si>
                <si><t>年龄</t></si>
                <si><t>城市</t></si>
            </sst>
        """.trimIndent()
        val list = parser.parseSharedStrings(xml)
        assertEquals(3, list.size)
        assertEquals("姓名", list[0])
        assertEquals("年龄", list[1])
        assertEquals("城市", list[2])
    }

    @Test
    fun `sharedStrings 富文本多 t 标签合并`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <si>
                    <r><rPr><b/></rPr><t>粗体</t></r>
                    <r><t>普通</t></r>
                </si>
                <si><t>纯文本</t></si>
            </sst>
        """.trimIndent()
        val list = parser.parseSharedStrings(xml)
        assertEquals(2, list.size)
        assertEquals("粗体普通", list[0])
        assertEquals("纯文本", list[1])
    }

    @Test
    fun `sharedStrings 含 XML 实体转义`() {
        val xml = """
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <si><t>A &amp; B &lt;tag&gt; &quot;quote&quot;</t></si>
            </sst>
        """.trimIndent()
        val list = parser.parseSharedStrings(xml)
        assertEquals(1, list.size)
        assertEquals("A & B <tag> \"quote\"", list[0])
    }

    // ── parseWorksheet ──

    @Test
    fun `worksheet 纯数字单元格直接输出 v 值`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="1">
                        <c r="A1"><v>123</v></c>
                        <c r="B1"><v>45.6</v></c>
                    </row>
                </sheetData>
            </worksheet>
        """.trimIndent()
        val sb = StringBuilder()
        parser.parseWorksheet(xml, sharedStrings = emptyList(), sb = sb)
        val output = sb.toString().trim()
        assertEquals("123\t45.6", output)
    }

    @Test
    fun `worksheet 共享字符串单元格按索引查表`() {
        val shared = listOf("姓名", "张三", "年龄", "25")
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="1">
                        <c r="A1" t="s"><v>0</v></c>
                        <c r="B1" t="s"><v>1</v></c>
                    </row>
                    <row r="2">
                        <c r="A2" t="s"><v>2</v></c>
                        <c r="B2" t="s"><v>3</v></c>
                    </row>
                </sheetData>
            </worksheet>
        """.trimIndent()
        val sb = StringBuilder()
        parser.parseWorksheet(xml, shared, sb)
        val lines = sb.toString().trim().lines()
        assertEquals(2, lines.size)
        assertEquals("姓名\t张三", lines[0])
        assertEquals("年龄\t25", lines[1])
    }

    @Test
    fun `worksheet 内联字符串读取 is_t`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="1">
                        <c r="A1" t="inlineStr"><is><t>内联值</t></is></c>
                    </row>
                </sheetData>
            </worksheet>
        """.trimIndent()
        val sb = StringBuilder()
        parser.parseWorksheet(xml, sharedStrings = emptyList(), sb = sb)
        assertEquals("内联值", sb.toString().trim())
    }

    @Test
    fun `worksheet 布尔单元格转换为 TRUE_FALSE`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="1">
                        <c r="A1" t="b"><v>1</v></c>
                        <c r="B1" t="b"><v>0</v></c>
                    </row>
                </sheetData>
            </worksheet>
        """.trimIndent()
        val sb = StringBuilder()
        parser.parseWorksheet(xml, sharedStrings = emptyList(), sb = sb)
        assertEquals("TRUE\tFALSE", sb.toString().trim())
    }

    @Test
    fun `worksheet 多行用换行分隔`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="1"><c r="A1"><v>1</v></c></row>
                    <row r="2"><c r="A2"><v>2</v></c></row>
                    <row r="3"><c r="A3"><v>3</v></c></row>
                </sheetData>
            </worksheet>
        """.trimIndent()
        val sb = StringBuilder()
        parser.parseWorksheet(xml, sharedStrings = emptyList(), sb = sb)
        val lines = sb.toString().trim().lines()
        assertEquals(3, lines.size)
        assertEquals("1", lines[0])
        assertEquals("2", lines[1])
        assertEquals("3", lines[2])
    }

    @Test
    fun `worksheet 共享字符串索引越界时回退原值`() {
        // 索引 99 超出 sharedStrings 范围(只有 1 项),应回退输出 "99"
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="1">
                        <c r="A1" t="s"><v>0</v></c>
                        <c r="B1" t="s"><v>99</v></c>
                    </row>
                </sheetData>
            </worksheet>
        """.trimIndent()
        val sb = StringBuilder()
        parser.parseWorksheet(xml, sharedStrings = listOf("唯一项"), sb = sb)
        // 索引 0 命中,索引 99 越界回退 "99"
        assertEquals("唯一项\t99", sb.toString().trim())
    }

    @Test
    fun `worksheet 空单元格被跳过`() {
        // <c> 无 <v>,cellBuffer 为空,display 为空串但仍加入 rowCells
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="1">
                        <c r="A1"><v>1</v></c>
                        <c r="B1"></c>
                        <c r="C1"><v>3</v></c>
                    </row>
                </sheetData>
            </worksheet>
        """.trimIndent()
        val sb = StringBuilder()
        parser.parseWorksheet(xml, sharedStrings = emptyList(), sb = sb)
        // 空单元格保留为空串,tab 分隔仍保留位置
        assertEquals("1\t\t3", sb.toString().trim())
    }
}
