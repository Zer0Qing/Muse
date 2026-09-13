package io.zer0.muse.session

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SessionExecutionRegistry 的代际隔离和取消阶段测试。 */
class SessionExecutionRegistryTest {

    private fun identity(generationId: String) = GenerationIdentity(
        sessionId = "session-1",
        turnId = "turn-1",
        generationId = generationId,
        streamId = "stream-$generationId",
    )

    @Test
    fun lateGenerationIsNotCurrentAfterCancellation() {
        val registry = SessionExecutionRegistry()
        val cancelCount = AtomicInteger(0)
        val old = identity("g1")
        val id = registry.register(old, ExecutionKind.LLM) { cancelCount.incrementAndGet() }
        registry.start(id)

        assertTrue(registry.isCurrent(old))
        assertTrue(registry.requestCancel(id, "new_generation"))
        assertEquals(1, cancelCount.get())
        assertEquals(ExecutionState.CANCEL_REQUESTED, registry.snapshot("session-1").single().state)
        assertTrue(registry.markCancelled(id))
        assertFalse(registry.isCurrent(old))
    }

    @Test
    fun lateNormalCompletionAfterCancelRemainsCancelled() {
        val registry = SessionExecutionRegistry()
        val identity = identity("g1")
        val id = registry.register(identity, ExecutionKind.TOOL)
        registry.start(id)

        assertTrue(registry.requestCancel(id, "user_stop"))
        assertTrue(registry.finish(id))
        assertEquals(ExecutionState.CANCELLED, registry.state(id))
        assertFalse(registry.isCurrent(identity))
    }

    @Test
    fun lateFailureAfterCancelRemainsCancelled() {
        val registry = SessionExecutionRegistry()
        val identity = identity("g1")
        val id = registry.register(identity, ExecutionKind.BROWSER)
        registry.start(id)

        assertTrue(registry.requestCancel(id, "session_switch"))
        assertTrue(registry.fail(id))
        assertEquals(ExecutionState.CANCELLED, registry.state(id))
    }

    @Test
    fun sessionCancellationOnlyTouchesMatchingSession() {
        val registry = SessionExecutionRegistry()
        val firstCancel = AtomicInteger(0)
        val secondCancel = AtomicInteger(0)
        val first = registry.register(identity("g1"), ExecutionKind.TOOL) { firstCancel.incrementAndGet() }
        val second = registry.register(
            identity("g2").copy(sessionId = "session-2"),
            ExecutionKind.BROWSER,
        ) { secondCancel.incrementAndGet() }

        val cancelled = registry.requestCancelForSession("session-1")

        assertEquals(listOf(first), cancelled)
        assertEquals(1, firstCancel.get())
        assertEquals(0, secondCancel.get())
        assertEquals(ExecutionState.CANCEL_REQUESTED, registry.snapshot("session-1").single().state)
        assertEquals(ExecutionState.REGISTERED, registry.snapshot("session-2").single().state)
        assertTrue(registry.finish(second))
    }

    @Test
    fun completedGenerationAndOlderGenerationAreNotCurrent() {
        val registry = SessionExecutionRegistry()
        val first = identity("g1")
        val firstId = registry.register(first, ExecutionKind.LLM)
        registry.start(firstId)
        assertTrue(registry.isCurrent(first))
        assertTrue(registry.finish(firstId))
        assertFalse(registry.isCurrent(first))

        val second = identity("g2")
        val secondId = registry.register(second, ExecutionKind.LLM)
        registry.start(secondId)
        assertFalse(registry.isCurrent(first))
        assertTrue(registry.isCurrent(second))
        assertTrue(registry.finish(secondId))
    }

    @Test
    fun generationMatchIgnoresDifferentGenerationAndSession() {
        val registry = SessionExecutionRegistry()
        val current = identity("g2")
        val id = registry.register(current, ExecutionKind.LLM)

        assertTrue(registry.matches(current, identity("g2")))
        assertFalse(registry.matches(current, identity("g1")))
        assertFalse(registry.matches(current, identity("g2").copy(sessionId = "session-2")))
        assertTrue(registry.finish(id))
    }
}
