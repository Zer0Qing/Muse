package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置浏览器地址栏的解析（整改计划 第 2 条的核心）。
 *
 * 修的实际问题：以前地址栏只做"没协议就补 https://"，
 * 输入 `天气` 就变成 `https://天气` —— 用户看到一个打不开的页面。
 * 现在按普通浏览器的做法分三类：完整网址直接开、像主机名的补协议、其余交给搜索引擎。
 */
class BrowserTargetResolveTest {

    @Test
    fun `空输入回到空白页而不是搜一个空串`() {
        assertEquals("about:blank", resolveBrowserTarget("   ", BrowserSearchEngine.BING))
    }

    @Test
    fun `已带协议的地址原样交给下游 SSRF 守卫`() {
        for (raw in listOf(
            "https://example.com/a?b=1",
            "http://example.com",
            "about:blank",
            "file:///sdcard/x.html",
        )) {
            assertEquals("不该被改写：$raw", raw, resolveBrowserTarget(raw, BrowserSearchEngine.BING))
        }
    }

    @Test
    fun `完整网址补上协议`() {
        assertEquals("https://example.com", resolveBrowserTarget("example.com", BrowserSearchEngine.BING))
        assertEquals(
            "带路径与查询也要当网址",
            "https://example.com:8443/path?x=1",
            resolveBrowserTarget("example.com:8443/path?x=1", BrowserSearchEngine.BING),
        )
    }

    @Test
    fun `本机与局域网地址不强迫用户写 http`() {
        assertEquals("http://localhost:8080", resolveBrowserTarget("localhost:8080", BrowserSearchEngine.BING))
        assertEquals("http://192.168.1.10", resolveBrowserTarget("192.168.1.10", BrowserSearchEngine.BING))
    }

    @Test
    fun `普通关键词走所选引擎`() {
        val url = resolveBrowserTarget("天气", BrowserSearchEngine.BAIDU)
        assertTrue("百度该用自己的域名，实际 $url", url.startsWith("https://www.baidu.com/s?wd="))
        assertTrue("查询词要 URL 编码", url.contains("%E5%A4%A9%E6%B0%94"))
    }

    @Test
    fun `每个引擎的模板都真的带上了查询词`() {
        BrowserSearchEngine.entries.forEach { engine ->
            val url = resolveBrowserTarget("python tutorial", engine)
            assertTrue("${engine.id} 的模板没填查询词：$url", url.contains("python+tutorial") || url.contains("python%20tutorial"))
            assertTrue("${engine.id} 应是 https 且非空：$url", url.startsWith("https://") && url.length > 12)
        }
    }

    @Test
    fun `空格编码成 %20 而不是加号`() {
        // 加号在部分站点（尤其 DuckDuckGo 与 Google 的某些端点）不被当空格，
        // 关键词里带 "+" 的语义就会错；统一用 %20。
        val url = resolveBrowserTarget("c++ 教程", BrowserSearchEngine.DUCKDUCKGO)
        assertTrue("应含 %20：$url", url.contains("%20"))
        assertTrue("+ 号本身要保留编码：$url", url.contains("%2B%2B"))
    }

    @Test
    fun `不含点的词绝不会被当网址打开`() {
        listOf("天气", "news", "python 教程", "苹果 官网").forEach { word ->
            val url = resolveBrowserTarget(word, BrowserSearchEngine.BING)
            assertTrue("被误当网址了：$word → $url", url.startsWith("https://www.bing.com/"))
        }
    }

    @Test
    fun `未知引擎 id 回落到默认而不是崩`() {
        assertEquals(BrowserSearchEngine.BING, BrowserSearchEngine.of("不存在"))
        assertEquals(BrowserSearchEngine.BING, BrowserSearchEngine.of(null))
        assertEquals(BrowserSearchEngine.GOOGLE, BrowserSearchEngine.of("google"))
    }
}
