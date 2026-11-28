package io.zer0.muse.transformer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AboutYouShardInjectionPolicyTest {
    @Test
    fun `profile recall respects the authorized assistant and session memory settings`() {
        val request = SystemPromptAssembler.DynamicPromptRequest(
            assistant = null,
            timeReminderEnabled = false,
            currentUserInput = "favorite food",
            relevantMemoryAssistantId = "assistant-a",
        )

        assertEquals("assistant-a", request.aboutYouShardAssistantId())
        assertNull(request.copy(relevantMemoryAssistantId = null).aboutYouShardAssistantId())
        assertNull(request.copy(ignoreMemory = true).aboutYouShardAssistantId())
        assertNull(request.copy(memoryEnabled = false).aboutYouShardAssistantId())
        assertNull(request.copy(forSubagent = true).aboutYouShardAssistantId())
        assertNull(request.copy(currentUserInput = " ").aboutYouShardAssistantId())
    }
}
