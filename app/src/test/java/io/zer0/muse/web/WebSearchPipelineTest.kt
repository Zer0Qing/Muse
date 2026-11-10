package io.zer0.muse.web

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全量修复后的链路契约：
 * ① date_range 不再被客户端丢弃，能到达 provider；
 * ② 时效词自动推断 date_range；
 * ③ 操作符从 outboundQuery 剥离并在结果层生效；
 * ④ 整句透传（不压成词袋）；
 * ⑤ 召回全不相关时返回空 + 原因（而非噪声兜底）；
 * ⑥ 响应带调试信息。
 */
class WebSearchPipelineTest {

    /** 记录调用参数的可编排 fake。 */
    private class RecordingService(
        private val results: List<WebSearchResult>,
    ) : WebSearchService {
        override val name = "Recording"
        var lastQuery: String? = null
        var lastOptions: Map<String, String> = emptyMap()
        var calls = 0
        override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> {
            calls++
            lastQuery = query
            return results
        }
        override suspend fun searchWithOptions(query: String, maxResults: Int, options: Map<String, String>): List<WebSearchResult> {
            calls++
            lastQuery = query
            lastOptions = options
            return results
        }
    }

    private fun result(title: String, url: String = "https://news.example.com/$title", snippet: String = "") =
        WebSearchResult(title = title, url = url, snippet = snippet, source = "Recording")

    @Test
    fun `显式 date_range 到达 provider`() = runTest {
        val service = RecordingService(listOf(result("OpenAI 最新发布 模型", snippet = "OpenAI 发布")))
        val response = WebSearchCoordinator(service).search(
            WebSearchRequest("OpenAI 最新发布 模型", dateRange = "past_day"),
            "t1",
        )
        assertEquals("past_day", service.lastOptions["date_range"])
        assertEquals(WebSearchStatus.RESULTS, response.status)
        assertEquals("past_day", response.debug?.appliedDateRange)
        assertFalse("显式传入不应标记为推断", response.debug?.inferredDateRange ?: true)
    }

    @Test
    fun `时效词自动推断 date_range 且标记来源`() = runTest {
        val service = RecordingService(listOf(result("今天国内科技新闻速递", snippet = "科技 新闻")))
        val response = WebSearchCoordinator(service).search(WebSearchRequest("今天国内科技新闻"), "t1")
        assertEquals("past_day", service.lastOptions["date_range"])
        assertTrue(response.debug?.inferredDateRange == true)
    }

    @Test
    fun `整句透传 不被压成单词`() = runTest {
        val service = RecordingService(listOf(result("OpenAI release news", snippet = "OpenAI release")))
        WebSearchCoordinator(service).search(
            WebSearchRequest("What did OpenAI release most recently?"),
            "t1",
        )
        assertEquals("What did OpenAI release most recently?", service.lastQuery)
    }

    @Test
    fun `site 操作符从 outboundQuery 剥离并在结果层过滤`() = runTest {
        val service = RecordingService(
            listOf(
                result("transformer github", "https://github.com/a/transformers"),
                result("transformer 知乎", "https://zhihu.com/q/1"),
            ),
        )
        val response = WebSearchCoordinator(service).search(
            WebSearchRequest("site:github.com transformer"),
            "t1",
        )
        assertEquals("transformer", service.lastQuery)
        assertTrue("只保留约束域名", response.results.all { it.url.contains("github.com") })
        assertEquals(listOf("github.com"), response.debug?.constraints?.domains)
    }

    @Test
    fun `未知操作符被剥离并记录`() = runTest {
        val service = RecordingService(listOf(result("四川专升本 2027 政策", snippet = "专升本 政策")))
        val response = WebSearchCoordinator(service).search(WebSearchRequest("foo:bar 专升本"), "t1")
        assertFalse("未知操作符不该留在发往引擎的 query", service.lastQuery!!.contains("foo:"))
        assertTrue(response.debug?.unsupportedOperators?.contains("foo") == true)
    }

    @Test
    fun `召回全不相关时返回空与原因 不返回噪声`() = runTest {
        val service = RecordingService(
            listOf(
                result("老黄历 今日宜忌", "https://huangli.com/today"),
                result("2027 日历", "https://calendar.com/2027"),
            ),
        )
        val response = WebSearchCoordinator(service).search(WebSearchRequest("四川专升本 2027 政策"), "t1")
        assertEquals(WebSearchStatus.RECALL_IRRELEVANT, response.status)
        assertTrue(response.results.isEmpty())
        assertNotNull(response.debug?.reason)
        assertTrue("原因要说明是召回不相关", response.debug?.reason!!.contains("没有一条与查询主题相关"))
    }

    @Test
    fun `调试信息带阶段计数与实际引擎`() = runTest {
        val service = RecordingService(
            listOf(
                result("四川专升本政策一", "https://sc.gov.cn/a", "政策"),
                result("四川专升本政策二", "https://sc.gov.cn/a/?utm=x", "政策"),
                result("四川专升本政策三", "https://scea.cn/b", "政策"),
            ),
        )
        val response = WebSearchCoordinator(service).search(WebSearchRequest("四川专升本政策"), "t1")
        val d = response.debug
        assertNotNull(d)
        assertEquals("Recording", d!!.effectiveProvider)
        assertEquals(3, d.recalledCount)
        assertTrue(d.afterFilterCount <= d.recalledCount)
        assertTrue(d.afterDedupCount <= d.afterFilterCount)
        assertEquals(response.results.size, d.finalCount)
    }

    @Test
    fun `date_range 软过滤 超范围旧闻被剔除 无日期保留`() = runTest {
        val service = RecordingService(
            listOf(
                result("今日科技新闻", "https://a.com/1", "3天前"),
                result("旧闻归档", "https://a.com/2", "2020-01-01 发布"),
                result("无日期科技新闻", "https://b.com/3", "内容提要"),
            ),
        )
        val response = WebSearchCoordinator(service).search(
            WebSearchRequest("今天国内科技新闻", dateRange = "past_week"),
            "t1",
        )
        assertTrue("超范围旧闻应剔除", response.results.none { it.url.endsWith("/2") })
        assertTrue("无日期结果应保留", response.results.any { it.url.endsWith("/3") })
    }
}
