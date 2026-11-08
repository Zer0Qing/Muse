package io.zer0.muse.ui.artifact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 37：产物「渲染形态 + 可用动作」的纯判据。
 *
 * 修的是用户两个痛点：① image/file 点开只有一串原始文本（应按类型给真正的查看形态）；
 * ② 展开后是个「什么都干不了的预览框」（每种类型都要有一组实在的动作，而不是只有复制）。
 */
class ArtifactCapabilitiesTest {

    @Test
    fun `富内容按 type 或 language 判为网页形态`() {
        assertEquals(ArtifactRenderMode.RichWeb, artifactRenderMode("html", null))
        assertEquals(ArtifactRenderMode.RichWeb, artifactRenderMode("svg", null))
        assertEquals(ArtifactRenderMode.RichWeb, artifactRenderMode("chart", null))
        assertEquals(ArtifactRenderMode.RichWeb, artifactRenderMode("mermaid", null))
        // 抽取器常把 HTML 归为 type=code + language=html
        assertEquals(ArtifactRenderMode.RichWeb, artifactRenderMode("code", "html"))
    }

    @Test
    fun `图片不再退化成纯文本`() {
        assertEquals(ArtifactRenderMode.Image, artifactRenderMode("image", null))
    }

    @Test
    fun `结构化文本走 Markdown 渲染`() {
        listOf("markdown", "document", "table", "json", "csv").forEach {
            assertEquals(it, ArtifactRenderMode.Markdown, artifactRenderMode(it, null))
        }
    }

    @Test
    fun `代码与其余文本各有形态`() {
        assertEquals(ArtifactRenderMode.Code, artifactRenderMode("code", "kotlin"))
        assertEquals(ArtifactRenderMode.PlainText, artifactRenderMode("text", null))
        assertEquals(ArtifactRenderMode.PlainText, artifactRenderMode("file", null))
        assertEquals(ArtifactRenderMode.PlainText, artifactRenderMode("", null))
    }

    @Test
    fun `每种形态都至少能复制保存分享与外部打开`() {
        // 「什么都干不了」的根治：任何类型都不许只剩一个复制按钮。
        val modes = listOf(
            artifactRenderMode("html", null),
            artifactRenderMode("image", null),
            artifactRenderMode("markdown", null),
            artifactRenderMode("code", "kotlin"),
            artifactRenderMode("text", null),
        )
        modes.forEach { mode ->
            val actions = artifactActions(mode)
            assertTrue("$mode 缺复制", ArtifactAction.Copy in actions)
            assertTrue("$mode 缺保存", ArtifactAction.Save in actions)
            assertTrue("$mode 缺分享", ArtifactAction.Share in actions)
            assertTrue("$mode 缺外部打开", ArtifactAction.OpenExternal in actions)
        }
    }

    @Test
    fun `只有网页形态提供源码切换与全屏`() {
        assertTrue(ArtifactAction.ToggleSource in artifactActions(ArtifactRenderMode.RichWeb))
        assertTrue(ArtifactAction.Fullscreen in artifactActions(ArtifactRenderMode.RichWeb))
        assertFalse(ArtifactAction.ToggleSource in artifactActions(ArtifactRenderMode.Image))
        assertFalse(ArtifactAction.ToggleSource in artifactActions(ArtifactRenderMode.Code))
        // 图片要能全屏查看，代码/文本不需要单独的全屏网页
        assertTrue(ArtifactAction.Fullscreen in artifactActions(ArtifactRenderMode.Image))
        assertFalse(ArtifactAction.Fullscreen in artifactActions(ArtifactRenderMode.Code))
    }

    @Test
    fun `data-uri 图片解码出字节而非把地址当图`() {
        // "MQ==" 是字符 '1' 的 base64
        val bytes = artifactImageBytes("data:image/png;base64,MQ==")
        assertEquals(listOf<Byte>(0x31), bytes?.toList())
    }

    @Test
    fun `非 data-uri 返回 null 交给 URL 或文件加载`() {
        assertEquals(null, artifactImageBytes("https://example.com/a.png"))
        assertEquals(null, artifactImageBytes("/sdcard/a.png"))
        assertEquals(null, artifactImageBytes(""))
    }
}
