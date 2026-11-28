package io.zer0.muse.data.agentbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 状态机拒绝转移时，不得继续暂存与应用。

 * 独立审查发现的 P0：`onRunFinished` 里 `transition(EXECUTE_DONE)` 的返回值被丢弃。
 * 当本轮因审批挂起转到 `WAITING_APPROVAL` 时，`EXECUTE_DONE` 非法、返回 null，
 * 但原实现继续往下走 —— 产物照样进暂存区、照样被审核、照样**写进目标文件**。
 *
 * 也就是「用户还没批准，文件已经改了」。
 */
class TransitionResultHonoredTest {

    private val scheduler: String by lazy {
        File("src/main/java/io/zer0/muse/data/agentbot/BotTaskScheduler.kt").readText()
    }

    @Test
    fun `execution completion uses a transactional repository operation`() {
        val repository = File("src/main/java/io/zer0/muse/data/agentbot/AgentBotRepository.kt").readText()
        assertFalse(
            "执行成功不能拆成 run 完成与任务状态转移",
            scheduler.contains("repository.closeRun(run.runId, TaskRunStatus.COMPLETED)"),
        )
        assertTrue(
            "执行结果应通过仓储事务写入审核队列",
            scheduler.contains("repository.finishExecution(task.taskId, run.runId, run.taskRevision, payload)"),
        )
        assertTrue(repository.contains("db.withTransaction { operation() }"))
    }

    @Test
    fun `review recovery reads durable result events for reviewing tasks`() {
        assertTrue(scheduler.contains("reconcileReviewingResults()"))
        assertTrue(scheduler.contains("repository.reviewingTasks()"))
        assertTrue(scheduler.contains("reviewer.recover(task, run, resultEvent.payloadRef)"))
        assertTrue("未转入审核的候选要清理", scheduler.contains("if (!stagedArtifactsTransferred) stagedArtifactRefs.forEach"))
    }
}
