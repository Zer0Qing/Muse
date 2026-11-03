package io.zer0.muse.data.sticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 素材来源识别（表情包重做 P1）。
 *
 * 判据只用文件名与头部字节：用户从微信存下来的文件经常没有扩展名，
 * 或者扩展名和内容不一致，所以**魔数优先**。
 */
class StickerImportFormatTest {

    private fun head(vararg bytes: Int): ByteArray = ByteArray(bytes.size) { bytes[it].toByte() }

    @Test
    fun `magic bytes win over file names`() {
        val png = head(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertEquals(StickerSourceKind.IMAGE, StickerImportFormat.detect("photo.txt", png))
    }

    @Test
    fun `the two container families are told apart`() {
        assertEquals(StickerSourceKind.PDF, StickerImportFormat.detect("a.pdf", "%PDF-1.6".toByteArray()))
        assertEquals(StickerSourceKind.ZIP, StickerImportFormat.detect("a.zip", head(0x50, 0x4B, 0x03, 0x04)))
    }

    @Test
    fun `riff audio is not mistaken for webp`() {
        // `RIFF` 开头也可能是 wav/avi：必须看偏移 8 的 `WEBP`
        val wav = "RIFF".toByteArray() + byteArrayOf(0x00, 0x01, 0x02, 0x03) + "WAVEfmt ".toByteArray()
        assertEquals(StickerSourceKind.UNKNOWN, StickerImportFormat.detect(null, wav))

        val webp = "RIFF".toByteArray() + byteArrayOf(0x00, 0x01, 0x02, 0x03) + "WEBP".toByteArray()
        assertEquals(StickerSourceKind.IMAGE, StickerImportFormat.detect(null, webp))
    }

    @Test
    fun `a zip container is refined by its entry names`() {
        assertEquals(
            StickerSourceKind.DOCX,
            StickerImportFormat.refineOfficeContainer(listOf("[Content_Types].xml", "word/document.xml")),
        )
        assertEquals(
            StickerSourceKind.PPTX,
            StickerImportFormat.refineOfficeContainer(listOf("ppt/slides/slide1.xml")),
        )
        assertEquals(
            "普通素材包不该被当成文档解",
            StickerSourceKind.ZIP,
            StickerImportFormat.refineOfficeContainer(listOf("猫猫/001.png", "猫猫/002.png")),
        )
    }

    @Test
    fun `extension fallback works when there is no signature to read`() {
        assertEquals(StickerSourceKind.IMAGE, StickerImportFormat.detect("x.GIF", ByteArray(0)))
        assertEquals(StickerSourceKind.PDF, StickerImportFormat.detect("x.pdf", ByteArray(0)))
        assertEquals(StickerSourceKind.UNKNOWN, StickerImportFormat.detect("x.exe", ByteArray(0)))
        assertEquals(StickerSourceKind.UNKNOWN, StickerImportFormat.detect(null, ByteArray(0)))
    }

    @Test
    fun `a text blob holding image links counts as a url list`() {
        assertTrue(StickerImportFormat.looksLikeUrlList("看这个 https://pic.imgdb.cn/i/abc.jpg 很好笑"))
        assertFalse("普通网页链接不算素材", StickerImportFormat.looksLikeUrlList("去 https://example.com/note/1 看看"))
    }
}
