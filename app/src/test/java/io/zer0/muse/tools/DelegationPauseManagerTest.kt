package io.zer0.muse.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DelegationPauseManagerTest {

    @Test
    fun `cancelDelegation completes every pause belonging to parent`() = runTest {
        val manager = DelegationPauseManager()
        val first = async {
            manager.awaitPauseDecision(pause("pause-1", "parent", parentRequestId = "parent"), longPolicy())
        }
        val second = async {
            manager.awaitPauseDecision(pause("pause-2", "parent", parentRequestId = "parent"), longPolicy())
        }
        eventually { manager.activePauses.value.keys == setOf("pause-1", "pause-2") }

        manager.cancelDelegation("parent")

        assertEquals(DelegationPauseManager.PauseOutcome.CANCELLED, first.await().outcome)
        assertEquals(DelegationPauseManager.PauseDecision.CANCEL, first.await().decision)
        assertEquals(DelegationPauseManager.PauseOutcome.CANCELLED, second.await().outcome)
        assertTrue(manager.activePauses.value.isEmpty())
        assertTrue(manager.isCancelled("parent"))
    }

    @Test
    fun `cancelDelegation also cancels nested pause requests`() = runTest {
        val manager = DelegationPauseManager()
        val child = async {
            manager.awaitPauseDecision(
                pause("pause-child", taskId = "parent/child", parentRequestId = "parent"),
                longPolicy(),
            )
        }
        eventually { manager.activePauses.value.containsKey("pause-child") }

        manager.cancelDelegation("parent")

        assertEquals(DelegationPauseManager.PauseOutcome.CANCELLED, child.await().outcome)
        assertTrue(manager.isCancelled("parent/child"))
    }

    @Test
    fun `clearAll completes pending pauses before clearing state`() = runTest {
        val manager = DelegationPauseManager()
        val first = async { manager.awaitPauseDecision(pause("pause-1", "parent"), longPolicy()) }
        val second = async { manager.awaitPauseDecision(pause("pause-2", "other"), longPolicy()) }
        eventually { manager.activePauses.value.size == 2 }

        manager.clearAll()

        assertEquals(DelegationPauseManager.PauseOutcome.CANCELLED, first.await().outcome)
        assertEquals(DelegationPauseManager.PauseOutcome.CANCELLED, second.await().outcome)
        assertTrue(manager.activePauses.value.isEmpty())
        assertFalse(manager.isCancelled("parent"))
    }

    @Test
    fun `timeout returns structured outcome and cleans pause`() = runTest {
        val manager = DelegationPauseManager()

        val response = manager.awaitPauseDecision(
            pause("pause-timeout", "parent"),
            DelegationPauseManager.PausePolicy(autoTimeoutSec = 0),
        )

        assertEquals(DelegationPauseManager.PauseDecision.REJECT, response.decision)
        assertEquals(DelegationPauseManager.PauseOutcome.TIMED_OUT, response.outcome)
        assertTrue(response.reason.orEmpty().contains("超时"))
        assertTrue(manager.activePauses.value.isEmpty())
    }

    @Test
    fun `caller cancellation is rethrown and cleans pause`() = runTest {
        val manager = DelegationPauseManager()
        val waiting = async {
            manager.awaitPauseDecision(pause("pause-cancel", "parent"), longPolicy())
        }
        eventually { manager.activePauses.value.containsKey("pause-cancel") }

        waiting.cancel(CancellationException("test cancellation"))

        var rethrown = false
        try {
            waiting.await()
        } catch (error: CancellationException) {
            rethrown = true
        }
        assertTrue("CancellationException must not be swallowed", rethrown)
        assertTrue(manager.activePauses.value.isEmpty())
    }

    private fun pause(
        requestId: String,
        taskId: String,
        parentRequestId: String? = null,
    ) = DelegationPauseManager.PauseRequest(
        requestId = requestId,
        taskId = taskId,
        taskTitle = "test",
        taskDescription = "test",
        targetType = "assistant",
        targetName = "assistant",
        reason = "test",
        parentRequestId = parentRequestId,
    )

    private fun longPolicy() = DelegationPauseManager.PausePolicy(autoTimeoutSec = 60)

    private suspend fun eventually(condition: () -> Boolean) {
        repeat(10) {
            if (condition()) return
            delay(1)
        }
        assertTrue("condition was not reached", condition())
    }
}
