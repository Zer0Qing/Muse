package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 工作区写入的并发正确性：同一文件的读—改—写串行，不同文件并行。
 *
 * 钉住三件事：
 *  1. 并发 `workspace_edit` 同一文件时，每一次改动都留在结果里，不是后写覆盖先写；
 *  2. 不同文件互相不阻塞（两个读—改—写能同时处于临界区）；
 *  3. 异常路径与拒绝路径都不留锁。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WorkspaceEditConcurrencyTest {

    private lateinit var manager: WorkspaceManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        manager = WorkspaceManager(context)
    }

    private fun seed(path: String, content: String) {
        val result = runBlocking { manager.writeFile(path, content) }
        assertTrue("写入夹具失败: $result", result is WorkspaceManager.OpResult.Success)
    }

    private fun contentOf(path: String): String =
        runBlocking {
            val result = manager.readFile(path)
            assertTrue("读取失败: $result", result is WorkspaceManager.ReadResult.Success)
            (result as WorkspaceManager.ReadResult.Success).content
        }

    private fun edit(path: String, oldString: String, newString: String): String =
        WorkspaceTool.execute(
            WorkspaceTool.NAME_EDIT,
            mapOf("path" to path, "old_string" to oldString, "new_string" to newString),
            manager,
        )

    @Test
    fun `并发 workspace_edit 同一文件时每一次改动都落盘`() {
        // 两位编号：任一标记都不是别的标记的前缀，保证 old_string 在文件里唯一
        val tokens = (1..12).map { it.toString().padStart(2, '0') }
        seed("race.txt", tokens.joinToString("\n") { "item $it" } + "\n")

        val receipts = runBlocking {
            coroutineScope {
                tokens.map { token ->
                    async(Dispatchers.IO) {
                        edit("race.txt", "item $token", "done $token")
                    }
                }.awaitAll()
            }
        }

        receipts.forEachIndexed { index, receipt ->
            assertTrue("第 $index 次编辑失败: $receipt", receipt.startsWith("OK:"))
        }

        val finalContent = contentOf("race.txt")
        tokens.forEach { token ->
            assertTrue("改动「$token」被别的写入覆盖掉了:\n$finalContent", finalContent.contains("done $token"))
            assertTrue("旧内容「$token」还在:\n$finalContent", !finalContent.contains("item $token"))
        }
    }

    @Test
    fun `同一文件的读改写不会同时进入临界区`() = runBlocking {
        seed("serial.txt", "start\n")
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)

        coroutineScope {
            (1..6).map { i ->
                async(Dispatchers.IO) {
                    manager.editFile("serial.txt") { original ->
                        val now = active.incrementAndGet()
                        peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                        try {
                            Thread.sleep(5)
                            WorkspaceManager.EditDecision.Commit(original + "line$i\n")
                        } finally {
                            active.decrementAndGet()
                        }
                    }
                }
            }.awaitAll()
        }

        assertEquals("同一文件不该有两个读—改—写交错", 1, peak.get())
        val finalContent = contentOf("serial.txt")
        (1..6).forEach { i -> assertTrue("第 $i 次追加丢了:\n$finalContent", finalContent.contains("line$i\n")) }
    }

    @Test
    fun `不同文件的读改写可以同时进行`() = runBlocking {
        seed("p1.txt", "a\n")
        seed("p2.txt", "b\n")
        // 两方都进到临界区才会放行：若两把锁被合成一把，第二个永远进不来
        val barrier = CyclicBarrier(2)

        val results = listOf("p1.txt", "p2.txt").map { path ->
            async(Dispatchers.IO) {
                manager.editFile(path) { original ->
                    barrier.await(5, TimeUnit.SECONDS)
                    WorkspaceManager.EditDecision.Commit(original + "x\n")
                }
            }
        }.awaitAll()

        results.forEach { assertTrue("编辑失败: $it", it is WorkspaceManager.EditResult.Applied) }
        assertTrue(contentOf("p1.txt").endsWith("x\n"))
        assertTrue(contentOf("p2.txt").endsWith("x\n"))
    }

    @Test
    fun `读改写抛异常后锁仍会释放`() = runBlocking {
        seed("boom.txt", "x\n")

        val boom = runCatching {
            manager.editFile("boom.txt") { throw IllegalStateException("boom") }
        }
        assertTrue("异常应原样上抛,实际=$boom", boom.exceptionOrNull() is IllegalStateException)

        val after = manager.editFile("boom.txt") { original ->
            WorkspaceManager.EditDecision.Commit(original + "y\n")
        }
        assertTrue("抛异常后锁没释放: $after", after is WorkspaceManager.EditResult.Applied)
        assertTrue(contentOf("boom.txt").endsWith("y\n"))
    }

    @Test
    fun `被判拒绝的编辑之后同一文件仍可继续编辑`() {
        seed("reject.txt", "alpha\n")

        val rejected = edit("reject.txt", "找不到的片段", "x")
        assertTrue(rejected, rejected.startsWith("Error:"))

        val ok = edit("reject.txt", "alpha", "beta")
        assertTrue(ok, ok.startsWith("OK:"))
        assertEquals("beta\n", contentOf("reject.txt"))
    }
}
