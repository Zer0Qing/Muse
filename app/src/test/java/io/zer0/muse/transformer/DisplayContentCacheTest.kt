package io.zer0.muse.transformer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `displayContent` 结果缓存的边界。
 *
 * 缓存的唯一职责是「同一段正文不重复洗第二遍」，因此必须同时钉住四件事：
 *  - 命中时不再重算，返回的是同一份结果；
 *  - 正文变了必须换结果，绝不能把上一次的文本当成本次结果；
 *  - 超出上限时从最久未使用的一端淘汰，容量恒定；
 *  - 输出与不走缓存的 `stripForDisplay` 逐字节一致。
 */
class DisplayContentCacheTest {

    private val cardStateTail = "${InternalMarkupSanitizer.CARD_STATE_OPEN}{\"cardId\":\"c1\"}${InternalMarkupSanitizer.CARD_STATE_CLOSE}"

    private fun countingCompute(counter: () -> Unit): (String) -> String = {
        counter()
        InternalMarkupSanitizer.stripForDisplay(it)
    }

    @Test
    fun `second call hits the cache instead of recomputing`() {
        var computeCount = 0
        val cache = DisplayContentCache(maxEntries = 8)
        val compute = countingCompute { computeCount++ }
        val raw = "<think>内部思考</think>正文$cardStateTail"

        val first = cache.getOrCompute(raw, compute)
        val second = cache.getOrCompute(raw, compute)

        assertEquals("同一段正文只该洗一次", 1, computeCount)
        assertSame("命中时返回的必须是同一份结果", first, second)
        assertEquals("正文", first)
    }

    @Test
    fun `changed content never returns the stale result`() {
        var computeCount = 0
        val cache = DisplayContentCache(maxEntries = 8)
        val compute = countingCompute { computeCount++ }

        val before = cache.getOrCompute("<think>内部思考</think>旧正文", compute)
        val after = cache.getOrCompute("<think>内部思考</think>新正文", compute)

        assertEquals("旧正文", before)
        assertEquals("正文变化后必须重新算，不能返回上一次的结果", "新正文", after)
        assertNotEquals(before, after)
        assertEquals(2, computeCount)
    }

    @Test
    fun `oldest entry is evicted when capacity is exceeded`() {
        val cache = DisplayContentCache(maxEntries = 2)
        val compute: (String) -> String = { it }

        cache.getOrCompute("甲", compute)
        cache.getOrCompute("乙", compute)
        // 访问甲，把它推到最近使用端；此时乙最久未用。
        cache.getOrCompute("甲", compute)
        cache.getOrCompute("丙", compute)

        assertEquals("容量达上限后条数不再增长", 2, cache.size())
        assertTrue("刚被访问过的条目应保留", cache.containsKey("甲"))
        assertFalse("最久未使用的条目应被淘汰", cache.containsKey("乙"))
        assertTrue(cache.containsKey("丙"))
    }

    @Test
    fun `oversized content is computed but not cached`() {
        val cache = DisplayContentCache(maxEntries = 8)
        val huge = "字".repeat(DISPLAY_CONTENT_CACHE_MAX_CHARS + 1)

        assertEquals(huge, cache.getOrCompute(huge) { it })
        assertEquals("超长正文不占缓存槽位", 0, cache.size())
        assertFalse(cache.containsKey(huge))
    }

    @Test
    fun `displayContent output is byte identical to stripForDisplay`() {
        val samples = listOf(
            "",
            "   \n  ",
            "普通正文",
            "<think>内部思考</think>正文",
            "<mood>心情不错</mood><mod>准备作答</mod>正文",
            "正文$cardStateTail",
            "上\n\n\n\n下",
            "[glow]亮[/glow]正文",
            "List<String> 与 [链接](https://a.b)",
        )
        for (sample in samples) {
            val expected = InternalMarkupSanitizer.stripForDisplay(sample)
            assertEquals(
                "缓存不得改变输出: <$sample>",
                expected,
                InternalMarkupSanitizer.displayContent(sample),
            )
            // 第二次走缓存，结果仍要一模一样。
            assertEquals(
                "命中缓存后输出不得改变: <$sample>",
                expected,
                InternalMarkupSanitizer.displayContent(sample),
            )
        }
    }

    @Test
    fun `editing a message shows the new text instead of the cached one`() {
        assertEquals("旧正文", InternalMarkupSanitizer.displayContent("<think>内部思考</think>旧正文"))
        assertEquals("新正文", InternalMarkupSanitizer.displayContent("<think>内部思考</think>新正文"))
        assertEquals("正文", InternalMarkupSanitizer.displayContent("正文$cardStateTail"))
    }
}
