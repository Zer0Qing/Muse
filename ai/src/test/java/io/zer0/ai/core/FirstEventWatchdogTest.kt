package io.zer0.ai.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

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
    fun `upstream empty completion falls back without waiting for watchdog timeout`() = runTest {
        val events = withTimeout(1_000) {
            emptyFlow<ChatStreamEvent>()
                .withFirstEventWatchdog(timeoutMs = 60_000, fallback = {
                    ChatCompletion(text = "immediate fallback", finishReason = "stop")
                })
                .toList()
        }

        assertTrue(events.any { it is ChatStreamEvent.FallbackNotice })
        assertTrue(events.any { it is ChatStreamEvent.ContentDelta && it.delta == "immediate fallback" })
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
        assertTrue(
            events.any {
                it is ChatStreamEvent.ContentDelta && it.delta == "fallback after empty stream"
            },
        )
        assertTrue(events.last() is ChatStreamEvent.Done)
    }

    @Test
    fun `empty upstream completion racing timeout starts only one fallback`() = runTest {
        val fallbackCalls = AtomicInteger()
        val events: List<ChatStreamEvent> =
            withTimeout<List<ChatStreamEvent>>(2_000) {
                flow<ChatStreamEvent> {
                    delay(100)
                }.withFirstEventWatchdog(
                    timeoutMs = 100,
                    fallback = {
                        fallbackCalls.incrementAndGet()
                        delay(25)
                        ChatCompletion(text = "single fallback", finishReason = "stop")
                    },
                ).toList()
            }

        assertEquals(1, fallbackCalls.get())
        assertEquals(1, events.count { it is ChatStreamEvent.FallbackNotice })
        assertEquals(1, events.count { it is ChatStreamEvent.ContentDelta && it.delta == "single fallback" })
        assertEquals(1, events.count { it is ChatStreamEvent.Done })
    }

    @Test
    fun `fallback preserves native citation urls`() = runTest {
        val events = emptyFlow<ChatStreamEvent>()
            .withFirstEventWatchdog(timeoutMs = 100, fallback = {
                ChatCompletion(
                    text = "grounded answer",
                    citationUrls = listOf("https://example.com/source"),
                )
            }).toList()

        assertTrue(
            events.any {
                it is ChatStreamEvent.CitationDelta && it.urls == listOf("https://example.com/source")
            },
        )
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
    fun `cancellation during non-stream fallback propagates instead of becoming an error event`() = runTest {
        val events = mutableListOf<ChatStreamEvent>()
        val cancellation = try {
            withTimeout(2_000) {
                emptyFlow<ChatStreamEvent>()
                    .withFirstEventWatchdog(
                        timeoutMs = 60_000,
                        fallback = { throw kotlinx.coroutines.CancellationException("fallback cancelled") },
                    )
                    .collect { events += it }
            }
            null
        } catch (error: kotlinx.coroutines.CancellationException) {
            error
        }

        assertEquals("fallback cancelled", cancellation?.message)
        assertFalse(events.any { it is ChatStreamEvent.Error })
    }

    @Test
    fun `reasoning and large context models use longer timeout`() {
        val reasoning = Model(id = "r1", name = "r1", providerId = "p", abilities = setOf(ModelAbility.REASONING))
        val largeContext = Model(id = "l1", name = "l1", providerId = "p", contextWindow = 300_000)
        val normal = Model(id = "n1", name = "n1", providerId = "p")

        // 断言引用常量而不是再抄一遍数字：抄的那份会在有人改档位时悄悄留下旧值
        assertEquals(RELAXED_FIRST_EVENT_TIMEOUT_MS, reasoning.firstEventTimeoutMs())
        assertEquals(RELAXED_FIRST_EVENT_TIMEOUT_MS, largeContext.firstEventTimeoutMs())
        assertEquals(DEFAULT_FIRST_EVENT_TIMEOUT_MS, normal.firstEventTimeoutMs())
    }

    @Test
    fun `省略 timeoutMs 的新调用方不该拿到那个已知过短的 15 秒档`() = runTest {
        // 必须用"一直挂着不吐事件"的流：空流会走"立即回退"那条快路径，
        // 量不到看门狗的默认档（这里要的正是那个默认值）。
        val hanging = flow<ChatStreamEvent> { kotlinx.coroutines.awaitCancellation() }

        val startedAt = testScheduler.currentTime
        val events = hanging
            .withFirstEventWatchdog(fallback = { ChatCompletion(text = "回退内容", finishReason = "stop") })
            .toList()
        val waitedMs = testScheduler.currentTime - startedAt

        assertTrue("最终仍要回退出内容", events.any { it is ChatStreamEvent.FallbackNotice })
        assertTrue(
            "默认档应是 $DEFAULT_FIRST_EVENT_TIMEOUT_MS ms，而不是旧的 15000ms；实际等了 $waitedMs ms",
            waitedMs >= DEFAULT_FIRST_EVENT_TIMEOUT_MS,
        )
    }
}
