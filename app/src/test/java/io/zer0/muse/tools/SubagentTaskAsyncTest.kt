package io.zer0.muse.tools

import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.zer0.muse.data.subagent.SubagentThreadStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for subagent_task's single asynchronous owner.
 *
 * The outer SubagentTool Job must wait for the real delegate child and cancellation
 * must reach that same child instead of only cancelling an acknowledgement Job.
 */
class SubagentTaskAsyncTest {

    @Test
    fun `launch remains running until the real delegate child completes`() = runBlocking {
        val deferredStore = DeferredResultStore()
        val threadStore = mockThreadStore()
        val executor = mockk<SkillExecutor>()
        val childStarted = CompletableDeferred<Unit>()
        val releaseChild = CompletableDeferred<Unit>()
        var observedNonBlocking = true

        coEvery { executor.delegateAgent(any()) } coAnswers {
            observedNonBlocking = firstArg<DelegationContract.DelegationRequest>().nonBlocking
            childStarted.complete(Unit)
            releaseChild.await()
            DelegationContract.DelegationResult(
                requestId = "child-request",
                success = true,
                resultText = "real child result",
            )
        }

        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val response = SubagentTool.execute(
                args = mapOf(
                    "action" to "launch",
                    "agent_id" to "assistant-a",
                    "task" to "long task",
                    "parent_session_id" to "session-a",
                ),
                skillExecutor = executor,
                subagentThreadStore = threadStore,
                deferredResultStore = deferredStore,
                appScope = appScope,
            )
            val taskId = taskIdFrom(response)

            withTimeout(5_000) { childStarted.await() }
            assertFalse("SubagentTool must not delegate a second async Job", observedNonBlocking)
            assertEquals("running", SubagentTool.getTask(taskId)?.status)
            assertEquals(
                "pending",
                deferredStore.getTask(taskId)?.status?.name?.lowercase(),
            )

            releaseChild.complete(Unit)
            withTimeout(5_000) {
                while (deferredStore.getTask(taskId)?.status != DeferredResultStore.TaskStatus.RESOLVED) {
                    delay(10)
                }
            }
            assertEquals("completed", SubagentTool.getTask(taskId)?.status)
            assertEquals("real child result", deferredStore.getTask(taskId)?.result)
        } finally {
            releaseChild.complete(Unit)
            appScope.cancel()
        }
    }

    @Test
    fun `cancel reaches the real delegate child and does not complete the outer task`() = runBlocking {
        val deferredStore = DeferredResultStore()
        val threadStore = mockThreadStore()
        val executor = mockk<SkillExecutor>()
        val childStarted = CompletableDeferred<Unit>()
        val childCancelled = CompletableDeferred<Unit>()
        val childGate = CompletableDeferred<Unit>()

        coEvery { executor.delegateAgent(any()) } coAnswers {
            childStarted.complete(Unit)
            try {
                childGate.await()
                DelegationContract.DelegationResult(
                    requestId = "child-request",
                    success = true,
                    resultText = "late result",
                )
            } catch (e: CancellationException) {
                childCancelled.complete(Unit)
                throw e
            }
        }

        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val response = SubagentTool.execute(
                args = mapOf(
                    "action" to "launch",
                    "agent_id" to "assistant-a",
                    "task" to "cancel me",
                    "parent_session_id" to "session-a",
                ),
                skillExecutor = executor,
                subagentThreadStore = threadStore,
                deferredResultStore = deferredStore,
                appScope = appScope,
            )
            val taskId = taskIdFrom(response)
            withTimeout(5_000) { childStarted.await() }

            val cancelResponse = SubagentTool.execute(
                args = mapOf("action" to "cancel", "task_id" to taskId),
                skillExecutor = executor,
                subagentThreadStore = threadStore,
                deferredResultStore = deferredStore,
                appScope = appScope,
            )

            assertTrue(cancelResponse.contains("cancelled"))
            withTimeout(5_000) { childCancelled.await() }
            assertEquals(DeferredResultStore.TaskStatus.ABORTED, deferredStore.getTask(taskId)?.status)
            assertEquals("cancelled", SubagentTool.getTask(taskId)?.status)
            assertFalse("cancelled child must not be reported as completed", SubagentTool.getTask(taskId)?.status == "completed")
        } finally {
            appScope.cancel()
        }
    }

    private fun mockThreadStore(): SubagentThreadStore {
        val store = mockk<SubagentThreadStore>(relaxed = true)
        coEvery { store.beginRun(any(), any(), any()) } just Runs
        coEvery { store.runSerialized<Any>(any(), any()) } coAnswers {
            secondArg<suspend () -> Any>()()
        }
        return store
    }

    private fun taskIdFrom(response: String): String {
        val match = Regex("taskId=([^,]+)").find(response)
        return match?.groupValues?.get(1) ?: error("No taskId in response: $response")
    }
}
