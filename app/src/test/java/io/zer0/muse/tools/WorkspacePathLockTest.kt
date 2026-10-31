package io.zer0.muse.tools

import io.zer0.muse.workspace.WorkspaceIoException
import io.zer0.muse.workspace.WorkspacePathLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 路径锁本身的行为：按路径串行、跨路径并行、等锁超时、异常与取消都不留锁。
 *
 * 这里不碰 Android 环境，锁是纯协程实现；工作区那一层的接线见
 * [WorkspaceEditConcurrencyTest]。
 */
class WorkspacePathLockTest {

    /** 记录同时处于临界区的最大数量。 */
    private class ConcurrencyProbe {
        private val active = AtomicInteger(0)

        /** 历史峰值。 */
        private val peak = AtomicInteger(0)

        val maxActive: Int get() = peak.get()

        suspend fun <T> record(block: suspend () -> T): T {
            val now = active.incrementAndGet()
            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                return block()
            } finally {
                active.decrementAndGet()
            }
        }
    }

    @Test
    fun `同一文件的不同写法映射到同一把锁`() {
        val lock = WorkspacePathLock()
        val base = lock.keyOf("main", "a.txt")

        assertEquals(base, lock.keyOf("main", "./a.txt"))
        assertEquals(base, lock.keyOf("main", "/a.txt"))
        assertEquals(base, lock.keyOf("main", "a.txt/"))
        // 大小写不敏感的文件系统上这是同一个文件
        assertEquals(base, lock.keyOf("main", "A.TXT"))
        // 不同文件、不同挂载各自独立
        assertNotEquals(base, lock.keyOf("main", "b.txt"))
        assertNotEquals(base, lock.keyOf("album", "a.txt"))
    }

    @Test
    fun `同一路径的并发进入被串行化`() = runBlocking {
        val lock = WorkspacePathLock()
        val probe = ConcurrencyProbe()
        val done = AtomicInteger(0)

        coroutineScope {
            (1..8).map {
                async(Dispatchers.Default) {
                    lock.withLock("main", "same.txt", "same.txt") {
                        probe.record {
                            delay(10)
                            done.incrementAndGet()
                        }
                    }
                }
            }.awaitAll()
        }

        assertEquals("同一文件不该有两个持有者", 1, probe.maxActive)
        assertEquals("8 次调用都应完成", 8, done.get())
    }

    @Test
    fun `不同路径互不阻塞`() = runBlocking {
        val lock = WorkspacePathLock()
        val probe = ConcurrencyProbe()

        coroutineScope {
            (1..6).map { i ->
                async(Dispatchers.Default) {
                    lock.withLock("main", "f$i.txt", "f$i.txt") {
                        probe.record { delay(50) }
                    }
                }
            }.awaitAll()
        }

        assertTrue("不同文件应能并行,实际峰值=${probe.maxActive}", probe.maxActive > 1)
    }

    @Test
    fun `等锁超时给出明确错误并马上返回`() = runBlocking {
        val lock = WorkspacePathLock(acquireTimeoutMs = 100)
        val holding = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            lock.withLock("main", "busy.txt", "busy.txt") {
                holding.complete(Unit)
                delay(2_000)
            }
        }
        holding.await()

        val startedAt = System.currentTimeMillis()
        val failure = runCatching { lock.withLock("main", "busy.txt", "busy.txt") { } }.exceptionOrNull()
        val elapsed = System.currentTimeMillis() - startedAt

        assertTrue("应当是工作区 IO 错误,实际=$failure", failure is WorkspaceIoException)
        assertTrue(
            "错误文案要说明是等锁超时,实际=${failure?.message}",
            failure?.message?.contains("等待文件锁超时") == true,
        )
        assertTrue("不该等到持锁方结束才返回,实际等了 ${elapsed}ms", elapsed < 1_500)
        holder.join()
    }

    @Test
    fun `临界区抛异常后锁仍然释放`() = runBlocking {
        val lock = WorkspacePathLock(acquireTimeoutMs = 500)

        val boom = runCatching {
            lock.withLock("main", "a.txt", "a.txt") { throw IllegalStateException("boom") }
        }
        assertTrue("异常应原样上抛,实际=$boom", boom.exceptionOrNull() is IllegalStateException)

        var ran = false
        lock.withLock("main", "a.txt", "a.txt") { ran = true }
        assertTrue("异常路径没释放锁:后续调用无法进入", ran)
    }

    @Test
    fun `等待者被取消后锁仍然可用`() = runBlocking {
        val lock = WorkspacePathLock(acquireTimeoutMs = 200)
        val holding = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            lock.withLock("main", "c.txt", "c.txt") {
                holding.complete(Unit)
                delay(300)
            }
        }
        holding.await()

        val waiter = launch(Dispatchers.Default) {
            lock.withLock("main", "c.txt", "c.txt") { }
        }
        delay(50)
        waiter.cancel()
        holder.join()

        var ran = false
        lock.withLock("main", "c.txt", "c.txt") { ran = true }
        assertTrue("被取消的等待者把锁带走了", ran)
    }
}
