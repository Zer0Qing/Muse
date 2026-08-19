package io.zer0.ai.core

import kotlinx.coroutines.delay

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstEventWatchdogTest {

    @Test
    fun `no first event triggers non-streaming fallback`() = runTest {
        val events = emptyFlow<ChatStreamEvent>()
            .withFirstEventWatchdog(timeoutMs = 100, fallback = {
                ChatCompletion(text = "fallback reply", finishReason = "stop")
            }).toList()

        assertTrue(events.any { it is ChatStreamEvent.FallbackNotice })
        assertTrue(events.any { it is ChatStreamEvent.ContentDelta && it.delta == "fallback reply" })
        assertTrue(events.last() is ChatStreamEvent.Done)
    }

    @Test
    fun `first event cancels watchdog`() = runTest {
        val events = flowOf(
            ChatStreamEvent.ContentDelta("ok"),
            ChatStreamEvent.Done("stop"),
        ).withFirstEventWatchdog(timeoutMs = 1_000, fallback = {
            ChatCompletion(text = "should not appear")
        }).toList()

        assertFalse(events.any { it is ChatStreamEvent.FallbackNotice })
        assertFalse(events.any { it is ChatStreamEvent.ContentDelta && it.delta == "should not appear" })
    }

    @Test
    fun `empty done triggers non-streaming fallback`() = runTest {
        val events = flowOf(
            ChatStreamEvent.Done("stop"),
        ).withFirstEventWatchdog(timeoutMs = 1_000, fallback = {
            ChatCompletion(text = "fallback after empty stream", finishReason = "stop")
        }).toList()

        assertTrue(events.any { it is ChatStreamEvent.FallbackNotice })
        assertTrue(events.any {
            it is ChatStreamEvent.ContentDelta && it.delta == "fallback after empty stream"
        })
        assertTrue(events.last() is ChatStreamEvent.Done)
    }

    @Test
    fun `partial event before timeout cancels watchdog`() = runTest {
        val events = flow {
            delay(50)
            emit(ChatStreamEvent.ContentDelta("partial"))
            delay(200)
            emit(ChatStreamEvent.Done("stop"))
        }.withFirstEventWatchdog(timeoutMs = 100, fallback = {
            ChatCompletion(text = "should not appear")
        }).toList()

        assertTrue(events.any { it is ChatStreamEvent.ContentDelta && it.delta == "partial" })
        assertFalse(events.any { it is ChatStreamEvent.FallbackNotice })
    }

    @Test
    fun `reasoning and large context models use longer timeout`() {
        val reasoning = Model(id = "r1", name = "r1", providerId = "p", abilities = setOf(ModelAbility.REASONING))
        val largeContext = Model(id = "l1", name = "l1", providerId = "p", contextWindow = 300_000)
        val normal = Model(id = "n1", name = "n1", providerId = "p")

        assertEquals(60_000L, reasoning.firstEventTimeoutMs())
        assertEquals(60_000L, largeContext.firstEventTimeoutMs())
        assertEquals(15_000L, normal.firstEventTimeoutMs())
    }
}
