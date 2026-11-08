package io.zer0.muse.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Termux 环回冗余路径：载荷头解析、命令外壳形状，以及真实 socket 往返。
 *
 * 这条路是广播被 ROM 整层拦掉时唯一还能拿到结果的通道，帧格式错一个字节就全废，
 * 所以除了纯函数单测还跑一次真实环回连接。
 */
class TermuxLoopbackProtocolTest {

    @Test
    fun `标准载荷头解析`() {
        val header = TermuxLoopbackProtocol.parseHeader("MUSE/1 abc-123 7 0 5 3")
        assertNotNull(header)
        assertEquals("abc-123", header!!.token)
        assertEquals(7, header.requestId)
        assertEquals(0, header.exitCode)
        assertEquals(5, header.stdoutLength)
        assertEquals(3, header.stderrLength)
    }

    @Test
    fun `非零退出码与多余空白容忍`() {
        val header = TermuxLoopbackProtocol.parseHeader("  MUSE/1   tok  9  127  0  0  ")
        assertNotNull(header)
        assertEquals(127, header!!.exitCode)
        assertEquals(0, header.stdoutLength)
    }

    @Test
    fun `前缀不符判为非法`() {
        assertNull(TermuxLoopbackProtocol.parseHeader("MUSE/2 tok 1 0 0 0"))
        assertNull(TermuxLoopbackProtocol.parseHeader("garbage"))
    }

    @Test
    fun `字段数不对判为非法`() {
        assertNull(TermuxLoopbackProtocol.parseHeader("MUSE/1 tok 1 0 0"))
        assertNull(TermuxLoopbackProtocol.parseHeader("MUSE/1 tok 1 0 0 0 0"))
    }

    @Test
    fun `非数字字段判为非法`() {
        assertNull(TermuxLoopbackProtocol.parseHeader("MUSE/1 tok x 0 0 0"))
        assertNull(TermuxLoopbackProtocol.parseHeader("MUSE/1 tok 1 0 - 0"))
    }

    @Test
    fun `负长度判为非法`() {
        assertNull(TermuxLoopbackProtocol.parseHeader("MUSE/1 tok 1 0 -1 0"))
        assertNull(TermuxLoopbackProtocol.parseHeader("MUSE/1 tok 1 0 0 -5"))
    }

    // ── 命令外壳 ──────────────────────────────────────────────────────────

    @Test
    fun `外壳带上端口 token 与请求 id`() {
        val wrapped = TermuxLoopbackProtocol.buildWrapper("echo hi", 43210, "tok-uuid", 9)
        assertTrue(wrapped.contains("/dev/tcp/127.0.0.1/43210"))
        assertTrue(wrapped.contains("'tok-uuid' '9'"))
        assertTrue(wrapped.contains("MUSE/1 %s %s %s %s %s"))
    }

    @Test
    fun `外壳保留原命令与退出码`() {
        val wrapped = TermuxLoopbackProtocol.buildWrapper("echo hi", 1, "t", 2)
        // 主分支与 mktemp 失败后的退回分支各出现一次原命令。
        assertEquals(2, wrapped.split("echo hi").size - 1)
        assertTrue(wrapped.contains("__muse_rc=\$?"))
        assertTrue(wrapped.contains("exit \"\$__muse_rc\""))
        assertTrue(wrapped.trimEnd().endsWith("fi"))
    }

    @Test
    fun `外壳把输出照常回放给 Termux 广播路径`() {
        val wrapped = TermuxLoopbackProtocol.buildWrapper("true", 1, "t", 2)
        // 少了这两行，广播路径回传的 stdout/stderr 就是空的——冗余会弄坏主路径。
        assertTrue(wrapped.contains("cat \"\$__muse_d/o\"\n"))
        assertTrue(wrapped.contains("cat \"\$__muse_d/e\" >&2"))
    }

    @Test
    fun `环回重定向先吞 stderr 再开连接`() {
        val wrapped = TermuxLoopbackProtocol.buildWrapper("true", 1, "t", 2)
        // 顺序反了的话，连接被拒时 bash 的报错会混进命令 stderr。
        assertTrue(wrapped.contains("} 2>/dev/null >\"/dev/tcp/127.0.0.1/1\""))
    }

    @Test
    fun `外壳清理临时文件`() {
        val wrapped = TermuxLoopbackProtocol.buildWrapper("true", 1, "t", 2)
        assertTrue(wrapped.contains("rm -f \"\$__muse_d/o\" \"\$__muse_d/e\""))
        assertTrue(wrapped.contains("rmdir \"\$__muse_d\""))
    }

