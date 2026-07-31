package io.zer0.muse.common.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.53: FrontmatterParser 单元测试(指南 Phase 0 验收用例)。
 */
class FrontmatterParserTest {

    @Test
    fun `完整 frontmatter 三字段解析正确`() {
        val md = """
            ---
            title: 我的文档
            cover: covers/hero.jpg
            description: 一句话描述
            ---
            # 正文标题
            正文内容
        """.trimIndent()

        val fm = FrontmatterParser.parse(md)
        assertTrue(fm != null)
        assertEquals("我的文档", fm?.title)
        assertEquals("covers/hero.jpg", fm?.cover)
        assertEquals("一句话描述", fm?.description)
        assertEquals(3, fm?.raw?.size)
    }

    @Test
    fun `无 frontmatter 文本返回 null 且 strip 原样返回`() {
        val md = "# 只有标题\n\n没有元数据"
        assertNull(FrontmatterParser.parse(md))
        assertEquals(md, FrontmatterParser.strip(md))
    }

    @Test
    fun `frontmatter 未闭合返回 null`() {
        val md = """
            ---
            title: 没有关闭
        """.trimIndent()
        assertNull(FrontmatterParser.parse(md))
    }

    @Test
    fun `带 BOM 正常解析`() {
        val md = "\uFEFF---\ntitle: BOM文档\n---\n正文"
        val fm = FrontmatterParser.parse(md)
        assertTrue(fm != null)
        assertEquals("BOM文档", fm?.title)
    }

    @Test
    fun `引号值去引号`() {
        val md = "---\ntitle: \"带引号标题\"\ncover: 'covers/x.jpg'\n---\n"
        val fm = FrontmatterParser.parse(md)
        assertEquals("带引号标题", fm?.title)
        assertEquals("covers/x.jpg", fm?.cover)
    }

    @Test
    fun `空值字段为 null`() {
        val md = "---\ntitle:\ncover: \n---\n正文"
        val fm = FrontmatterParser.parse(md)
        assertNull(fm?.title)
        assertNull(fm?.cover)
    }

    @Test
    fun `键大小写不敏感且去空格`() {
        val md = "---\n  Title : 大写键\n---\n"
        val fm = FrontmatterParser.parse(md)
        assertEquals("大写键", fm?.title)
    }

    @Test
    fun `不支持语法行被忽略`() {
        val md = """
            ---
            title: 正常
            tags:
              - a
              - b
            nested: { x: 1 }
            ---
            正文
        """.trimIndent()
        val fm = FrontmatterParser.parse(md)
        assertTrue(fm != null)
        assertEquals("正常", fm?.title)
        // tags/nested 不是合法 key: value 单行,应被忽略
        assertNull(fm?.raw?.get("tags"))
    }

    @Test
    fun `strip 只保留正文`() {
        val md = "---\ntitle: 文档\n---\n# 正文标题\n第一段"
        val body = FrontmatterParser.strip(md)
        assertTrue(body.startsWith("# 正文标题"))
        assertTrue(!body.contains("title:"))
    }

    @Test
    fun `withCover 无 frontmatter 时新建并从标题提取 title`() {
        val md = "# 我的文章\n\n正文"
        val out = FrontmatterParser.withCover(md, "covers/gen_001.jpg")
        val fm = FrontmatterParser.parse(out)
        assertEquals("我的文章", fm?.title)
        assertEquals("covers/gen_001.jpg", fm?.cover)
        // 正文保留
        assertTrue(out.contains("正文"))
    }

    @Test
    fun `withCover 已有 frontmatter 时替换 cover 行`() {
        val md = "---\ntitle: 旧文档\ncover: covers/old.jpg\n---\n正文"
        val out = FrontmatterParser.withCover(md, "covers/new.jpg")
        val fm = FrontmatterParser.parse(out)
        assertEquals("covers/new.jpg", fm?.cover)
        assertEquals("旧文档", fm?.title)
        // 不产生重复 cover 行
        val coverLines = out.lineSequence().filter { it.trimStart().startsWith("cover:") }.count()
        assertEquals(1, coverLines)
    }

    @Test
    fun `withCover 已有 frontmatter 无 cover 时插入`() {
        val md = "---\ntitle: 无封面文档\n---\n正文"
        val out = FrontmatterParser.withCover(md, "covers/added.jpg")
        val fm = FrontmatterParser.parse(out)
        assertEquals("covers/added.jpg", fm?.cover)
        assertEquals("无封面文档", fm?.title)
    }
}
