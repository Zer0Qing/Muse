package io.zer0.muse.ui.settings

import io.zer0.muse.web.AUTO_CHAIN_PROVIDER_KEYS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「搜索顺序」列表的纯逻辑（界面那半的行为锁；链侧的顺序另由 `WebSearchChainOrderTest` 守）。
 *
 * 这里最容易被写错、也最值得钉住的一点是：**用户存的顺序必须真的作用到链上**。
 * 所以 `displayedOrder` 的键空间与 `AUTO_CHAIN_PROVIDER_KEYS` 必须同源，
 * 而不是界面自己维护一套名字（那样拖完看到的是假顺序）。
 */
class WebSearchOrderStateTest {

    @Test
    fun `没调过顺序时列表就是内置顺序`() {
        assertEquals(AUTO_CHAIN_PROVIDER_KEYS, WebSearchOrderState.displayedOrder(emptyList(), emptyList()))
    }

    @Test
    fun `自定义引擎排在列表最前`() {
        val order = WebSearchOrderState.displayedOrder(listOf("custom:我的引擎"), emptyList())
        assertEquals("custom:我的引擎", order.first())
        assertEquals("内置表仍然完整跟在后面", AUTO_CHAIN_PROVIDER_KEYS, order.subList(1, order.size))
    }

    @Test
    fun `保存过的顺序被尊重`() {
        val saved = listOf("Brave", "Tavily")
        val order = WebSearchOrderState.displayedOrder(emptyList(), saved)
        assertEquals(listOf("Brave", "Tavily"), order.take(2))
        assertEquals("其余项保持内置相对顺序", AUTO_CHAIN_PROVIDER_KEYS.size, order.size)
    }

    @Test
    fun `陈旧或未知的键静默丢掉而不是留一行幽灵`() {
        val saved = listOf("已被删掉的引擎", "Brave")
        val order = WebSearchOrderState.displayedOrder(emptyList(), saved)
        assertFalse("失效键不该出现在界面上", order.contains("已被删掉的引擎"))
        assertEquals("Brave 仍排第一", "Brave", order.first())
        assertEquals("总数不变（少一项就要多补一项）", AUTO_CHAIN_PROVIDER_KEYS.size, order.size)
    }

    @Test
    fun `上移下移只在可动范围内生效`() {
        val base = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "a", "c", "d"), WebSearchOrderState.moved(base, 1, -1))
        assertEquals(listOf("a", "c", "b", "d"), WebSearchOrderState.moved(base, 1, +1))
        assertEquals("首行不能上移", base, WebSearchOrderState.moved(base, 0, -1))
        assertEquals("末行不能下移", base, WebSearchOrderState.moved(base, 3, +1))
        assertEquals("越界索引不动手", base, WebSearchOrderState.moved(base, 9, -1))
    }

    @Test
    fun `移动不增删元素`() {
        val base = AUTO_CHAIN_PROVIDER_KEYS
        val moved = WebSearchOrderState.moved(base, 5, -1)
        assertEquals(base.size, moved.size)
        assertEquals(base.toSet(), moved.toSet())
    }

    @Test
    fun `列表不显示键前缀`() {
        assertEquals("我的引擎", WebSearchOrderState.displayName("custom:我的引擎"))
        assertEquals("Tavily", WebSearchOrderState.displayName("Tavily"))
    }

    @Test
    fun `重置就是存一张空表`() {
        assertTrue(WebSearchOrderState.defaultOrder.isEmpty())
    }
}
