package io.zer0.muse.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v2.6.2（用户反馈）: 三层聊天背景回落优先级。
 *
 * 优先级：会话级 > 助手级 > 全局默认。
 */
class ChatBackgroundResolverTest {

    private val assistantBg = mapOf("default" to "file:///assistant_default.jpg")
    private val sessionBg = mapOf("s1" to "file:///session_s1.jpg")

    @Test
    fun `session level wins over assistant and global`() {
        assertEquals(
            "file:///session_s1.jpg",
            resolveChatBackground(
                sessionId = "s1",
                assistantId = "default",
                sessionBackgrounds = sessionBg,
                assistantBackgrounds = assistantBg,
                globalBackground = "file:///global.jpg",
            ),
        )
    }

    @Test
    fun `assistant level wins when session has no override`() {
        assertEquals(
            "file:///assistant_default.jpg",
            resolveChatBackground(
                sessionId = "s2",
                assistantId = "default",
                sessionBackgrounds = sessionBg,
                assistantBackgrounds = assistantBg,
                globalBackground = "file:///global.jpg",
            ),
        )
    }

    @Test
    fun `global is used when neither session nor assistant set`() {
        assertEquals(
            "file:///global.jpg",
            resolveChatBackground(
                sessionId = "s3",
                assistantId = "other",
                sessionBackgrounds = sessionBg,
                assistantBackgrounds = assistantBg,
                globalBackground = "file:///global.jpg",
            ),
        )
    }

    @Test
    fun `null when nothing is configured`() {
        assertNull(
            resolveChatBackground(
                sessionId = "s3",
                assistantId = "other",
                sessionBackgrounds = emptyMap(),
                assistantBackgrounds = emptyMap(),
                globalBackground = null,
            ),
        )
    }

    @Test
    fun `blank values are treated as unset and fall through`() {
        assertNull(
            resolveChatBackground(
                sessionId = "s1",
                assistantId = "default",
                sessionBackgrounds = mapOf("s1" to "   "),
                assistantBackgrounds = mapOf("default" to ""),
                globalBackground = "  ",
            ),
        )
    }

    @Test
    fun `null session id falls through to assistant level`() {
        assertEquals(
            "file:///assistant_default.jpg",
            resolveChatBackground(
                sessionId = null,
                assistantId = "default",
                sessionBackgrounds = sessionBg,
                assistantBackgrounds = assistantBg,
                globalBackground = null,
            ),
        )
    }
}
