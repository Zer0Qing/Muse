package io.zer0.muse.data.sticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整页格子图的自动切分（表情包重做 P1）。
 *
 * 这里断言的是**数量与位置**，不是像素级边界：切多 8 个像素无所谓，
 * 但"把 21 格切成 1 格"或"把标题条也切成一格"必须被挡住。
 */
class StickerMontageSplitterTest {

    private val white = 0xFFFFFFFF.toInt()

    private inner class Page(val width: Int, val height: Int) {
        val pixels = IntArray(width * height) { white }

        fun fill(x0: Int, y0: Int, x1: Int, y1: Int, color: Int = 0xFF3355AA.toInt()) {
            for (y in y0 until y1) for (x in x0 until x1) pixels[y * width + x] = color
        }
    }

    @Test
    fun `a two by two sheet yields four cells`() {
        val page = Page(800, 800)
        listOf(60 to 120, 340 to 120, 60 to 400, 340 to 400).forEach { (x, y) -> page.fill(x, y, x + 120, y + 120) }

        val cells = StickerMontageSplitter.split(page.pixels, page.width, page.height)

        assertEquals(4, cells.size)
        // 断言每个预期方块的中心都落在某一格里：像素边界会变，中心有没有被覆盖才是用户在乎的
        val corners = listOf(60 to 120, 340 to 120, 60 to 400, 340 to 400)
        corners.forEach { corner ->
            val centerX = corner.first + 60
            val centerY = corner.second + 60
            assertTrue(
                "方块 " + corner + " 的中心没被任何格覆盖: " + cells,
                cells.any { it.left <= centerX && centerX < it.right && it.top <= centerY && centerY < it.bottom },
            )
        }
        assertTrue("阅读顺序应先上后左", cells[0].top <= cells[2].top)
        assertTrue(cells[0].left < cells[1].left)
    }

    @Test
    fun `title bars and corner watermarks are not turned into stickers`() {
        val page = Page(800, 800)
        page.fill(40, 20, 740, 60) // 标题条（细长）
        page.fill(700, 760, 760, 782) // 角标水印（太小）
        page.fill(60, 160, 180, 280)
        page.fill(340, 160, 460, 280)

        val cells = StickerMontageSplitter.split(page.pixels, page.width, page.height)

        assertEquals("只留下两张真表情: $cells", 2, cells.size)
    }

    @Test
    fun `a blank page yields nothing instead of one giant cell`() {
        val page = Page(400, 400)

        assertTrue(StickerMontageSplitter.split(page.pixels, page.width, page.height).isEmpty())
    }

    @Test
    fun `a fully painted page is refused rather than shipped as garbage`() {
        val page = Page(800, 800)
        page.fill(0, 0, 800, 800, 0xFF202020.toInt())

        assertTrue("整页连成一片时不该硬切", StickerMontageSplitter.split(page.pixels, page.width, page.height).isEmpty())
    }

    @Test
    fun `rows separated only horizontally still split into rows`() {
        val page = Page(800, 800)
        // 两行、每行内部贴纸互相贴着：列方向没有空隙，但仍要切成两格
        page.fill(20, 60, 780, 320)
        page.fill(20, 420, 780, 680)

        val cells = StickerMontageSplitter.split(page.pixels, page.width, page.height)

        assertEquals(2, cells.size)
        assertTrue(cells.first().top < cells.last().top)
    }
}
