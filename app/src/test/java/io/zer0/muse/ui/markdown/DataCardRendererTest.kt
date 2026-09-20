package io.zer0.muse.ui.markdown

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 2: DataCardRenderer 纯逻辑回归测试。
 *
 * 覆盖柱状图空数据除零防护,以及 donut 图例/扇区同序取色的不变量。
 */
class DataCardRendererTest {

    private val accent = Color(0xFF4CAF50)
    private val info = Color(0xFF2196F3)

    @Test
    fun `barWidthFor 空数据返回 0 且不抛除零异常`() {
        assertEquals(0f, barWidthFor(canvasWidth = 300f, barCount = 0, gap = 6f), 0.001f)
    }

    @Test
    fun `barWidthFor 常规数据按可用宽度均分`() {
        // (330 - 6 * 2) / 3 = 106
        assertEquals(106f, barWidthFor(canvasWidth = 330f, barCount = 3, gap = 6f), 0.001f)
    }

    @Test
    fun `barWidthFor 画布过窄时收缩为 0 而非负数`() {
        assertEquals(0f, barWidthFor(canvasWidth = 10f, barCount = 10, gap = 6f), 0.001f)
    }

    @Test
    fun `barWidthFor 单根柱占满画布`() {
        assertEquals(200f, barWidthFor(canvasWidth = 200f, barCount = 1, gap = 6f), 0.001f)
    }

    @Test
    fun `donut 图例颜色与扇区颜色同序一致`() {
        val palette = donutSectorPalette(accent, info)
        for (index in 0..7) {
            assertEquals(
                "index=$index 的图例色必须等于同序扇区色",
                sectorColor(palette, index),
                legendSwatchColor(cardType = "donut", index = index, accent = accent, donutPalette = palette),
            )
        }
    }

    @Test
    fun `donut 调色板首项为强调色且循环取色`() {
        val palette = donutSectorPalette(accent, info)
        assertEquals(5, palette.size)
        assertEquals(accent, sectorColor(palette, 0))
        assertEquals(accent, sectorColor(palette, palette.size))
        assertEquals(info, sectorColor(palette, palette.size - 1))
    }

    @Test
    fun `非 donut 图表图例统一使用强调色`() {
        val palette = donutSectorPalette(accent, info)
        assertEquals(accent, legendSwatchColor(cardType = "bar", index = 0, accent = accent, donutPalette = palette))
        assertEquals(accent, legendSwatchColor(cardType = "line", index = 3, accent = accent, donutPalette = palette))
    }
}
