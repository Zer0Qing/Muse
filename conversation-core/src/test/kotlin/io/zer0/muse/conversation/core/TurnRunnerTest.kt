package io.zer0.muse.conversation.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TurnRunnerTest {
    @Test
    fun `content and completion form one draft and one terminal commit`() = runTest {
        val terminal = CompletableDeferred<CheckpointSnapshot>()
        val runner = TurnRunner(
            provider = ProviderPort {
                flowOf(
                    ProviderEvent.Content(1, "hello"),
                    ProviderEvent.Completed(2, "stop"),
                )
            },
            checkpointPort = RecordingCheckpointPort(terminal),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        runner.run(request())
        advanceUntilIdle()

        assertEquals("hello", terminal.await().text)
        assertEquals(1, runner.terminalCommits)
    }

    @Test
    fun `provider failure produces a failed terminal snapshot`() = runTest {
        val terminal = CompletableDeferred<CheckpointSnapshot>()
        val runner = TurnRunner(
            provider = ProviderPort { flowOf(ProviderEvent.Failed(1, "network")) },
            checkpointPort = RecordingCheckpointPort(terminal),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        runner.run(request())
        advanceUntilIdle()

        assertEquals("network", runner.failureMessage)
        assertTrue(runner.terminalCommits == 1)
    }

    private fun request() = ProviderRequest(
        identity = RunIdentity(
            sessionId = "session-1",
            incarnation = 1,
            turnId = "turn-1",
            generationId = "generation-1",
            streamId = "stream-1",
            selectionSetId = "selection-1",
        ),
        selectionSetId = "selection-1",
        messages = emptyList(),
        tools = emptyList(),
    )

    private class RecordingCheckpointPort(
        private val terminal: CompletableDeferred<CheckpointSnapshot>,
    ) : CheckpointPort {
        override suspend fun writeCheckpoint(snapshot: CheckpointSnapshot) = Unit

        override suspend fun commitTerminal(snapshot: CheckpointSnapshot) {
            terminal.complete(snapshot)
        }
    }
}
