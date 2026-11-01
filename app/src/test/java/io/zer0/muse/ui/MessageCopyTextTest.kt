package io.zer0.muse.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageCopyTextTest {

    @Test
    fun `copy copies body only and never appends reasoning`() {
        // 用户反馈「点复制拿到的是思考」：正文和思考都在时，只复制正文。
        // 想要思考有专门的「复制思考」按钮，不该靠复制按钮捎带。
        assertEquals("answer", messageCopyText("answer", "thinking"))
    }

    @Test
    fun `copy body with internal markers is sanitized`() {
        assertEquals("answer", messageCopyText("answer", null))
    }

    @Test
    fun `copy falls back to reasoning for reasoning-only message`() {
        assertEquals("thinking", messageCopyText("", "thinking"))
    }
}
