package io.zer0.muse.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 审查修复 (2.0) 配套测试:
 * - B-33: resolve/fail 并发不丢条目(CAS 原子更新)
 * - A-15: abort 后 resolve/fail 拒绝回灌 + abort 取消登记的 Job
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeferredResultStoreTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    // ── A-15: abort 语义 ────────────────────────────────────────────

    @Test
    fun `abort 后 resolve 拒绝回灌`() = runTest {
        val store = DeferredResultStore()
        store.defer("t1", "s1", null, null, "task")
        store.abort("t1")
        store.resolve("t1", "结果")

        assertEquals(0, store.consumeCompleted("s1").size)
        assertEquals(DeferredResultStore.TaskStatus.ABORTED, store.getTask("t1")?.status)
    }

    @Test
    fun `abort 后 fail 拒绝回灌`() = runTest {
        val store = DeferredResultStore()
        store.defer("t1", "s1", null, null, "task")
        store.abort("t1")
        store.fail("t1", "err")

        assertEquals(0, store.consumeCompleted("s1").size)
        assertEquals(DeferredResultStore.TaskStatus.ABORTED, store.getTask("t1")?.status)
    }

    @Test
    fun `abort 取消登记的后台 Job`() = runTest {
        val store = DeferredResultStore()
        var cancelled = false
        val job = Job()
        job.invokeOnCompletion { cancelled = true }
        store.attachJob("t1", job)
        store.abort("t1")

        assertTrue("abort 必须取消登记的后台 Job", cancelled)
    }

    @Test
    fun `未中止任务 resolve 正常回灌`() = runTest {
        val store = DeferredResultStore()
        store.defer("t1", "s1", null, null, "task")
        store.resolve("t1", "结果")

        val consumed = store.consumeCompleted("s1")
        assertEquals(1, consumed.size)
        assertEquals("结果", consumed[0].result)
        assertEquals(DeferredResultStore.TaskStatus.RESOLVED, consumed[0].status)
    }

    // ── B-33: 并发原子性 ────────────────────────────────────────────

    @Test
    fun `并发 resolve 多个任务不丢条目`() = runTest {
        val store = DeferredResultStore()
        repeat(50) { i -> store.defer("t$i", "s1", null, null, "task$i") }

        // 模拟多子任务并发完成:全部在各自协程中 resolve
        val jobs = (0 until 50).map { i ->
            launch(Dispatchers.IO) { store.resolve("t$i", "result$i") }
        }
        jobs.forEach { it.join() }

        val consumed = store.consumeCompleted("s1")
        assertEquals("并发 resolve 后全部条目都应回灌", 50, consumed.size)
        assertEquals(
            "结果内容不得丢失",
            (0 until 50).map { "result$it" }.toSet(),
            consumed.map { it.result }.toSet(),
        )
    }

    @Test
    fun `并发 resolve 与 abort 竞争时已中止任务不出现`() = runTest {
        val store = DeferredResultStore()
        repeat(20) { i -> store.defer("t$i", "s1", null, null, "task$i") }

        val jobs = (0 until 20).map { i ->
            launch(Dispatchers.IO) {
                if (i % 2 == 0) {
                    store.abort("t$i")
                } else {
                    store.resolve("t$i", "r$i")
                }
            }
        }
        jobs.forEach { it.join() }

        val consumed = store.consumeCompleted("s1")
        // 只有奇数任务(未中止)应回灌
        assertEquals(10, consumed.size)
        assertTrue(consumed.all { it.status == DeferredResultStore.TaskStatus.RESOLVED })
        assertFalse(consumed.any { it.taskId.toInt() % 2 == 0 })
    }

    @Test
    fun `consumeCompleted 消费后不残留`() = runTest {
        val store = DeferredResultStore()
        store.defer("t1", "s1", null, null, "task")
        store.resolve("t1", "r")
        store.consumeCompleted("s1")
        assertEquals(0, store.consumeCompleted("s1").size)
    }
}
