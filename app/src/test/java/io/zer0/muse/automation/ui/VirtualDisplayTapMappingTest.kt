package io.zer0.muse.automation.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 预览点击 → 虚拟屏坐标的换算。
 *
 * 这段错了的表现很具体：用户点画面中间的按钮，实际点到旁边 ——
 * 而且越靠边缘偏得越多（`ContentScale.Fit` 会等比缩放并居中留黑边）。
 */
class VirtualDisplayTapMappingTest {

    @Test
    fun `same aspect ratio maps proportionally`() {
        // 预览 1000x500，屏幕 2000x1000 → 缩放 0.5，无黑边
        val (x, y) = mapTapToDisplay(
            tap = Offset(500f, 250f),
            viewSize = Size(1000f, 500f),
            displaySize = Size(2000f, 1000f),
        )

        assertEquals(1000, x)
        assertEquals(500, y)
    }

    @Test
    fun `letterboxed preview subtracts the black bars`() {
        // 预览 1000x1000，屏幕 2000x1000 → 实际渲染 1000x500，上下各留 250 黑边
        val (x, y) = mapTapToDisplay(
            tap = Offset(500f, 250f),
            viewSize = Size(1000f, 1000f),
            displaySize = Size(2000f, 1000f),
        )

        assertEquals("水平方向没有黑边", 1000, x)
        assertEquals("垂直方向要减掉 250 的黑边", 0, y)
    }

    @Test
    fun `tapping inside the black bar clamps to the screen edge`() {
        val (_, y) = mapTapToDisplay(
            tap = Offset(500f, 100f),
            viewSize = Size(1000f, 1000f),
            displaySize = Size(2000f, 1000f),
        )

        assertEquals("黑边不属于屏幕，要夹到 0", 0, y)
    }

    @Test
    fun `tapping past the right edge clamps inside the screen`() {
        val (x, _) = mapTapToDisplay(
            tap = Offset(5000f, 250f),
            viewSize = Size(1000f, 500f),
            displaySize = Size(2000f, 1000f),
        )

        assertEquals("不能把越界坐标发给系统", 1999, x)
    }

    @Test
    fun `zero sized inputs degrade to the origin instead of dividing by zero`() {
        assertEquals(0 to 0, mapTapToDisplay(Offset(10f, 10f), Size.Zero, Size(100f, 100f)))
        assertEquals(0 to 0, mapTapToDisplay(Offset(10f, 10f), Size(100f, 100f), Size.Zero))
    }
}
