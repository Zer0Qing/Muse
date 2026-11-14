package io.zer0.muse.web

import org.junit.Assert.assertEquals
import org.junit.Test

class WebSearchQueryNormalizerTest {
    @Test
    fun `removes malformed smart quotes and outer ascii quotes`() {
        assertEquals(
            "ornith1.5-35ba3b 模型 OR model",
            WebSearchQueryNormalizer.normalize("\"\"ornith1.5-35ba3b”模型 OR model\""),
        )
    }

    @Test
    fun `keeps a normal query unchanged`() {
        assertEquals("DeepSeek V4 Flash 模型", WebSearchQueryNormalizer.normalize("DeepSeek V4 Flash 模型"))
    }

    @Test
    fun `collapses invisible characters and whitespace`() {
        assertEquals("OpenAI model", WebSearchQueryNormalizer.normalize("  OpenAI\u200B   model  "))
    }

    @Test
    fun `removes unmatched quote`() {
        assertEquals("ornith model", WebSearchQueryNormalizer.normalize("ornith \"model"))
    }

    @Test
    fun `refine keeps short keyword queries unchanged`() {
        assertEquals("DeepSeek V4 Flash 模型", WebSearchQueryNormalizer.refine("DeepSeek V4 Flash 模型"))
    }

    @Test
    fun `refine strips conversational lead-in from long queries`() {
        assertEquals(
            "最近有什么好看的科幻电影推荐",
            WebSearchQueryNormalizer.refine("请问帮我查一下最近有什么好看的科幻电影推荐"),
        )
    }

    @Test
    fun `refine strips trailing modal particle`() {
        assertEquals(
            "北京到上海高铁需要多长时间",
            WebSearchQueryNormalizer.refine("北京到上海高铁需要多长时间呢"),
        )
    }

    @Test
    fun `refine is idempotent`() {
        val once = WebSearchQueryNormalizer.refine("麻烦帮我搜索一下 2026 年新能源汽车销量排行")
        assertEquals(once, WebSearchQueryNormalizer.refine(once))
    }

    @Test
    fun `refine keeps all terms in an overlong query`() {
        val long = "请 " + "keyword ".repeat(20).trim()
        val refined = WebSearchQueryNormalizer.refine(long)
        assertEquals(20, refined.split(" ").count { it == "keyword" })
    }

    @Test
    fun `refine preserves meaningful terms near the end of a long query`() {
        val raw =
            "please search the latest comprehensive guide for mobile development packaging deployment permissions " +
                "networking debugging and troubleshooting details about python android termux"
        val refined = WebSearchQueryNormalizer.refine(raw)
        assert(refined.contains("python", ignoreCase = true)) { "python was discarded: $refined" }
        assert(refined.contains("android", ignoreCase = true)) { "android was discarded: $refined" }
        assert(refined.contains("termux", ignoreCase = true)) { "termux was discarded: $refined" }
    }
}
