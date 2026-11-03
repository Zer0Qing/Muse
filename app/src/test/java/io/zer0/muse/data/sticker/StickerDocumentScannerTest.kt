package io.zer0.muse.data.sticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 从 docx/pptx 里按阅读顺序取图与配文（表情包重做 P1）。
 *
 * 样本结构是"图片 → 图床链接 + 描述"严格交替，第一段是包名。
 * 顺序一旦错位，用户看到的就是"每张图配着别人的说明"，比不导更糟。
 */
class StickerDocumentScannerTest {

    private fun docxEntries(body: String, rels: String) = mapOf(
        "word/document.xml" to body.toByteArray(),
        "word/_rels/document.xml.rels" to rels.toByteArray(),
        "word/media/image1.png" to ByteArray(120) { 1 },
        "word/media/image2.png" to ByteArray(120) { 2 },
    )

    private fun imageBlock(rid: String) =
        """<w:p><w:r><w:drawing><inline><graphic><graphicData><pic><blipFill><a:blip r:embed="$rid"/>""" +
            """</blipFill></pic></graphicData></graphic></inline></drawing></w:r></w:p>"""

    private fun textBlock(text: String) = """<w:p><w:r><w:t>$text</w:t></w:r></w:p>"""

    private val relsBasic = """
        <?xml version="1.0"?><Relationships
        xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="image" Target="media/image1.png"/>
        <Relationship Id="rId2" Type="image" Target="media/image2.png"/>
        </Relationships>
    """.trimIndent()

    @Test
    fun `images keep document order and pick up the caption that follows`() {
        val body = "<w:document><w:body>" +
            textBlock("呆猫八条") +
            imageBlock("rId1") +
            textBlock("https://pic1.imgdb.cn/i/aaa.jpg（小猫歪头笑）") +
            imageBlock("rId2") +
            textBlock("小猫躲在墙后") +
            "</w:body></w:document>"

        val scanned = StickerDocumentScanner.scan(StickerSourceKind.DOCX, docxEntries(body, relsBasic))

        assertEquals("呆猫八条", scanned.title)
        assertEquals(2, scanned.candidates.size)
        assertEquals(listOf(0, 1), scanned.candidates.map { it.order })
        assertEquals(listOf("word/media/image1.png", "word/media/image2.png"), scanned.candidates.map { it.mediaPath })
        val first = scanned.candidates.first()
        assertEquals("https://pic1.imgdb.cn/i/aaa.jpg", first.sourceUrl)
        assertEquals("（小猫歪头笑）", first.caption)
        assertTrue("配文里不该再留链接", !first.caption.orEmpty().contains("http"))
    }

    @Test
    fun `a caption-less image still becomes a candidate`() {
        val body = "<w:body>" + imageBlock("rId1") + "</w:body>"

        val scanned = StickerDocumentScanner.scan(StickerSourceKind.DOCX, docxEntries(body, relsBasic))

        assertEquals(1, scanned.candidates.size)
        assertEquals(null, scanned.candidates.single().caption)
        assertEquals(null, scanned.candidates.single().sourceUrl)
    }

    @Test
    fun `absolute and escaping targets are normalized inside the package`() {
        val rels = """<Relationships>
            <Relationship Id="rId1" Target="/word/media/image1.png"/>
            <Relationship Id="rId2" Target="../media/image2.png"/>
            </Relationships>"""
        val body = "<w:body>" + imageBlock("rId1") + imageBlock("rId2") + "</w:body>"

        val scanned = StickerDocumentScanner.scan(StickerSourceKind.DOCX, docxEntries(body, rels))

        // 绝对写法与相对写法都要落到包内；`word/../media/` 越出 word 但仍在包根，
        // 关键是**不能出现 `..`**（那会让后面的读取跳出插件目录）
        assertEquals(
            listOf("word/media/image1.png", "media/image2.png"),
            scanned.candidates.map { it.mediaPath },
        )
        assertTrue(
            "规范化之后不能残留上级目录",
            scanned.candidates.none { ".." in it.mediaPath },
        )
    }

    @Test
    fun `external relationship targets are ignored`() {
        val rels = """<Relationships><Relationship Id="rId1" Target="https://example.com/a.png"/></Relationships>"""
        val body = "<w:body>" + imageBlock("rId1") + "</w:body>"

        val scanned = StickerDocumentScanner.scan(StickerSourceKind.DOCX, docxEntries(body, rels))

        assertTrue("外链不是包内媒体，不能当成候选图", scanned.candidates.isEmpty())
    }

    @Test
    fun `slides are walked in numeric order not lexical order`() {
        val entries = mapOf(
            "ppt/slides/slide1.xml" to ("<p:sld>" + imageBlock("rIdA") + "</p:sld>").toByteArray(),
            "ppt/slides/_rels/slide1.xml.rels" to
                """<Relationships><Relationship Id="rIdA" Target="../media/image1.png"/></Relationships>""".toByteArray(),
            "ppt/slides/slide2.xml" to ("<p:sld>" + imageBlock("rIdB") + "</p:sld>").toByteArray(),
            "ppt/slides/_rels/slide2.xml.rels" to
                """<Relationships><Relationship Id="rIdB" Target="../media/image2.png"/></Relationships>""".toByteArray(),
            "ppt/slides/slide10.xml" to ("<p:sld>" + imageBlock("rIdC") + "</p:sld>").toByteArray(),
            "ppt/slides/_rels/slide10.xml.rels" to
                """<Relationships><Relationship Id="rIdC" Target="../media/image3.png"/></Relationships>""".toByteArray(),
        )

        val scanned = StickerDocumentScanner.scan(StickerSourceKind.PPTX, entries)

        assertEquals(
            "slide10 必须排在 slide2 之后",
            listOf("ppt/media/image1.png", "ppt/media/image2.png", "ppt/media/image3.png"),
            scanned.candidates.map { it.mediaPath },
        )
    }

    @Test
    fun `zip path normalization drops dot segments`() {
        assertEquals("word/media/a.png", StickerDocumentScanner.normalizeZipPath("word/./media//a.png"))
        assertEquals("media/a.png", StickerDocumentScanner.normalizeZipPath("word/../media/a.png"))
        assertEquals("a.png", StickerDocumentScanner.normalizeZipPath("../a.png"))
    }
}
