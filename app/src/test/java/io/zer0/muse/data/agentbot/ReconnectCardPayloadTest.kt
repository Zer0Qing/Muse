package io.zer0.muse.data.agentbot

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReconnectCardPayloadTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `round trips a reconnect payload`() {
        val payload = ReconnectCardPayload(
            action = ReconnectActionKind.API_KEY,
            taskId = "task-1",
            reason = "Provider returned 401 unauthorized",
        )
        val decoded = ReconnectCardPayload.decode(json.encodeToString(payload))

        assertEquals(payload, decoded)
    }

    @Test
    fun `classifies only fixable failures`() {
        assertEquals(
            ReconnectActionKind.API_KEY,
            ReconnectCardPayload.classify("task-1", "HTTP 401 unauthorized: invalid api key")?.action,
        )
        assertEquals(
            ReconnectActionKind.ACCESSIBILITY,
            ReconnectCardPayload.classify("task-2", "无障碍服务未开启")?.action,
        )
        assertEquals(
            ReconnectActionKind.RUNTIME,
            ReconnectCardPayload.classify("task-3", "proot runtime not installed")?.action,
        )
        assertEquals(
            ReconnectActionKind.COMPUTE_NODE,
            ReconnectCardPayload.classify("task-4", "remote node offline")?.action,
        )
        assertNull(ReconnectCardPayload.classify("task-5", "业务校验失败，无需修复环境"))
    }

    @Test
    fun `malformed payload returns null`() {
        assertNull(ReconnectCardPayload.decode("not-json"))
    }
}
