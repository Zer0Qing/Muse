package io.zer0.muse.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 后台终端 job 的状态机（对比清单 P1-07）。
 *
 * 守住的是"长任务能收尾"这件事：提交拿得到 id、输出有上限、终止之后不会被误报成正常结束、
 * 并发与保留数量都有边界。
 */
class TerminalJobRegistryTest {

    private lateinit var registry: TerminalJobRegistry
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 可控制结束时机与退出码的假句柄。 */
    private class FakeHandle(private val exit: Int?) : TerminalJobRegistry.Handle {
        val gate = CountDownLatch(1)
        var killed = false

        override fun waitFor(): Int? {
            gate.await(3, TimeUnit.SECONDS)
            return if (killed) null else exit
        }

        override fun kill() {
            killed = true
            gate.countDown()
        }
    }

    private class FakeLauncher(private val exit: Int?) : TerminalJobRegistry.Launcher {
        val handles = mutableListOf<FakeHandle>()
        var lastOnOutput: ((String) -> Unit)? = null

        override fun launch(command: String, onOutput: (String) -> Unit): TerminalJobRegistry.Handle {
            lastOnOutput = onOutput
            val handle = FakeHandle(exit)
            handles += handle
            return handle
        }
    }

    @Before
    fun setUp() {
        registry = TerminalJobRegistry(maxConcurrent = 2, maxRetained = 3, tailChars = 100, scope = scope)
    }

    @After
    fun tearDown() {
        registry.resetForTests()
    }

    private fun submit(launcher: TerminalJobRegistry.Launcher, command: String = "sleep 100") =
        registry.submit(label = "test", command = command, launcher = launcher)

    private fun awaitState(id: String, expected: TerminalJobRegistry.State) {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            if (registry.get(id)?.state == expected) return
            Thread.sleep(10)
        }
        assertEquals("等待状态超时", expected, registry.get(id)?.state)
    }

    @Test
    fun `a submitted job is running and has an id`() {
        val result = submit(FakeLauncher(0))

        assertTrue(result is TerminalJobRegistry.SubmitResult.Accepted)
        val job = (result as TerminalJobRegistry.SubmitResult.Accepted).job
        assertTrue("job_id 必须能交回给调用方: ${job.id}", job.id.startsWith("job-"))
        assertEquals(TerminalJobRegistry.State.RUNNING, job.state)
    }

    @Test
    fun `an empty command is rejected instead of launching a shell`() {
        val result = registry.submit("t", "   ", FakeLauncher(0))

        assertEquals(
            TerminalJobRegistry.Rejection.EMPTY_COMMAND,
            (result as TerminalJobRegistry.SubmitResult.Rejected).reason,
        )
    }

    @Test
    fun `the concurrency cap is enforced with a countable reason`() {
        val launcher = FakeLauncher(0)
        submit(launcher)
        submit(launcher)

        val third = submit(launcher)

        val rejected = third as TerminalJobRegistry.SubmitResult.Rejected
        assertEquals(TerminalJobRegistry.Rejection.TOO_MANY_RUNNING, rejected.reason)
        assertEquals("要告诉调用方现在有几个在跑", 2, rejected.running)
    }

    @Test
    fun `a finished job reports its exit code`() {
        val launcher = FakeLauncher(0)
        val job = (submit(launcher) as TerminalJobRegistry.SubmitResult.Accepted).job
        launcher.handles.first().gate.countDown()

        awaitState(job.id, TerminalJobRegistry.State.FINISHED)
        assertEquals(0, registry.get(job.id)?.exitCode)
    }

    @Test
    fun `a killed job is never reported as normally finished`() {
        val launcher = FakeLauncher(0)
        val job = (submit(launcher) as TerminalJobRegistry.SubmitResult.Accepted).job

        assertTrue(registry.kill(job.id))
        launcher.handles.first().gate.countDown()
        Thread.sleep(100)

        assertEquals(TerminalJobRegistry.State.KILLED, registry.get(job.id)?.state)
        assertNull("终止后退出码不可信，必须留空", registry.get(job.id)?.exitCode)
        assertFalse("已结束的 job 不能再被终止", registry.kill(job.id))
    }

    @Test
    fun `output is truncated to the tail so a runaway command cannot eat memory`() {
        val launcher = FakeLauncher(0)
        val job = (submit(launcher) as TerminalJobRegistry.SubmitResult.Accepted).job

        launcher.lastOnOutput?.invoke("A".repeat(150))
        launcher.lastOnOutput?.invoke("TAIL")

        val tail = registry.get(job.id)?.tail.orEmpty()
        assertEquals("只保留尾部 100 个字符", 100, tail.length)
        assertTrue("最新输出必须留下: $tail", tail.endsWith("TAIL"))
    }

    @Test
    fun `old finished jobs are evicted so the ledger does not grow forever`() {
        repeat(3) {
            val launcher = FakeLauncher(0)
            val job = (submit(launcher) as TerminalJobRegistry.SubmitResult.Accepted).job
            launcher.handles.first().gate.countDown()
            awaitState(job.id, TerminalJobRegistry.State.FINISHED)
        }
        // 第 4 条：保留上限 3，最老的已结束项应被淘汰
        val launcher = FakeLauncher(0)
        submit(launcher)

        assertTrue("台账不应超过保留上限: ${registry.list().size}", registry.list().size <= 3)
    }

    @Test
    fun `an unknown job id returns null instead of an empty job`() {
        assertNull(registry.get("job-999"))
        assertFalse(registry.kill("job-999"))
    }
}
