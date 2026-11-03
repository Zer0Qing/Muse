package io.zer0.muse.data.sticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两段式切格在"密集目录页"上的行为（表情包重做 P5）。
 *
 * 图是**程序画的**，不放真实素材进仓库：这里要测的是算法形状，
 * 而真实样本是别人的作品（还带水印），不适合作为回归资产。
 *
 * 画的就是让我先前那版失败的场景：贴纸之间只有约 20px 空隙，
 * 每张贴纸下面还有一行"配文" —— 连通块要么把相邻两张并起来，
 * 要么把一张切成"画 + 字"两块。
 */
class StickerGridLayoutTest {

    private val white = 0xFFFFFFFF.toInt()

    private inner class Sheet(val width: Int, val height: Int) {
        val pixels = IntArray(width * height) { white }

        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, color: Int = 0xFF202020.toInt()) {
            for (y in y0 until y1) for (x in x0 until x1) pixels[y * width + x] = color
        }
    }

    /** 6 行 × 4 列，每格：一张"画" + 下面一行"配文"，格间空隙 22px。 */
    private fun denseSheet(): Pair<Sheet, List<StickerMontageSplitter.Cell>> {
        val sheet = Sheet(990, 1400)
        val expected = mutableListOf<StickerMontageSplitter.Cell>()
        val colX = intArrayOf(70, 300, 530, 760)
        val rowY = intArrayOf(200, 400, 600, 800, 1000, 1200)
        rowY.forEach { y ->
            colX.forEach { x ->
                sheet.rect(x, y, x + 160, y + 90) // 画
                sheet.rect(x + 20, y + 104, x + 140, y + 124) // 配文
                expected += StickerMontageSplitter.Cell(x - 5, y - 5, x + 165, y + 130)
            }
        }
        return sheet to expected
    }

    @Test
    fun `dense sheet is cut once per sticker, not into drawing plus caption`() {
        val (sheet, expected) = denseSheet()

        val cells = StickerGridLayout.layout(sheet.pixels, sheet.width, sheet.height) { color ->
            StickerMontageSplitter.isBackgroundPixel(color, white)
        }

        assertEquals("应该是 24 格（6 行 × 4 列）", 24, cells.size)
        expected.forEach { want ->
            val cx = (want.left + want.right) / 2
            val cy = (want.top + want.bottom) / 2
            assertTrue(
                "贴纸中心 ($cx,$cy) 没被任何格覆盖: $cells",
                cells.any { it.left <= cx && cx < it.right && it.top <= cy && cy < it.bottom },
            )
        }
    }

    @Test
    fun `each cell spans its whole sticker including the caption line`() {
        val (sheet, _) = denseSheet()

        val cells = StickerGridLayout.layout(sheet.pixels, sheet.width, sheet.height) { color ->
            StickerMontageSplitter.isBackgroundPixel(color, white)
        }

        // 高度必须盖到"画 + 配文"（约 130px），只到 90px 就是把配文切走了
        assertTrue(
            "格子高度应包含配文行: " + cells.map { it.bottom - it.top },
            cells.all { it.bottom - it.top >= 110 },
        )
    }

    @Test
    fun `a blank page yields nothing`() {
        val sheet = Sheet(400, 400)

        val cells = StickerGridLayout.layout(sheet.pixels, sheet.width, sheet.height) { color ->
            StickerMontageSplitter.isBackgroundPixel(color, white)
        }

        assertTrue(cells.isEmpty())
    }

    @Test
    fun `a single big sticker on a page is not cut into strips`() {
        val sheet = Sheet(800, 800)
        sheet.rect(100, 100, 700, 700)

        val cells = StickerGridLayout.layout(sheet.pixels, sheet.width, sheet.height) { color ->
            StickerMontageSplitter.isBackgroundPixel(color, white)
        }

        // 一张整块只有一行一列：不该由"列数<2"硬切出碎片，交给上层按整页处理
        assertTrue("整块页应该交给规划层按整页收: $cells", cells.size <= 1)
    }

    @Test
    fun `cell count is capped`() {
        val sheet = Sheet(2000, 2000)
        var placed = 0
        var y = 10
        while (y < 1990 && placed < 120) {
            var x = 10
            while (x < 1990 && placed < 120) {
                sheet.rect(x, y, x + 60, y + 60)
                placed++
                x += 80
            }
            y += 80
        }

        val cells = StickerGridLayout.layout(sheet.pixels, sheet.width, sheet.height) { color ->
            StickerMontageSplitter.isBackgroundPixel(color, white)
        }

        assertTrue("格子数必须有上限: ${cells.size}", cells.size <= StickerGridLayout.MAX_CELLS)
    }
}
