package io.zer0.muse.session

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1.7: 会话运行时 turn 检查点状态机测试。
 *
 * 覆盖验收要求:
 * - 同一会话多次进入复用同一运行时(ConversationSessionManager.getOrCreateRuntime)。
 * - turn 检查点按 GENERATING -> WAITING_TOOL/WAITING_APPROVAL -> GENERATING -> 终态迁移。
 * - 终态后不允许继续迁移;新 turn 重新开启。
 * - 引用计数归零且无生成任务时 idle 清理;有生成任务时不清理。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRuntimeTest {

    // ── SessionRuntime 状态机 ─────────────────────────────────────────

    @Test
    fun `beginTurn enters generating and records turn id`() {
        val runtime = SessionRuntime("s1")
        assertEquals(TurnPhase.NOT_STARTED, runtime.phase.value)
        runtime.beginTurn("t1")
        assertEquals("t1", runtime.currentTurnId.get())
        assertEquals(TurnPhase.GENERATING, runtime.phase.value)
    }

    @Test
    fun `tool execution cycles waiting tool back to generating`() {
        val runtime = SessionRuntime("s1")
        runtime.beginTurn("t1")
        runtime.markWaitingTool("t1")
        assertEquals(TurnPhase.WAITING_TOOL, runtime.phase.value)
        runtime.markResumed("t1")
        assertEquals(TurnPhase.GENERATING, runtime.phase.value)
    }

    @Test
    fun `approval suspends and resolves back to generating`() {
        val runtime = SessionRuntime("s1")
        runtime.beginTurn("t1")
        runtime.markWaitingApproval("t1")
        assertEquals(TurnPhase.WAITING_APPROVAL, runtime.phase.value)
        runtime.markResumed("t1")
        assertEquals(TurnPhase.GENERATING, runtime.phase.value)
    }

    @Test
    fun `terminal phase is sticky within the same turn`() {
        val runtime = SessionRuntime("s1")
        runtime.beginTurn("t1")
        runtime.markFinished(TurnPhase.COMPLETED, "t1")
        assertEquals(TurnPhase.COMPLETED, runtime.phase.value)
        // 终态后中间态/终态迁移都必须被忽略
        runtime.markWaitingTool("t1")
        runtime.markFinished(TurnPhase.FAILED, "t1")
        runtime.markFinished(TurnPhase.CANCELLED, "t1")
        assertEquals(TurnPhase.COMPLETED, runtime.phase.value)
    }

    @Test
    fun `new turn restarts from generating with fresh turn id`() {
        val runtime = SessionRuntime("s1")
        runtime.beginTurn("t1")
        runtime.markFinished(TurnPhase.CANCELLED, "t1")
        assertEquals(TurnPhase.CANCELLED, runtime.phase.value)
        runtime.beginTurn("t2")
        assertEquals("t2", runtime.currentTurnId.get())
        assertEquals(TurnPhase.GENERATING, runtime.phase.value)
        // 新 turn 的取消标志被清除
        assertFalse(runtime.cancelRequested)
    }

    @Test
    fun `beginTurn clears cancelRequested from previous stop`() {
        val runtime = SessionRuntime("s1")
        runtime.beginTurn("t1")
        runtime.requestCancel()
        assertTrue(runtime.cancelRequested)
        runtime.beginTurn("t2")
        assertFalse(runtime.cancelRequested)
    }

    @Test
    fun `mismatched turn id transitions are rejected`() {
        val runtime = SessionRuntime("s1")
        runtime.beginTurn("t1")
        runtime.markFinished(TurnPhase.COMPLETED, "other-turn")
        assertEquals(TurnPhase.GENERATING, runtime.phase.value)
        runtime.markWaitingTool("other-turn")
        assertEquals(TurnPhase.GENERATING, runtime.phase.value)
    }

    @Test
    fun `null or matching turn id transitions are accepted`() {
        val runtime = SessionRuntime("s1")
        runtime.beginTurn("t1")
        // null = 调用方不知道 turn id(如审批入口),放行
        runtime.markWaitingApproval(null)
        assertEquals(TurnPhase.WAITING_APPROVAL, runtime.phase.value)
        runtime.markResumed(null)
        assertEquals(TurnPhase.GENERATING, runtime.phase.value)
    }

    @Test
    fun `cancel requested is recorded`() {
        val runtime = SessionRuntime("s1")
        runtime.beginTurn("t1")
        runtime.requestCancel()
        assertTrue(runtime.cancelRequested)
    }

    @Test
    fun `resumable marks restored turns`() {
        val runtime = SessionRuntime("s1")
        runtime.markResumable("t1")
        assertEquals(TurnPhase.RESUMABLE, runtime.phase.value)
        assertEquals("t1", runtime.currentTurnId.get())
    }

    // ── ConversationSessionManager 运行时复用与 idle 清理 ─────────────

    @Test
    fun `same session reuses same runtime instance across acquire and release`() = runTest {
        val manager = ConversationSessionManager(backgroundScope)
        manager.acquire("s1")
        val first = manager.getOrCreateRuntime("s1")
        manager.release("s1")
        manager.acquire("s1")
        val second = manager.getOrCreateRuntime("s1")
        // 同一 sessionId 多次进入必须复用同一运行时实例
        assertEquals(first, second)
        // release 再 acquire 后引用计数回到 1
        assertEquals(1, manager.getOrCreateRuntime("s1").refCount.get())
    }

    @Test
    fun `setGenerationJob creates runtime for never acquired session`() = runTest {
        val manager = ConversationSessionManager(backgroundScope)
        assertNull(manager.runtime("group:chat-1"))
        val job = launch { delay(10_000) }
        manager.setGenerationJob("group:chat-1", job)
        // 群聊生成 id 未走 acquire 路径,也必须进入运行时管理
        assertTrue(manager.runtime("group:chat-1") != null)
        assertEquals(job, manager.runtime("group:chat-1")?.generationJob)
        job.cancelAndJoin()
    }

    @Test
    fun `setGenerationJob cancels previous job for same session`() = runTest {
        val manager = ConversationSessionManager(backgroundScope)
        val oldJob = launch { delay(10_000) }
        manager.setGenerationJob("s1", oldJob)
        val newJob = launch { delay(10_000) }
        manager.setGenerationJob("s1", newJob)
        assertTrue(oldJob.isCancelled)
        assertFalse(newJob.isCancelled)
        newJob.cancelAndJoin()
    }

    @Test
    fun `late completion of replaced job does not clear the new job`() = runTest {
        val manager = ConversationSessionManager(backgroundScope)
        // 旧任务用 NonCancellable 收尾模拟真实场景(中断路径的持久化拖慢 completion),
        // 使其完成回调晚于新 job 的登记执行。
        val oldJob = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                delay(10_000)
            } finally {
                withContext(NonCancellable) { delay(100) }
            }
        }
        manager.setGenerationJob("s1", oldJob)
        val newJob = launch(start = CoroutineStart.UNDISPATCHED) { delay(60_000) }
        // setGenerationJob 取消旧任务;旧任务进入 cancelling,尚未完成
        manager.setGenerationJob("s1", newJob)
        // 推进虚拟时间:旧任务 finally 完成,晚到的 invokeOnCompletion 触发
        advanceTimeBy(10_200)
        // 旧回调不得清空新 job 引用(否则 cancelGeneration 失效、idle reaper 误清理)
        assertTrue(manager.runtime("s1")?.generationJob === newJob)
        // 运行时不得被 idle 清理(新任务仍在挂)
        advanceTimeBy(10_000)
        assertTrue(manager.runtime("s1") != null)
        newJob.cancelAndJoin()
        oldJob.join()
    }

    @Test
    fun `idle reaper removes runtime after timeout when no refs and no job`() = runTest {
        val manager = ConversationSessionManager(backgroundScope)
        manager.acquire("s1")
        manager.release("s1")
        // 虚拟时间推进超过 idle 超时(5s)+ 余量
        advanceTimeBy(10_000)
        assertNull(manager.runtime("s1"))
    }

    @Test
    fun `idle reaper keeps runtime while generation job is attached`() = runTest {
        val manager = ConversationSessionManager(backgroundScope)
        manager.acquire("s1")
        manager.release("s1")
        val job = launch { delay(60_000) }
        manager.setGenerationJob("s1", job)
        advanceTimeBy(10_000)
        // 有生成任务时不得清理
        assertTrue(manager.runtime("s1") != null)
        job.cancelAndJoin()
        // job 完成后触发 idle 调度,再推进虚拟时间即可清理
        advanceTimeBy(10_000)
        assertNull(manager.runtime("s1"))
    }

    @Test
    fun `beginTurn convenience entry creates runtime and starts turn`() = runTest {
        val manager = ConversationSessionManager(backgroundScope)
        manager.beginTurn("s1", "t1")
        val runtime = manager.runtime("s1")
        assertTrue(runtime != null)
        assertEquals(TurnPhase.GENERATING, runtime?.phase?.value)
        assertEquals("t1", runtime?.currentTurnId?.get())
    }

    @Test
    fun `cancelGeneration records cancelRequested and cancels job`() = runTest {
        val manager = ConversationSessionManager(backgroundScope)
        val job = launch { delay(10_000) }
        manager.setGenerationJob("s1", job)
        manager.cancelGeneration("s1")
        withTimeout(2_000) {
            while (!job.isCancelled) delay(10)
        }
        assertTrue(manager.runtime("s1")?.cancelRequested == true)
    }

    @Test
    fun `cancelGeneration for one session does not affect another`() = runTest {
        val manager = ConversationSessionManager(backgroundScope)
        val jobA = launch { delay(60_000) }
        val jobB = launch { delay(60_000) }
        manager.setGenerationJob("sA", jobA)
        manager.setGenerationJob("sB", jobB)

        manager.cancelGeneration("sA")

        assertTrue(jobA.isCancelled)
        assertFalse(jobB.isCancelled)
        assertTrue(manager.runtime("sA")?.cancelRequested == true)
        assertTrue(manager.runtime("sB")?.cancelRequested == false)

        jobB.cancelAndJoin()
        jobA.join()
    }

    @Test
    fun `clearAll cancels all jobs and drops runtimes`() = runTest {
        val manager = ConversationSessionManager(backgroundScope)
        val job = launch { delay(10_000) }
        manager.setGenerationJob("s1", job)
        manager.acquire("s2")
        manager.clearAll()
        assertTrue(job.isCancelled)
        assertNull(manager.runtime("s1"))
        assertNull(manager.runtime("s2"))
    }
}
