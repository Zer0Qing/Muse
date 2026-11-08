package io.zer0.muse.tools.system

import io.zer0.common.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

/**
 * Shizuku newProcess 快路径：这条路径绕开 UserService 的冷启动，直接在 Shizuku 服务进程里
 * 起 `sh -c`。它一旦把命令跑起来就**不能再退回 UserService**（会重复执行），所以
 * 「起不来 → null」与「跑起来了但失败 → 负退出码」这两种返回必须分得干干净净。
 *
 * 输出排空必须是两路并发的：命令写满 stderr 管道时会在 write 上阻塞，
 * 顺序排空 stdout 的实现会永远等不到 stdout 的 EOF —— 这里用真实阻塞管道复现。
 */
class ShizukuRemoteExecutorTest {

    @Before
    fun setUp() {
        Logger.enabled = false
    }

    // ── 文本路径 ──────────────────────────────────────────────────────────

    @Test
    fun `取回 stdout stderr 与退出码`() {
        val process = FakeProcess(out = "你好\n", err = "warn\n", code = 0)
        val result = ShizukuRemoteExecutor { process }.execute("echo hi")

        assertNotNull(result)
        assertEquals(0, result!!.exitCode)
        assertEquals("你好\n", result.stdout)
        assertEquals("warn\n", result.stderr)
        assertTrue(process.closed)
    }

    @Test
    fun `非零退出码原样保留`() {
        val result = ShizukuRemoteExecutor { FakeProcess(code = 3) }.execute("false")
        assertEquals(3, result!!.exitCode)
        assertFalse(result.isSuccess)
    }

    @Test
    fun `命令按 sh -c 原样下发`() {
        var seen: String? = null
        ShizukuRemoteExecutor { command -> seen = command; FakeProcess() }.execute("ls -l /sdcard")
        assertEquals("ls -l /sdcard", seen)
    }

    @Test
    fun `等待超时用毫秒单位传给远程进程`() {
        val process = FakeProcess()
        ShizukuRemoteExecutor { process }.execute("true", timeoutMs = 4_321L)
        assertEquals(4_321L, process.waitTimeoutMs)
    }

    @Test
    fun `超时销毁进程并把原因写进 stderr`() {
        val process = FakeProcess(exits = false)
        val result = ShizukuRemoteExecutor { process }.execute("sleep 999", timeoutMs = 50L)

        assertNotNull(result)
        assertEquals(-1, result!!.exitCode)
        assertTrue(process.destroyed)
        assertTrue(process.closed)
        assertTrue(result.stderr.contains("超时"))
    }

    @Test
    fun `起不来返回 null 让调用方退回 UserService`() {
        assertNull(ShizukuRemoteExecutor { null }.execute("id"))
    }

    @Test
    fun `启动抛异常同样返回 null`() {
        val executor = ShizukuRemoteExecutor { throw android.os.RemoteException("unknown transaction 7") }
        assertNull(executor.execute("id"))
    }

    @Test
    fun `命令已经跑起来之后失败绝不返回 null`() {
        // 返回 null 会让调用方再执行一遍同一条命令；副作用类命令会被做两次。
        val process = FakeProcess(waitThrows = true)
        val result = ShizukuRemoteExecutor { process }.execute("rm /sdcard/x")

        assertNotNull(result)
        assertEquals(-1, result!!.exitCode)
        assertTrue(process.destroyed)
        assertTrue(process.closed)
    }

    @Test
    fun `文本输出超限截断并标注`() {
        val huge = "x".repeat(ShizukuRemoteExecutor.MAX_TEXT_BYTES + 1024)
        val result = ShizukuRemoteExecutor { FakeProcess(out = huge) }.execute("cat big")

        assertTrue(result!!.stdout.length <= ShizukuRemoteExecutor.MAX_TEXT_BYTES + 64)
        assertTrue(result.stdout.contains("截断"))
    }

