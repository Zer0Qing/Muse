package io.zer0.muse.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v2.5.3 (P3-2): 会话级配置快照的编解码。
 */
class SessionConfigSnapshotTest {

    @Test
    fun `round trips a populated snapshot`() {
        val snapshot =
            SessionConfigSnapshot(
                providerId = "openai",
                modelId = "gpt-5",
                reasoningLevel = "HIGH",
                temperature = 0.7f,
                maxTokens = 4096,
            )
        val encoded = SessionConfigSnapshot.encode(snapshot)
        val decoded = SessionConfigSnapshot.decode(encoded)
        assertEquals(snapshot, decoded)
    }

    @Test
    fun `empty snapshot encodes to null`() {
        assertNull(SessionConfigSnapshot.encode(SessionConfigSnapshot()))
    }

    @Test
    fun `null and blank and bad json decode to null`() {
        assertNull(SessionConfigSnapshot.decode(null))
        assertNull(SessionConfigSnapshot.decode(""))
        assertNull(SessionConfigSnapshot.decode("   "))
        assertNull(SessionConfigSnapshot.decode("{not-json"))
    }

    @Test
    fun `partial snapshot survives round trip`() {
        val snapshot = SessionConfigSnapshot(providerId = "deepseek", modelId = "deepseek-v4-flash")
        val decoded = SessionConfigSnapshot.decode(SessionConfigSnapshot.encode(snapshot))
        assertEquals("deepseek", decoded?.providerId)
        assertEquals("deepseek-v4-flash", decoded?.modelId)
        assertNull(decoded?.reasoningLevel)
    }
}
