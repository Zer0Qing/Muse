package io.zer0.muse.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.53: DataCardParser 单元测试(指南 Phase 5 验收用例)。
 */
class DataCardParserTest {

    @Test
    fun `合法 bar 卡片解析成功`() {
        val md = """
            以下是数据:
            ```card
            {"type":"bar","title":"本周消息量","labels":["一","二","三"],"values":[12,30,18]}
            ```
        """.trimIndent()
        val card = DataCardParser.parse(md)
        assertTrue(card != null)
        assertEquals("bar", card?.type)
        assertEquals("本周消息量", card?.title)
        assertEquals(3, card?.labels?.size)
        assertEquals(12f, card?.values?.get(0))
    }

    @Test
    fun `line 与 donut 卡片也支持`() {
        assertTrue(DataCardParser.parseJson("""{"type":"line","labels":["a","b"],"values":[1,2]}""") != null)
        assertTrue(DataCardParser.parseJson("""{"type":"donut","labels":["a","b"],"values":[1,2]}""") != null)
    }

    @Test
    fun `非法 JSON 返回 null`() {
        assertNull(DataCardParser.parseJson("""{invalid json"""))
    }

    @Test
    fun `不支持的图表类型返回 null`() {
        assertNull(DataCardParser.parseJson("""{"type":"pie","labels":["a"],"values":[1]}"""))
    }

    @Test
    fun `labels 与 values 数量不一致返回 null`() {
        assertNull(DataCardParser.parseJson("""{"type":"bar","labels":["a","b"],"values":[1]}"""))
    }

    @Test
    fun `空值列表返回 null`() {
        assertNull(DataCardParser.parseJson("""{"type":"bar","labels":[],"values":[]}"""))
    }

    @Test
    fun `无卡片块返回 null`() {
        assertNull(DataCardParser.parse("# 普通文档\n没有卡片"))
    }

    @Test
    fun `containsCardBlock 检测`() {
        assertTrue(DataCardParser.containsCardBlock("```card\n{}```"))
        assertFalse(DataCardParser.containsCardBlock("普通文本"))
    }
}
