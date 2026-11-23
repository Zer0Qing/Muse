package io.zer0.muse.data.agentbot

import io.zer0.muse.runtime.RuntimeMissing
import io.zer0.muse.tools.SubagentRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeAgentResumeIntegrationTest {

    @Test
    fun `runtime missing parks only the failed run with the structured prerequisite code`() {
        val missing = SubagentRunner.Result(
            success = false,
            summary = "",
            error = RuntimeMissing.CODE,
        )
        val otherFailure = missing.copy(error = "network timeout")

        assertTrue(RuntimeAgentResumePolicy.shouldPark(missing))
        assertFalse(RuntimeAgentResumePolicy.shouldPark(otherFailure))
        assertFalse(RuntimeAgentResumePolicy.shouldPark(missing.copy(success = true)))
    }

    @Test
    fun `ready runtime resumes a parked run once through the task status machine`() {
        val task = BotTaskEntity(taskId = "task-1", status = BotTaskStatus.WAITING_RESOURCE)
        val run = TaskRunEntity(
            runId = "run-1",
            taskId = task.taskId,
            status = TaskRunStatus.WAITING_RESOURCE,
            errorCode = RuntimeMissing.CODE,
        )

        assertFalse(RuntimeAgentResumePolicy.shouldResume(task, run, runtimeReady = false))
        assertTrue(RuntimeAgentResumePolicy.shouldResume(task, run, runtimeReady = true))

        val queued = BotTaskStatusMachine.next(task.status, BotTaskStatusMachine.Event.RESUME_FROM_RESOURCE)
        assertEquals(BotTaskStatus.QUEUED, queued)
        assertFalse(RuntimeAgentResumePolicy.shouldResume(task.copy(status = queued.orEmpty()), run, runtimeReady = true))
    }
}
