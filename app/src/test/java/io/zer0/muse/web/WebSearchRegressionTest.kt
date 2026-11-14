package io.zer0.muse.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断报告 12 条查询的回归集，按**真实根因**断言：
 * 整句是否原样到达 provider、操作符/时效/去重/导航页/门禁是否生效、
 * 是否出现黑名单词。不联网、不判断"结果像不像样"。
 */
class WebSearchRegressionTest {

    private fun r(title: String, url: String, snippet: String = "") =
        WebSearchResult(title = title, url = url, snippet = snippet, source = "test")

    private val now = 1730000000000L

    private fun run(q: String, results: List<WebSearchResult>, dateRange: String? = null) =
        WebSearchQueryAnalyzer.analyze(q, explicitDateRange = dateRange).let { plan ->
            plan to WebSearchResultPostProcessor.process(results, plan, plan.effectiveDateRange, now)
        }

    @Test
    fun `01 红烧肉 做法 步骤 相关菜谱通过`() {
        val (plan, out) = run(
            "红烧肉 做法 步骤",
            listOf(r("红烧肉的家常做法步骤", "https://zhihu.com/q/1", "五花肉 冰糖")),
        )
        assertEquals("红烧肉 做法 步骤", plan.outboundQuery)
        assertTrue(out.recallOk)
    }

    @Test
    fun `02 OpenAI 最新发布 模型 时效信号推断`() {
        val (plan, _) = run("OpenAI 最新发布 模型", emptyList())
        assertEquals("past_week", plan.effectiveDateRange)
        assertTrue(plan.topics.any { it.text.equals("OpenAI", ignoreCase = true) })
    }

    @Test
    fun `05 四川专升本 门户首页与百科被剔除 真政策页保留`() {
        val (_, out) = run(
            "四川专升本 2027 考试政策 报名时间",
            listOf(
                r("四川省_百度百科", "https://baike.baidu.com/item/四川省"),
                r("四川省人民政府", "https://www.sc.gov.cn/"),
                r("四川省人民政府", "https://www.sc.gov.cn/?fanwww"),
                r("四川专升本 2027 考试政策及报名时间", "https://scea.cn/zsb/2027", "专升本 报名"),
            ),
        )
        assertTrue("门户/百科不该进结果", out.results.none { it.url.contains("sc.gov.cn") || it.url.contains("baike.baidu.com") })
        assertTrue("真政策页应保留", out.results.any { it.url.contains("scea.cn") })
    }

    @Test
    fun `07 今天国内科技新闻 past_day 老黄历与日历被挡`() {
        val (plan, out) = run(
            "今天国内科技新闻",
            listOf(
                r("老黄历 今日宜忌", "https://huangli.com/today"),
                r("2027 日历", "https://calendar.com/2027"),
                r("今天诗刊词条", "https://baike.baidu.com/item/今天"),
                r("国内科技新闻速递", "https://tech.example.com/today", "2小时前 科技 新闻"),
            ),
            dateRange = "past_day",
        )
        assertEquals("past_day", plan.effectiveDateRange)
        // 显式 past_day 下，「3天前」会被软过滤正确剔除，这里用与之相符的 2 小时前验证保留路径。
        assertTrue("真新闻应保留", out.results.any { it.url.contains("tech.example.com") })
        assertFalse("黑名单词不该进结果", out.results.any { it.title.contains("老黄历") || it.title.contains("日历") })
    }

    @Test
    fun `09 What did OpenAI release most recently DID 教程被门禁挡掉`() {
        val (plan, out) = run(
            "What did OpenAI release most recently?",
            listOf(
                r("双重差分法 DID 教程", "https://econ.example.com/did", "Difference-in-Differences"),
                r("What did OpenAI release most recently", "https://openai.com/news", "OpenAI release"),
            ),
        )
        assertEquals("整句必须原样透传", "What did OpenAI release most recently?", plan.outboundQuery)
        assertFalse("did 不能当主题词", plan.topics.any { it.text.equals("did", ignoreCase = true) })
        assertTrue("DID 教程不该排第一", out.results.firstOrNull()?.url?.contains("openai.com") == true)
    }

    @Test
    fun `10 how to install python on android 词典页被挡 真教程保留`() {
        val (plan, out) = run(
            "how to install python on android",
            listOf(
                r("install 剑桥词典释义", "https://dictionary.cambridge.org/install", "definition of install"),
                r("Install Python on Android", "https://real.example.com/guide", "python android install"),
            ),
        )
        assertTrue("python/android 应是主题词", plan.topics.map { it.text.lowercase() }.containsAll(listOf("python", "android")))
        assertTrue("真教程应排第一", out.results.first().url.contains("real.example.com"))
    }

    @Test
    fun `11 site github transformer 只保留 github`() {
        val (plan, out) = run(
            "site:github.com transformer",
            listOf(
                r("transformer 知乎", "https://zhihu.com/q/1"),
                r("transformer 菜鸟教程", "https://runoob.com/t"),
                r("transformers 源码", "https://github.com/huggingface/transformers"),
            ),
        )
        assertEquals("site:github.com transformer", plan.outboundQuery)
        assertEquals(listOf("github.com"), plan.constraints.domains)
        assertTrue("必须全是 github.com", out.results.all { it.url.contains("github.com") })
        assertEquals(1, out.results.size)
    }

    @Test
    fun `12 site sc gov cn 专升本 2027 汉字百科被挡`() {
        val (plan, out) = run(
            "site:sc.gov.cn 专升本 2027",
            listOf(
                r("专 字 百度百科", "https://baike.baidu.com/item/专"),
                r("四川专升本 2027 政策", "https://www.sc.gov.cn/zsb/2027", "专升本"),
            ),
        )
        assertEquals(listOf("sc.gov.cn"), plan.constraints.domains)
        assertTrue("site 约束应保留在 outboundQuery", plan.outboundQuery.contains("site:sc.gov.cn"))
        assertTrue(out.results.all { it.url.contains("sc.gov.cn") })
    }

    @Test
    fun `同域名最多 2 条 不再刷屏`() {
        val (_, out) = run(
            "四川专升本政策",
            listOf(
                r("四川专升本政策一", "https://sc.gov.cn/a", "政策"),
                r("四川专升本政策二", "https://sc.gov.cn/b", "政策"),
                r("四川专升本政策三", "https://sc.gov.cn/c", "政策"),
                r("四川专升本政策四", "https://scea.cn/a", "政策"),
            ),
        )
        assertTrue(out.results.count { it.url.contains("sc.gov.cn") } <= 2)
    }

    @Test
    fun `全噪声时标记召回失败 供上层返回空加原因`() {
        val (_, out) = run(
            "四川专升本 2027 政策",
            listOf(
                r("老黄历", "https://huangli.com/x"),
                r("2027 日历", "https://calendar.com/y"),
            ),
        )
        assertFalse(out.recallOk)
        assertTrue(out.reason!!.contains("没有一条与查询主题相关"))
    }
}