    @Test
    fun `量不出长度就不发环回`() {
        val wrapped = TermuxLoopbackProtocol.buildWrapper("true", 1, "t", 2)
        // wc 缺失时头部会声明 0 长度却跟着真实字节，错位的载荷还会抢在广播前面送回坏结果。
        val guard = "if [ -n \"\$__muse_ol\" ] && [ -n \"\$__muse_el\" ]; then"
        val guardAt = wrapped.indexOf(guard)
        val tcpAt = wrapped.indexOf("/dev/tcp/127.0.0.1/1")
        assertTrue("外壳要带长度守卫", guardAt >= 0)
        assertTrue("环回发送必须在守卫之内", guardAt < tcpAt)
        assertTrue("守卫要有收尾 fi", wrapped.indexOf("\nfi\n", tcpAt) > tcpAt)
        // 回放给广播路径的两行必须在守卫之外，否则 wc 一坏连广播输出都没了。
        assertTrue(wrapped.indexOf("cat \"\$__muse_d/o\"\n") > wrapped.indexOf("\nfi\n", tcpAt))
    }

    // ── 真实环回往返 ──────────────────────────────────────────────────────

    @Test
    fun `环回端到端收全 stdout 与 stderr`() {
        val listener = TermuxLoopbackListener.open("tok", 7)
        assertNotNull(listener)
        try {
            val sender = thread { sendPayload(listener!!.port, "MUSE/1 tok 7 0 5 3\nhelloerr") }
            val result = listener!!.await(5_000L)
            sender.join(5_000L)
            assertNotNull(result)
            assertEquals(0, result!!.exitCode)
            assertEquals("hello", result.stdout)
            assertEquals("err", result.stderr)
            assertNull(result.error)
            assertFalse(result.truncated)
        } finally {
            listener!!.close()
        }
    }

    @Test
    fun `环回身份不符丢弃`() {
        val listener = TermuxLoopbackListener.open("tok", 7)!!
        try {
            val sender = thread { sendPayload(listener.port, "MUSE/1 other 7 0 2 0\nhi") }
            val result = listener.await(5_000L)
            sender.join(5_000L)
            assertNull(result)
        } finally {
            listener.close()
        }
    }

    @Test
    fun `环回请求 id 不符丢弃`() {
        val listener = TermuxLoopbackListener.open("tok", 7)!!
        try {
            val sender = thread { sendPayload(listener.port, "MUSE/1 tok 8 0 2 0\nhi") }
            val result = listener.await(5_000L)
            sender.join(5_000L)
            assertNull(result)
        } finally {
            listener.close()
        }
    }

    @Test
    fun `声明长度大于实收判为截断`() {
        val listener = TermuxLoopbackListener.open("tok", 7)!!
        try {
            // 声明 100 字节却只发 10 字节就断开：宁可给不完整输出并标记，也不能无限等。
            val sender = thread { sendPayload(listener.port, "MUSE/1 tok 7 1 100 0\n0123456789") }
            val result = listener.await(5_000L)
            sender.join(5_000L)
            assertNotNull(result)
            assertEquals("0123456789", result!!.stdout)
            assertEquals(1, result.exitCode)
            assertTrue(result.truncated)
        } finally {
            listener.close()
        }
    }

    @Test
    fun `关掉监听能打断阻塞中的等待`() {
        val listener = TermuxLoopbackListener.open("tok", 7)!!
        var observed: TermuxChannel.TermuxResult? = null
        var returned = false
        val waiter = thread {
            observed = listener.await(30_000L)
            returned = true
        }
        // 不等 soTimeout：close 必须立刻让 accept 抛出来，否则广播早已返回时
        // exec 仍会陪着环回路径干等到超时。
        Thread.sleep(150L)
        listener.close()
        waiter.join(3_000L)
        assertFalse(waiter.isAlive)
        assertTrue(returned)
        assertNull(observed)
    }

    @Test
    fun `关掉监听能打断读了一半的载荷`() {
        val listener = TermuxLoopbackListener.open("tok", 7)!!
        var returned = false
        // 只发头部、声明 100 字节正文却一直不发：读会阻塞在 socket 上。
        val sender = thread {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", listener.port), 3_000)
                    socket.getOutputStream().apply {
                        write("MUSE/1 tok 7 0 100 0\n".toByteArray(Charsets.UTF_8))
                        flush()
                    }
                    Thread.sleep(3_000L)
                }
            }
        }
        val waiter = thread {
            listener.await(30_000L)
            returned = true
        }
        Thread.sleep(300L)
        listener.close()
        waiter.join(3_000L)
        sender.join(3_000L)
        // close 只关 server 不关已接受的 socket 时，这里会一直卡到 READ_TIMEOUT_MS。
        assertFalse(waiter.isAlive)
        assertTrue(returned)
    }

    @Test
    fun `重复 close 不抛`() {
        val listener = TermuxLoopbackListener.open("tok", 7)!!
        listener.close()
        listener.close()
    }

    private fun sendPayload(port: Int, payload: String) {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 3_000)
                socket.getOutputStream().apply {
                    write(payload.toByteArray(Charsets.UTF_8))
                    flush()
                }
            }
        }
    }
}
