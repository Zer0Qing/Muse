package io.zer0.muse.data.sticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 图床链接清单的提取（表情包重做 P1）。 */
class StickerUrlListTest {

    @Test
    fun `sample lines from a real pack are harvested with trailing punctuation stripped`() {
        val text = "https://pic1.imgdb.cn/i/0340ii7sLVIjUexlwEHfnf.jpg（小猫额头贴着创可贴）"

        val urls = StickerUrlList.harvest(text)

        assertEquals(listOf("https://pic1.imgdb.cn/i/0340ii7sLVIjUexlwEHfnf.jpg"), urls)
    }

    @Test
    fun `duplicates collapse and order is kept`() {
        val text = """
            https://a.example/x/1.png 第一
            https://a.example/x/2.png 第二
            https://a.example/x/1.png 又出现
        """.trimIndent()

        assertEquals(listOf("https://a.example/x/1.png", "https://a.example/x/2.png"), StickerUrlList.harvest(text))
    }

    @Test
    fun `page links are not image links`() {
        assertFalse(StickerUrlList.isImageUrl("https://example.com/note/123"))
        assertTrue(StickerUrlList.isImageUrl("https://example.com/a/b.GIF"))
        assertTrue("图床 id 形式没有扩展名也算", StickerUrlList.isImageUrl("https://pic.imgdb.cn/i/abcdef"))
    }

    @Test
    fun `extension only mode refuses hosted paths`() {
        assertFalse(StickerUrlList.isImageUrl("https://pic.imgdb.cn/i/abcdef", requireExtension = true))
    }

    @Test
    fun `non http schemes are never harvested`() {
        assertTrue(StickerUrlList.harvest("file:///etc/passwd ftp://a/b.png").isEmpty())
    }

    @Test
    fun `the harvest is capped`() {
        val text = (1..StickerUrlList.MAX_URLS + 50).joinToString(" ") { "https://a.example/i/$it.png" }

        assertEquals(StickerUrlList.MAX_URLS, StickerUrlList.harvest(text).size)
    }
}
