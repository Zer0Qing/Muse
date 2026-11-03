package io.zer0.muse.conversation.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeOwnershipTest {
    @Test
    fun `detaching a page does not cancel generation`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runtime = ConversationRuntime(
            sessionId = "session-1",
            incarnation = ConversationIncarnation(1),
            selectionSetId = SelectionSetId("selection-1"),
            dispatcher = StandardTestDispatcher(testScheduler),
            generation = { gate.await() },
        )

        runtime.attach()
        runtime.start("turn-1", "generation-1", "stream-1")
        runtime.detach()
        advanceUntilIdle()

        assertFalse(runtime.isFinished)
        assertEquals(TurnPhase.Preparing, runtime.phase)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(runtime.isFinished)
    }

    @Test
    fun `deleted incarnation rejects late events and further starts`() = runTest {
        val runtime = ConversationRuntime(
            sessionId = "session-1",
            incarnation = ConversationIncarnation(1),
            selectionSetId = SelectionSetId("selection-1"),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        runtime.start("turn-1", "generation-1", "stream-1")
        runtime.invalidate()

        assertTrue(runtime.accepts(identity(turnId = "turn-1")) is EventAcceptance.Rejected)
        assertTrue(runtime.start("turn-2", "generation-2", "stream-2") is StartResult.IncarnationInvalid)
    }

    @Test
    fun `a newer start replaces the previous job identity`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val starts = mutableListOf<String>()
        val runtime = ConversationRuntime(
            sessionId = "session-1",
            incarnation = ConversationIncarnation(1),
            selectionSetId = SelectionSetId("selection-1"),
            dispatcher = StandardTestDispatcher(testScheduler),
            generation = { id ->
                starts += id
                gate.await()
            },
        )
        runtime.start("turn-1", "generation-1", "stream-1")
        runtime.start("turn-2", "generation-2", "stream-2")
        advanceUntilIdle()

        assertEquals("generation-2", runtime.activeGenerationId)
        assertTrue(runtime.accepts(identity(turnId = "turn-1", generationId = "generation-1")) is EventAcceptance.Rejected)
    }

    private fun identity(turnId: String, generationId: String = "generation-1") = RunIdentity(
        sessionId = "session-1",
        incarnation = 1,
        turnId = turnId,
        generationId = generationId,
        streamId = "stream-1",
        selectionSetId = "selection-1",
    )
}
