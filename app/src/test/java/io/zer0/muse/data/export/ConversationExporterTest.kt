package io.zer0.muse.data.export

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * [ConversationExporter] 的纯函数部分(Markdown / HTML)单测。
 *
 * 只覆盖不依赖 Android 的导出路径;PDF 依赖 [android.graphics.pdf.PdfDocument],
 * 在 JVM 单测里无法实例化,故不在本测试范围(需要 instrumented / Robolectric)。
 */
class ConversationExporterTest {

    private fun msg(
        role: MessageRole,
        content: String,
        reasoning: String? = null,
        createdAt: Long = 1_700_000_000_000L,
    ) = UIMessage(role = role, content = content, reasoning = reasoning, createdAt = createdAt)

    @Test
    fun `markdown starts with title and uses english role labels for non-zh`() {
        val out = ConversationExporter.exportToMarkdown(
            listOf(msg(MessageRole.USER, "hello"), msg(MessageRole.ASSISTANT, "hi there")),
            chatTitle = "My Chat",
            locale = Locale.ENGLISH,
        )
        assertTrue("应以 # 标题开头", out.startsWith("# My Chat"))
        assertTrue("应含英文角色标签", out.contains("**User**: hello"))
        assertTrue("应含英文助手标签", out.contains("**Assistant**: hi there"))
    }

    @Test
    fun `markdown uses chinese role labels for zh locale`() {
        val out = ConversationExporter.exportToMarkdown(
            listOf(msg(MessageRole.USER, "你好"), msg(MessageRole.ASSISTANT, "在的")),
            chatTitle = "会话",
            locale = Locale.SIMPLIFIED_CHINESE,
        )
        assertTrue(out.contains("**用户**: 你好"))
        assertTrue(out.contains("**助手**: 在的"))
    }

    @Test
    fun `markdown marks empty assistant reply instead of leaving a blank`() {
        val out = ConversationExporter.exportToMarkdown(
            listOf(msg(MessageRole.ASSISTANT, "")),
            chatTitle = "t",
            locale = Locale.ENGLISH,
        )
        assertTrue("空回复应标注 (Empty reply)", out.contains("(Empty reply)"))
    }

    @Test
    fun `markdown keeps reasoning as its own section`() {
        val out = ConversationExporter.exportToMarkdown(
            listOf(msg(MessageRole.ASSISTANT, "answer", reasoning = "because 1+1=2")),
            chatTitle = "t",
            locale = Locale.ENGLISH,
        )
        assertTrue(out.contains("### Thinking"))
        assertTrue(out.contains("because 1+1=2"))
    }

    @Test
    fun `html is a standalone document with lang and escaped title`() {
        val out = ConversationExporter.exportToHtml(
            listOf(msg(MessageRole.USER, "hi")),
            chatTitle = "A & B <x>",
            locale = Locale.ENGLISH,
        )
        assertTrue(out.startsWith("<!DOCTYPE html>"))
        assertTrue("英文 locale 应输出 lang=en", out.contains("lang=\"en\""))
        assertTrue("<title> 应转义", out.contains("<title>A &amp; B &lt;x&gt;</title>"))
    }

    @Test
    fun `html escapes message content so raw tags cannot leak`() {
        val out = ConversationExporter.exportToHtml(
            listOf(msg(MessageRole.USER, "<script>alert(1)</script>")),
            chatTitle = "t",
            locale = Locale.ENGLISH,
        )
        assertTrue("正文应被转义", out.contains("&lt;script&gt;"))
        assertTrue("不得出现可执行的原始 script 标签", !out.contains("<script>alert"))
    }

    @Test
    fun `html renders fenced code block as pre code with language class`() {
        val out = ConversationExporter.exportToHtml(
            listOf(msg(MessageRole.ASSISTANT, "```kotlin\nval x = 1\n```")),
            chatTitle = "t",
            locale = Locale.ENGLISH,
        )
        assertTrue(out.contains("<pre><code class=\"language-kotlin\">"))
        assertTrue(out.contains("val x = 1"))
    }

    @Test
    fun `html uses zh lang for chinese locale`() {
        val out = ConversationExporter.exportToHtml(
            listOf(msg(MessageRole.USER, "你好")),
            chatTitle = "会话",
            locale = Locale.SIMPLIFIED_CHINESE,
        )
        assertTrue(out.contains("lang=\"zh-CN\""))
    }

    @Test
    fun `html inlines base64 image and downgrades remote url to a safe link`() {
        val m = UIMessage(
            role = MessageRole.USER,
            content = "pic",
            imageBase64List = listOf("AAAA"),
            imageUrls = listOf("https://example.com/a.png"),
            createdAt = 1L,
        )
        val out = ConversationExporter.exportToHtml(listOf(m), "t", Locale.ENGLISH)
        assertTrue("base64 图应内联为 data URI", out.contains("src=\"data:image/jpeg;base64,AAAA\""))
        assertTrue("远程图应降级为带 rel 的链接", out.contains("rel=\"noopener noreferrer\""))
    }
}
