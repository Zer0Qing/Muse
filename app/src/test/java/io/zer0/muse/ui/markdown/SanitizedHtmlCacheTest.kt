package io.zer0.muse.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 清洗结果缓存。
 *
 * 锁两件事：同一份内容第二次进来必须命中原对象（否则等于没缓存），
 * 以及条目上限有效（缓存不能变成无界内存增长）。
 */
class SanitizedHtmlCacheTest {

    @Before
    fun setUp() {
        SanitizedHtmlCache.clear()
    }

    @Test
    fun `same_content_returns_same_instance`() {
        val code = "<div onclick=\"go()\">hello</div>"
        val first = SanitizedHtmlCache.get(code)
        val second = SanitizedHtmlCache.get(code)
        assertSame("同一份内容必须命中缓存", first, second)
        assertEquals(1, SanitizedHtmlCache.size())
    }

    @Test
    fun `different_content_is_sanitized_separately`() {
        val a = SanitizedHtmlCache.get("<div>a</div>")
        val b = SanitizedHtmlCache.get("<div>b</div>")
        assertNotSame(a, b)
        assertEquals(2, SanitizedHtmlCache.size())
    }

    @Test
    fun `cache_is_bounded`() {
        repeat(40) { index ->
            SanitizedHtmlCache.get("<div id=\"n$index\">x</div>")
        }
        assertTrue(
            "缓存条目必须被上限压住，实际 ${SanitizedHtmlCache.size()}",
            SanitizedHtmlCache.size() <= 12,
        )
    }

    @Test
    fun `cached_result_equals_direct_sanitize`() {
        val code = "<iframe src=\"https://evil\"></iframe><form action=\"/x\"></form><div>ok</div>"
        val cached = SanitizedHtmlCache.get(code)
        assertEquals(sanitizeHtml(code), cached)
        assertTrue("iframe 必须被清掉", !cached.contains("iframe"))
        assertTrue("form 必须被清掉", !cached.contains("<form"))
    }

    @Test
    fun `oversized_content_bypasses_cache`() {
        val huge = "<div>" + "x".repeat(70_000) + "</div>"
        val result = SanitizedHtmlCache.get(huge)
        assertEquals(0, SanitizedHtmlCache.size())
        assertTrue(result.contains("xxxx"))
    }
}
