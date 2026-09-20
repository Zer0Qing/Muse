package io.zer0.muse.ui.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2: RichContentCard 全屏预览语言覆盖测试。
 *
 * 锁定两件事:
 *  - html/svg 保留原有导航预览能力;
 *  - chart/mermaid 也必须有全屏预览路径(依赖本地 assets 脚本,走卡片内全屏 WebView)。
 */
class RichContentPreviewTest {

    @Test
    fun `html 与 svg 支持全屏预览`() {
        assertTrue(richContentSupportsPreview("html"))
        assertTrue(richContentSupportsPreview("svg"))
    }

    @Test
    fun `chart 与 mermaid 支持全屏预览`() {
        assertTrue(richContentSupportsPreview("chart"))
        assertTrue(richContentSupportsPreview("mermaid"))
    }

    @Test
    fun `语言大小写与空白容错`() {
        assertTrue(richContentSupportsPreview(" Mermaid "))
    }

    @Test
    fun `普通代码语言不支持预览`() {
        assertFalse(richContentSupportsPreview("kotlin"))
        assertFalse(richContentSupportsPreview(""))
    }

    @Test
    fun `chart 与 mermaid 走卡片内本地预览而非导航预览`() {
        assertTrue(RICH_LOCAL_PREVIEW_LANGUAGES.contains("chart"))
        assertTrue(RICH_LOCAL_PREVIEW_LANGUAGES.contains("mermaid"))
        assertFalse(RICH_LOCAL_PREVIEW_LANGUAGES.contains("html"))
        assertFalse(RICH_LOCAL_PREVIEW_LANGUAGES.contains("svg"))
    }
}
