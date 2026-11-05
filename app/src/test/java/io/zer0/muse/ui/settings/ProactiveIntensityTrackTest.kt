package io.zer0.muse.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主动程度轨道的两个换算（纯函数）。
 *
 * 这两个式子看着像"随便怎么写都行"，但语义差得很实：
 * 填充比例若写成 `index / (count - 1)`，**最左一档就没有任何填充**，
 * 用户看到的是"一格都没填"，会读成"这等于关掉"——而它其实是"安静但依然会主动"。
 * 把手若落在格子**右边界**，最右档的把手会贴边被裁掉一半。
 * 所以这两条不是数学题，是界面上会被误读的东西。
 */
class ProactiveIntensityTrackTest {

    private val count = 5

    @Test
    fun `填充比例单调递增且最左一档也必须有可见填充`() {
        var prev = -1f
        for (i in 0 until count) {
            val f = intensityFillFraction(i, count)
            assertTrue("第 $i 档的填充比例越界: $f", f > 0f && f <= 1f)
            assertTrue("填充必须随档位递增（$prev → $f）", f > prev)
            prev = f
        }
        assertEquals(
            "最左一档不能是零填充（会被读成「等于关掉」）",
            1f / count.toFloat(),
            intensityFillFraction(0, count),
            0.0001f,
        )
        assertEquals("最高档应填满轨道", 1f, intensityFillFraction(count - 1, count), 0.0001f)
    }

    @Test
    fun `把手中心落在格子中点而不是右边界`() {
        assertEquals("最左档把手应在 1/10 处", 0.1f, intensityHandleFraction(0, count), 0.0001f)
        assertEquals("最右档把手应在 9/10 处（贴右边会被裁）", 0.9f, intensityHandleFraction(count - 1, count), 0.0001f)
        for (i in 0 until count) {
            val f = intensityHandleFraction(i, count)
            assertTrue("把手中心必须始终在轨道内: $f", f > 0f && f < 1f)
        }
    }

    @Test
    fun `越界档位与空档位都被夹住而不是崩`() {
        // 档位集合以后加一档时，这里不该画出 1.5 倍宽的轨道
        assertEquals(1f, intensityFillFraction(99, count), 0.0001f)
        assertEquals(1f / count.toFloat(), intensityFillFraction(-3, count), 0.0001f)
        assertEquals(0f, intensityFillFraction(0, 0), 0.0001f)
        assertEquals(0f, intensityHandleFraction(2, 0), 0.0001f)
        assertTrue(intensityHandleFraction(99, count) < 1f)
    }
}
