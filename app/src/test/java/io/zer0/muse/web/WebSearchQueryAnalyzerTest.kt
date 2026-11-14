package io.zer0.muse.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 联网搜索查询分析：报告里「主题词坍缩」的根因在代码里已被证伪（整句透传），
 * 但结果侧确实需要「主题词 + 权重 + 时效词信号 + 操作符」来判相关性和过滤。
 * 这里的分析产物只作用于排序/门禁/约束，绝不替换发给上游的整句 query。
 */
class WebSearchQueryAnalyzerTest {

    @Test
    fun `整句查询原样透传 不缩成单词`() {
        val raw = "What did OpenAI release most recently?"
        val plan = WebSearchQueryAnalyzer.analyze(raw)
        assertEquals(raw, plan.outboundQuery)
        assertTrue("OpenAI 必须是主题词", plan.topics.any { it.text.equals("OpenAI", ignoreCase = true) })
        assertTrue("did 是虚词，不该当主题词", plan.topics.none { it.text.equals("did", ignoreCase = true) })
    }

    @Test
    fun `中文虚词与单字不进主题词`() {
        val plan = WebSearchQueryAnalyzer.analyze("今天国内科技新闻")
        assertTrue("今天 是虚词", plan.topics.none { it.text == "今天" })
        // 中文不做分词：清洗虚词后「国内科技新闻」整段保留为主题词。
        assertTrue("清洗后的连续中文串应整段保留", plan.topics.any { it.text.contains("科技") && it.text.contains("新闻") })
        assertTrue(plan.topics.none { it.text.length == 1 })
        // 整段保留的收益：门户首页/老黄历不会误命中，真实主题页会命中。
        assertTrue(plan.hasTopicHit("国内科技新闻速递", ""))
        assertFalse(plan.hasTopicHit("老黄历 今日宜忌", ""))
    }

    @Test
    fun `how to install python on android 保留内容词`() {
        val plan = WebSearchQueryAnalyzer.analyze("how to install python on android")
        val words = plan.topics.map { it.text.lowercase() }
        assertTrue("python 应在", "python" in words)
        assertTrue("android 应在", "android" in words)
        assertTrue("how/to/on 是虚词", "how" !in words && "to" !in words && "on" !in words)
    }

    @Test
    fun `时效词转成 date_range 信号且不丢弃词`() {
        assertEquals("past_day", WebSearchQueryAnalyzer.analyze("今天国内科技新闻").inferredDateRange)
        assertEquals("past_week", WebSearchQueryAnalyzer.analyze("最近国内科技圈有什么值得关注的新消息吗").inferredDateRange)
        assertEquals("past_week", WebSearchQueryAnalyzer.analyze("What did OpenAI release most recently?").inferredDateRange)
        assertEquals("past_month", WebSearchQueryAnalyzer.analyze("本月新发布政策").inferredDateRange)
        // 显式传参优先于推断
        assertEquals("past_year", WebSearchQueryAnalyzer.analyze("今天新闻", explicitDateRange = "past_year").effectiveDateRange)
    }

    @Test
    fun `site 操作符被预提取并保留在 outbound query`() {
        val plan = WebSearchQueryAnalyzer.analyze("site:github.com transformer")
        assertEquals(listOf("github.com"), plan.constraints.domains)
        assertEquals("site:github.com transformer", plan.outboundQuery)
        assertTrue("site 域名不是主题词", plan.topics.none { it.text.equals("github.com", ignoreCase = true) })
    }

    @Test
    fun `中文 site 操作符与后续词`() {
        val plan = WebSearchQueryAnalyzer.analyze("site:sc.gov.cn 专升本 2027")
        assertEquals(listOf("sc.gov.cn"), plan.constraints.domains)
        assertTrue("site 约束应同时交给上游引擎", plan.outboundQuery.contains("site:sc.gov.cn"))
        assertTrue(plan.outboundQuery.contains("专升本"))
    }

    @Test
    fun `filetype 与 intitle 操作符`() {
        val plan = WebSearchQueryAnalyzer.analyze("filetype:pdf intitle:政策 专升本")
        assertEquals("pdf", plan.constraints.filetype)
        assertEquals(listOf("政策"), plan.constraints.titleContains)
        assertFalse(plan.outboundQuery.contains("filetype:"))
        assertFalse(plan.outboundQuery.contains("intitle:"))
    }

    @Test
    fun `URL 不被误判为操作符`() {
        val plan = WebSearchQueryAnalyzer.analyze("https://example.com/a:b 查询")
        assertTrue(plan.constraints.isEmpty())
        assertTrue(plan.outboundQuery.contains("https://"))
    }

    @Test
    fun `未知操作符被剥离并记录 不污染查询`() {
        val plan = WebSearchQueryAnalyzer.analyze("foo:bar 专升本")
        assertTrue(plan.unsupportedOperators.contains("foo"))
        assertFalse(plan.outboundQuery.contains("foo:"))
    }

    @Test
    fun `主题词权重 高区分度词高于通用词`() {
        val plan = WebSearchQueryAnalyzer.analyze("四川专升本 2027 考试政策 报名时间")
        val topic = plan.topics.first { it.text == "四川专升本" }
        assertTrue(topic.weight > 0.0)
        // 门户首页只命中地名，不该通过门禁；真实政策页命中主题串。
        assertTrue(plan.hasTopicHit("四川专升本 2027 考试政策解读", ""))
        assertFalse(plan.hasTopicHit("四川省人民政府首页", ""))
    }

    @Test
    fun `空查询与纯虚词不崩`() {
        assertTrue(WebSearchQueryAnalyzer.analyze("").topics.isEmpty())
        assertTrue(WebSearchQueryAnalyzer.analyze("   ").outboundQuery.isBlank())
        assertTrue(WebSearchQueryAnalyzer.analyze("请问 谢谢").topics.isEmpty())
    }

    @Test
    fun `结果是否满足约束 domain filetype intitle`() {
        val plan = WebSearchQueryAnalyzer.analyze("site:github.com filetype:pdf intitle:transformer 代码")
        assertTrue(plan.constraints.matches("https://github.com/a/paper.pdf", "Transformer 代码", ""))
        assertFalse("域名不符", plan.constraints.matches("https://zhihu.com/a.pdf", "Transformer 代码", ""))
        assertFalse("标题不含", plan.constraints.matches("https://github.com/a.pdf", "无关标题", ""))
    }

    @Test
    fun `主题词命中判定 忽略大小写且至少命中一个`() {
        val plan = WebSearchQueryAnalyzer.analyze("how to install python on android")
        assertTrue(plan.hasTopicHit("Install Python on Android", "step by step"))
        assertFalse(plan.hasTopicHit("Cambridge Dictionary", "definition of install"))
    }

    @Test
    fun `中文长主题词用 2-gram 共现 部分匹配能命中 门户首页仍被挡`() {
        val plan = WebSearchQueryAnalyzer.analyze("今天国内科技新闻")
        assertTrue("只共享部分词也应命中", plan.hasTopicHit("无日期科技新闻速递", ""))
        assertFalse(
            "只共享地名的门户首页不该命中",
            WebSearchQueryAnalyzer.analyze("四川专升本 2027 政策")
                .hasTopicHit("四川省人民政府办公厅", "机构职能"),
        )
    }
}
