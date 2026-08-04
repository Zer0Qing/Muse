package io.zer0.ai.core

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstEventWatchdogTest {

    @Test
    fun `no first event triggers non-streaming fallback`() = runTest {
        val events = emptyFlow<ChatStreamEvent>()
            .withFirstEventWatchdog(timeoutMs = 100) {
                ChatCompletion(text = "fallback reply", finishReason = "stop")
            }
            .toList()

        assertTrue(events.any { it is ChatStreamEvent.FallbackNotice })
        assertTrue(events.any { it is ChatStreamEvent.ContentDelta && it.delta == "fallback reply" })
        assertTrue(events.last() is ChatStreamEvent.Done)
    }

    @Test
    fun `first event cancels watchdog`() = runTest {
        val events = flowOf(
            ChatStreamEvent.ContentDelta("ok"),
            ChatStreamEvent.Done("stop"),
        ).withFirstEventWatchdog(timeoutMs = 1_000) {
            ChatCompletion(text = "should not appear")
        }.toList()

        assertFalse(events.any { it is ChatStreamEvent.FallbackNotice })
        assertFalse(events.any { it is ChatStreamEvent.ContentDelta && it.delta == "should not appear" })
    }
}
