package io.zer0.muse.perf

import io.zer0.muse.ui.markdown.MarkdownBlock
import io.zer0.muse.ui.markdown.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 渲染缓存的边界用例。
 *
 * 盯住三件事:
 *  - 非流式 Markdown 同一段正文第二次进入 composition 时直接命中缓存(不再解析);
 *  - 缓存超出上限时淘汰最久未使用的一条,而不是无限增长;
 *  - data-URI 图片命中缓存时不再重新解码。
 *
 * 缓存都是进程级单例,每个用例开始前清空。
 */
class RenderCachesTest {

    @Before
    fun resetCaches() {
        MarkdownParseCache.clear()
        DataUriImageCache.clear()
    }

    /** 计数用的解析函数:绕开缓存直接走真实解析,用来数实际解析了几次。 */
    private fun countingParser(counter: () -> Unit): (String) -> List<MarkdownBlock> = {
        counter()
        parseMarkdown(it)
    }

    // ── Markdown 解析缓存 ──

    @Test
    fun `same content parsed only once`() {
        var parseCount = 0
        val parse = countingParser { parseCount++ }

        val first = MarkdownParseCache.getOrParse("msg-1", "## 标题\n正文", parse)
        val second = MarkdownParseCache.getOrParse("msg-1", "## 标题\n正文", parse)

        assertEquals("第二次应命中缓存,不重新解析", 1, parseCount)
        assertSame("命中时返回同一份解析结果", first, second)
        assertTrue("解析结果不应为空", first.isNotEmpty())
    }

    @Test
    fun `different content parsed separately`() {
        var parseCount = 0
        val parse = countingParser { parseCount++ }

        MarkdownParseCache.getOrParse("msg-1", "第一段", parse)
        MarkdownParseCache.getOrParse("msg-1", "第二段", parse)
        MarkdownParseCache.getOrParse("msg-1", "第一段", parse)

        assertEquals("两段内容各解析一次,第三次命中", 2, parseCount)
    }

    @Test
    fun `cache key includes message id and content hash`() {
        val base = MarkdownParseCache.keyOf("msg-1", "同一段正文")

        assertNotEquals("不同消息同内容不共用槽位", base, MarkdownParseCache.keyOf("msg-2", "同一段正文"))
        assertNotEquals("同消息内容变化应换 key", base, MarkdownParseCache.keyOf("msg-1", "改过的正文"))
        assertEquals(
            "无消息 id 时退化为内容 hash,同类内容 key 相同",
            MarkdownParseCache.keyOf(null, "同一段正文"),
            MarkdownParseCache.keyOf(null, "同一段正文"),
        )
        assertNotEquals(
            "无 id 时不同内容仍要区分",
            MarkdownParseCache.keyOf(null, "甲"),
            MarkdownParseCache.keyOf(null, "乙"),
        )
    }

    @Test
    fun `eldest entry evicted when over capacity`() {
        val parse: (String) -> List<MarkdownBlock> = { emptyList() }
        repeat(MARKDOWN_CACHE_MAX_ENTRIES) { index ->
            MarkdownParseCache.getOrParse(null, "内容-$index", parse)
        }
        assertEquals(MARKDOWN_CACHE_MAX_ENTRIES, MarkdownParseCache.size())

        val newest = "内容-$MARKDOWN_CACHE_MAX_ENTRIES"
        MarkdownParseCache.getOrParse(null, newest, parse)

        assertEquals("容量达上限后条数不再增长", MARKDOWN_CACHE_MAX_ENTRIES, MarkdownParseCache.size())
        assertFalse(
            "最早写入的条目应被淘汰",
            MarkdownParseCache.containsKey(MarkdownParseCache.keyOf(null, "内容-0")),
        )
        assertTrue(
            "最新写入的条目应保留",
            MarkdownParseCache.containsKey(MarkdownParseCache.keyOf(null, newest)),
        )
    }

    @Test
    fun `oversized content not cached`() {
        var parseCount = 0
        val parse: (String) -> List<MarkdownBlock> = {
            parseCount++
            emptyList()
        }
        val huge = "x".repeat(MARKDOWN_CACHE_MAX_CHARS + 1)

        MarkdownParseCache.getOrParse(null, huge, parse)
        MarkdownParseCache.getOrParse(null, huge, parse)

        assertEquals("超长正文不进缓存,两次都解析", 2, parseCount)
        assertEquals("超长正文不占缓存槽位", 0, MarkdownParseCache.size())
    }

    // ── 通用 LRU ──

    @Test
    fun `lru evicts least recently used`() {
        val cache = BoundedLruCache<String, String>(2) { 1 }
        cache.put("a", "A")
        cache.put("b", "B")
        assertNotNull("访问 a,把它推到最近使用端", cache.get("a"))

        cache.put("c", "C")

        assertNotNull("a 刚被访问过,应保留", cache.get("a"))
        assertFalse("b 是最久未使用的,应被淘汰", cache.containsKey("b"))
        assertTrue(cache.containsKey("c"))
    }

    @Test
    fun `lru evicts by size not only by count`() {
        val cache = BoundedLruCache<String, String>(10) { it.length }
        cache.put("k1", "aaaa")
        cache.put("k2", "bbbb")
        assertEquals(8, cache.totalSize())

        cache.put("k3", "cccc")

        assertEquals("按字节计的上限生效", 8, cache.totalSize())
        assertEquals(2, cache.size())
    }

    // ── data-URI 图片缓存 ──

    @Test
    fun `image cache hit does not decode again`() {
        val cache = BoundedLruCache<String, String>(1024) { it.length }
        var decodeCount = 0

        val first = cachedDecode(cache, "img-1") {
            decodeCount++
            "decoded-bitmap"
        }
        val second = cachedDecode(cache, "img-1") {
            decodeCount++
            "decoded-bitmap"
        }

        assertEquals("命中缓存时不应重新解码", 1, decodeCount)
        assertSame(first, second)
    }

    @Test
    fun `image cache key distinguishes different data uris`() {
        val a = DataUriImageCache.keyOf("data:image/png;base64,AAAA")
        val b = DataUriImageCache.keyOf("data:image/png;base64,BBBB")

        assertNotEquals(a, b)
        assertEquals(a, DataUriImageCache.keyOf("data:image/png;base64,AAAA"))
    }
}