    @Test(timeout = 15_000L)
    fun `两路输出并发排空不会互相堵死`() {
        // stdout 只写一小段就不动了，等 stderr 排空后才关闭；stderr 远超管道缓冲。
        // 先把 stdout 读到 EOF 的实现会在这里死锁。
        val outPipe = PipedInputStream(PIPE_BYTES)
        val outWriter = PipedOutputStream(outPipe)
        val errPipe = PipedInputStream(PIPE_BYTES)
        val errWriter = PipedOutputStream(errPipe)
        val payload = "e".repeat(200_000)
        val writer = thread {
            outWriter.write("head\n".toByteArray(Charsets.UTF_8))
            outWriter.flush()
            errWriter.write(payload.toByteArray(Charsets.UTF_8))
            errWriter.close()
            outWriter.close()
        }
        val process = FakeProcess(outStream = outPipe, errStream = errPipe)
        try {
            val result = ShizukuRemoteExecutor { process }.execute("noisy", timeoutMs = 10_000L)
            writer.join(5_000L)
            assertEquals("head\n", result!!.stdout)
            assertEquals(payload, result.stderr)
        } finally {
            runCatching { outWriter.close() }
            runCatching { errWriter.close() }
            writer.join(5_000L)
        }
    }

    // ── 流式路径 ──────────────────────────────────────────────────────────

    @Test
    fun `流式路径把 stderr 并进 stdout 保持真实顺序`() {
        var seen: String? = null
        ShizukuRemoteExecutor { command -> seen = command; FakeProcess() }
            .streamTo("echo hi", ByteArrayOutputStream())
        assertNotNull(seen)
        assertTrue(seen!!.contains("2>&1"))
        assertTrue(seen!!.contains("echo hi"))
    }

    @Test
    fun `流式路径把字节原样交给 sink`() {
        val sink = ByteArrayOutputStream()
        val code = ShizukuRemoteExecutor { FakeProcess(out = "abc", err = "def") }.streamTo("cmd", sink)
        assertEquals(0, code)
        assertEquals("abc", sink.toString("UTF-8"))
    }

    @Test
    fun `流式路径起不来返回 null`() {
        assertNull(ShizukuRemoteExecutor { null }.streamTo("cmd", ByteArrayOutputStream()))
    }

    @Test
    fun `流式路径超时把说明写进 sink`() {
        val sink = ByteArrayOutputStream()
        val code = ShizukuRemoteExecutor { FakeProcess(exits = false) }.streamTo("cmd", sink, timeoutMs = 50L)
        assertEquals(-1, code)
        assertTrue(sink.toString("UTF-8").contains("超时"))
    }

    // ── UTF-8 增量解码 ────────────────────────────────────────────────────

    @Test
    fun `多字节字符被切在两次写入之间也不乱码`() {
        val text = StringBuilder()
        val sink = Utf8TextSink { text.append(it) }
        val bytes = "中文abc".toByteArray(Charsets.UTF_8)
        sink.write(bytes, 0, 1)
        sink.write(bytes, 1, 3)
        sink.write(bytes, 4, bytes.size - 4)
        sink.flushRemainder()
        assertEquals("中文abc", text.toString())
    }

    @Test
    fun `单字节写入接口同样走增量解码`() {
        val text = StringBuilder()
        val sink = Utf8TextSink { text.append(it) }
        "a中".toByteArray(Charsets.UTF_8).forEach { sink.write(it.toInt()) }
        sink.flushRemainder()
        assertEquals("a中", text.toString())
    }

    @Test
    fun `不完整字节在收尾时不会抛`() {
        val text = StringBuilder()
        val sink = Utf8TextSink { text.append(it) }
        sink.write(0xE4)
        sink.flushRemainder()
        assertTrue(text.isNotEmpty())
    }

    private companion object {
        const val PIPE_BYTES = 4_096
    }

    private class FakeProcess(
        private val out: String = "",
        private val err: String = "",
        private val code: Int = 0,
        private val exits: Boolean = true,
        private val waitThrows: Boolean = false,
        private val outStream: InputStream? = null,
        private val errStream: InputStream? = null,
    ) : RemoteShellProcess {
        var destroyed = false
        var closed = false
        var waitTimeoutMs = -1L

        override fun stdout(): InputStream = outStream ?: ByteArrayInputStream(out.toByteArray(Charsets.UTF_8))
        override fun stderr(): InputStream = errStream ?: ByteArrayInputStream(err.toByteArray(Charsets.UTF_8))

        override fun waitFor(timeoutMs: Long): Boolean {
            waitTimeoutMs = timeoutMs
            if (waitThrows) throw android.os.RemoteException("binder died")
            // 真实进程退出后管道才 EOF；这里模拟「没退出」时读端仍然会 EOF，
            // 由 destroy() 负责打断，超时判定只看 waitFor 的返回值。
            return exits
        }

        override fun exitValue(): Int = code
        override fun destroy() {
            destroyed = true
        }

        override fun close() {
            closed = true
        }
    }
}
