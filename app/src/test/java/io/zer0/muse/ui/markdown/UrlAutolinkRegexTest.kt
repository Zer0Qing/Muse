package io.zer0.muse.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.97 阶段二: URL_AUTOLINK_REGEX 边界用例测试。
 *
 * 验证纯文本 URL 自动识别正则的匹配/排除行为:
 *  - 标准 http/https URL 全匹配
 *  - URL 含 query string / 锚点 / 路径 全匹配
 *  - URL 后跟空白/引号/括号/中文标点时,标点不纳入匹配
 *  - 纯文本无 URL 不匹配
 *  - 多个 URL 各自独立匹配
 *  - 非小写 http/https 协议不匹配(预期行为,大写 URL 由 markdown [text](url) 处理)
 *  - ftp/mailto 等非 http 协议不匹配
 *
 * 参考: openhanako trimAutoLinkifiedSuffixes — 排除尾部中文标点(。、,;:!?」』)。
 */
class UrlAutolinkRegexTest {

    /** 提取文本中所有 URL 匹配结果为字符串列表(便于断言)。 */
    private fun urls(input: String): List<String> =
        URL_AUTOLINK_REGEX.findAll(input).map { it.value }.toList()

    // ── 标准匹配 ──

    @Test
    fun `standard https url matches fully`() {
        assertEquals(listOf("https://example.com"), urls("https://example.com"))
    }

    @Test
    fun `standard http url matches fully`() {
        assertEquals(listOf("http://example.com"), urls("http://example.com"))
    }

    @Test
    fun `url with path matches fully`() {
        assertEquals(
            listOf("https://example.com/path/to/resource"),
            urls("https://example.com/path/to/resource"),
        )
    }

    @Test
    fun `url with query string matches fully`() {
        assertEquals(
            listOf("https://example.com/search?q=kotlin&p=1"),
            urls("https://example.com/search?q=kotlin&p=1"),
        )
    }

    @Test
    fun `url with anchor matches fully`() {
        assertEquals(
            listOf("https://example.com/guide#section-2"),
            urls("https://example.com/guide#section-2"),
        )
    }

    @Test
    fun `url with port matches fully`() {
        assertEquals(
            listOf("http://localhost:8080/api/v1"),
            urls("http://localhost:8080/api/v1"),
        )
    }

    // ── 尾部分隔符排除 ──

    @Test
    fun `url followed by space excludes space`() {
        assertEquals(
            listOf("https://example.com"),
            urls("see https://example.com for more"),
        )
    }

    @Test
    fun `url followed by closing paren excludes paren`() {
        assertEquals(
            listOf("https://example.com"),
            urls("(see https://example.com)"),
        )
    }

    @Test
    fun `url followed by closing bracket excludes bracket`() {
        assertEquals(
            listOf("https://example.com"),
            urls("[link: https://example.com]"),
        )
    }

    @Test
    fun `url followed by double quote excludes quote`() {
        assertEquals(
            listOf("https://example.com"),
            urls("href=\"https://example.com\""),
        )
    }

    @Test
    fun `url followed by chinese period excludes period`() {
        // 中文句号应被排除(openhanako trimAutoLinkifiedSuffixes)
        assertEquals(
            listOf("https://example.com"),
            urls("查看 https://example.com。这是下一句。"),
        )
    }

    @Test
    fun `url followed by chinese comma excludes comma`() {
        assertEquals(
            listOf("https://example.com"),
            urls("访问 https://example.com,即可看到"),
        )
    }

    @Test
    fun `url followed by chinese semicolon excludes it`() {
        assertEquals(
            listOf("https://example.com"),
            urls("网址 https://example.com;记得收藏"),
        )
    }

    @Test
    fun `url followed by chinese fullwidth exclamation excludes it`() {
        assertEquals(
            listOf("https://example.com"),
            urls("快看 https://example.com!太棒了"),
        )
    }

    @Test
    fun `url followed by chinese right corner quote excludes it`() {
        // 「」右角引号应被排除
        assertEquals(
            listOf("https://example.com"),
            urls("「官网: https://example.com」"),
        )
    }

    @Test
    fun `url followed by ascii period includes period`() {
        // ASCII 句点 `.` 在 URL 内合法(example.com),正则不排除,句末 `.` 会被纳入匹配。
        // 句末标点的二次清理由 openhanako trimAutoLinkifiedSuffixes 在调用处负责,
        // 此处仅做纯文本识别,保持字符类简单。
        assertEquals(
            listOf("https://example.com."),
            urls("Visit https://example.com."),
        )
    }

    @Test
    fun `url followed by ascii comma excludes comma`() {
        assertEquals(
            listOf("https://example.com"),
            urls("Visit https://example.com, and explore."),
        )
    }

    // ── 多 URL 与无 URL ──

    @Test
    fun `multiple urls match independently`() {
        assertEquals(
            listOf("https://a.com", "https://b.com"),
            urls("first https://a.com then https://b.com end"),
        )
    }

    @Test
    fun `plain text without url produces no matches`() {
        assertTrue(urls("hello world, 这是普通文本").isEmpty())
    }

    @Test
    fun `text mentioning protocol without scheme does not match`() {
        // "example.com" 不带 http/https 前缀不匹配(避免误识别)
        assertTrue(urls("visit example.com sometime").isEmpty())
    }

    // ── 非预期行为(明确不匹配)──

    @Test
    fun `uppercase https does not match`() {
        // 正则区分大小写,大写 HTTPS 不匹配(预期行为)
        assertTrue(urls("HTTPS://EXAMPLE.COM").isEmpty())
    }

    @Test
    fun `ftp protocol does not match`() {
        assertTrue(urls("ftp://files.example.com").isEmpty())
    }

    @Test
    fun `mailto protocol does not match`() {
        assertTrue(urls("mailto:user@example.com").isEmpty())
    }

    @Test
    fun `url embedded in chinese text matches only the url`() {
        // 嵌在中文句子中的 URL:URL 前后是空格,正则从 https 开始匹配,到下一个空格结束。
        // 中文句子的其余部分不影响 URL 识别。
        val matches = urls("你可以访问 https://example.com/path 来了解详情")
        assertEquals(1, matches.size)
        assertEquals("https://example.com/path", matches[0])
    }
}
