package io.zer0.muse.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveManualRouteConfigTest {
    @Test
    fun `manual route config read failure keeps agent enabled and plain chats disabled`() {
        val fallback = proactiveConfigOnReadFailure()

        assertTrue(fallback.agentSessionEnabled)
        assertFalse(fallback.allowPlainChatSession)
        assertFalse(fallback.enabled)
        assertEquals("safe fallback is deterministic", fallback, proactiveConfigOnReadFailure())
    }
}
