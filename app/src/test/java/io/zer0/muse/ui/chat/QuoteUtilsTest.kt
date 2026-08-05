package io.zer0.muse.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuoteUtilsTest {

    @Test
    fun buildQuotedContent_roundTripsThroughParser() {
        val content = buildQuotedContent("被引用的原消息", "这是我的回复")

        val (quote, body) = parseQuotedContent(content)

        assertEquals("被引用的原消息", quote)
        assertEquals("这是我的回复", body)
        assertNull(parseQuotedContent("普通消息").first)
        assertEquals("普通消息", parseQuotedContent("普通消息").second)
    }

    @Test
    fun buildQuotedContent_multilineQuoteKeepsAllLines() {
        val content = buildQuotedContent("第一行\n第二行", "正文")

        val (quote, body) = parseQuotedContent(content)

        assertEquals("第一行\n第二行", quote)
        assertEquals("正文", body)
    }

    @Test
    fun buildQuotedContent_emptyQuoteReturnsBodyOnly() {
        assertEquals("只有正文", buildQuotedContent("", "只有正文"))
    }
}
