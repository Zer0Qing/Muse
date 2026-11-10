package io.zer0.muse.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 结果后处理：域名去重、导航页剔除、约束过滤、软过滤、加权排序、召回门禁。 */
class WebSearchResultPostProcessorTest {

    private fun r(title: String, url: String, snippet: String = "") =
        WebSearchResult(title = title, url = url, snippet = snippet, source = "test")

    private val now = 1730000000000L

    @Test
    fun `同域名最多保留 2 条`() {
        val input = listOf(
            r("政策一", "https://sc.gov.cn/a"),
            r("政策二", "https://sc.gov.cn/?fanwww"),
            r("政策三", "https://sc.gov.cn/rst/index.shtml"),
            r("政策四", "https://scea.cn/a"),
        )
        val out = WebSearchResultPostProcessor.process(
            input,
            WebSearchQueryAnalyzer.analyze("四川专升本 2027 政策"),
            null,
            now,
        )
        val sc = out.results.count { it.url.contains("sc.gov.cn") }
        assertTrue("同域名应 ≤2，实际 $sc", sc <= 2)
        assertTrue(out.results.any { it.url.contains("scea.cn") })
    }

    @Test
    fun `URL 规范化去重 去参数与 index 后缀`() {
        val input = listOf(
            r("政策", "https://sc.gov.cn/a/?utm=x"),
            r("政策副本", "https://sc.gov.cn/a/"),
        )
        val out = WebSearchResultPostProcessor.process(
            input,
            WebSearchQueryAnalyzer.analyze("四川专升本 政策"),
            null,
            now,
        )
        assertEquals(1, out.results.size)
    }

    @Test
    fun `导航页与门户首页被剔除`() {
        val input = listOf(
            r("四川省人民政府", "https://www.sc.gov.cn/", "首页"),
            r("四川省_百度百科", "https://baike.baidu.com/item/四川省", "地理"),
            r("四川专升本 2027 政策解读", "https://scea.cn/zsb/2027", "报名时间 考试科目"),
        )
        val out = WebSearchResultPostProcessor.process(
            input,
            WebSearchQueryAnalyzer.analyze("四川专升本 2027 政策"),
            null,
            now,
        )
        assertTrue("门户/百科首页应被剔除", out.results.none { it.url == "https://www.sc.gov.cn/" || it.url.contains("baike.baidu.com") })
        assertTrue("真实内容页应保留", out.results.any { it.url.contains("scea.cn/zsb/2027") })
    }

    @Test
    fun `site 约束在结果层执行`() {
        val input = listOf(
            r("transformer 教程", "https://github.com/a/transformers"),
            r("transformer 教程", "https://zhihu.com/q/123"),
            r("transformer 教程", "https://csdn.net/a"),
        )
        val out = WebSearchResultPostProcessor.process(
            input,
            WebSearchQueryAnalyzer.analyze("site:github.com transformer"),
            null,
            now,
        )
        assertTrue("只应保留 github.com", out.results.all { it.url.contains("github.com") })
        assertEquals(1, out.results.size)
    }

    @Test
    fun `date_range 软过滤 超范围剔除 无法解析日期降权但不剔除`() {
        val input = listOf(
            r("今日科技新闻", "https://a.com/1", "3天前"),
            r("旧闻", "https://a.com/2", "2020-01-01 发布"),
            r("无日期科技新闻", "https://b.com/3", "内容提要"),
        )
        val out = WebSearchResultPostProcessor.process(
            input,
            WebSearchQueryAnalyzer.analyze("今天国内科技新闻", explicitDateRange = "past_week"),
            "past_week",
            now,
        )
        assertTrue("明显超范围的旧闻应剔除", out.results.none { it.url.endsWith("/2") })
        assertTrue("无日期结果应保留（降权不剔除）", out.results.any { it.url.endsWith("/3") })
        assertTrue("3天前的应保留", out.results.any { it.url.endsWith("/1") })
    }

    @Test
    fun `低质量词典与老黄历被降权或剔除`() {
        val dict = r("install 词典释义", "https://dictionary.cambridge.org/install", "definition of install")
        val good = r("Install Python on Android", "https://real.example.com/guide", "step by step")
        val plan = WebSearchQueryAnalyzer.analyze("how to install python on android")
        val out = WebSearchResultPostProcessor.process(listOf(dict, good), plan, null, now)
        assertEquals(good.url, out.results.first().url)
    }

    @Test
    fun `召回门禁 top 结果全不相关时标记失败`() {
        val noise = listOf(
            r("老黄历 今日宜忌", "https://huangli.com/today"),
            r("2027 日历", "https://calendar.com/2027"),
        )
        val out = WebSearchResultPostProcessor.process(
            noise,
            WebSearchQueryAnalyzer.analyze("四川专升本 2027 政策"),
            null,
            now,
        )
        assertFalse("全噪声时不应判定召回成功", out.recallOk)
    }

    @Test
    fun `加权排序 命中多个主题词的结果排前`() {
        val weak = r("安装指南", "https://a.com/install", "install")
        val strong = r("Install Python on Android", "https://b.com/guide", "python android install")
        val out = WebSearchResultPostProcessor.process(
            listOf(weak, strong),
            WebSearchQueryAnalyzer.analyze("how to install python on android"),
            null,
            now,
        )
        assertEquals(strong.url, out.results.first().url)
    }

    @Test
    fun `阶段计数可用于调试信息`() {
        val input = listOf(
            r("四川专升本政策", "https://sc.gov.cn/a", "政策"),
            r("四川专升本政策副本", "https://sc.gov.cn/a/?x=1", "政策"),
            r("无关", "https://x.com/", "首页"),
        )
        val out = WebSearchResultPostProcessor.process(
            input,
            WebSearchQueryAnalyzer.analyze("四川专升本政策"),
            null,
            now,
        )
        assertEquals(3, out.recalledCount)
        assertTrue(out.afterFilterCount <= out.recalledCount)
        assertTrue(out.afterDedupCount <= out.afterFilterCount)
        assertEquals(out.results.size, out.finalCount)
    }
}
