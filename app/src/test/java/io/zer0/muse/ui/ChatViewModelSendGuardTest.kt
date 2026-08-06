package io.zer0.muse.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-TEST-06: ChatViewModel 发送守卫状态机纯逻辑。
 */
class ChatViewModelSendGuardTest {

    @Test
    fun `blank text without images is rejected`() {
        assertFalse(canStartGeneration("", emptyList(), isStreaming = false, isCreatingAgentSession = false))
        assertFalse(canStartGeneration("   ", emptyList(), isStreaming = false, isCreatingAgentSession = false))
    }

    @Test
    fun `text or images allow generation`() {
        assertTrue(canStartGeneration("你好", emptyList(), isStreaming = false, isCreatingAgentSession = false))
        assertTrue(
            canStartGeneration(
                "", listOf("data:image/png;base64,xxx"), isStreaming = false, isCreatingAgentSession = false,
            ),
        )
    }

    @Test
    fun `streaming blocks new generation`() {
        assertFalse(canStartGeneration("你好", emptyList(), isStreaming = true, isCreatingAgentSession = false))
    }

    @Test
    fun `agent session creation blocks reentry`() {
        assertFalse(canStartGeneration("你好", emptyList(), isStreaming = false, isCreatingAgentSession = true))
    }
}
