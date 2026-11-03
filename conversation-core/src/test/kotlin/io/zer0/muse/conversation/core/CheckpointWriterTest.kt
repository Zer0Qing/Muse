package io.zer0.muse.conversation.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CheckpointWriterTest {
    @Test
    fun `only one checkpoint writes and only latest pending snapshot survives`() = runTest {
        val port = ControlledCheckpointPort()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val writer = CheckpointWriter(port, dispatcher)

        writer.schedule(checkpoint(1, "one"))
        writer.schedule(checkpoint(2, "two"))
        writer.schedule(checkpoint(3, "three"))
        advanceUntilIdle()

        assertEquals(1, port.startedSnapshots.size)
        assertEquals("one", port.startedSnapshots.single().text)
        port.firstWrite.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("one", "three"), port.startedSnapshots.map { it.text })
    }

    @Test
    fun `terminal write waits for active checkpoint and bypasses pending baseline`() = runTest {
        val port = ControlledCheckpointPort()
        val writer = CheckpointWriter(port, StandardTestDispatcher(testScheduler))

        writer.schedule(checkpoint(1, "one"))
        writer.schedule(checkpoint(2, "two"))
        writer.terminal(checkpoint(3, "terminal"))
        advanceUntilIdle()

        assertEquals(listOf("one"), port.startedSnapshots.map { it.text })
        assertFalse(port.terminalCommitted)
        port.firstWrite.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("one", "terminal"), port.startedSnapshots.map { it.text })
        assertTrue(port.terminalCommitted)
    }

    @Test
    fun `completed turn cannot return to streaming`() {
        assertThrows(IllegalStateException::class.java) {
            TurnPhase.Completed.transitionTo(TurnPhase.Streaming)
        }
    }

    private fun checkpoint(sequence: Long, text: String) = CheckpointSnapshot(
        identity = identity,
        sequence = sequence,
        text = text,
        parts = listOf(MessagePart("part-1", PartKind.Text, sequence, text)),
    )

    private class ControlledCheckpointPort : CheckpointPort {
        val firstWrite = CompletableDeferred<Unit>()
        val startedSnapshots = mutableListOf<CheckpointSnapshot>()
        var terminalCommitted = false

        override suspend fun writeCheckpoint(snapshot: CheckpointSnapshot) {
            startedSnapshots += snapshot
            if (startedSnapshots.size == 1) firstWrite.await()
        }

        override suspend fun commitTerminal(snapshot: CheckpointSnapshot) {
            startedSnapshots += snapshot
            terminalCommitted = true
        }
    }

    private val identity =
        RunIdentity(
            sessionId = "session-1",
            incarnation = 1,
            turnId = "turn-1",
            generationId = "generation-1",
            streamId = "stream-1",
            selectionSetId = "selection-1",
        )
}
