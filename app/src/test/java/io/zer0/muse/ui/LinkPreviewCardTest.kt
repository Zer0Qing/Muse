package io.zer0.muse.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Phase 2: 链接预览纯逻辑测试(域名提取 + 新状态字段默认值)。
 */
class LinkPreviewCardTest {

    @Test
    fun `linkDomain 去掉 www 前缀`() {
        assertEquals("example.com", linkDomain("https://www.example.com/a/b?c=1"))
    }

    @Test
    fun `linkDomain 保留子域名并去掉端口`() {
        assertEquals("sub.example.com", linkDomain("https://sub.example.com:8443/path"))
    }

    @Test
    fun `linkDomain 解析失败回退原始 URL`() {
        assertEquals("not a url", linkDomain("not a url"))
    }

    @Test
    fun `默认数据既非加载中也非失败`() {
        val data = LinkPreviewData(url = "https://example.com")
        assertFalse(data.isLoading)
        assertFalse(data.isFailed)
    }

    @Test
    fun `纯文本不创建链接预览状态`() {
        assertFalse(shouldResolveLinkPreviews("这是一段没有链接的长消息。"))
        assertFalse(shouldResolveLinkPreviews("ftp://example.com 不是网页链接"))
    }

    @Test
    fun `包含网页链接的正文保留链接预览`() {
        assertEquals(true, shouldResolveLinkPreviews("参考 https://example.com/article"))
        assertEquals(true, shouldResolveLinkPreviews("参考 HTTPS://EXAMPLE.COM/article"))
    }
}
