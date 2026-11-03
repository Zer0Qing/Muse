package io.zer0.muse.data.sticker

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PDF 一页该切几刀的判定（表情包重做 P5）。
 *
 * 真机上"这页切了几刀"很难肉眼核对，所以边界全钉在这里：
 * 目录页要切开、单表情页不能切碎、噪声页宁可什么都不导。
 */
class StickerPdfPlannerTest {

    private val pageWidth = 1200
    private val pageHeight = 1600
    private val pageArea = pageWidth.toLong() * pageHeight

    private fun cell(index: Int, size: Int = 200) = StickerMontageSplitter.Cell(
        left = index * size,
        top = index * size,
        right = index * size + size,
        bottom = index * size + size,
    )

    @Test
    fun `a montage page is cut into its cells`() {
        // 尺寸按真实目录页的比例：一张贴纸约占页 3%
        val cells = (0 until 21).map { i -> cell(i, size = 300) }

        val plan = StickerPdfPlanner.plan(cells, pageWidth, pageHeight)

        assertTrue("目录页应按格切: $plan", plan is StickerPdfPlanner.Plan.Crop)
        val crop = plan as StickerPdfPlanner.Plan.Crop
        assertTrue(crop.cells.size == 21)
    }

    @Test
    fun `a single sticker filling the page is kept whole`() {
        val big = StickerMontageSplitter.Cell(0, 0, 1190, 1590)

        val plan = StickerPdfPlanner.plan(listOf(big), pageWidth, pageHeight)

        assertTrue("整页一张不该被切碎: $plan", plan is StickerPdfPlanner.Plan.Crop)
        assertTrue((plan as StickerPdfPlanner.Plan.Crop).cells.size == 1)
    }

    @Test
    fun `a lone small mark on a page is skipped`() {
        // 只有角落一个水印/二维码时，导进来就是往用户库里塞垃圾
        val small = StickerMontageSplitter.Cell(1000, 1500, 1150, 1580)

        assertTrue(StickerPdfPlanner.plan(listOf(small), pageWidth, pageHeight) is StickerPdfPlanner.Plan.Skip)
    }

    @Test
    fun `an empty page is skipped`() {
        assertTrue(StickerPdfPlanner.plan(emptyList(), pageWidth, pageHeight) is StickerPdfPlanner.Plan.Skip)
    }

    @Test
    fun `absurd cell counts are treated as noise`() {
        val noisy = (0 until StickerPdfPlanner.MAX_CELLS_PER_PAGE + 5).map { cell(it, size = 300) }

        assertTrue("碎块过多宁可整页不收: ", StickerPdfPlanner.plan(noisy, pageWidth, pageHeight) is StickerPdfPlanner.Plan.Skip)
    }

    @Test
    fun `a page cut into fragments is skipped even when the sizes look uniform`() {
        // 真实样本的形状：每格约 82x100，占页 0.6% —— 面积彼此接近但都是碎片
        val fragments = (0 until 25).map { i ->
            StickerMontageSplitter.Cell(i % 5 * 200, i / 5 * 250, i % 5 * 200 + 82, i / 5 * 250 + 100)
        }

        assertTrue(
            "碎片级切格必须整页不收",
            StickerPdfPlanner.plan(fragments, pageWidth, pageHeight) is StickerPdfPlanner.Plan.Skip,
        )
    }

    @Test
    fun `two cells are already worth cutting`() {
        val pair = listOf(StickerMontageSplitter.Cell(0, 0, 1190, 790), StickerMontageSplitter.Cell(0, 800, 1190, 1590))

        val plan = StickerPdfPlanner.plan(pair, pageWidth, pageHeight)

        assertTrue("两格就按两格切: $plan", plan is StickerPdfPlanner.Plan.Crop)
    }

    @Test
    fun `the whole-page threshold is measured against the page area`() {
        // 60% 的单格：更像"页面里的一块内容"，不足以当成一张表情
        val medium = StickerMontageSplitter.Cell(0, 0, 1200, 800)
        assertTrue(medium.area.toDouble() < pageArea * StickerPdfPlanner.WHOLE_PAGE_AREA_RATIO)
        assertTrue(StickerPdfPlanner.plan(listOf(medium), pageWidth, pageHeight) is StickerPdfPlanner.Plan.Skip)
    }

    @Test
    fun `heterogeneous cells mean the cut went wrong so the page is skipped`() {
        // 真实样本上的失败形状：几块大的（多张并一起）+ 几块小的（半截表情）
        val uneven = listOf(
            StickerMontageSplitter.Cell(0, 0, 100, 100),
            StickerMontageSplitter.Cell(200, 0, 900, 700),
            StickerMontageSplitter.Cell(0, 300, 130, 340),
        )

        assertTrue(
            "大小差这么多说明根本没切准: " + StickerPdfPlanner.plan(uneven, pageWidth, pageHeight),
            StickerPdfPlanner.plan(uneven, pageWidth, pageHeight) is StickerPdfPlanner.Plan.Skip,
        )
    }

    @Test
    fun `a regular grid of same-size cells passes the uniformity check`() {
        val grid = (0 until 20).map { i ->
            StickerMontageSplitter.Cell(i * 10, 0, i * 10 + 300, 300)
        }

        assertTrue(StickerPdfPlanner.uniformEnough(grid))
    }
}

