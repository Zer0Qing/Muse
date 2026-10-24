package io.zer0.muse.ui.common.animation

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发送飞行的几何推算。
 *
 * 这段逻辑在真机上只有一个观感结果，看不出对错，所以把可验证的部分钉在测试里：
 * 落点必须落在列表内部、宽度受上限约束、无效输入直接放弃（宁可不飞，也不要飞错位置）。
 */
class SendFlightTest {

    private val list = Rect(left = 0f, top = 200f, right = 1080f, bottom = 2200f)
    private val input = Rect(left = 40f, top = 2100f, right = 1040f, bottom = 2200f)

    @Test
    fun `blank text produces no flight`() {
        assertNull(computeSendFlight("   ", input, list))
    }

    @Test
    fun `unmeasured rects produce no flight`() {
        assertNull(computeSendFlight("hi", Rect.Zero, list))
        assertNull(computeSendFlight("hi", input, Rect.Zero))
    }

    @Test
    fun `target sits inside the list`() {
        val flight = computeSendFlight("hello", input, list)
        assertNotNull(flight)
        val to = requireNotNull(flight).to
        assertTrue("落点顶部不应高于列表顶部", to.top >= list.top)
        assertTrue("落点底部不应低于列表底部", to.bottom <= list.bottom)
        assertTrue("落点右边不应超出列表", to.right <= list.right)
        assertTrue("落点左边不应为负", to.left >= list.left)
    }

    @Test
    fun `target width respects the bubble ratio`() {
        val flight = requireNotNull(computeSendFlight("hello", input, list))
        val maxWidth = list.width * 0.72f
        assertTrue("落点宽度不应超过气泡上限", flight.to.width <= maxWidth + 0.5f)
        assertTrue("落点宽度应大于零", flight.to.width > 0f)
    }

    @Test
    fun `flight keeps the text and both endpoints`() {
        val flight = requireNotNull(computeSendFlight("hello", input, list))
        assertEquals("hello", flight.text)
        assertEquals(input, flight.from)
    }
}
