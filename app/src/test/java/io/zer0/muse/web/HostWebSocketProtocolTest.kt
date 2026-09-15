package io.zer0.muse.web

import io.zer0.common.AppJson
import io.zer0.muse.ui.SsrfGuard
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host Mode WebSocket 命令与快照协议的序列化回归测试。 */
class HostWebSocketProtocolTest {

    @Test
    fun `command round trip preserves chat intent`() {
        val command = HostCommand(
            type = "chat.send",
            requestId = "req-1",
            sessionId = "session-1",
            text = "你好，Muse",
        )

        val decoded = AppJson.decodeFromString<HostCommand>(AppJson.encodeToString(command))

        assertEquals(command, decoded)
    }

    @Test
    fun `chat send SSRF policy rejects private and metadata URLs`() {
        assertTrue(SsrfGuard.hasBlockedUrlInText("请访问 http://127.0.0.1:8080/admin"))
        assertTrue(SsrfGuard.hasBlockedUrlInText("http://169.254.169.254/latest/meta-data/"))
        assertTrue(SsrfGuard.hasBlockedUrlInText("http://[fd00::1]/secret"))
        assertTrue(SsrfGuard.hasBlockedUrlInText("http://2130706433/"))
    }

    @Test
    fun `chat send SSRF policy permits public URL`() {
        assertFalse(SsrfGuard.hasBlockedUrlInText("请参考 https://8.8.8.8/docs"))
    }

    @Test
    fun `snapshot round trip preserves messages and host state`() {
        val event = HostEvent(
            type = "state.snapshot",
            sessionId = "session-1",
            isStreaming = true,
            sessions = listOf(
                HostSession("session-1", "测试会话", "default", 123L, archived = false, pinned = true),
            ),
            messages = listOf(
                HostMessage("message-1", "assistant", "你好", reasoning = "思考", createdAt = 124L),
            ),
        )

        val decoded = AppJson.decodeFromString<HostEvent>(AppJson.encodeToString(event))

        assertEquals(event, decoded)
        assertTrue(decoded.messages.single().reasoning == "思考")
        assertTrue(decoded.sessions.single().pinned)
    }
}
