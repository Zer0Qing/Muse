package io.zer0.muse.ui.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 朗读前的文本清洗：不该念出来的东西要被剥掉。
 *
 * 最容易被忽略的是 HTML 标记——模型偶尔用 `<b>`、`<br>`、`<span style=…>` 排版，
 * 直接送进 TTS 会被逐字念成「小于 b 大于」。Markdown 那边早已覆盖，HTML 是漏的。
 */
class TtsSpeakableTextTest {

    private fun clean(text: String): String = TtsManager.stripMarkdown(text)

    @Test
    fun `HTML 标签被剥掉 文字留下`() {
        assertEquals("这是重点", clean("<b>这是重点</b>"))
        assertEquals("第一行", clean("<span style=\"color:red\">第一行</span>"))
        assertEquals("甲", clean("<br/>甲"))
    }

    @Test
    fun `不会把标签当成要念的内容`() {
        val out = clean("<p>你好</p><p>再见</p>")
        assertFalse("不该残留尖括号: $out", out.contains("<"))
        assertFalse("不该残留尖括号: $out", out.contains(">"))
        assertTrue(out.contains("你好"))
        assertTrue(out.contains("再见"))
    }

    @Test
    fun `代码块不朗读`() {
        assertEquals("看这段", clean("看这段\n```\nval x = 1\n```"))
    }

    @Test
    fun `链接只留文字`() {
        assertEquals("点这里", clean("[点这里](https://example.com)"))
    }

    @Test
    fun `普通文字原样保留`() {
        assertEquals("今天天气不错。", clean("今天天气不错。"))
    }
}
