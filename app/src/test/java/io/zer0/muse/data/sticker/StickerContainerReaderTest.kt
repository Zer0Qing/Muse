package io.zer0.muse.data.sticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Office 容器读取 + 文档扫描的合流测试（表情包重做 P2）。
 *
 * 这一层不需要 Android：容器就是一个 zip，条目表就是字节。
 * 之所以单独测，是因为上一轮文档导入接不上——问题出在"读到了哪些条目"还是
 * "扫描有没有认出图片"，只有把中间量断言出来才分得清。
 */
class StickerContainerReaderTest {

    private fun docxBytes(
        document: String,
        rels: String,
        media: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, Charsets.UTF_8).use { zos ->
            fun put(path: String, bytes: ByteArray) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(bytes)
                zos.closeEntry()
            }
            put("word/document.xml", document.toByteArray(Charsets.UTF_8))
            put("word/_rels/document.xml.rels", rels.toByteArray(Charsets.UTF_8))
            put("word/theme/theme1.xml", "<theme/>".toByteArray())
            put("word/media/image1.png", media["image1.png"] ?: byteArrayOf(1, 2, 3, 4))
            put("word/media/image2.png", media["image2.png"] ?: byteArrayOf(5, 6, 7, 8))
        }
        return out.toByteArray()
    }

    private val relsBasic = """
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="image" Target="media/image1.png"/>
          <Relationship Id="rId2" Type="image" Target="media/image2.png"/>
        </Relationships>
    """.trimIndent()

    private fun documentBasic() = """
        <w:document><w:body>
          <w:p><w:r><w:t>呆猫八条</w:t></w:r></w:p>
          <w:p><w:r><w:drawing><a:blip r:embed="rId1"/></w:drawing></w:r></w:p>
          <w:p><w:r><w:t>https://pic1.imgdb.cn/i/aaa.jpg（小猫歪头）</w:t></w:r></w:p>
          <w:p><w:r><w:drawing><a:blip r:embed="rId2"/></w:drawing></w:r></w:p>
          <w:p><w:r><w:t>小猫躲墙后</w:t></w:r></w:p>
        </w:body></w:document>
    """.trimIndent()

    @Test
    fun `the container keeps structure and media but drops unrelated parts`() {
        val container = StickerContainerReader.read(ByteArrayInputStream(docxBytes(documentBasic(), relsBasic)))

        assertNotNull("应当被认成文档容器", container)
        val entries = container!!.entries
        assertTrue("结构文件要在: ${entries.keys}", "word/document.xml" in entries)
        assertTrue("rels 要在: ${entries.keys}", "word/_rels/document.xml.rels" in entries)
        assertTrue("媒体要在: ${entries.keys}", "word/media/image1.png" in entries)
        assertEquals("容器类型", StickerSourceKind.DOCX, container.kind)
        assertTrue("主题文件不该收进来: ${entries.keys}", entries.keys.none { it.contains("theme") })
    }

    @Test
    fun `scan pairs each image with the text that follows it`() {
        val container = StickerContainerReader.read(ByteArrayInputStream(docxBytes(documentBasic(), relsBasic)))
        requireNotNull(container)

        val scanned = StickerDocumentScanner.scan(container.kind, container.entries)

        assertEquals("包名取正文首段: title=${scanned.title}", "呆猫八条", scanned.title)
        assertEquals(
            "两张图都该被扫出来（候选=${scanned.candidates}）",
            2,
            scanned.candidates.size,
        )
        assertEquals("word/media/image1.png", scanned.candidates[0].mediaPath)
        assertEquals("（小猫歪头）", scanned.candidates[0].caption)
        assertEquals("https://pic1.imgdb.cn/i/aaa.jpg", scanned.candidates[0].sourceUrl)
        assertEquals("小猫躲墙后", scanned.candidates[1].caption)
    }

    @Test
    fun `a plain zip container is refused so the caller can fall back`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, Charsets.UTF_8).use { zos ->
            zos.putNextEntry(ZipEntry("猫猫/001.png"))
            zos.write(byteArrayOf(1, 2, 3))
            zos.closeEntry()
        }

        val container = StickerContainerReader.read(ByteArrayInputStream(out.toByteArray()))

        assertEquals("普通素材包不是文档容器", null, container)
    }
}
