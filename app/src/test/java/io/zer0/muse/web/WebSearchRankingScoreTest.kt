package io.zer0.muse.web

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索结果排序：低质量结果**降权而不是丢弃**。
 *
 * 修复前 `normalizeResults` 用 `.filter { !isLowQualityResult(...) }` 把命中黑名单的结果整条删掉。
 * 黑名单里是「字典」「词典」「基本解释」这类**裸子串**，中文查询的正文里出现它们是常态 ——
 * 于是一条本来相关的结果也可能被删光，用户看到的就是「搜了 4.7 秒、零结果」。
 *
 * 这里钉住两件事：①低质量结果的分数确实被压下去；②它仍然可能被选中（不是被删掉）。
 */
class WebSearchRankingScoreTest {

    private fun result(title: String, snippet: String, url: String) =
        WebSearchResult(title = title, url = url, snippet = snippet, source = "test")

    @Test
    fun `命中词典类站点时分数被压到低于同类正常结果`() {
        val query = "四川专升本 政策"
        val normal = result("四川专升本政策解读", "2024 年起统考，三科各 150 分", "https://edu.example.com/a")
        val dictLike = result("专升本是什么意思", "词典 基本解释", "https://zdic.net/b")

        val normalScore = AutoWebSearchService.rankingScore(query, normal)
        val dictScore = AutoWebSearchService.rankingScore(query, dictLike)

        assertTrue("低质量结果应被降权: normal=$normalScore dict=$dictScore", dictScore < normalScore)
    }

    @Test
    fun `低质量不等于零分 仍可能被选中`() {
        // 只要不是被丢弃，分数就仍然是一个有限值 —— 全部候选都很差时用户还能拿到点东西。
        val score = AutoWebSearchService.rankingScore(
            "测试",
            result("测试是什么意思", "词典 释义", "https://zdic.net/x"),
        )
        assertTrue("不能是 -Int.MAX 之类的哨兵，否则等于丢弃", score > -1000)
    }

    @Test
    fun `惩罚值足以让低质量沉底 但不至于抹掉相关性`() {
        val query = "四川专升本"
        // 标题与摘要都命中关键词的正常结果
        val relevant = result("四川专升本 2024 统考政策", "四川专升本 三科", "https://a.example.com")
        val relevantScore = AutoWebSearchService.rankingScore(query, relevant)
        assertTrue("正常结果应为正分: $relevantScore", relevantScore > 0)
        assertTrue(
            "惩罚要比一次命中关键词的分高，否则压不下去",
            AutoWebSearchService.LOW_QUALITY_PENALTY > 3,
        )
    }
}
