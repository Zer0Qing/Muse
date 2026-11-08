package io.zer0.muse.ui.markdown.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡片就地视口的纯判据（36）：限高、是否溢出、聚焦后的滚动归属。
 *
 * 关键安全约束——**未聚焦一律交回聊天列表**，所以这套逻辑不会重现「卡内滚与外层抢手势」
 * （39② 的老问题）：只有「已聚焦 且 内容确实比可视高度高」才让卡自己滚。
 */
class CardViewportTest {

    @Test
    fun `限高取测量高与上限的较小值`() {
        assertEquals(300, effectiveInlineHeightPx(measuredPx = 300, capPx = 800))
        assertEquals(800, effectiveInlineHeightPx(measuredPx = 1200, capPx = 800))
    }

    @Test
    fun `未测量时不占溢出`() {
        assertEquals(0, effectiveInlineHeightPx(measuredPx = 0, capPx = 800))
        assertFalse(hasInlineOverflow(measuredPx = 0, capPx = 800))
    }

    @Test
    fun `内容高于上限才算溢出`() {
        assertFalse(hasInlineOverflow(measuredPx = 800, capPx = 800))
        assertTrue(hasInlineOverflow(measuredPx = 801, capPx = 800))
    }

    @Test
    fun `未聚焦一律交回聊天列表`() {
        // 未聚焦时无论有没有溢出，都不卡内滚——这正是 39② 不被回退的前提。
        assertEquals(CardScrollMode.PassToChat, resolveCardScrollMode(focused = false, hasOverflow = true))
        assertEquals(CardScrollMode.PassToChat, resolveCardScrollMode(focused = false, hasOverflow = false))
    }

    @Test
    fun `聚焦且有溢出才卡内滚`() {
        assertEquals(CardScrollMode.InternalScroll, resolveCardScrollMode(focused = true, hasOverflow = true))
    }

    @Test
    fun `聚焦但没溢出仍交回列表`() {
        // 短卡片聚焦了也没的可滚，继续让列表滚，避免出现「聚焦后整页卡住滚不动」。
        assertEquals(CardScrollMode.PassToChat, resolveCardScrollMode(focused = true, hasOverflow = false))
    }

    @Test
    fun `焦点同一时刻只属于一张卡`() {
        val focus = CardFocusController()
        focus.focus("a")
        focus.focus("b")
        assertTrue(focus.isFocused("b"))
        assertFalse("聚焦新卡要自动松开旧卡", focus.isFocused("a"))
    }

    @Test
    fun `点击外部清除焦点`() {
        val focus = CardFocusController()
        focus.focus("a")
        focus.clearFocus()
        assertFalse(focus.isFocused("a"))
        // clearFocus 幂等
        focus.clearFocus()
        assertFalse(focus.isFocused("a"))
    }
}
